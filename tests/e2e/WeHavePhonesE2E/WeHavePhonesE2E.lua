-- WeHavePhones end-to-end test marker.
--
-- scripts/e2e-wehavephones.py copies this addon into the game under test, writes
-- its .toc for the client's interface version and removes it afterwards. It never
-- ships with the app. The addon draws a strip of colored squares near the top of
-- the screen that the runner reads from an Android screenshot:
--
--   magenta | in world | jumped | moved | turned | wheel | ring | slot | clean | cyan
--
-- In world, moved and turned are black until their event happens and then stay
-- green. Jumped is a pulse instead: green for five seconds after the character
-- leaves the ground, yellow for five seconds after the jump binding ran without
-- the character leaving the ground, black otherwise. The runner waits for black,
-- jumps and looks for green, so a fall while the world streams in never counts.
-- Turned is yellow when the client does not report the player's facing (Retail
-- can hide it), so the runner measures the turn on screen instead.
-- The touch action wheel's squares: wheel is green while it is on screen. Ring is a
-- pulse like jumped, green after a change to the next ring and yellow after one to
-- the previous ring. Slot pulses green after a wheel slot was clicked. Clean is green
-- until the touch addon raises a Lua error or WoW blocks one of its actions, then
-- black, and the first such message is written under the strip.
-- The magenta and cyan ends let the runner find the strip at any resolution.
-- Works on every client from Classic Era to Retail: it uses only APIs that all
-- of them share.

local SIZE, GAP = 26, 4
local ORDER = { "inWorld", "jumped", "moved", "turned", "wheel", "ring", "slot", "clean" }
local COLORS = {
    off = { 0, 0, 0 },
    on = { 0, 1, 0 },
    pressed = { 1, 1, 0 },
}

local state = { inWorld = "off", jumped = "off", moved = "off", turned = "off",
    wheel = "off", ring = "off", slot = "off", clean = "on" }
local enteredAt, firstFacing, hooked, wasFalling
local fellAt, pressedAt = -100, -100
local ringAt, ringStep, slotAt, lastRing = -100, "on", -100, nil
local TOUCH = "WeHavePhonesTouch"
local PULSE = 5

local strip = CreateFrame("Frame", "WeHavePhonesE2EMarker", UIParent)
strip:SetFrameStrata("TOOLTIP")
strip:SetSize((#ORDER + 2) * (SIZE + GAP) + GAP, SIZE + 2 * GAP)
strip:SetPoint("TOP", UIParent, "TOP", 0, -150)
strip:Hide()

local backdrop = strip:CreateTexture(nil, "BACKGROUND")
backdrop:SetAllPoints()
backdrop:SetColorTexture(0.5, 0.5, 0.5, 1)

local squares = {}
local function square(index, r, g, b)
    local texture = strip:CreateTexture(nil, "ARTWORK")
    texture:SetSize(SIZE, SIZE)
    texture:SetPoint("LEFT", strip, "LEFT", GAP + (index - 1) * (SIZE + GAP), 0)
    texture:SetColorTexture(r, g, b, 1)
    return texture
end

square(1, 1, 0, 1)
for i, name in ipairs(ORDER) do
    squares[name] = square(i + 1, 0, 0, 0)
end
square(#ORDER + 2, 0, 1, 1)
squares.clean:SetColorTexture(0, 1, 0, 1)

local problem = strip:CreateFontString(nil, "OVERLAY", "GameFontHighlightSmall")
problem:SetPoint("TOP", strip, "BOTTOM", 0, -4)
problem:SetWidth(700)
problem:SetJustifyH("LEFT")

-- Only the touch addon's own problems count. Other addons on the account are not under test.
local function report(text)
    text = tostring(text)
    if not text:find(TOUCH, 1, true) then return end
    if state.clean == "on" then
        state.clean = "off"
        squares.clean:SetColorTexture(0, 0, 0, 1)
        problem:SetText(text:sub(1, 400))
    end
    WeHavePhonesE2EDB = WeHavePhonesE2EDB or {}
    WeHavePhonesE2EDB.problems = WeHavePhonesE2EDB.problems or {}
    table.insert(WeHavePhonesE2EDB.problems, text)
end

local wrapped
local function watchErrors()
    local current = geterrorhandler()
    if current == wrapped then return end
    wrapped = function(message, ...)
        pcall(report, message)
        return current(message, ...)
    end
    seterrorhandler(wrapped)
end
watchErrors()

local function paint(name, value)
    if state[name] == value then return end
    state[name] = value
    local color = COLORS[value]
    squares[name]:SetColorTexture(color[1], color[2], color[3], 1)
end

-- Wheel slot clicks and ring changes. The wheel is created when the touch layout first runs.
local slotsHooked
local function watchWheel()
    local wheel = _G.WeHavePhonesTouchWheel
    paint("wheel", wheel and wheel:IsVisible() and "on" or "off")
    if not wheel then return end
    if not slotsHooked then
        slotsHooked = true
        for i = 1, 9 do
            local slot = _G["WeHavePhonesTouchWheelSlot" .. i]
            if slot then slot:HookScript("PostClick", function() slotAt = GetTime() end) end
        end
    end
    local ring = wheel:GetAttribute("ring")
    if lastRing and ring ~= lastRing then
        ringAt = GetTime()
        -- Four rings: 1 after 4 is the next ring, 4 after 1 the previous one.
        local step = (ring - lastRing) % 4
        ringStep = step == 1 and "on" or "pressed"
    end
    lastRing = ring
    local now = GetTime()
    paint("ring", now - ringAt < PULSE and ringStep or "off")
    paint("slot", now - slotAt < PULSE and "on" or "off")
end

local function set(name, value)
    if state[name] == value or state[name] == "on" then return end
    state[name] = value
    local color = COLORS[value]
    squares[name]:SetColorTexture(color[1], color[2], color[3], 1)
    WeHavePhonesE2EDB = WeHavePhonesE2EDB or {}
    WeHavePhonesE2EDB[name] = value
    WeHavePhonesE2EDB[name .. "At"] = GetTime()
end

local function onJump()
    pressedAt = GetTime()
end

local function showJump()
    local now = GetTime()
    local value = now - fellAt < PULSE and "on" or now - pressedAt < PULSE and "pressed" or "off"
    if state.jumped == value then return end
    state.jumped = value
    local color = COLORS[value]
    squares.jumped:SetColorTexture(color[1], color[2], color[3], 1)
end

-- The runner presses Enter at character select. A press that lands after the
-- loading screen opens the chat box, and Space would then type into it.
local function closeChat()
    local editBox = type(ChatEdit_GetActiveWindow) == "function" and ChatEdit_GetActiveWindow()
    if editBox and type(ChatEdit_DeactivateChat) == "function" then ChatEdit_DeactivateChat(editBox) end
end

-- JumpOrAscendStart is what the JUMP binding runs from the keyboard or the
-- touch controls' Jump key. Gamepad bindings may jump without it.
if type(JumpOrAscendStart) == "function" then
    hooked = pcall(hooksecurefunc, "JumpOrAscendStart", onJump)
end

-- Retail can return facing as a secret value: it looks like a number, but math
-- on it raises an error. Either case counts as "facing not available".
local function checkTurn()
    local facing = GetPlayerFacing and GetPlayerFacing()
    if type(facing) ~= "number" or (type(issecretvalue) == "function" and issecretvalue(facing)) then
        set("turned", "pressed")
        return
    end
    if not firstFacing then firstFacing = facing end
    local turn = math.abs(facing - firstFacing)
    if math.min(turn, 2 * math.pi - turn) > 0.25 then set("turned", "on") end
end

strip:SetScript("OnUpdate", function()
    local since = GetTime() - (enteredAt or GetTime())
    if since < 8 then closeChat() end
    local falling = IsFalling()
    if falling and not wasFalling then fellAt = GetTime() end
    wasFalling = falling
    showJump()
    local speed = GetUnitSpeed("player")
    if speed and speed > 0 and not IsFalling() then set("moved", "on") end
    if state.turned ~= "on" and not pcall(checkTurn) then set("turned", "pressed") end
    watchWheel()
end)

local events = CreateFrame("Frame")
events:RegisterEvent("PLAYER_ENTERING_WORLD")
events:RegisterEvent("ADDON_ACTION_BLOCKED")
events:RegisterEvent("ADDON_ACTION_FORBIDDEN")
events:SetScript("OnEvent", function(_, event, name, action)
    if event ~= "PLAYER_ENTERING_WORLD" then
        if name == TOUCH then report(event .. " " .. TOUCH .. " " .. tostring(action)) end
        return
    end
    watchErrors()
    local problems = WeHavePhonesE2EDB and WeHavePhonesE2EDB.problems
    WeHavePhonesE2EDB = { hooked = hooked and true or false, build = (GetBuildInfo()), problems = problems }
    enteredAt = GetTime()
    strip:Show()
    set("inWorld", "on")
end)

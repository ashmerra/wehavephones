-- WeHavePhones end-to-end test marker.
--
-- scripts/e2e-wehavephones.py copies this addon into the game under test, writes
-- its .toc for the client's interface version and removes it afterwards. It never
-- ships with the app. The addon draws a strip of colored squares near the top of
-- the screen that the runner reads from an Android screenshot:
--
--   magenta | in world | jumped | moved | turned | cyan
--
-- In world, moved and turned are black until their event happens and then stay
-- green. Jumped is a pulse instead: green for five seconds after the character
-- leaves the ground, yellow for five seconds after the jump binding ran without
-- the character leaving the ground, black otherwise. The runner waits for black,
-- jumps and looks for green, so a fall while the world streams in never counts.
-- Turned is yellow when the client does not report the player's facing (Retail
-- can hide it), so the runner measures the turn on screen instead.
-- The magenta and cyan ends let the runner find the strip at any resolution.
-- Works on every client from Classic Era to Retail: it uses only APIs that all
-- of them share.

local SIZE, GAP = 26, 4
local ORDER = { "inWorld", "jumped", "moved", "turned" }
local COLORS = {
    off = { 0, 0, 0 },
    on = { 0, 1, 0 },
    pressed = { 1, 1, 0 },
}

local state = { inWorld = "off", jumped = "off", moved = "off", turned = "off" }
local enteredAt, firstFacing, hooked, wasFalling
local fellAt, pressedAt = -100, -100
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
end)

local events = CreateFrame("Frame")
events:RegisterEvent("PLAYER_ENTERING_WORLD")
events:SetScript("OnEvent", function()
    WeHavePhonesE2EDB = { hooked = hooked and true or false, build = (GetBuildInfo()) }
    enteredAt = GetTime()
    strip:Show()
    set("inWorld", "on")
end)

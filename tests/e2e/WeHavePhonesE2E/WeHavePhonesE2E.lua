-- WeHavePhones end-to-end test marker.
--
-- scripts/e2e-wehavephones.py copies this addon into the game under test, writes
-- its .toc for the client's interface version and removes it afterwards. It never
-- ships with the app. The addon draws a strip of colored squares near the top of
-- the screen that the runner reads from an Android screenshot:
--
--   magenta | in world | jumped | moved | turned | cyan
--
-- Each flag square is black until its event happens and then turns green. Jumped
-- turns green when the character leaves the ground more than three seconds after
-- entering the world (a character standing still never falls by itself), and
-- yellow when the jump binding ran but the character never left the ground.
-- The magenta and cyan ends let the runner find the strip at any resolution.
-- Flags only go from black to green during a session.
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
local enteredAt, firstFacing, hooked

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
    set("jumped", "pressed")
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

strip:SetScript("OnUpdate", function()
    local since = GetTime() - (enteredAt or GetTime())
    if since < 8 then closeChat() end
    if IsFalling() and since > 3 then set("jumped", "on") end
    local speed = GetUnitSpeed("player")
    if speed and speed > 0 and not IsFalling() then set("moved", "on") end
    local facing = GetPlayerFacing and GetPlayerFacing()
    if facing then
        if not firstFacing then firstFacing = facing end
        local turn = math.abs(facing - firstFacing)
        if math.min(turn, 2 * math.pi - turn) > 0.35 then set("turned", "on") end
    end
end)

local events = CreateFrame("Frame")
events:RegisterEvent("PLAYER_ENTERING_WORLD")
events:SetScript("OnEvent", function()
    WeHavePhonesE2EDB = { hooked = hooked and true or false, build = (GetBuildInfo()) }
    enteredAt = GetTime()
    strip:Show()
    set("inWorld", "on")
end)

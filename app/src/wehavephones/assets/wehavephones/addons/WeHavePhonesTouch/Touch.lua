local _, addon = ...
local frame = CreateFrame("Frame")
local enabled = addon.enabled
local applied = false

-- Temporary overrides leave the player's saved keyboard and controller bindings intact.
local bindings = {
    W = "MOVEFORWARD", S = "MOVEBACKWARD", A = "STRAFELEFT", D = "STRAFERIGHT",
    SPACE = "JUMP", TAB = "TARGETNEARESTENEMY", F = "INTERACTTARGET",
    B = "OPENALLBAGS", M = "TOGGLEWORLDMAP",
    N = "NEXTACTIONPAGE",
}

local function SetSavedCVar(name, value)
    local current = GetCVar(name)
    if current == nil then return end
    if WeHavePhonesTouchDB.cvars[name] == nil then
        WeHavePhonesTouchDB.cvars[name] = current
    end
    SetCVar(name, value)
end

local function Layout()
    if not enabled or InCombatLockdown() then return end
    -- Classic keeps the stock secure buttons and their parent, including paging, macros,
    -- cooldowns and class/stance behaviour. Only their size and anchor points change.
    if not WOW_PROJECT_CLASSIC or WOW_PROJECT_ID ~= WOW_PROJECT_CLASSIC then return end
    local width = UIParent:GetWidth()
    local cell = math.min(width * .075, UIParent:GetHeight() * .105)
    local scale = (cell - 6) / 36
    local bottom = 48
    for i = 1, 12 do
        local button = _G["ActionButton" .. i]
        if button then
            button:SetScale(scale)
            button:ClearAllPoints()
            local col = (i - 1) % 6
            local row = math.floor((i - 1) / 6)
            button:SetPoint("BOTTOMLEFT", UIParent, "BOTTOM", (col - 3) * cell / scale,
                (bottom + (1 - row) * cell) / scale)
            button:SetAttribute("showgrid", 1)
            button:Show()
        end
    end
    -- Suppress decoration without hiding the secure parent or its micro menu and bags.
    for _, name in ipairs({"MainMenuBarTexture0", "MainMenuBarTexture1", "MainMenuBarTexture2",
        "MainMenuBarTexture3", "MainMenuBarLeftEndCap", "MainMenuBarRightEndCap"}) do
        local texture = _G[name]
        if texture then texture:SetAlpha(0) end
    end
end

local function Apply()
    if InCombatLockdown() then return end
    WeHavePhonesTouchDB = WeHavePhonesTouchDB or {}
    local db = WeHavePhonesTouchDB
    db.cvars = db.cvars or {}
    if enabled then
        if not applied then
            SetSavedCVar("GamePadEnable", "0")
            SetSavedCVar("InputDeviceInterfaceStyle", "0")
            SetSavedCVar("useUiScale", "1")
            local previous = tonumber(db.cvars.uiScale or GetCVar("uiScale")) or .8
            SetSavedCVar("uiScale", tostring(math.max(previous, math.min(1, previous * 1.2))))
            for key, action in pairs(bindings) do SetOverrideBinding(frame, true, key, action) end
            applied = true
            print("WeHavePhones touch controls: tap spells in the action bars. Drag on the right to look.")
        end
        Layout()
    elseif next(db.cvars) then
        -- This small addon stays installed after opt-out so every character can restore
        -- its own CVars on its next login. Stock frame positions reset on a fresh load.
        for name, value in pairs(db.cvars) do SetCVar(name, value) end
        db.cvars = {}
        ClearOverrideBindings(frame)
    end
end

frame:RegisterEvent("PLAYER_LOGIN")
frame:RegisterEvent("PLAYER_ENTERING_WORLD")
frame:RegisterEvent("PLAYER_REGEN_ENABLED")
frame:RegisterEvent("UI_SCALE_CHANGED")
frame:SetScript("OnEvent", function(_, event)
    if event == "UI_SCALE_CHANGED" then
        C_Timer.After(0, Layout)
    else
        Apply()
    end
end)

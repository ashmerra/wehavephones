local _, addon = ...
local enabled = addon.enabled
local frame = CreateFrame("Frame")
local applied, editor, controls, selectedSlot, chatHooked, positioningChat
local category, page, entries = "Spells", 1, {}
local undo
local buttons = {}
local Layout, OpenEditor, RefreshEditor

-- Overrides last for this UI session and never overwrite saved bindings.
local bindings = {
    W = "MOVEFORWARD", S = "MOVEBACKWARD", A = "STRAFELEFT", D = "STRAFERIGHT",
    SPACE = "JUMP", TAB = "TARGETNEARESTENEMY", ["SHIFT-TAB"] = "TARGETPREVIOUSENEMY",
    F = "INTERACTTARGET", B = "OPENALLBAGS", M = "TOGGLEWORLDMAP",
    N = "NEXTACTIONPAGE", R = "TOGGLEAUTORUN", X = "SITORSTAND",
}

local function Notice(text)
    UIErrorsFrame:AddMessage(text, 1, .8, .2)
end

local function CanEdit()
    if InCombatLockdown() then Notice("Hotbars can be changed after combat.") return false end
    if GetCursorInfo() then Notice("Finish moving the item on your cursor first.") return false end
    return true
end

local function SaveCVar(name, value)
    local current = GetCVar(name)
    if current == nil then return end
    if WeHavePhonesTouchDB.cvars[name] == nil then WeHavePhonesTouchDB.cvars[name] = current end
    SetCVar(name, value)
end

local function UniformScale()
    if not enabled or InCombatLockdown() then return end
    local scale = math.max(1, math.min(1.75, tonumber(WeHavePhonesTouchDB.touchScale) or 1.5))
    -- The whole UI inherits this scale. Do not multiply an already scaled parent.
    if math.abs(UIParent:GetScale() - scale) > .001 then UIParent:SetScale(scale) end
end

local function Panel(name, title, width, height)
    local panel = CreateFrame("Frame", name, UIParent, "BackdropTemplate")
    panel:SetSize(width, height)
    panel:SetPoint("CENTER")
    panel:SetFrameStrata("DIALOG")
    panel:EnableMouse(true)
    panel:SetClampedToScreen(true)
    panel:SetBackdrop({bgFile="Interface\\DialogFrame\\UI-DialogBox-Background",
        edgeFile="Interface\\DialogFrame\\UI-DialogBox-Border", tile=true, tileSize=32, edgeSize=24,
        insets={left=6, right=6, top=6, bottom=6}})
    panel.title = panel:CreateFontString(nil, "OVERLAY", "GameFontNormalLarge")
    panel.title:SetPoint("TOP", 0, -18)
    panel.title:SetText(title)
    tinsert(UISpecialFrames, name)
    panel:Hide()
    return panel
end

local function Button(parent, name, text, x, y, width, height, callback)
    local button = CreateFrame("Button", name, parent, "UIPanelButtonTemplate")
    button:SetSize(width, height)
    button:SetPoint("TOPLEFT", x, y)
    button:SetText(text)
    button:SetScript("OnClick", callback)
    return button
end

local function Collect()
    entries = {}
    if category == "Spells" then
        for tab = 1, GetNumSpellTabs() do
            local _, _, offset, count = GetSpellTabInfo(tab)
            for slot = offset + 1, offset + count do
                local kind = GetSpellBookItemInfo(slot, BOOKTYPE_SPELL)
                if kind == "SPELL" and not IsPassiveSpell(slot, BOOKTYPE_SPELL) then
                    local name, rank = GetSpellBookItemName(slot, BOOKTYPE_SPELL)
                    local bookSlot = slot
                    if name then tinsert(entries, {name=name .. (rank and rank ~= "" and "\n" .. rank or ""),
                        icon=GetSpellBookItemTexture(slot, BOOKTYPE_SPELL),
                        pickup=function() PickupSpellBookItem(bookSlot, BOOKTYPE_SPELL) end}) end
                end
            end
        end
    elseif category == "Items" then
        for bag = 0, 4 do
            for slot = 1, C_Container.GetContainerNumSlots(bag) do
                local info = C_Container.GetContainerItemInfo(bag, slot)
                if info and not info.isLocked then
                    local bagID, bagSlot = bag, slot
                    local name = GetItemInfo(info.hyperlink or info.itemID) or "Item"
                    tinsert(entries, {name=name, icon=info.iconFileID,
                        pickup=function() C_Container.PickupContainerItem(bagID, bagSlot) end})
                end
            end
        end
    else
        local account, character = GetNumMacros()
        for i = 1, (MAX_ACCOUNT_MACROS or 120) + character do
            if i <= account or i > (MAX_ACCOUNT_MACROS or 120) then
                local macro = i
                local name, icon = GetMacroInfo(macro)
                if name then tinsert(entries, {name=name, icon=icon, pickup=function() PickupMacro(macro) end}) end
            end
        end
    end
end

local function Snapshot(slot)
    local kind, id, subtype = GetActionInfo(slot)
    return {slot=slot, kind=kind, id=id, subtype=subtype}
end

local function Place(entry)
    if not selectedSlot or not CanEdit() then return end
    local previous = Snapshot(selectedSlot)
    entry.pickup()
    if not GetCursorInfo() then Notice("That action could not be picked up.") return end
    PlaceAction(selectedSlot)
    ClearCursor()
    undo = previous
    editor:Hide()
end

local function ClearSlot()
    if not selectedSlot or not CanEdit() then return end
    undo = Snapshot(selectedSlot)
    PickupAction(selectedSlot)
    ClearCursor()
    editor:Hide()
end

local function Undo()
    if not undo or not CanEdit() then return end
    local previous = Snapshot(undo.slot)
    if not undo.kind then PickupAction(undo.slot)
    elseif undo.kind == "spell" then PickupSpell(undo.id)
    elseif undo.kind == "item" then PickupItem(undo.id)
    elseif undo.kind == "macro" then PickupMacro(undo.id)
    else Notice("This action type must be restored from its own game window.") return end
    if undo.kind and not GetCursorInfo() then Notice("That action is no longer available.") return end
    if undo.kind then PlaceAction(undo.slot) end
    ClearCursor()
    undo = previous
    if editor then editor:Hide() end
end

RefreshEditor = function()
    Collect()
    local pages = math.max(1, math.ceil(#entries / 6))
    page = math.max(1, math.min(page, pages))
    editor.title:SetText("Choose action for slot " .. selectedSlot)
    editor.page:SetText(category .. "  " .. page .. " / " .. pages)
    for i, button in ipairs(editor.choices) do
        local entry = entries[(page - 1) * 6 + i]
        button.entry = entry
        button:SetShown(entry ~= nil)
        if entry then button.icon:SetTexture(entry.icon) button.label:SetText(entry.name) end
    end
    editor.empty:SetShown(#entries == 0)
    editor.undo:SetEnabled(undo ~= nil)
end

OpenEditor = function(button)
    if not enabled or not CanEdit() then return end
    local slot = button.action
    if not slot then return end
    selectedSlot, page = slot, 1
    if not editor then
        editor = Panel("WeHavePhonesTouchEditor", "Choose action", 450, 384)
        editor.choices = {}
        for i, text in ipairs({"Spells", "Items", "Macros"}) do
            Button(editor, nil, text, 18 + (i - 1) * 138, -48, 132, 32, function()
                category, page = text, 1
                RefreshEditor()
            end)
        end
        for i = 1, 6 do
            local choice = Button(editor, "WeHavePhonesTouchChoice" .. i, "",
                18 + ((i - 1) % 2) * 210, -94 - math.floor((i - 1) / 2) * 66, 204, 60,
                function(self) if self.entry then Place(self.entry) end end)
            choice.icon = choice:CreateTexture(nil, "ARTWORK")
            choice.icon:SetSize(36, 36)
            choice.icon:SetPoint("LEFT", 8, 0)
            choice.label = choice:CreateFontString(nil, "OVERLAY", "GameFontNormal")
            choice.label:SetPoint("LEFT", 50, 0)
            choice.label:SetWidth(146)
            choice.label:SetJustifyH("LEFT")
            editor.choices[i] = choice
        end
        editor.empty = editor:CreateFontString(nil, "OVERLAY", "GameFontHighlight")
        editor.empty:SetPoint("CENTER")
        editor.empty:SetText("Nothing available in this category.")
        Button(editor, nil, "Previous", 18, -294, 102, 32, function() page=page-1 RefreshEditor() end)
        Button(editor, nil, "Next", 330, -294, 102, 32, function() page=page+1 RefreshEditor() end)
        editor.page = editor:CreateFontString(nil, "OVERLAY", "GameFontNormal")
        editor.page:SetPoint("TOP", 0, -304)
        Button(editor, nil, "Clear slot", 18, -338, 126, 32, ClearSlot)
        editor.undo = Button(editor, nil, "Undo", 162, -338, 126, 32, Undo)
        Button(editor, nil, "Cancel", 306, -338, 126, 32, function() editor:Hide() end)
    end
    RefreshEditor()
    editor:Show()
end

local function ShowControls()
    if InCombatLockdown() then Notice("Touch interface settings are available after combat.") return end
    if not controls then
        controls = Panel("WeHavePhonesTouchControls", "Touch interface", 426, 398)
        controls.size = controls:CreateFontString(nil, "OVERLAY", "GameFontNormal")
        controls.size:SetPoint("TOP", 0, -54)
        for i, scale in ipairs({1.25, 1.5, 1.75}) do
            Button(controls, nil, string.format("%d%%", scale * 100), 18 + (i-1)*132, -76, 126, 36, function()
                WeHavePhonesTouchDB.touchScale = scale
                UniformScale()
                Layout()
                controls.size:SetText(string.format("All interface elements: %d%%", scale * 100))
            end)
        end
        local actions = {
            {"Character", function() ToggleCharacter("PaperDollFrame") end},
            {"Spellbook", function() ToggleSpellBook(BOOKTYPE_SPELL) end},
            {"Quests", function() ToggleQuestLog() end},
            {"Talents", function() ToggleTalentFrame() end},
            {"Social", function() ToggleFriendsFrame() end},
            {"Next bar page", function() ActionBar_PageUp() end},
            {"Undo last edit", Undo},
            {"Game menu", function() ToggleGameMenu() end},
        }
        for i, action in ipairs(actions) do
            Button(controls, nil, action[1], 18+((i-1)%2)*198, -132-math.floor((i-1)/2)*46,
                192, 38, function() controls:Hide() action[2]() end)
        end
        local hint = controls:CreateFontString(nil, "OVERLAY", "GameFontHighlightSmall")
        hint:SetPoint("TOP", 0, -324)
        hint:SetWidth(384)
        hint:SetText("Hold a hotbar slot to edit it. Hold elsewhere to right-click.\nPinch to zoom. Use window gestures for dragging and scrolling.")
        Button(controls, nil, "Close", 132, -352, 162, 32, function() controls:Hide() end)
    end
    controls.size:SetText(string.format("All interface elements: %d%%", (WeHavePhonesTouchDB.touchScale or 1.5)*100))
    controls:Show()
end

local function PositionChat()
    if not enabled or InCombatLockdown() or not ChatFrame1 or positioningChat then return end
    positioningChat = true
    local db = WeHavePhonesTouchDB
    if not db.chat then
        local point, relative, relativePoint, x, y = ChatFrame1:GetPoint(1)
        db.chat = {point=point, relative=relative and relative:GetName(), relativePoint=relativePoint,
            x=x, y=y, width=ChatFrame1:GetWidth(), height=ChatFrame1:GetHeight()}
    end
    if ChatFrame1.BreakFromFrameManager then ChatFrame1:BreakFromFrameManager() end
    ChatFrame1:ClearAllPoints()
    ChatFrame1:SetPoint("BOTTOMLEFT", UIParent, "LEFT", 16, 36)
    ChatFrame1:SetSize(math.min(300, UIParent:GetWidth()*.34), 92)
    positioningChat = false
end

Layout = function()
    if not enabled or InCombatLockdown() then return end
    if WOW_PROJECT_ID ~= WOW_PROJECT_CLASSIC then return end
    if MultiBarBottomLeft then MultiBarBottomLeft:Show() end
    if MultiBarBottomRight then MultiBarBottomRight:Show() end
    if VIEWABLE_ACTION_BAR_PAGES then
        VIEWABLE_ACTION_BAR_PAGES[5], VIEWABLE_ACTION_BAR_PAGES[6] = nil, nil
    end
    for i = 1, 36 do
        local button
        if i <= 12 then button = _G["ActionButton" .. i]
        elseif i <= 24 then button = _G["MultiBarBottomLeftButton" .. (i-12)]
        else button = _G["MultiBarBottomRightButton" .. (i-24)] end
        if button then
            -- Every widget inherits the same parent scale, with no per-button enlargement.
            button:SetScale(1)
            button:ClearAllPoints()
            if i <= 24 then
                button:SetPoint("BOTTOMLEFT", UIParent, "BOTTOMRIGHT", -256+((i-1)%6)*40, 64+(3-math.floor((i-1)/6))*40)
            else
                local index = i-25
                button:SetPoint("BOTTOMLEFT", UIParent, "BOTTOM", -120+(index%6)*40, 64+(1-math.floor(index/6))*40)
            end
            button:SetAttribute("showgrid", 1)
            button:SetAttribute("type2", "")
            button:RegisterForClicks("LeftButtonUp", "LeftButtonDown")
            button:Show()
            if not buttons[button] then
                button:HookScript("OnMouseUp", function(self, mouse)
                    if mouse == "RightButton" then OpenEditor(self) end
                end)
                buttons[button] = true
            end
        end
    end
    for _, name in ipairs({"MainMenuBarTexture0", "MainMenuBarTexture1", "MainMenuBarTexture2",
        "MainMenuBarTexture3", "MainMenuBarLeftEndCap", "MainMenuBarRightEndCap"}) do
        if _G[name] then _G[name]:SetAlpha(0) end
    end
    PositionChat()
    if not chatHooked and ChatFrame1 then
        -- Panel and layout updates can move chat again after login. Reapply only
        -- when its anchor changes, without editing the player's saved layout.
        hooksecurefunc(ChatFrame1, "SetPoint", PositionChat)
        chatHooked = true
    end
end

local function Apply()
    if InCombatLockdown() then return end
    WeHavePhonesTouchDB = WeHavePhonesTouchDB or {}
    local db = WeHavePhonesTouchDB
    db.cvars = db.cvars or {}
    if enabled then
        if not applied then
            applied = true
            if db.originalRootScale == nil then db.originalRootScale = UIParent:GetScale() end
            SaveCVar("GamePadEnable", "0")
            SaveCVar("InputDeviceInterfaceStyle", "0")
            SaveCVar("useUiScale", "1")
            SaveCVar("uiScale", "1")
            if WOW_PROJECT_ID == WOW_PROJECT_CLASSIC and GetActionBarToggles then
                local toggles = {GetActionBarToggles()}
                for i = 1, 7 do toggles[i] = toggles[i] == true or toggles[i] == 1 end
                if not db.actionBars then
                    db.actionBars = {}
                    for i = 1, 7 do db.actionBars[i] = toggles[i] and true or false end
                end
                toggles[1], toggles[2] = true, true
                SetActionBarToggles(unpack(toggles))
            end
            for key, action in pairs(bindings) do SetOverrideBinding(frame, true, key, action) end
            local trigger = CreateFrame("Button", "WeHavePhonesTouchMenuTrigger", UIParent)
            trigger:RegisterForClicks("AnyUp", "AnyDown")
            trigger:SetSize(1, 1)
            trigger:SetPoint("TOPLEFT")
            trigger:SetScript("OnClick", ShowControls)
            trigger:Show()
            SetOverrideBindingClick(frame, true, "F9", "WeHavePhonesTouchMenuTrigger")
            print("WeHavePhones touch: hold a slot to edit. More opens interface size, keyboard and window gestures.")
        end
        UniformScale()
        Layout()
    else
        for name, value in pairs(db.cvars) do SetCVar(name, value) end
        db.cvars = {}
        if db.originalRootScale then UIParent:SetScale(db.originalRootScale) db.originalRootScale=nil end
        if db.actionBars and SetActionBarToggles then
            SetActionBarToggles(unpack(db.actionBars))
            db.actionBars = nil
        end
        if db.chat and ChatFrame1 then
            ChatFrame1:ClearAllPoints()
            ChatFrame1:SetPoint(db.chat.point, (db.chat.relative and _G[db.chat.relative]) or UIParent, db.chat.relativePoint, db.chat.x, db.chat.y)
            ChatFrame1:SetSize(db.chat.width, db.chat.height)
            db.chat = nil
        end
        ClearOverrideBindings(frame)
    end
end

frame:RegisterEvent("PLAYER_LOGIN")
frame:RegisterEvent("PLAYER_ENTERING_WORLD")
frame:RegisterEvent("PLAYER_REGEN_ENABLED")
frame:RegisterEvent("PLAYER_REGEN_DISABLED")
frame:RegisterEvent("UI_SCALE_CHANGED")
frame:SetScript("OnEvent", function(_, event)
    if event == "PLAYER_REGEN_DISABLED" then
        if editor then editor:Hide() end
        if controls then controls:Hide() end
    elseif event == "UI_SCALE_CHANGED" then
        C_Timer.After(0, function() if enabled and applied then UniformScale() Layout() end end)
    else Apply() end
end)

local _, addon = ...
local enabled = addon.enabled
local frame = CreateFrame("Frame")
local applied, editor, controls, selectedSlot, chatHooked, positioningChat, pager
local category, page, entries = "Spells", 1, {}
local undo
local buttons = {}
local Layout, OpenEditor, RefreshEditor, LayoutWorkspace, QueueWorkspace
local ACTION_SCALE = 1.35
local WHITE = "Interface\\Buttons\\WHITE8X8"

-- Overrides last for this UI session and never overwrite saved bindings.
local bindings = {
    W = "MOVEFORWARD", S = "MOVEBACKWARD", A = "STRAFELEFT", D = "STRAFERIGHT",
    SPACE = "JUMP", TAB = "TARGETNEARESTENEMY", ["SHIFT-TAB"] = "TARGETPREVIOUSENEMY",
    F = "INTERACTTARGET", B = "OPENALLBAGS", M = "TOGGLEWORLDMAP",
    N = "NEXTACTIONPAGE", R = "TOGGLEAUTORUN", X = "SITORSTAND",
    F1 = "TARGETSELF", F2 = "TARGETPARTYMEMBER1", F3 = "TARGETPARTYMEMBER2",
    F4 = "TARGETPARTYMEMBER3", F5 = "TARGETPARTYMEMBER4", F6 = "TARGETNEARESTFRIEND",
    F7 = "TOGGLERUN", G = "ASSISTTARGET", F11 = "SITORSTAND",
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

local function LayoutWidth()
    -- Reserve the outer thumb rails even on 4:3 and 16:10 displays.
    return math.min(UIParent:GetWidth()*.80, UIParent:GetHeight()*16/9)
end

local function CompactColumns()
    return UIParent:GetWidth()/UIParent:GetHeight()<1.25 and 3 or 4
end

local function ActionFit()
    local aspect = UIParent:GetWidth()/UIParent:GetHeight()
    if aspect<1.25 then return math.min(1, UIParent:GetHeight()/548) end
    if aspect<1.6 then return math.min(1, UIParent:GetWidth()/760) end
    return math.min(1, LayoutWidth()/760)
end

local function SetRelativeScale(object, scale)
    -- Some stock frames, notably the map, ignore their parent's scale. Use
    -- the observed effective scale instead of assuming a UIParent child inherits it.
    local inherited = object:GetEffectiveScale()/math.max(.001, object:GetScale())
    object:SetScale(scale*UIParent:GetEffectiveScale()/math.max(.001, inherited))
end

local function FitPanel(panel)
    if not panel or InCombatLockdown() then return end
    local scale = math.min(1, UIParent:GetWidth()*.94/panel:GetWidth(), UIParent:GetHeight()*.78/panel:GetHeight())
    SetRelativeScale(panel, scale)
    panel:ClearAllPoints()
    panel:SetPoint("CENTER", UIParent, "CENTER", 0, -UIParent:GetHeight()*.08/scale)
end

local function Panel(name, title, width, height)
    local panel = CreateFrame("Frame", name, UIParent, "BackdropTemplate")
    panel:SetSize(width, height)
    panel:SetPoint("CENTER")
    panel:SetFrameStrata("DIALOG")
    panel:EnableMouse(true)
    panel:SetClampedToScreen(true)
    panel:SetBackdrop({bgFile=WHITE, edgeFile=WHITE, edgeSize=1})
    panel:SetBackdropColor(.035, .086, .157, .98)
    panel:SetBackdropBorderColor(.55, .77, .97, .9)
    panel.brand = panel:CreateFontString(nil, "OVERLAY", "GameFontHighlightSmall")
    panel.brand:SetPoint("TOP", 0, -12)
    panel.brand:SetText("We|cff62b2f2Have|rPhones")
    panel.title = panel:CreateFontString(nil, "OVERLAY", "GameFontNormalLarge")
    panel.title:SetPoint("TOP", 0, -28)
    panel.title:SetTextColor(.93, .96, 1)
    panel.title:SetText(title)
    FitPanel(panel)
    tinsert(UISpecialFrames, name)
    panel:Hide()
    return panel
end

-- The app's raised keys: navy, or blue for the current choice, over a dark lip.
local KEY = {{.04, .14, .28}, {.09, .25, .42}, {.24, .40, .58}}
local ACTIVE = {{.06, .35, .69}, {.13, .45, .73}, {.46, .74, .96}}
local function PaintKey(button, active)
    local colors = active and ACTIVE or KEY
    local bottom, top, edge = colors[1], colors[2], colors[3]
    if button.face.SetGradient and CreateColor then
        button.face:SetGradient("VERTICAL", CreateColor(bottom[1], bottom[2], bottom[3], .97), CreateColor(top[1], top[2], top[3], .97))
    else button.face:SetVertexColor(top[1], top[2], top[3], .97) end
    button:SetBackdropBorderColor(edge[1], edge[2], edge[3], 1)
    local label = button:GetFontString()
    if label then label:SetTextColor(active and 1 or .93, active and 1 or .96, 1) end
    button.active = active and true or false
end

local function Button(parent, name, text, x, y, width, height, callback, template)
    local button = CreateFrame("Button", name, parent, template or "BackdropTemplate")
    button:SetSize(width, height)
    button:SetPoint("TOPLEFT", x, y)
    button:SetBackdrop({bgFile=WHITE, edgeFile=WHITE, edgeSize=1})
    button:SetBackdropColor(.024, .086, .176, 1)
    button.face = button:CreateTexture(nil, "BACKGROUND", nil, 1)
    button.face:SetTexture(WHITE)
    button.face:SetPoint("TOPLEFT", 1, -1)
    button.face:SetPoint("BOTTOMRIGHT", -1, 3)
    button:SetHighlightTexture(WHITE)
    button:GetHighlightTexture():SetVertexColor(.55, .77, .97, .22)
    local label = button:CreateFontString(nil, "OVERLAY", "GameFontHighlight")
    label:SetPoint("CENTER")
    label:SetWidth(width-12)
    label:SetText(text)
    button:SetFontString(label)
    PaintKey(button, false)
    if callback then button:SetScript("OnClick", callback) end
    return button
end

local function UpdatePager()
    if not pager then return end
    local current = C_ActionBar.GetActionBarPage()
    pager.label:SetText(tostring(current))
    -- Six dots show that more pages exist and which one is in use.
    for i, dot in ipairs(pager.dots) do
        if i == current then dot:SetVertexColor(.46, .74, .96, 1) else dot:SetVertexColor(.55, .62, .72, .55) end
    end
end

local function Chevron(button, direction)
    if not button.CreateLine then button:SetText(direction < 0 and "<" or ">") return end
    for _, y in ipairs({8, -8}) do
        local line = button:CreateLine(nil, "OVERLAY")
        line:SetThickness(3)
        line:SetColorTexture(.93, .96, 1, 1)
        line:SetStartPoint("CENTER", button, -4*direction, y)
        line:SetEndPoint("CENTER", button, 4*direction, 0)
    end
end

local function CreatePager()
    if pager then return end
    pager = CreateFrame("Frame", "WeHavePhonesTouchPager", UIParent)
    pager:SetSize(120, 48)
    pager.label = pager:CreateFontString(nil, "OVERLAY", "GameFontHighlight")
    pager.label:SetPoint("CENTER", 0, 4)
    pager.label:SetWidth(32)
    pager.dots = {}
    for i = 1, 6 do
        local dot = pager:CreateTexture(nil, "OVERLAY")
        dot:SetTexture(WHITE)
        dot:SetSize(3, 3)
        dot:SetPoint("CENTER", pager, "CENTER", (i-3.5)*5, -10)
        pager.dots[i] = dot
    end
    for i, direction in ipairs({"decrement", "increment"}) do
        local button = Button(pager, "WeHavePhonesTouchPage" .. i, "",
            (i-1)*76, 0, 44, 48, nil, "SecureActionButtonTemplate,BackdropTemplate")
        Chevron(button, i == 1 and -1 or 1)
        button:RegisterForClicks("AnyUp", "AnyDown")
        button:SetAttribute("type", "actionbar")
        button:SetAttribute("action", direction)
    end
    UpdatePager()
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
    for _, tab in ipairs(editor.tabs) do PaintKey(tab, tab.category == category) end
end

OpenEditor = function(button)
    if not enabled or not CanEdit() then return end
    local slot = button.action
    if not slot then return end
    selectedSlot, page = slot, 1
    if not editor then
        editor = Panel("WeHavePhonesTouchEditor", "Choose action", 450, 384)
        editor.choices, editor.tabs = {}, {}
        for i, text in ipairs({"Spells", "Items", "Macros"}) do
            local tab = Button(editor, nil, text, 18 + (i - 1) * 138, -48, 132, 32, function()
                category, page = text, 1
                RefreshEditor()
            end)
            tab.category = text
            editor.tabs[i] = tab
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
    FitPanel(editor)
    editor:Show()
end

local function ShowControls()
    if InCombatLockdown() then Notice("Touch interface settings are available after combat.") return end
    if not controls then
        controls = Panel("WeHavePhonesTouchControls", "Touch interface", 540, 398)
        controls.size = controls:CreateFontString(nil, "OVERLAY", "GameFontNormal")
        controls.size:SetPoint("TOP", 0, -54)
        for i, scale in ipairs({1.25, 1.5, 1.75}) do
            Button(controls, nil, string.format("%d%%", scale * 100), 18 + (i-1)*170, -76, 164, 36, function()
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
            {"Macros", function() ShowMacroFrame() end},
            {"Skills", function() ToggleCharacter("SkillFrame") end},
            {"Reputation", function() ToggleCharacter("ReputationFrame") end},
            {"PvP", function() ToggleCharacter("HonorFrame") end},
            {"Undo last edit", Undo},
            {"Game menu", function() ToggleGameMenu() end},
            {"Help", function() ToggleHelpFrame() end},
        }
        for i, action in ipairs(actions) do
            Button(controls, nil, action[1], 18+((i-1)%3)*170, -132-math.floor((i-1)/3)*46,
                164, 38, function() controls:Hide() action[2]() end)
        end
        local hint = controls:CreateFontString(nil, "OVERLAY", "GameFontHighlightSmall")
        hint:SetPoint("TOP", 0, -324)
        hint:SetWidth(504)
        hint:SetText("Windows clears the thumb controls for menus. Inspect reads tooltips without clicking.\nHold a slot to edit. Hold elsewhere to right-click. Tap Play to return to camera gestures.")
        Button(controls, nil, "Close", 189, -352, 162, 32, function() controls:Hide() end)
    end
    controls.size:SetText(string.format("All interface elements: %d%%", (WeHavePhonesTouchDB.touchScale or 1.5)*100))
    FitPanel(controls)
    controls:Show()
end

local function PositionChat()
    if not enabled or not ChatFrame1 or positioningChat then return end
    if InCombatLockdown() and ChatFrame1:IsProtected() then return end
    positioningChat = true
    local db = WeHavePhonesTouchDB
    if not db.chat then
        local point, relative, relativePoint, x, y = ChatFrame1:GetPoint(1)
        db.chat = {point=point, relative=relative and relative:GetName(), relativePoint=relativePoint,
            x=x, y=y, width=ChatFrame1:GetWidth(), height=ChatFrame1:GetHeight()}
    end
    if not InCombatLockdown() and ChatFrame1.BreakFromFrameManager then ChatFrame1:BreakFromFrameManager() end
    ChatFrame1:ClearAllPoints()
    local party = PartyFrame and PartyFrame.MemberFrame1 and PartyFrame.MemberFrame1:IsShown()
    ChatFrame1:SetPoint("BOTTOMLEFT", UIParent, "CENTER", -LayoutWidth()/2+16,
        party and -UIParent:GetHeight()*.02 or 36)
    ChatFrame1:SetSize(math.min(300, LayoutWidth()*.34), party and math.min(92, UIParent:GetHeight()*.10) or 92)
    positioningChat = false
end

local function PositionActionTooltip(button)
    if not enabled or not ChatFrame1 or not GameTooltip:IsOwned(button) then return end
    -- Keep the owner and spell contents while replacing WoW's corner anchor.
    GameTooltip:SetAnchorType("ANCHOR_NONE")
    GameTooltip:ClearAllPoints()
    GameTooltip:SetPoint("BOTTOMLEFT", ChatFrame1, "BOTTOMLEFT", 0, 0)
end

local tooltipHooked
local function PositionDefaultTooltip(tooltip)
    if not enabled or tooltip ~= GameTooltip or not ChatFrame1 then return end
    tooltip:SetAnchorType("ANCHOR_NONE")
    tooltip:ClearAllPoints()
    tooltip:SetPoint("BOTTOMLEFT", ChatFrame1, "BOTTOMLEFT", 0, 0)
end

-- Fit stock panels and their bags as one workspace. Never reparent secure frames,
-- replace their handlers, or change them in combat. Buying, trading and quest
-- decisions remain Blizzard's own buttons and confirmation dialogs.
local workspaceNames = {
    "CharacterFrame", "SpellBookFrame", "QuestLogFrame", "QuestLogDetailFrame", "QuestLogPopupDetailFrame",
    "PlayerTalentFrame", "FriendsFrame", "PVPFrame", "PVPParentFrame", "MacroFrame", "InspectFrame", "DressUpFrame",
    "GossipFrame", "QuestFrame", "MerchantFrame", "BankFrame", "MailFrame", "TradeFrame",
    "ClassTrainerFrame", "TradeSkillFrame", "CraftFrame", "AuctionFrame", "AuctionHouseFrame",
    "ItemTextFrame", "PetStableFrame", "TabardFrame", "GuildRegistrarFrame", "PetitionFrame", "TaxiFrame", "LootFrame", "WorldMapFrame", "StackSplitFrame",
}
local workspaceOriginal, workspaceHooks = {}, {}
local workspaceQueued, positioningWorkspace, workspaceSignature
local inventoryStrip

local function ShowBagPage(bag)
    if InCombatLockdown() then Notice("Bag pages are available after combat.") return end
    -- Closing a bag does not clear its cursor. A picked-up item can be carried
    -- to another bag page and placed with the next ordinary stock item click.
    local keyring = KEYRING_CONTAINER and bag == KEYRING_CONTAINER
    if bag ~= nil and not keyring and C_Container.GetContainerNumSlots(bag) <= 0 then return end
    CloseAllBags()
    if KEYRING_CONTAINER and CloseBag then CloseBag(KEYRING_CONTAINER) end
    if bag == nil then OpenAllBags()
    elseif keyring then ToggleBag(bag)
    else OpenBag(bag) end
    QueueWorkspace()
end

local function InventoryStrip(bags)
    if #bags == 0 or (editor and editor:IsShown()) or (controls and controls:IsShown()) then
        if inventoryStrip then inventoryStrip:Hide() end
        return 0
    end
    if not inventoryStrip then
        inventoryStrip = CreateFrame("Frame", "WeHavePhonesTouchInventory", UIParent)
        inventoryStrip:SetSize(434, 38)
        inventoryStrip:SetFrameStrata("DIALOG")
        inventoryStrip.bags, inventoryStrip.bank = {}, {}
        for bag = 0, 4 do
            local id = bag
            inventoryStrip.bags[bag+1] = Button(inventoryStrip, "WeHavePhonesTouchBag"..bag,
                bag == 0 and "Pack" or "Bag "..bag, bag*64, 0, 58, 38, function() ShowBagPage(id) end)
        end
        inventoryStrip.all = Button(inventoryStrip, "WeHavePhonesTouchAllBags", "All", 320, 0, 54, 38, function() ShowBagPage() end)
        inventoryStrip.keys = Button(inventoryStrip, "WeHavePhonesTouchKeys", "Keys", 380, 0, 54, 38,
            function() if KEYRING_CONTAINER and ToggleBag then ShowBagPage(KEYRING_CONTAINER) end end)
        for bag = 5, 11 do
            local id = bag
            inventoryStrip.bank[bag-4] = Button(inventoryStrip, "WeHavePhonesTouchBankBag"..bag,
                "Bank "..(bag-4), (bag-5)*54, -44, 50, 38, function() ShowBagPage(id) end)
        end
    end
    local bank = BankFrame and BankFrame:IsShown()
    local rows = bank and 2 or 1
    local hasKeyring = KEYRING_CONTAINER ~= nil and ToggleBag ~= nil
    inventoryStrip:SetWidth(hasKeyring and 434 or 374)
    inventoryStrip.keys:SetShown(hasKeyring)
    local scale = math.min(1, UIParent:GetWidth()*.94/inventoryStrip:GetWidth())
    SetRelativeScale(inventoryStrip, scale)
    inventoryStrip:ClearAllPoints()
    inventoryStrip:SetPoint("TOP", UIParent, "CENTER", 0, UIParent:GetHeight()*.30/scale)
    -- One open container is a page. Several are All.
    local single = #bags == 1 and bags[1].GetID and bags[1]:GetID() or nil
    for i, button in ipairs(inventoryStrip.bags) do
        button:SetEnabled(C_Container.GetContainerNumSlots(i-1)>0)
        PaintKey(button, single == i-1)
    end
    PaintKey(inventoryStrip.all, single == nil)
    PaintKey(inventoryStrip.keys, single ~= nil and single == KEYRING_CONTAINER)
    for i, button in ipairs(inventoryStrip.bank) do
        button:SetShown(bank and true or false)
        button:SetEnabled(bank and C_Container.GetContainerNumSlots(i+4)>0)
        PaintKey(button, single == i+4)
    end
    inventoryStrip:Show()
    return (rows*44+6)*scale
end

local function RememberGeometry(object)
    local state = {scale=object:GetScale(), points={}}
    for i = 1, object:GetNumPoints() do
        state.points[i] = {object:GetPoint(i)}
    end
    return state
end

local function RestoreGeometry(object, state)
    object:SetScale(state.scale)
    object:ClearAllPoints()
    for _, point in ipairs(state.points) do object:SetPoint(unpack(point)) end
end

local function HudObject(name)
    if type(name) ~= "string" or name == "" then return end
    local object = _G
    for part in name:gmatch("[^.]+") do object = object and object[part] end
    return object
end

local function HudName(object)
    if not object then return "UIParent" end
    if object:GetName() and object:GetName() ~= "" then return object:GetName() end
    if PartyFrame then
        for i = 1, 4 do if object == PartyFrame["MemberFrame"..i] then return "PartyFrame.MemberFrame"..i end end
    end
    return "UIParent"
end

local function RememberHud(name, hide)
    local object = HudObject(name)
    if not object then return end
    local db = WeHavePhonesTouchDB
    db.hud = db.hud or {}
    if not db.hud[name] then
        local state = {scale=object:GetScale(), alpha=object:GetAlpha(), points={}}
        if hide then state.shown = object:IsShown() end
        for i = 1, object:GetNumPoints() do
            local point, relative, relativePoint, x, y = object:GetPoint(i)
            state.points[i] = {point, HudName(relative), relativePoint, x, y}
        end
        db.hud[name] = state
    end
    if hide then
        if db.hud[name].shown == nil then db.hud[name].shown = object:IsShown() end
        object:Hide()
    end
end

local function RestoreHud()
    local states = WeHavePhonesTouchDB.hud or {}
    for name, state in pairs(states) do
        local object = HudObject(name)
        if object then
            object:SetScale(state.scale)
            object:ClearAllPoints()
            for _, point in ipairs(state.points) do
                object:SetPoint(point[1], HudObject(point[2]) or UIParent, point[3], point[4], point[5])
            end
            if state.shown ~= nil then object:SetShown(state.shown) end
            if state.alpha ~= nil then object:SetAlpha(state.alpha) end
        end
        -- Missing frames have not been changed in this fresh UI session.
        states[name] = nil
    end
end

local function WorkspaceFrames()
    local panels, bags, seen = {}, {}, {}
    for _, name in ipairs(workspaceNames) do
        local object = _G[name]
        if object and object:IsShown() then panels[#panels+1] = object seen[object] = true end
    end
    -- Include only the visible stock containers, including bank bags and keyring.
    if ContainerFrame1 and ContainerFrame1.bags then
        for _, name in ipairs(ContainerFrame1.bags) do
            local object = _G[name]
            if object and object:IsShown() and not seen[object] then bags[#bags+1] = object seen[object] = true end
        end
    end
    return panels, bags, seen
end

local function PackWorkspace(panels, bags, scale, width, height)
    local places, x, y, columnWidth = {}, 0, 0, 0
    local function place(object, isBag)
        local w, h = object:GetWidth()*scale, object:GetHeight()*scale
        if isBag then
            if y > 0 and y+h > height then x, y, columnWidth = x+columnWidth+8, 0, 0 end
        else
            if #places > 0 then x = x+columnWidth+8 end
            y, columnWidth = 0, 0
        end
        if x+w > width or y+h > height then return false end
        places[#places+1] = {object=object, x=x, y=y}
        columnWidth = math.max(columnWidth, w)
        y = y+h+8
        return true
    end
    for _, object in ipairs(panels) do if not place(object, false) then return end end
    -- Bags start in a separate column so item transfers never cover the destination.
    if #panels > 0 and #bags > 0 then x, y, columnWidth = x+columnWidth+8, 0, 0 end
    for _, object in ipairs(bags) do if not place(object, true) then return end end
    return places, x+columnWidth
end

LayoutWorkspace = function()
    if not enabled or WOW_PROJECT_ID ~= WOW_PROJECT_CLASSIC or InCombatLockdown() or positioningWorkspace then return end
    positioningWorkspace = true
    local panels, bags, visible = WorkspaceFrames()
    for object, state in pairs(workspaceOriginal) do
        if not visible[object] then RestoreGeometry(object, state) workspaceOriginal[object] = nil end
    end
    local stripHeight = InventoryStrip(bags)
    local width, height = UIParent:GetWidth()*.94, UIParent:GetHeight()*.78-stripHeight
    local places, used, scale
    for step = 100, 20, -2 do
        scale = step/100
        places, used = PackWorkspace(panels, bags, scale, width, height)
        if places then break end
    end
    if places then
        for _, place in ipairs(places) do
            local object = place.object
            if not workspaceOriginal[object] then workspaceOriginal[object] = RememberGeometry(object) end
            SetRelativeScale(object, scale)
            object:ClearAllPoints()
            object:SetPoint("TOPLEFT", UIParent, "CENTER", (place.x-used/2)/scale,
                (UIParent:GetHeight()*.30-stripHeight-place.y)/scale)
        end
    end
    FitPanel(editor)
    FitPanel(controls)
    positioningWorkspace = false
end

QueueWorkspace = function()
    if not enabled or workspaceQueued or positioningWorkspace then return end
    workspaceQueued = true
    C_Timer.After(0, function() workspaceQueued = false LayoutWorkspace() end)
end

local function WatchWorkspace()
    if WOW_PROJECT_ID ~= WOW_PROJECT_CLASSIC then return end
    for _, name in ipairs({"ShowUIPanel", "HideUIPanel", "UpdateUIPanelPositions", "UpdateContainerFrameAnchors"}) do
        if _G[name] and not workspaceHooks[name] then
            hooksecurefunc(name, QueueWorkspace)
            workspaceHooks[name] = true
        end
    end
end

-- Lazy-loaded panels and dynamic bag heights also need fitting. Only changed
-- visibility or dimensions trigger a layout, without a frame-by-frame rewrite.
local workspaceElapsed = 0
frame:SetScript("OnUpdate", function(_, elapsed)
    if not enabled or not applied or InCombatLockdown() then return end
    workspaceElapsed = workspaceElapsed+elapsed
    if workspaceElapsed < .2 then return end
    workspaceElapsed = 0
    local panels, bags = WorkspaceFrames()
    local signature = {UIParent:GetWidth(), UIParent:GetHeight(),
        editor and editor:IsShown() and 1 or 0, controls and controls:IsShown() and 1 or 0}
    for _, group in ipairs({panels, bags}) do
        for _, object in ipairs(group) do
            signature[#signature+1] = object:GetName()
            signature[#signature+1] = object:GetWidth()
            signature[#signature+1] = object:GetHeight()
        end
    end
    signature = table.concat(signature, ":")
    if signature ~= workspaceSignature then workspaceSignature = signature QueueWorkspace() end
end)

local positioningClassBars
local function PositionClassBars()
    if not enabled or InCombatLockdown() or positioningClassBars then return end
    positioningClassBars = true
    local bars, total = {}, 0
    for _, name in ipairs({"StanceBar", "PetActionBar"}) do
        local object = _G[name]
        if object then
            RememberHud(name)
            bars[#bars+1] = object
            total = total+object:GetWidth()
        end
    end
    local reserved = UIParent:GetWidth()/UIParent:GetHeight()<1.6 and 132*ActionFit() or 0
    local scale = math.min(1, (LayoutWidth()-32-reserved)/math.max(1, total+12))
    local x = -LayoutWidth()/2+16+reserved
    for _, object in ipairs(bars) do
        SetRelativeScale(object, scale)
        object:ClearAllPoints()
        object:SetPoint("BOTTOMLEFT", UIParent, "CENTER", x/scale,
            (math.max(12, 18*math.min(1, LayoutWidth()/760))-UIParent:GetHeight()/2)/scale)
        x = x+object:GetWidth()*scale+12
    end
    -- Possession replaces normal pet commands. Keep its native secure buttons
    -- in the same reserved strip, including the return-to-player action.
    if PossessActionBar and PetActionBar then
        RememberHud("PossessActionBar")
        local _, _, _, petX, petY = PetActionBar:GetPoint(1)
        SetRelativeScale(PossessActionBar, scale)
        PossessActionBar:ClearAllPoints()
        PossessActionBar:SetPoint("BOTTOMLEFT", UIParent, "CENTER", petX, petY)
    end
    positioningClassBars = false
end

local hudHooked, positioningHud, hudQueued, chatButtonsHooked
local trackerHooks = {}
local PositionHud
local function QueueHud()
    if not enabled or hudQueued or positioningHud then return end
    hudQueued = true
    C_Timer.After(0, function() hudQueued = false PositionHud() end)
end

local partyHooked
local function PositionParty()
    if not PartyFrame then return end
    if not partyHooked and PartyFrame.Layout then
        hooksecurefunc(PartyFrame, "Layout", QueueHud)
        partyHooked = true
    end
    local width, maxWidth = UIParent:GetWidth(), 128
    for i = 1, 4 do
        local member = PartyFrame["MemberFrame"..i]
        if member then maxWidth = math.max(maxWidth, member:GetWidth()) end
    end
    local available = width*.76
    if MinimapCluster and MinimapCluster:GetLeft() then
        available = math.min(available, MinimapCluster:GetLeft()*MinimapCluster:GetEffectiveScale()/UIParent:GetEffectiveScale()-width*.025-16)
    end
    -- Reserve pet and aura space as well as the 53-pixel stock unit button.
    local scale = math.min(1, math.max(1, available)/(maxWidth*4+42), UIParent:GetHeight()*.125/104)
    for i = 1, 4 do
        local member = PartyFrame["MemberFrame"..i]
        if member then
            RememberHud("PartyFrame.MemberFrame"..i)
            SetRelativeScale(member, scale)
            member:ClearAllPoints()
            member:SetPoint("TOPLEFT", UIParent, "CENTER",
                (-width*.475+(i-1)*(maxWidth+14)*scale)/scale, UIParent:GetHeight()*.32/scale)
        end
    end
    if PartyFrame.Background then RememberHud("PartyFrame.Background") PartyFrame.Background:SetAlpha(0) end
    PositionChat()
end

local function PositionTracker()
    local name = Questie_BaseFrame and Questie_BaseFrame:IsShown() and "Questie_BaseFrame" or "QuestWatchFrame"
    local object = _G[name]
    if not object then return end
    RememberHud(name)
    if name == "Questie_BaseFrame" then RememberHud("QuestWatchFrame", true) end
    local fit = math.min(1, LayoutWidth()/760)
    local top = UIParent:GetHeight()*.65
    if MinimapCluster and MinimapCluster:GetBottom() then
        top = math.min(top, MinimapCluster:GetBottom()*MinimapCluster:GetEffectiveScale()/UIParent:GetEffectiveScale()-12)
    end
    local actionTop = UIParent:GetWidth()/UIParent:GetHeight()<1.6
        and (64+(math.ceil(12/CompactColumns())-1)*54+36*ACTION_SCALE)*ActionFit() or (118+36*ACTION_SCALE)*fit
    local height = math.max(32, top-actionTop-16)
    local scale = math.min(1, LayoutWidth()*.30/math.max(1, object:GetWidth()), height/math.max(1, object:GetHeight()))
    SetRelativeScale(object, scale)
    object:ClearAllPoints()
    object:SetPoint("TOPRIGHT", UIParent, "CENTER", (LayoutWidth()/2-16)/scale,
        (top-UIParent:GetHeight()/2)/scale)
    if not trackerHooks[object] then
        for _, method in ipairs({"SetPoint", "SetSize", "SetHeight", "SetWidth", "Show"}) do
            hooksecurefunc(object, method, QueueHud)
        end
        trackerHooks[object] = true
    end
end

PositionHud = function()
    if not enabled or WOW_PROJECT_ID ~= WOW_PROJECT_CLASSIC or InCombatLockdown() or positioningHud then return end
    positioningHud = true
    PositionParty()
    if ChatFrame1ButtonFrame then
        RememberHud("ChatFrame1ButtonFrame")
        local party = PartyFrame and PartyFrame.MemberFrame1 and PartyFrame.MemberFrame1:IsShown()
        if party then
            SetRelativeScale(ChatFrame1ButtonFrame, math.min(1, UIParent:GetHeight()*.16/math.max(1, ChatFrame1ButtonFrame:GetHeight())))
        else ChatFrame1ButtonFrame:SetScale(WeHavePhonesTouchDB.hud.ChatFrame1ButtonFrame.scale) end
        if not chatButtonsHooked and ChatFrame1ButtonFrame.Layout then
            hooksecurefunc(ChatFrame1ButtonFrame, "Layout", QueueHud)
            chatButtonsHooked = true
        end
    end
    PositionClassBars()
    PositionTracker()
    for _, name in ipairs({"MicroMenuContainer", "BagsBar", "MicroButtonAndBagsBar", "MainMenuBarPerformanceBarFrame"}) do RememberHud(name, true) end
    if MainActionBar and MainActionBar.ActionBarPageNumber then MainActionBar.ActionBarPageNumber:Hide() end
    if StatusTrackingBarManager then
        RememberHud("StatusTrackingBarManager")
        local scale = math.min(.35, LayoutWidth()/math.max(1, StatusTrackingBarManager:GetWidth()))
        SetRelativeScale(StatusTrackingBarManager, scale)
        StatusTrackingBarManager:ClearAllPoints()
        StatusTrackingBarManager:SetPoint("BOTTOM", UIParent, "BOTTOM", 0, 1/scale)
    end
    positioningHud = false
end

Layout = function()
    if not enabled or InCombatLockdown() then return end
    if WOW_PROJECT_ID ~= WOW_PROJECT_CLASSIC then return end
    if MultiBarBottomLeft then MultiBarBottomLeft:Hide() end
    if MultiBarBottomRight then MultiBarBottomRight:Hide() end
    if VIEWABLE_ACTION_BAR_PAGES then
        for i = 1, 6 do VIEWABLE_ACTION_BAR_PAGES[i] = 1 end
    end
    local halfWidth, halfHeight = LayoutWidth()/2, UIParent:GetHeight()/2
    local compact = UIParent:GetWidth()/UIParent:GetHeight()<1.6
    local fit = ActionFit()
    local columns = CompactColumns()
    for i = 1, 12 do
        local button = _G["ActionButton" .. i]
        if button then
            SetRelativeScale(button, ACTION_SCALE*fit)
            button:ClearAllPoints()
            -- Compensate local button scale so anchors stay in UIParent units.
            if compact then
                button:SetPoint("BOTTOMLEFT", UIParent, "CENTER",
                    (UIParent:GetWidth()*.36+(-(columns==3 and 166 or 228)+((i-1)%columns)*54)*fit)/(ACTION_SCALE*fit),
                    ((64+(math.ceil(12/columns)-1-math.floor((i-1)/columns))*54)*fit-halfHeight)/(ACTION_SCALE*fit))
            elseif i <= 8 then
                button:SetPoint("BOTTOMLEFT", UIParent, "CENTER",
                    (halfWidth+(-228+((i-1)%4)*54)*fit)/(ACTION_SCALE*fit),
                    ((64+(1-math.floor((i-1)/4))*54)*fit-halfHeight)/(ACTION_SCALE*fit))
            else
                button:SetPoint("BOTTOMLEFT", UIParent, "CENTER",
                    (-108+(i-9)*54)/ACTION_SCALE, (64*fit-halfHeight)/(ACTION_SCALE*fit))
            end
            button:SetAttribute("showgrid", 1)
            button:SetAttribute("type2", "")
            button:RegisterForClicks("LeftButtonUp", "LeftButtonDown")
            button:Show()
            if not buttons[button] then
                button:HookScript("OnMouseUp", function(self, mouse)
                    if mouse == "RightButton" then OpenEditor(self) end
                end)
                if button.SetTooltip then hooksecurefunc(button, "SetTooltip", PositionActionTooltip) end
                buttons[button] = true
            end
        end
    end
    CreatePager()
    local pagerScale = compact and .8*fit or fit
    SetRelativeScale(pager, pagerScale)
    pager:ClearAllPoints()
    if compact then
        pager:SetPoint("BOTTOMLEFT", UIParent, "CENTER", (-halfWidth+16)/pagerScale, (12-halfHeight)/pagerScale)
    else pager:SetPoint("BOTTOMLEFT", UIParent, "CENTER", -240, (64*fit-halfHeight)/fit) end
    UpdatePager()
    for _, name in ipairs({"MainMenuBarTexture0", "MainMenuBarTexture1", "MainMenuBarTexture2",
        "MainMenuBarTexture3", "MainMenuBarLeftEndCap", "MainMenuBarRightEndCap"}) do
        if _G[name] then _G[name]:SetAlpha(0) end
    end
    PositionChat()
    if not tooltipHooked and GameTooltip_SetDefaultAnchor then
        hooksecurefunc("GameTooltip_SetDefaultAnchor", PositionDefaultTooltip)
        tooltipHooked = true
    end
    PositionHud()
    if not hudHooked and UIParent_ManageFramePositions then
        hooksecurefunc("UIParent_ManageFramePositions", PositionHud)
        hudHooked = true
    end
    WatchWorkspace()
    QueueWorkspace()
    if not chatHooked and ChatFrame1 then
        -- Panel and layout updates can move chat again after login. Reapply only
        -- when its anchor changes, without editing the player's saved layout.
        for _, method in ipairs({"SetPoint", "SetSize", "SetHeight", "SetWidth"}) do
            hooksecurefunc(ChatFrame1, method, PositionChat)
        end
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
            -- Native touch is mouse input. Enable Blizzard's nearby interact target
            -- without relying on the gamepad-only default or pixel-perfect corpse taps.
            SaveCVar("SoftTargetInteract", "3")
            SaveCVar("SoftTargetInteractArc", "1")
            SaveCVar("SoftTargetIconInteract", "1")
            SaveCVar("lootUnderMouse", "0")
            SaveCVar("alwaysCompareItems", "1")
            if WOW_PROJECT_ID == WOW_PROJECT_CLASSIC and GetActionBarToggles then
                local toggles = {GetActionBarToggles()}
                for i = 1, 7 do toggles[i] = toggles[i] == true or toggles[i] == 1 end
                if not db.actionBars then
                    db.actionBars = {}
                    for i = 1, 7 do db.actionBars[i] = toggles[i] and true or false end
                end
                toggles[1], toggles[2] = false, false
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
            local corpse = CreateFrame("Button", "WeHavePhonesTouchCorpseTrigger", UIParent, "SecureActionButtonTemplate")
            corpse:RegisterForClicks("AnyUp", "AnyDown")
            corpse:SetSize(1, 1)
            corpse:SetPoint("TOPLEFT")
            corpse:SetAttribute("type", "macro")
            corpse:SetAttribute("macrotext", "/target [@softinteract,exists,dead]")
            corpse:SetScript("PostClick", function(_, _, down)
                if down then return end
                if not UnitExists("target") or not UnitIsDead("target") then
                    Notice("Face a nearby lootable corpse, then hold Target again.")
                end
            end)
            corpse:Show()
            SetOverrideBindingClick(frame, true, "F10", "WeHavePhonesTouchCorpseTrigger")
            print("WeHavePhones touch: tap Controls, then Play for movement. Drag Controls aside if needed. Hold a slot to edit. More opens the interface menu.")
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
        RestoreHud()
        for object, state in pairs(workspaceOriginal) do RestoreGeometry(object, state) end
        workspaceOriginal = {}
        ClearOverrideBindings(frame)
    end
end

frame:RegisterEvent("PLAYER_LOGIN")
frame:RegisterEvent("PLAYER_ENTERING_WORLD")
frame:RegisterEvent("PLAYER_REGEN_ENABLED")
frame:RegisterEvent("PLAYER_REGEN_DISABLED")
frame:RegisterEvent("UI_SCALE_CHANGED")
frame:RegisterEvent("DISPLAY_SIZE_CHANGED")
frame:RegisterEvent("ACTIONBAR_PAGE_CHANGED")
frame:RegisterEvent("QUEST_LOG_UPDATE")
frame:RegisterEvent("QUEST_WATCH_UPDATE")
frame:RegisterEvent("UPDATE_SHAPESHIFT_FORMS")
frame:RegisterEvent("UNIT_PET")
frame:RegisterEvent("GROUP_ROSTER_UPDATE")
frame:SetScript("OnEvent", function(_, event)
    if event == "ACTIONBAR_PAGE_CHANGED" then
        UpdatePager()
    elseif event == "PLAYER_REGEN_DISABLED" then
        if editor then editor:Hide() end
        if controls then controls:Hide() end
        if inventoryStrip then inventoryStrip:Hide() end
    elseif event == "UI_SCALE_CHANGED" or event == "DISPLAY_SIZE_CHANGED" then
        C_Timer.After(0, function() if enabled and applied then UniformScale() Layout() end end)
    elseif event == "QUEST_LOG_UPDATE" or event == "QUEST_WATCH_UPDATE" or event == "UPDATE_SHAPESHIFT_FORMS" or event == "UNIT_PET" or event == "GROUP_ROSTER_UPDATE" then
        if enabled and applied then QueueHud() end
    else Apply() end
end)

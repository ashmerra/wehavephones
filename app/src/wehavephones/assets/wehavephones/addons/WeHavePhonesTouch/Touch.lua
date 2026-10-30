local _, addon = ...
local enabled = addon.enabled
local frame = CreateFrame("Frame")
local applied, editor, selectedSlot, chatHooked, positioningChat, pager
local category, page, entries = "Spells", 1, {}
local undo
-- Bottom of the player and target frames, kept free of the native toolbar.
local unitBottom = 100
local buttons = {}
local Layout, OpenEditor, RefreshEditor, LayoutWorkspace, QueueWorkspace, LateTracker
local ACTION_SCALE = 1.35
-- Taller action grids below this aspect ratio. 16:10 is wide on both sides of the
-- native and Lua boundary, whatever the rounding. Keep in step with MobileTouchLayout.
local COMPACT = 1.59
local WHITE = "Interface\\Buttons\\WHITE8X8"
local retail = WOW_PROJECT_ID == WOW_PROJECT_MAINLINE
local function SupportedLayout()
    return retail or WOW_PROJECT_ID == WOW_PROJECT_CLASSIC
        or (WOW_PROJECT_BURNING_CRUSADE_CLASSIC and WOW_PROJECT_ID == WOW_PROJECT_BURNING_CRUSADE_CLASSIC)
        or (WOW_PROJECT_MISTS_CLASSIC and WOW_PROJECT_ID == WOW_PROJECT_MISTS_CLASSIC)
end
local function LastPlayerBag()
    return retail and (Constants.InventoryConstants.NumBagSlots+Constants.InventoryConstants.NumReagentBagSlots) or 4
end

-- Overrides last for this UI session and never overwrite saved bindings.
local bindings = {
    W = "MOVEFORWARD", S = "MOVEBACKWARD", A = "STRAFELEFT", D = "STRAFERIGHT",
    SPACE = "JUMP", TAB = "TARGETNEARESTENEMY", ["SHIFT-TAB"] = "TARGETPREVIOUSENEMY",
    F = "INTERACTTARGET", B = "OPENALLBAGS", M = "TOGGLEWORLDMAP",
    N = "NEXTACTIONPAGE", R = "TOGGLEAUTORUN", X = "SITORSTAND",
    F1 = "TARGETSELF", F2 = "TARGETPARTYMEMBER1", F3 = "TARGETPARTYMEMBER2",
    F4 = "TARGETPARTYMEMBER3", F5 = "TARGETPARTYMEMBER4", F6 = "TARGETNEARESTFRIEND",
    F7 = "TOGGLERUN", G = "ASSISTTARGET", F11 = "SITORSTAND",
    -- The touch menu. Modifier chords on function keys stay clear of letters players type.
    ["CTRL-SHIFT-F1"] = "TOGGLECHARACTER0", ["CTRL-SHIFT-F2"] = "TOGGLESPELLBOOK", ["CTRL-SHIFT-F3"] = "TOGGLETALENTS",
    ["CTRL-SHIFT-F4"] = "TOGGLEQUESTLOG", ["CTRL-SHIFT-F5"] = "TOGGLESOCIAL", ["CTRL-SHIFT-F6"] = retail and "TOGGLEPROFESSIONBOOK" or "TOGGLECHARACTER1",
    ["CTRL-SHIFT-F7"] = "TOGGLECHARACTER2", ["CTRL-SHIFT-F8"] = "TOGGLECHARACTER4", ["CTRL-SHIFT-F9"] = "TOGGLEPETBOOK",
    ["CTRL-SHIFT-F10"] = "TOGGLEGAMEMENU", ["CTRL-SHIFT-F11"] = "FOLLOWTARGET", ["CTRL-SHIFT-F12"] = "REPLY",
    ["SHIFT-F7"] = "NAMEPLATES",
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
    if aspect<COMPACT then return math.min(1, UIParent:GetWidth()/760) end
    return math.min(1, LayoutWidth()/760)
end

local function SetRelativeScale(object, scale, setter)
    -- Some stock frames, notably the map, ignore their parent's scale. Use
    -- the observed effective scale instead of assuming a UIParent child inherits it.
    local inherited = object:GetEffectiveScale()/math.max(.001, object:GetScale())
    local applyScale = setter or object.SetScale
    applyScale(object, scale*UIParent:GetEffectiveScale()/math.max(.001, inherited))
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
        if retail then
            local bank = Enum.SpellBookSpellBank.Player
            for tab = 1, C_SpellBook.GetNumSpellBookSkillLines() do
                local line = C_SpellBook.GetSpellBookSkillLineInfo(tab)
                if line and not line.shouldHide and not line.offSpecID then
                    for slot = line.itemIndexOffset+1, line.itemIndexOffset+line.numSpellBookItems do
                        local info = C_SpellBook.GetSpellBookItemInfo(slot, bank)
                        if info and info.itemType == Enum.SpellBookItemType.Spell and not info.isPassive and not info.isOffSpec then
                            local bookSlot = slot
                            tinsert(entries, {name=info.name, icon=info.iconID,
                                pickup=function() C_SpellBook.PickupSpellBookItem(bookSlot, bank) end})
                        end
                    end
                end
            end
        else
            for tab = 1, GetNumSpellTabs() do
                local _, _, offset, count, _, offSpec, hidden = GetSpellTabInfo(tab)
                if (not offSpec or offSpec == 0) and not hidden then
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
            end
        end
    elseif category == "Items" then
        for bag = 0, LastPlayerBag() do
            for slot = 1, C_Container.GetContainerNumSlots(bag) do
                local info = C_Container.GetContainerItemInfo(bag, slot)
                if info and not info.isLocked then
                    local bagID, bagSlot = bag, slot
                    local name = (retail and C_Item.GetItemInfo or GetItemInfo)(info.hyperlink or info.itemID) or "Item"
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
    elseif undo.kind == "spell" then (retail and C_Spell.PickupSpell or PickupSpell)(undo.id)
    elseif undo.kind == "item" then (retail and C_Item.PickupItem or PickupItem)(undo.id)
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

local function SetTouchScale(scale)
    WeHavePhonesTouchDB.touchScale = scale
    if InCombatLockdown() then Notice("The interface size changes after combat.") return end
    UniformScale()
    Layout()
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
    "PlayerSpellsFrame", "ProfessionsBookFrame", "ProfessionsFrame",
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
    -- Retail's combined bag has its own inventory navigation. Preserve that mode.
    if #bags == 0 or (retail and ContainerFrameCombinedBags and ContainerFrameCombinedBags:IsShown()) or (editor and editor:IsShown()) then
        if inventoryStrip then inventoryStrip:Hide() end
        return 0
    end
    if not inventoryStrip then
        inventoryStrip = CreateFrame("Frame", "WeHavePhonesTouchInventory", UIParent)
        inventoryStrip:SetSize(434, 38)
        inventoryStrip:SetFrameStrata("DIALOG")
        inventoryStrip.bags, inventoryStrip.bank = {}, {}
        for bag = 0, LastPlayerBag() do
            local id = bag
            inventoryStrip.bags[bag+1] = Button(inventoryStrip, "WeHavePhonesTouchBag"..bag,
                bag == 0 and "Pack" or bag == 5 and "Reagents" or "Bag "..bag, bag*64, 0, 58, 38, function() ShowBagPage(id) end)
        end
        inventoryStrip.all = Button(inventoryStrip, "WeHavePhonesTouchAllBags", "All", (LastPlayerBag()+1)*64, 0, 54, 38, function() ShowBagPage() end)
        inventoryStrip.keys = Button(inventoryStrip, "WeHavePhonesTouchKeys", "Keys", 380, 0, 54, 38,
            function() if KEYRING_CONTAINER and ToggleBag then ShowBagPage(KEYRING_CONTAINER) end end)
        for bag = 5, 11 do
            local id = bag
            inventoryStrip.bank[bag-4] = Button(inventoryStrip, "WeHavePhonesTouchBankBag"..bag,
                "Bank "..(bag-4), (bag-5)*54, -44, 50, 38, function() ShowBagPage(id) end)
        end
    end
    -- Modern banks use their own tabs, rather than Classic's bag IDs 5 through 11.
    local bank = not retail and BankFrame and BankFrame:IsShown()
    local rows = bank and 2 or 1
    local hasKeyring = KEYRING_CONTAINER ~= nil and ToggleBag ~= nil
    inventoryStrip:SetWidth(hasKeyring and 434 or (LastPlayerBag()+1)*64+54)
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

local editBoxes = {}
local function RaiseEditBox(editBox)
    if not enabled or not editBox or retail or not SupportedLayout() then return end
    -- The Android keyboard covers the lower half. Type just below the unit frames instead.
    if not editBoxes[editBox] then
        local state = RememberGeometry(editBox)
        state.width = editBox:GetWidth()
        editBoxes[editBox] = state
    end
    local relative = editBox:GetEffectiveScale()/UIParent:GetEffectiveScale()
    editBox:ClearAllPoints()
    editBox:SetPoint("TOP", UIParent, "TOP", 0, -(math.max(.14*UIParent:GetHeight(), unitBottom)+6)/relative)
    editBox:SetWidth(math.min(LayoutWidth()*.6, 560)/relative)
end

local function LowerEditBox(editBox)
    local state = editBox and editBoxes[editBox]
    if not state then return end
    RestoreGeometry(editBox, state)
    editBox:SetWidth(state.width)
    editBoxes[editBox] = nil
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
        local state = {scale=object.GetScale and object:GetScale(), alpha=object:GetAlpha(), points={}}
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

-- Put a remembered HUD object back on its own anchors, optionally smaller.
local function ApplyHud(name, scale)
    local object, state = HudObject(name), WeHavePhonesTouchDB.hud and WeHavePhonesTouchDB.hud[name]
    if not object or not state then return end
    object:SetScale(state.scale*(scale or 1))
    object:ClearAllPoints()
    for _, point in ipairs(state.points) do
        object:SetPoint(point[1], HudObject(point[2]) or UIParent, point[3], point[4], point[5])
    end
end

local function RestoreHud()
    local states = WeHavePhonesTouchDB.hud or {}
    for name, state in pairs(states) do
        local object = HudObject(name)
        if object then
            if state.parent then object:SetParent(HudObject(state.parent) or UIParent) end
            if state.scale then object:SetScale(state.scale) end
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
    if retail and ContainerFrameUtil_EnumerateContainerFrames then
        for _, object in ContainerFrameUtil_EnumerateContainerFrames() do
            if object:IsShown() and not seen[object] then bags[#bags+1] = object seen[object] = true end
        end
    end
    return panels, bags, seen
end

local function PackWorkspace(panels, bags, scale, width, height)
    local places, x, y, columnWidth = {}, 0, 0, 0
    local function place(object, isBag)
        local w, h = object:GetWidth()*scale, object:GetHeight()*scale
        -- Classic spellbooks draw school tabs to the right and book tabs
        -- below their frame. Keep those controls inside the workspace too.
        if object == SpellBookFrame then w, h = w+48*scale, h+36*scale end
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
    if not enabled or not SupportedLayout() or InCombatLockdown() or positioningWorkspace then return end
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
    positioningWorkspace = false
end

QueueWorkspace = function()
    if not enabled or workspaceQueued or positioningWorkspace then return end
    workspaceQueued = true
    C_Timer.After(0, function() workspaceQueued = false LayoutWorkspace() end)
end

local function WatchWorkspace()
    if not SupportedLayout() then return end
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
    if not enabled or not applied or not SupportedLayout() or InCombatLockdown() then return end
    workspaceElapsed = workspaceElapsed+elapsed
    if workspaceElapsed < .2 then return end
    workspaceElapsed = 0
    local panels, bags = WorkspaceFrames()
    local signature = {UIParent:GetWidth(), UIParent:GetHeight(), editor and editor:IsShown() and 1 or 0}
    for _, group in ipairs({panels, bags}) do
        for _, object in ipairs(group) do
            signature[#signature+1] = object:GetName() or tostring(object)
            signature[#signature+1] = object:GetWidth()
            signature[#signature+1] = object:GetHeight()
        end
    end
    signature = table.concat(signature, ":")
    if signature ~= workspaceSignature then workspaceSignature = signature QueueWorkspace() end
    if LateTracker then LateTracker() end
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
    local reserved = UIParent:GetWidth()/UIParent:GetHeight()<COMPACT and 132*ActionFit() or 0
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

-- The native toolbar's reserved regions, in fractions of the game's height. Keep these
-- in step with MobileTouchLayout: a band along the top with a minimap square at its right
-- end on wide games, and the right rail beside the centered layout on narrower ones.
local TOOLBAR_BAND, MINIMAP_RESERVE, ZONE_GAP, AURA_ROW, AURA_ICON = .14, .30, .012, 45, 35
local topHooks, auraLeft, auraBottom = {}, 0, 0
local function HookTop(object)
    -- Edit Mode reapplies its anchors and frame sizes after login and layout changes.
    if not object or topHooks[object] then return end
    hooksecurefunc(object, "SetPoint", QueueHud)
    hooksecurefunc(object, "SetScale", QueueHud)
    topHooks[object] = true
end

local function ToolbarZone()
    return math.max(.42, math.min(.75, UIParent:GetWidth()/UIParent:GetHeight()-MINIMAP_RESERVE-.80))
end

local function RelativeScale(object)
    return object:GetEffectiveScale()/UIParent:GetEffectiveScale()
end

local minimapLeft, minimapBottom = 0, 0
local function PositionTopHud()
    local width, height = UIParent:GetWidth(), UIParent:GetHeight()
    local compact = width/height < COMPACT
    local rail = compact and (width-LayoutWidth())/2 or 0
    minimapLeft, minimapBottom = width-rail, 0
    if MinimapCluster then
        RememberHud("MinimapCluster")
        local scale = math.min(1, MINIMAP_RESERVE*height/math.max(1, MinimapCluster:GetWidth(), MinimapCluster:GetHeight()))
        SetRelativeScale(MinimapCluster, scale)
        MinimapCluster:ClearAllPoints()
        MinimapCluster:SetPoint("TOPRIGHT", UIParent, "TOPRIGHT", -rail/scale, 0)
        minimapLeft = width-rail-MinimapCluster:GetWidth()*scale
        minimapBottom = MinimapCluster:GetHeight()*scale
        HookTop(MinimapCluster)
    end
    if not compact and UIWidgetTopCenterContainerFrame then
        -- Battleground scores and other top-center widgets start below the toolbar band.
        RememberHud("UIWidgetTopCenterContainerFrame")
        UIWidgetTopCenterContainerFrame:ClearAllPoints()
        UIWidgetTopCenterContainerFrame:SetPoint("TOP", UIParent, "TOP", 0,
            -(TOOLBAR_BAND*height+4)/RelativeScale(UIWidgetTopCenterContainerFrame))
        HookTop(UIWidgetTopCenterContainerFrame)
    end
    -- Player and target frames keep their own anchors. They shrink toward those anchors only
    -- when the target frame would reach the toolbar, or the minimap on narrower games.
    if PlayerFrame and TargetFrame then
        local right = compact and minimapLeft-8 or width-(MINIMAP_RESERVE+ZONE_GAP+ToolbarZone())*height-8
        RememberHud("PlayerFrame")
        RememberHud("TargetFrame")
        if retail then
            -- Retail's Edit Mode stores screen positions in absolute UI units. Scaling
            -- the root alone can leave the unit frames in the stick or outside the game.
            local scale = math.min(1, (right-16)/(PlayerFrame:GetWidth()+TargetFrame:GetWidth()+8))
            for i, object in ipairs({PlayerFrame, TargetFrame}) do
                SetRelativeScale(object, scale)
                object:ClearAllPoints()
                object:SetPoint("TOPLEFT", UIParent, "TOPLEFT", (8+(i-1)*(PlayerFrame:GetWidth()*scale+8))/scale, -8/scale)
            end
        else
            ApplyHud("PlayerFrame")
            ApplyHud("TargetFrame")
            local edge = TargetFrame:GetRight() and TargetFrame:GetRight()*RelativeScale(TargetFrame)
                or (232+TargetFrame:GetWidth())*RelativeScale(TargetFrame)
            local scale = math.max(.4, math.min(1, right/math.max(1, edge)))
            if scale < 1 then
                ApplyHud("PlayerFrame", scale)
                ApplyHud("TargetFrame", scale)
            end
        end
        HookTop(PlayerFrame)
        HookTop(TargetFrame)
        local bottom = height-100
        for _, object in ipairs({PlayerFrame, TargetFrame}) do
            if object:GetBottom() then bottom = math.min(bottom, object:GetBottom()*RelativeScale(object)) end
        end
        unitBottom = height-bottom
    end
end

local retailBuffRows = 1
local function BuffRows()
    if retail then
        -- Restrictions can begin before combat lockdown. Keep the last public
        -- spacing while aura access is restricted, including on restricted maps.
        if not C_Secrets or not C_Secrets.ShouldAurasBeSecret or C_Secrets.ShouldAurasBeSecret() then return retailBuffRows end
        local count = 0
        for i = 1, 40 do
            if C_Secrets.ShouldUnitAuraIndexBeSecret and C_Secrets.ShouldUnitAuraIndexBeSecret("player", i, "HELPFUL") then return retailBuffRows end
            local ok, aura = pcall(C_UnitAuras.GetBuffDataByIndex, "player", i)
            if not ok or (canaccessvalue and not canaccessvalue(aura)) then return retailBuffRows end
            if not aura then break end
            count = count+1
        end
        retailBuffRows = math.max(1, math.ceil(count/11))
        return retailBuffRows
    end
    local count = 0
    for i = 1, 40 do
        local aura = UnitBuff and UnitBuff("player", i)
        if not aura then break end
        count = count+1
    end
    return math.max(1, math.ceil(count/11))
end

local PositionTracker
local trackerSpace
local PositionAuras
local positioningTracker, trackerQueued, aurasQueued
local function QueueAuras()
    if not enabled or aurasQueued then return end
    aurasQueued = true
    C_Timer.After(0, function()
        aurasQueued = false
        PositionAuras()
    end)
end
-- Auras are not protected. They follow buff changes in combat as well.
PositionAuras = function()
    if not enabled or not SupportedLayout() then return end
    local width, height = UIParent:GetWidth(), UIParent:GetHeight()
    local compact = width/height < COMPACT
    -- Wide games: right-aligned under the toolbar band. Narrower games: under the minimap,
    -- clear of Target, Use and Jump, which can be wider than the rail.
    local right = compact and math.max((width-LayoutWidth())/2, width*.16)+4 or (MINIMAP_RESERVE+ZONE_GAP)*height
    local top = compact and minimapBottom+4 or TOOLBAR_BAND*height+4
    local rows = BuffRows()
    auraLeft, auraBottom = width-right-11*AURA_ICON, top+(rows+1)*AURA_ROW
    for _, aura in ipairs({{"BuffFrame", 0, 0}, {"DebuffFrame", 0, rows*AURA_ROW}}) do
        local object = _G[aura[1]]
        if object then
            RememberHud(aura[1])
            local relative = RelativeScale(object)
            object:ClearAllPoints()
            object:SetPoint("TOPRIGHT", UIParent, "TOPRIGHT", -(right+aura[2])/relative, -(top+aura[3])/relative)
            HookTop(object)
        end
    end
    -- The tracker may be an addon's scaled frame. Refit it after combat.
    if PositionTracker and not InCombatLockdown() then PositionTracker() end
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
    local available = math.min(width*.76, minimapLeft-width*.025-16)
    if width/UIParent:GetHeight() >= COMPACT then available = math.min(available, auraLeft-width*.025-16) end
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

-- Questie can show its tracker after the first layout. Fit it once it appears.
LateTracker = function()
    if Questie_BaseFrame and Questie_BaseFrame:IsShown() and not trackerHooks[Questie_BaseFrame] then QueueHud() end
end

PositionTracker = function()
    if not enabled or not SupportedLayout() or positioningTracker then return end
    local name = retail and "ObjectiveTrackerFrame" or Questie_BaseFrame and Questie_BaseFrame:IsShown() and "Questie_BaseFrame" or (WatchFrame and "WatchFrame" or "QuestWatchFrame")
    local object = _G[name]
    if not object then return end
    -- Retail reanchors its unprotected tracker when combat starts. Keep it clear
    -- of the actions, while leaving protected replacements alone until combat ends.
    if InCombatLockdown() and (not retail or object:IsProtected()) then return end
    positioningTracker = true
    local ok, problem = pcall(function()
        RememberHud(name)
        if name == "Questie_BaseFrame" then RememberHud(WatchFrame and "WatchFrame" or "QuestWatchFrame", true) end
        local fit = math.min(1, LayoutWidth()/760)
        -- Below the minimap and the player's buffs and debuffs.
        local top = math.min(UIParent:GetHeight()*.65, UIParent:GetHeight()-math.max(minimapBottom+12, auraBottom+8))
        local actionTop = UIParent:GetWidth()/UIParent:GetHeight()<COMPACT
            and (64+(math.ceil(12/CompactColumns())-1)*54+36*ACTION_SCALE)*ActionFit() or (118+36*ACTION_SCALE)*fit
        local height = math.max(32, top-actionTop-16)
        if name == "WatchFrame" then
            -- MoP fills the space down to its parent's bottom in WatchFrame_Update.
            -- Give it a bounded parent with the same effective scale. Scaling the
            -- tracker alone mixes those coordinates and feeds its growing height
            -- back into the next fit.
            local state = WeHavePhonesTouchDB.hud[name]
            if not state.parent then state.parent = HudName(object:GetParent()) end
            if not trackerSpace then trackerSpace = CreateFrame("Frame", "WeHavePhonesTouchTrackerSpace", UIParent) end
            local scale = math.min(1, LayoutWidth()*.30/math.max(1, object:GetWidth()))
            SetRelativeScale(trackerSpace, scale)
            trackerSpace:SetSize(object:GetWidth(), height/scale)
            trackerSpace:ClearAllPoints()
            trackerSpace:SetPoint("TOPRIGHT", UIParent, "CENTER", (LayoutWidth()/2-16)/scale,
                (top-UIParent:GetHeight()/2)/scale)
            if object:GetParent() ~= trackerSpace then object:SetParent(trackerSpace) end
            object:SetScale(1)
            object:ClearAllPoints()
            object:SetPoint("TOPRIGHT", trackerSpace, "TOPRIGHT", 0, 0)
            object:SetHeight(height/scale)
        else
            local scale = math.min(1, LayoutWidth()*.30/math.max(1, object:GetWidth()), height/math.max(1, object:GetHeight()))
            -- Retail Edit Mode wraps these methods with global layout callbacks.
            -- Those callbacks can refresh secret auras in this addon's tainted call.
            -- Its saved base methods perform geometry only, with engine protection intact.
            local clearPoints = retail and object.ClearAllPointsBase or object.ClearAllPoints
            local setPoint = retail and object.SetPointBase or object.SetPoint
            SetRelativeScale(object, scale, retail and object.SetScaleBase)
            clearPoints(object)
            setPoint(object, "TOPRIGHT", UIParent, "CENTER", (LayoutWidth()/2-16)/scale,
                (top-UIParent:GetHeight()/2)/scale)
        end
        if not trackerHooks[object] then
            for _, method in ipairs({"SetPoint", "SetSize", "SetHeight", "SetWidth", "Show"}) do
                hooksecurefunc(object, method, function()
                    if positioningTracker or trackerQueued then return end
                    -- Let Blizzard finish its right-frame layout before refitting.
                    -- Reentering it from SetPoint can run stock aura work tainted.
                    trackerQueued = true
                    C_Timer.After(0, function()
                        trackerQueued = false
                        PositionTracker()
                    end)
                end)
            end
            trackerHooks[object] = true
        end
    end)
    positioningTracker = false
    if not ok then geterrorhandler()(problem) end
end

local function PlaceHud()
    PositionTopHud()
    PositionAuras()
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
    for _, name in ipairs({"MicroMenuContainer", "MicroMenu", "BagsBar", "MicroButtonAndBagsBar", "MainMenuBarPerformanceBarFrame",
        "MainActionBar.ActionBarPageNumber", "MainActionBar.EndCaps", "MainActionBar.BorderArt"}) do RememberHud(name, true) end
    if StatusTrackingBarManager then
        RememberHud("StatusTrackingBarManager")
        local scale = math.min(.35, LayoutWidth()/math.max(1, StatusTrackingBarManager:GetWidth()))
        SetRelativeScale(StatusTrackingBarManager, scale)
        StatusTrackingBarManager:ClearAllPoints()
        StatusTrackingBarManager:SetPoint("BOTTOM", UIParent, "BOTTOM", 0, 1/scale)
    end
end

PositionHud = function()
    if not enabled or not SupportedLayout() or InCombatLockdown() or positioningHud then return end
    positioningHud = true
    -- Report an error, but never leave the guard set: that would stop every later layout.
    local ok, problem = pcall(PlaceHud)
    positioningHud = false
    if not ok then geterrorhandler()(problem) end
end

Layout = function()
    if not enabled or InCombatLockdown() then return end
    if not SupportedLayout() then return end
    RememberHud("MultiBarBottomLeft", true)
    RememberHud("MultiBarBottomRight", true)
    if VIEWABLE_ACTION_BAR_PAGES then
        for i = 1, 6 do VIEWABLE_ACTION_BAR_PAGES[i] = 1 end
    end
    local halfWidth, halfHeight = LayoutWidth()/2, UIParent:GetHeight()/2
    local compact = UIParent:GetWidth()/UIParent:GetHeight()<COMPACT
    local fit = ActionFit()
    local columns = CompactColumns()
    for i = 1, 12 do
        local button = _G["ActionButton" .. i]
        if button then
            -- Retail and Classic use different stock button sizes. Keep the same
            -- physical action rectangles and native gesture exclusions for both.
            local actionScale = ACTION_SCALE*fit*36/math.max(1, button:GetWidth(), button:GetHeight())
            SetRelativeScale(button, actionScale)
            button:ClearAllPoints()
            -- Compensate local button scale so anchors stay in UIParent units.
            if compact then
                button:SetPoint("BOTTOMLEFT", UIParent, "CENTER",
                    (UIParent:GetWidth()*.36+(-(columns==3 and 166 or 228)+((i-1)%columns)*54)*fit)/actionScale,
                    ((64+(math.ceil(12/columns)-1-math.floor((i-1)/columns))*54)*fit-halfHeight)/actionScale)
            elseif i <= 8 then
                button:SetPoint("BOTTOMLEFT", UIParent, "CENTER",
                    (halfWidth+(-228+((i-1)%4)*54)*fit)/actionScale,
                    ((64+(1-math.floor((i-1)/4))*54)*fit-halfHeight)/actionScale)
            else
                button:SetPoint("BOTTOMLEFT", UIParent, "CENTER",
                    (-108+(i-9)*54)*fit/actionScale, (64*fit-halfHeight)/actionScale)
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
        -- Classic Era 1.15 calls ChatFrameUtil directly. ChatEdit_* are only deprecated aliases there.
        if ChatFrameUtil and ChatFrameUtil.ActivateChat then
            hooksecurefunc(ChatFrameUtil, "ActivateChat", RaiseEditBox)
            hooksecurefunc(ChatFrameUtil, "DeactivateChat", LowerEditBox)
        elseif ChatEdit_ActivateChat then
            hooksecurefunc("ChatEdit_ActivateChat", RaiseEditBox)
            hooksecurefunc("ChatEdit_DeactivateChat", LowerEditBox)
        end
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
            -- The app's size reaches every character once. Later in-game choices survive a reload.
            if addon.scale and db.modeScale ~= addon.scale then db.touchScale, db.modeScale = addon.scale, addon.scale end
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
            -- Nameplates are steadier tap targets than moving creature models.
            SaveCVar("nameplateShowEnemies", "1")
            if not retail and SupportedLayout() and GetActionBarToggles then
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
            -- Touch menu entries without a stock binding. Each runs once, when its chord is released.
            -- No Alt chords: Windows reads Alt with F4 as closing the game window.
            for key, action in pairs({["SHIFT-F1"] = function()
                    if InCombatLockdown() then Notice("Macros can be opened after combat.") else ShowMacroFrame() end
                end, ["SHIFT-F2"] = function() ToggleHelpFrame() end, ["SHIFT-F3"] = Undo,
                ["SHIFT-F4"] = function() SetTouchScale(1.25) end, ["SHIFT-F5"] = function() SetTouchScale(1.5) end,
                ["SHIFT-F6"] = function() SetTouchScale(1.75) end}) do
                local name = "WeHavePhonesTouchMenu" .. key:gsub("%-", "")
                local trigger = _G[name] or CreateFrame("Button", name, UIParent)
                trigger:RegisterForClicks("AnyUp", "AnyDown")
                trigger:SetSize(1, 1)
                trigger:SetPoint("TOPLEFT")
                trigger:SetScript("OnClick", function(_, _, down) if not down then action() end end)
                trigger:Show()
                SetOverrideBindingClick(frame, true, key, name)
            end
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
            print("WeHavePhones touch: tap Controls, then Play to move. Menu has every window and the touch guide. Hold a hotbar slot to change it.")
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
        for editBox in pairs(editBoxes) do LowerEditBox(editBox) end
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
if frame.RegisterUnitEvent then frame:RegisterUnitEvent("UNIT_AURA", "player") end
local buffRows
frame:SetScript("OnEvent", function(_, event)
    if event == "ACTIONBAR_PAGE_CHANGED" then
        UpdatePager()
    elseif event == "UNIT_AURA" then
        -- A new row of buffs moves the debuffs down, in combat as well. The tracker follows after combat.
        if retail then
            QueueAuras()
        else
            local rows = BuffRows()
            if enabled and applied and rows ~= buffRows then buffRows = rows PositionAuras() end
        end
    elseif event == "PLAYER_REGEN_DISABLED" then
        if editor then editor:Hide() end
        if inventoryStrip then inventoryStrip:Hide() end
    elseif event == "UI_SCALE_CHANGED" or event == "DISPLAY_SIZE_CHANGED" then
        C_Timer.After(0, function() if enabled and applied then UniformScale() Layout() end end)
    elseif event == "QUEST_LOG_UPDATE" or event == "QUEST_WATCH_UPDATE" or event == "UPDATE_SHAPESHIFT_FORMS" or event == "UNIT_PET" or event == "GROUP_ROSTER_UPDATE" then
        if enabled and applied then QueueHud() end
    else Apply() end
end)

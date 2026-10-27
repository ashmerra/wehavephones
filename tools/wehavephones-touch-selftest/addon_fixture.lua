local original = {GamePadEnable="1", InputDeviceInterfaceStyle="1", useUiScale="0", uiScale="0.64"}
local values, held, objects, all = {}, {}, {}, {}
for k,v in pairs(original) do values[k]=v end
local combat, cursor, layout, notices, actionPage = false, nil, 0, 0, 1
local extraBars = {false,false,true,false,false,false,false}
local actions = {[1]={"spell",101}, [61]={"item",500}}
WOW_PROJECT_CLASSIC, WOW_PROJECT_ID, BOOKTYPE_SPELL = 2, 2, "spell"
tinsert, UISpecialFrames, unpack = table.insert, {}, table.unpack or unpack
VIEWABLE_ACTION_BAR_PAGES = {1,1,1,1,1,1}
local methods = {}
function methods:SetScale(value) assert(not combat) self.scale=value end
function methods:GetScale() return self.scale or 1 end
function methods:GetWidth() return self.width or 1600 end
function methods:GetHeight() return self.height or 900 end
function methods:SetSize(w,h) self.width,self.height=w,h end
function methods:SetWidth(w) self.width=w end
function methods:SetPoint(...) self.point={...} if self.action then layout=layout+1 end end
function methods:GetPoint() return unpack(self.point or {"BOTTOMLEFT",UIParent,"BOTTOMLEFT",2,3}) end
function methods:GetName() return self.name end
function methods:SetAttribute(key,value) assert(not combat) self[key]=value end
function methods:RegisterForClicks(...) self.clicks={...} end
function methods:SetScript(name,fn) self.scripts[name]=fn end
function methods:HookScript(name,fn) self.scripts[name]=fn end
function methods:Show() self.visible=true end
function methods:Hide() self.visible=false end
function methods:SetShown(v) self.visible=v end
function methods:SetEnabled(v) self.enabled=v end
function methods:SetText(v) self.text=v end
function methods:SetFontString(label) self.font=label end
function methods:GetHighlightTexture() return self.highlight end
function methods:SetHighlightTexture() self.highlight=CreateFrame("Texture") end
for _,name in ipairs({"RegisterEvent","ClearAllPoints","SetFrameStrata","EnableMouse","SetClampedToScreen","SetBackdrop",
    "SetTexture","SetJustifyH","SetAlpha","SetTextColor","SetBackdropColor","SetBackdropBorderColor","SetVertexColor"}) do methods[name]=function() end end
function CreateFrame(_,name,parent)
    local object=setmetatable({name=name, scripts={}, parent=parent}, {__index=methods})
    if name then objects[name]=object _G[name]=object end
    table.insert(all,object)
    return object
end
function methods:CreateFontString() return CreateFrame("FontString") end
function methods:CreateTexture() return CreateFrame("Texture") end
UIParent=CreateFrame("Frame","UIParent")
ChatFrame1=CreateFrame("Frame","ChatFrame1")
ChatFrame1:SetSize(300,150)
MultiBarBottomLeft=CreateFrame("Frame","MultiBarBottomLeft")
MultiBarBottomRight=CreateFrame("Frame","MultiBarBottomRight")
function ChatFrame1:ApplySystemAnchor() self:SetPoint("BOTTOMLEFT", UIParent, "BOTTOMLEFT", 0, 0) end
function hooksecurefunc(object,name,callback)
    local original=object[name]
    object[name]=function(...) original(...) callback(...) end
end
UIErrorsFrame={AddMessage=function() notices=notices+1 end}
Settings={
    GetValue=function() return nil end,
    SetValue=function() error("Settings proxies are unavailable before their definitions load") end,
}
function GetActionBarToggles() return unpack(extraBars) end
function SetActionBarToggles(...) extraBars={...} end
function ChatFrame1:BreakFromFrameManager() self.detached=true end
C_Timer={After=function(_,fn) fn() end}
function GetCVar(k) return values[k] end
function SetCVar(k,v) values[k]=v end
function SetOverrideBinding(_,_,key,action) held[key]=action end
function SetOverrideBindingClick(_,_,key,name) held[key]=name end
function ClearOverrideBindings() held={} end
function InCombatLockdown() return combat end
function GetCursorInfo() if cursor then return cursor[1],cursor[2] end end
function ClearCursor() cursor=nil end
function GetActionInfo(slot) if actions[slot] then return unpack(actions[slot]) end end
C_ActionBar={
    GetActionBarPage=function() return actionPage end,
    SetActionBarPage=function(value)
        actionPage=value
        for i=1,12 do _G["ActionButton"..i].action=(value-1)*12+i end
    end,
}
function PickupAction(slot) assert(not combat) cursor=actions[slot] actions[slot]=nil end
function PickupSpell(id) cursor={"spell",id} end
function PickupItem(id) cursor={"item",id} end
function PickupMacro(id) cursor={"macro",id} end
function PickupSpellBookItem(slot) cursor={"spell",100+slot} end
function PlaceAction(slot) assert(not combat) actions[slot],cursor=cursor,actions[slot] end
function GetNumSpellTabs() return 1 end
function GetSpellTabInfo() return "Test",nil,0,3 end
function GetSpellBookItemInfo() return "SPELL" end
function IsPassiveSpell(slot) return slot==2 end
function GetSpellBookItemName(slot) return "Spell "..slot,"Rank 1" end
function GetSpellBookItemTexture(slot) return slot end
C_Container={
    GetContainerNumSlots=function(bag) return bag==0 and 1 or 0 end,
    GetContainerItemInfo=function() return {itemID=500,iconFileID=55} end,
    PickupContainerItem=function() cursor={"item",500} end,
}
function GetItemInfo() return "Test item" end
function GetNumMacros() return 1,1 end
function GetMacroInfo(i) return "Macro "..i,i end

for i=1,12 do
    local main=CreateFrame("Button","ActionButton"..i)
    main.action=i
    local extra=CreateFrame("Button","MultiBarBottomLeftButton"..i)
    extra.action=60+i
    local middle=CreateFrame("Button","MultiBarBottomRightButton"..i)
    middle.action=48+i
end
local active
local function boot(enabled)
    local chunk=assert(load(ADDON,"Touch.lua"))
    local before=#all
    chunk("WeHavePhonesTouch",{enabled=enabled})
    active=all[before+1]
    active.scripts.OnEvent(active,"PLAYER_LOGIN")
end
local function clickText(text)
    for i=#all,1,-1 do
        local button=all[i]
        if (button.text==text or (button.font and button.font.text==text)) and button.scripts.OnClick then button.scripts.OnClick(button) return end
    end
    error("Missing button "..text)
end
local function pageClick(name)
    -- Emulate Blizzard's secure actionbar action from the configured attributes.
    -- This path must not depend on an insecure OnClick callback or Layout in combat.
    local button=objects[name]
    assert(button.type=="actionbar" and button.scripts.OnClick==nil)
    local delta=button.action=="increment" and 1 or -1
    local value=((actionPage-1+delta)%6)+1
    assert(VIEWABLE_ACTION_BAR_PAGES[value])
    C_ActionBar.SetActionBarPage(value)
    active.scripts.OnEvent(active,"ACTIONBAR_PAGE_CHANGED")
end
local function edit(button)
    button.scripts.OnMouseUp(button,"RightButton")
end
boot(false)
assert(next(held)==nil and UIParent:GetScale()==1)
boot(true)
assert(values.GamePadEnable=="0" and not extraBars[1] and not extraBars[2] and extraBars[3])
assert(UIParent:GetScale()==1.5 and layout==12)
for i=1,6 do assert(VIEWABLE_ACTION_BAR_PAGES[i],"all six pages accessible through the switcher") end
assert(not MultiBarBottomLeft.visible and not MultiBarBottomRight.visible,"extra grids do not take camera space")
assert(held.F9=="WeHavePhonesTouchMenuTrigger" and held.W=="MOVEFORWARD")
assert(objects.WeHavePhonesTouchMenuTrigger.clicks[1]=="AnyUp" and objects.WeHavePhonesTouchMenuTrigger.clicks[2]=="AnyDown",
    "menu accepts bindings dispatched on press or release")
for i=1,12 do
    assert(_G["ActionButton"..i].scale==1.35,"35 percent larger default actions")
    assert(_G["ActionButton"..i].type2=="" and _G["ActionButton"..i].clicks[1]=="LeftButtonUp")
end
assert(ActionButton1.point[3]=="BOTTOMRIGHT", "main grid at the bottom right")
assert(ActionButton8.point[3]=="BOTTOMRIGHT" and ActionButton9.point[3]=="BOTTOM", "eight right actions and four middle actions")
assert(ActionButton9.point[5]==ActionButton12.point[5],"middle group is only one row")
assert(math.abs(ActionButton1.point[5]*1.35-118)<.001,"right grid is only two rows high")
pageClick("WeHavePhonesTouchPage1")
assert(actionPage==6 and objects.WeHavePhonesTouchPager.label.text=="Bar 6","previous wraps to page six")
pageClick("WeHavePhonesTouchPage2")
assert(actionPage==1,"next wraps back to page one")
ChatFrame1:ApplySystemAnchor()
assert(ChatFrame1.point[3]=="LEFT" and ChatFrame1.detached, "late Edit Mode changes cannot move chat over the controls")
ChatFrame1:SetPoint("BOTTOMLEFT", UIParent, "BOTTOMLEFT", 0, 0)
assert(ChatFrame1.point[3]=="LEFT", "panel-driven anchor changes cannot cover the joystick")
edit(ActionButton1)
assert(objects.WeHavePhonesTouchEditor.visible and actions[1][2]==101)
assert(objects.WeHavePhonesTouchChoice1.entry.name:find("Spell 1"))
assert(objects.WeHavePhonesTouchChoice2.entry.name:find("Spell 3"),"passives excluded")
objects.WeHavePhonesTouchChoice2.scripts.OnClick(objects.WeHavePhonesTouchChoice2)
assert(actions[1][2]==103 and not cursor,"selection changes only the selected slot")
edit(ActionButton1)
clickText("Undo")
assert(actions[1][2]==101,"undo restores the original action")
pageClick("WeHavePhonesTouchPage1")
edit(ActionButton1)
clickText("Macros")
objects.WeHavePhonesTouchChoice1.scripts.OnClick(objects.WeHavePhonesTouchChoice1)
assert(actions[61][1]=="macro" and actions[61][2]==1)
edit(ActionButton1)
clickText("Items")
objects.WeHavePhonesTouchChoice1.scripts.OnClick(objects.WeHavePhonesTouchChoice1)
assert(actions[61][1]=="item" and actions[61][2]==500)
pageClick("WeHavePhonesTouchPage2")
edit(ActionButton1)
clickText("Clear slot")
assert(actions[1]==nil)
edit(ActionButton1)
clickText("Undo")
assert(actions[1][2]==101)
cursor={"item",900}
local before=notices
edit(ActionButton1)
assert(notices==before+1 and cursor[2]==900,"cursor contents never discarded to open editor")
cursor=nil
objects.WeHavePhonesTouchMenuTrigger.scripts.OnClick()
clickText("175%")
assert(UIParent:GetScale()==1.75)
clickText("150%")
local scale=UIParent:GetScale()
active.scripts.OnEvent(active,"PLAYER_ENTERING_WORLD")
assert(UIParent:GetScale()==scale,"re-entry never compounds scale")
boot(true)
assert(UIParent:GetScale()==scale,"reload never compounds scale")
edit(ActionButton1)
combat=true
active.scripts.OnEvent(active,"PLAYER_REGEN_DISABLED")
assert(not objects.WeHavePhonesTouchEditor.visible,"combat closes the editor")
before=layout
pageClick("WeHavePhonesTouchPage2")
assert(actionPage==2 and ActionButton1.action==13 and layout==before,"secure paging works in combat without moving buttons")
active.scripts.OnEvent(active,"UI_SCALE_CHANGED")
assert(layout==before,"secure buttons untouched in combat")
objects.WeHavePhonesTouchChoice1.scripts.OnClick(objects.WeHavePhonesTouchChoice1)
assert(actions[1][2]==101,"stale picker callbacks cannot edit in combat")
boot(false)
assert(values.GamePadEnable=="0","restoration waits until out of combat")
combat=false
active.scripts.OnEvent(active,"PLAYER_REGEN_ENABLED")
for k,v in pairs(original) do assert(values[k]==v,"restore CVar "..k) end
assert(UIParent:GetScale()==1 and not extraBars[1] and not extraBars[2] and extraBars[3],"root scale and extra bars restored without changing another bar")
assert(ChatFrame1.width==300 and ChatFrame1.height==150,"chat geometry restored")
assert(next(held)==nil and next(WeHavePhonesTouchDB.cvars)==nil)
print("Touch addon PASS (large paged actions, spell/item/macro editing, undo, combat and restoration)")

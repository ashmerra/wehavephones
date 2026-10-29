local original = {GamePadEnable="1", InputDeviceInterfaceStyle="1", useUiScale="0", uiScale="0.64", lootUnderMouse="1", alwaysCompareItems="0",
    SoftTargetInteract="1", SoftTargetInteractArc="0", SoftTargetIconInteract="0", nameplateShowEnemies="0"}
local values, held, objects, all = {}, {}, {}, {}
for k,v in pairs(original) do values[k]=v end
local combat, cursor, layout, notices, actionPage = false, nil, 0, 0, 1
local extraBars = {false,false,true,false,false,false,false}
local actions = {[1]={"spell",101}, [61]={"item",500}}
local targetExists, targetDead = false, false
local buffs = 0
WOW_PROJECT_MAINLINE, WOW_PROJECT_CLASSIC, WOW_PROJECT_ID, BOOKTYPE_SPELL = 1, 2, 2, "spell"
tinsert, UISpecialFrames, unpack = table.insert, {}, table.unpack or unpack
VIEWABLE_ACTION_BAR_PAGES = {1,1,1,1,1,1}
local methods = {}
local secureHooks = {}
function methods:SetScale(value) assert(not combat or self==ObjectiveTrackerFrame and not self:IsProtected()) self.scale=value end
function methods:GetScale() return self.scale or 1 end
function methods:GetAlpha() return self.alpha or 1 end
function methods:SetAlpha(value) self.alpha=value end
function methods:GetWidth() return self.width or 1600 end
function methods:GetHeight() return self.height or 900 end
function methods:SetSize(w,h) self.width,self.height=w,h end
function methods:SetWidth(w) self.width=w end
function methods:SetHeight(h) self.height=h end
local function topRect(object)
    -- Left, bottom, width and height in UIParent units, from the object's single anchor.
    local s=object:GetEffectiveScale()/UIParent:GetEffectiveScale()
    local point,_,relativePoint,x,y=unpack(object.point)
    local W,H=UIParent:GetWidth(),UIParent:GetHeight()
    local ax=relativePoint:find("LEFT") and 0 or relativePoint:find("RIGHT") and W or W/2
    local ay=relativePoint:find("TOP") and H or relativePoint:find("BOTTOM") and 0 or H/2
    ax,ay=ax+x*s,ay+y*s
    local w,h=object:GetWidth()*s,object:GetHeight()*s
    local left=point:find("LEFT") and ax or point:find("RIGHT") and ax-w or ax-w/2
    local bottom=point:find("TOP") and ay-h or point:find("BOTTOM") and ay or ay-h/2
    return left,bottom,w,h
end
-- Like WoW, edges are in the object's own scaled coordinates.
function methods:GetBottom()
    if not self.point or self.point[2]~=UIParent then return nil end
    local _,bottom=topRect(self)
    return bottom*UIParent:GetEffectiveScale()/self:GetEffectiveScale()
end
function methods:GetRight()
    if not self.point or self.point[2]~=UIParent then return nil end
    local left,_,w=topRect(self)
    return (left+w)*UIParent:GetEffectiveScale()/self:GetEffectiveScale()
end
function methods:SetPoint(...) self.point={...} if self.action then layout=layout+1 end end
function methods:GetPoint() return unpack(self.point or {"BOTTOMLEFT",UIParent,"BOTTOMLEFT",2,3}) end
function methods:GetName() return self.name end
function methods:GetNumPoints() return self.point and 1 or 0 end
function methods:GetParent() return self.parent end
function methods:IsProtected() return self.protected or false end
function methods:GetEffectiveScale()
    return self:GetScale()*((not self.ignoreParentScale and self.parent) and self.parent:GetEffectiveScale() or 1)
end
function methods:IsShown() return self.visible==true end
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
function methods:GetFontString() return self.font end
function methods:GetID() return self.id end
function methods:SetVertexColor(...) self.color={...} end
function methods:SetTextColor(...) self.textColor={...} end
function methods:CreateLine() return CreateFrame("Line") end
function methods:GetHighlightTexture() return self.highlight end
function methods:SetHighlightTexture() self.highlight=CreateFrame("Texture") end
function methods:IsOwned(owner) return self.owner==owner end
function methods:SetAnchorType(anchor) self.anchor=anchor end
for _,name in ipairs({"RegisterEvent","RegisterUnitEvent","ClearAllPoints","SetFrameStrata","EnableMouse","SetClampedToScreen","SetBackdrop",
    "SetTexture","SetJustifyH","SetBackdropColor","SetBackdropBorderColor","SetGradient","SetThickness",
    "SetColorTexture","SetStartPoint","SetEndPoint"}) do methods[name]=function() end end
function CreateColor(r,g,b,a) return {r=r,g=g,b=b,a=a} end
function UnitBuff(_,i) if i<=buffs then return "Buff "..i end end
function CreateFrame(_,name,parent)
    local object=setmetatable({name=name, scripts={}, parent=parent}, {__index=methods})
    if name then objects[name]=object _G[name]=object end
    table.insert(all,object)
    return object
end
function methods:CreateFontString() return CreateFrame("FontString") end
function methods:CreateTexture() return CreateFrame("Texture") end
UIParent=CreateFrame("Frame","UIParent")
ChatFrame1=CreateFrame("Frame","ChatFrame1",UIParent)
ChatFrame1:SetSize(300,150)
ChatFrame1ButtonFrame=CreateFrame("Frame","ChatFrame1ButtonFrame",ChatFrame1)
ChatFrame1ButtonFrame:SetSize(29,184)
function ChatFrame1ButtonFrame:Layout() end
GameTooltip=CreateFrame("GameTooltip","GameTooltip")
function GameTooltip_SetDefaultAnchor(tooltip)
    tooltip:SetPoint("BOTTOMRIGHT",UIParent,"BOTTOMRIGHT",0,0)
end
ChatFrame1EditBox=CreateFrame("EditBox","ChatFrame1EditBox",UIParent)
ChatFrame1EditBox:SetSize(300,32)
ChatFrame1EditBox:SetPoint("TOPLEFT",ChatFrame1,"BOTTOMLEFT",-5,-2)
-- Classic Era 1.15 activates chat through ChatFrameUtil. The old globals are aliases.
ChatFrameUtil={ActivateChat=function(editBox) editBox:Show() end, DeactivateChat=function(editBox) editBox:Hide() end}
ChatEdit_ActivateChat, ChatEdit_DeactivateChat = ChatFrameUtil.ActivateChat, ChatFrameUtil.DeactivateChat
local macroFrames, helpFrames = 0, 0
function ShowMacroFrame() macroFrames=macroFrames+1 end
function ToggleHelpFrame() helpFrames=helpFrames+1 end
-- Classic Era's Edit Mode preset positions at the top of the screen.
for _, top in ipairs({{"MinimapCluster",192,192,"TOPRIGHT",0,0},{"PlayerFrame",232,100,"TOPLEFT",-1,0},
    {"TargetFrame",232,100,"TOPLEFT",232,-4},{"BuffFrame",385,45,"TOPRIGHT",-187,-13},{"DebuffFrame",280,45,"TOPRIGHT",-202,-152},
    {"UIWidgetTopCenterContainerFrame",300,40,"TOP",0,-15}}) do
    local object=CreateFrame("Frame",top[1],UIParent)
    object:SetSize(top[2],top[3])
    object:SetPoint(top[4],UIParent,top[4],top[5],top[6])
    object:Show()
end
MultiBarBottomLeft=CreateFrame("Frame","MultiBarBottomLeft")
MultiBarBottomRight=CreateFrame("Frame","MultiBarBottomRight")
function ChatFrame1:ApplySystemAnchor() self:SetPoint("BOTTOMLEFT", UIParent, "BOTTOMLEFT", 0, 0) end
function hooksecurefunc(object,name,callback)
    if type(object)=="string" then callback,name,object=name,object,_G end
    local original=object[name]
    secureHooks[#secureHooks+1]={object,name,original}
    object[name]=function(...) original(...) callback(...) end
end
UIErrorsFrame={AddMessage=function() notices=notices+1 end}
function geterrorhandler() return error end
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
function UnitExists() return targetExists end
function UnitIsDead() return targetDead end
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
    local main=CreateFrame("Button","ActionButton"..i,UIParent)
    main:SetSize(36,36)
    main.action=i
    function main:SetTooltip()
        GameTooltip.owner=self
        GameTooltip.contents="Action "..self.action
        GameTooltip.anchor="ANCHOR_RIGHT"
        GameTooltip:SetPoint("BOTTOMRIGHT",UIParent,"BOTTOMRIGHT",0,0)
    end
    local extra=CreateFrame("Button","MultiBarBottomLeftButton"..i)
    extra.action=60+i
    local middle=CreateFrame("Button","MultiBarBottomRightButton"..i)
    middle.action=48+i
end
for _, name in ipairs({"StanceBar","PetActionBar","PossessActionBar","MicroMenuContainer","BagsBar","MicroButtonAndBagsBar","StatusTrackingBarManager"}) do
    local object=CreateFrame("Frame",name,UIParent)
    object:SetSize(name=="StatusTrackingBarManager" and 1024 or 360,30)
    object:SetPoint("BOTTOMLEFT",UIParent,"BOTTOMLEFT",2,3)
    object:Show()
end
QuestWatchFrame=CreateFrame("Frame","QuestWatchFrame",UIParent)
QuestWatchFrame:SetSize(280,160)
QuestWatchFrame:SetPoint("TOPRIGHT",UIParent,"TOPRIGHT",-10,-200)
QuestWatchFrame:Show()
PartyFrame=CreateFrame("Frame","PartyFrame",UIParent)
PartyFrame:SetSize(144,320)
PartyFrame.Background=CreateFrame("Frame",nil,PartyFrame)
PartyFrame.Background:SetAlpha(.6)
for i=1,4 do
    local member=CreateFrame("Button",nil,PartyFrame)
    PartyFrame["MemberFrame"..i]=member
    member:SetSize(128,53)
    member:SetPoint("TOPLEFT",PartyFrame,"TOPLEFT",0,-(i-1)*80)
    member:Hide()
end
function PartyFrame:Layout()
    for i=1,4 do self["MemberFrame"..i]:SetPoint("TOPLEFT",self,"TOPLEFT",0,-(i-1)*80) end
end
ContainerFrame1=CreateFrame("Frame","ContainerFrame1",UIParent)
ContainerFrame1.bags={}
function ShowUIPanel(object) object:Show() end
function HideUIPanel(object) object:Hide() end
function UpdateUIPanelPositions() end
function UpdateContainerFrameAnchors() end
function CloseAllBags()
    for i=1,12 do if _G["ContainerFrame"..i] then _G["ContainerFrame"..i]:Hide() end end
    ContainerFrame1.bags={}
    UpdateContainerFrameAnchors()
end
function OpenBag(id)
    local object=_G["ContainerFrame"..(id+1)]
    object:Show()
    local found=false
    for _,name in ipairs(ContainerFrame1.bags) do if name==object:GetName() then found=true end end
    if not found then ContainerFrame1.bags[#ContainerFrame1.bags+1]=object:GetName() end
    UpdateContainerFrameAnchors()
end
function OpenAllBags()
    for _,name in ipairs(ContainerFrame1.bags) do if _G[name]:IsShown() then return end end
    for id=0,4 do if C_Container.GetContainerNumSlots(id)>0 then OpenBag(id) end end
end
function UIParent_ManageFramePositions()
    StanceBar:SetPoint("BOTTOMLEFT",UIParent,"BOTTOMLEFT",0,0)
    PetActionBar:SetPoint("BOTTOMLEFT",UIParent,"BOTTOMLEFT",0,0)
end
local active
local function boot(enabled, scale)
    -- Reloading WoW discards the previous Lua runtime and its secure hooks.
    -- Keep the DB and frame geometry here, but never run old enabled callbacks
    -- while exercising a new disabled addon instance.
    for i=#secureHooks,1,-1 do
        local hook=secureHooks[i]
        hook[1][hook[2]]=hook[3]
    end
    secureHooks={}
    local chunk=assert(load(ADDON,"Touch.lua"))
    local before=#all
    chunk("WeHavePhonesTouch",{enabled=enabled,scale=scale})
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
assert(held.W=="MOVEFORWARD" and held.F9==nil,"the old in-game touch panel is gone")
assert(held["CTRL-SHIFT-F1"]=="TOGGLECHARACTER0" and held["CTRL-SHIFT-F4"]=="TOGGLEQUESTLOG" and held["CTRL-SHIFT-F10"]=="TOGGLEGAMEMENU"
    and held["CTRL-SHIFT-F11"]=="FOLLOWTARGET" and held["CTRL-SHIFT-F12"]=="REPLY" and held["SHIFT-F7"]=="NAMEPLATES" and held["CTRL-ALT-F7"]==nil,
    "the touch menu opens windows and helpers through stock bindings")
local function menuChord(key)
    local trigger=objects[held[key]]
    assert(trigger and trigger.clicks[1]=="AnyUp" and trigger.clicks[2]=="AnyDown","menu triggers accept either click phase")
    trigger.scripts.OnClick(trigger,"LeftButton",true)
    trigger.scripts.OnClick(trigger,"LeftButton",false)
end
menuChord("SHIFT-F1")
menuChord("SHIFT-F2")
assert(macroFrames==1 and helpFrames==1,"Macros and Help open once per chord, not on both click phases")
assert(values.nameplateShowEnemies=="1","enemy nameplates give steadier tap targets")
assert(held.F1=="TARGETSELF" and held.F2=="TARGETPARTYMEMBER1" and held.F5=="TARGETPARTYMEMBER4"
    and held.F6=="TARGETNEARESTFRIEND" and held.G=="ASSISTTARGET", "friendly targets use standard secure bindings")
assert(held.F7=="TOGGLERUN" and held.F11=="SITORSTAND", "walking and held descent use Classic's actual binding names")
assert(values.lootUnderMouse=="0", "loot opens in a fitted panel rather than under a thumb")
assert(values.alwaysCompareItems=="1","Inspect can show equipment comparisons without a held modifier")
assert(not MicroMenuContainer.visible and not BagsBar.visible and not MicroButtonAndBagsBar.visible,
    "menu and bag shortcuts no longer overlap the class controls")
assert(QuestWatchFrame.point[3]=="CENTER","the stock tracker stays inside the right thumb rail")
assert(PossessActionBar.point[4]==PetActionBar.point[4] and PossessActionBar.point[5]==PetActionBar.point[5],
    "possession retains its secure commands in the pet footer")
Questie_BaseFrame=CreateFrame("Frame","Questie_BaseFrame",UIParent)
Questie_BaseFrame:SetSize(300,180)
Questie_BaseFrame:SetPoint("TOPRIGHT",UIParent,"TOPRIGHT",-2,-200)
Questie_BaseFrame:Show()
active.scripts.OnUpdate(active,.3)
assert(Questie_BaseFrame.point[3]=="CENTER","a Questie tracker shown after login is fitted without a quest event")
assert(not QuestWatchFrame.visible and Questie_BaseFrame.point[3]=="CENTER",
    "late Questie initialization removes the duplicate stock tracker without changing Questie settings")
Questie_BaseFrame:SetPoint("TOPRIGHT",UIParent,"TOPRIGHT",-2,-200)
assert(Questie_BaseFrame.point[3]=="CENTER","late tracker layout cannot move quests back over Target")
local getRight=TargetFrame.GetRight
TargetFrame.GetRight=function() error("fixture layout failure") end
assert(not pcall(active.scripts.OnEvent,active,"GROUP_ROSTER_UPDATE"),"layout errors are still reported")
TargetFrame.GetRight=getRight
Questie_BaseFrame:SetPoint("TOPRIGHT",UIParent,"TOPRIGHT",-2,-200)
assert(Questie_BaseFrame.point[3]=="CENTER","one failed layout never blocks the next one")
assert(values.SoftTargetInteract=="3" and values.SoftTargetInteractArc=="1" and values.SoftTargetIconInteract=="1",
    "mouse-based touch enables nearby interaction with a visible front-cone target")
local corpse=objects.WeHavePhonesTouchCorpseTrigger
assert(held.F10==corpse.name and corpse.type=="macro" and corpse.macrotext=="/target [@softinteract,exists,dead]",
    "corpse selection uses a dead-only secure macro with no living-target fallback")
assert(corpse.scripts.OnClick==nil,"corpse targeting never runs from an insecure click callback")
local beforeCorpseNotice=notices
corpse.scripts.PostClick(corpse,"LeftButton",true)
assert(notices==beforeCorpseNotice,"press phase does not duplicate failure notices")
corpse.scripts.PostClick(corpse,"LeftButton",false)
assert(notices==beforeCorpseNotice+1,"missing corpse gives a useful range and facing hint")
targetExists,targetDead=true,true
corpse.scripts.PostClick(corpse,"LeftButton",false)
assert(notices==beforeCorpseNotice+1,"selected corpses need no extra status text")
for i=1,12 do
    assert(math.abs(_G["ActionButton"..i].scale-1.35)<.001,"35 percent larger default actions")
    assert(_G["ActionButton"..i].type2=="" and _G["ActionButton"..i].clicks[1]=="LeftButtonUp")
end
assert(ActionButton1.point[3]=="CENTER", "main grid uses the screen center")
assert(ActionButton8.point[3]=="CENTER" and ActionButton9.point[3]=="CENTER", "both action groups use the same anchor")
assert(ActionButton9.point[5]==ActionButton12.point[5],"middle group is only one row")
assert(math.abs(ActionButton1.point[5]*1.35+UIParent:GetHeight()/2-118)<.001,"right grid is only two rows high")
local function close(a,b,message) assert(math.abs(a-b)<.001,message) end
local function positions()
    local result={}
    for i=1,12 do
        local button=_G["ActionButton"..i]
        assert(button.point[2]==UIParent and button.point[3]=="CENTER")
        result[i]={button.point[4]*button:GetScale(),button.point[5]*button:GetScale()}
    end
    return result
end
local geometry=GEOMETRY_PATH and assert(io.open(GEOMETRY_PATH,"w"))
local function exportGeometry(object, width, height, kind, fullHeight)
    if not geometry then return end
    local scale=object:GetEffectiveScale()/UIParent:GetEffectiveScale()
    if object.point[3]~="CENTER" then
        local left,bottom,w,h=topRect(object)
        geometry:write(table.concat({width,height,left,bottom,w,h,kind},"\t"),"\n")
        return
    end
    local x=width/2+object.point[4]*scale
    local y=height/2+object.point[5]*scale
    local h=(fullHeight or object:GetHeight())*scale
    if object.point[1]=="TOPLEFT" then y=y-h end
    geometry:write(table.concat({width,height,x,y,object:GetWidth()*scale,h,kind},"\t"),"\n")
end
for _,scale in ipairs({1.25,1.5,1.75}) do
    WeHavePhonesTouchDB.touchScale=scale
    for _,aspect in ipairs({1,6/5,5/4,4/3,3/2,16/10,16/9,2,19.5/9,21/9,32/9}) do
        local height=768/scale
        local width=height*aspect
        UIParent:SetSize(width,height)
        active.scripts.OnEvent(active,"DISPLAY_SIZE_CHANGED")
        local current=positions()
        local pager=objects.WeHavePhonesTouchPager
        local fit=ActionButton1:GetScale()/1.35
        local compact=aspect<1.59
        local pagerScale=pager:GetEffectiveScale()/UIParent:GetEffectiveScale()
        close(pager.point[5]*pagerScale+height/2,compact and 12 or 64*fit,"pager follows the fitted action layout")
        local rectangles={}
        for i=1,12 do
            local x,y=current[i][1]+width/2,current[i][2]+height/2
            local side=36*_G["ActionButton"..i]:GetScale()
            assert(x>=width*.09 and x+side<=width*.91 and y>0 and y+side<height,"actions stay inside thumb rails and screen")
            if compact then assert(x>=width*.54,"compact actions leave the character and self-targeted ground clear") end
            rectangles[#rectangles+1]={x,y,x+side,y+side}
            if compact then assert(y+side<=height*.51,"compact actions stay below the upper camera area")
            elseif i<=8 then assert(y+side<=height*.40,"right actions stay below the camera gesture region")
            else assert(y+side<=height*.28,"middle actions stay below the camera gesture region") end
            exportGeometry(_G["ActionButton"..i],width,height,"action")
        end
        local px=pager.point[4]*pagerScale+width/2
        local py=pager.point[5]*pagerScale+height/2
        rectangles[#rectangles+1]={px,py,px+pager:GetWidth()*pagerScale,py+pager:GetHeight()*pagerScale}
        exportGeometry(pager,width,height,"pager")
        exportGeometry(StanceBar,width,height,"class")
        exportGeometry(PetActionBar,width,height,"class")
        for i,a in ipairs(rectangles) do
            for j=i+1,#rectangles do
                local b=rectangles[j]
                assert(a[3]<=b[1] or b[3]<=a[1] or a[4]<=b[2] or b[4]<=a[2],"action and pager hit regions never overlap")
            end
        end
        assert(StanceBar.point[3]=="CENTER" and PetActionBar.point[3]=="CENTER","class controls have a separate footer")
        local compactTop=aspect<1.59
        local rail=compactTop and (width-math.min(width*.8,height*16/9))/2 or 0
        local zoneLeft=width-(.312+math.max(.42,math.min(.75,aspect-1.1)))*height
        local ml,mb,mw,mh=topRect(MinimapCluster)
        assert(math.abs(ml+mw-(width-rail))<.01 and mw<=height*.30+.01 and mh<=height*.30+.01,
            "the minimap fits its square, out of the rail on narrower games")
        local pl,pb,pw,ph=topRect(PlayerFrame)
        local tl,tb,tw,th=topRect(TargetFrame)
        assert(pl<1 and math.abs(tl-(pl+pw+1*(pw/232)))<1 and math.abs(pb-tb-4*pw/232)<.01,"player and target frames keep their stock order")
        assert(PlayerFrame.point[5]==0 and TargetFrame.point[5]==-4 and TargetFrame.point[4]==232,
            "unit frames keep their own anchors and only change size")
        assert(tl+tw<=(compactTop and ml or zoneLeft)+.01 or tw<=232*.4+.01,"the target frame stays left of the toolbar zone and minimap")
        for _,name in ipairs({"BuffFrame","DebuffFrame"}) do
            local al,ab,aw,ah=topRect(_G[name])
            if compactTop then assert(al+aw<=width-math.max(rail,width*.16)+.01 and ab+ah<=mb+.01,"narrower games show auras under the minimap")
            else assert(ab+ah<=height*.86+.01 and al+aw<=width-.30*height+.01,"wide games show auras under the toolbar band") end
            exportGeometry(_G[name],width,height,"aura")
        end
        if not compactTop then
            local wl,wb,ww,wh=topRect(UIWidgetTopCenterContainerFrame)
            assert(wb+wh<=height*.86+.01,"top-center widgets start below the toolbar band")
            exportGeometry(UIWidgetTopCenterContainerFrame,width,height,"widget")
        end
        exportGeometry(MinimapCluster,width,height,"minimap")
        exportGeometry(PlayerFrame,width,height,"unit")
        exportGeometry(TargetFrame,width,height,"unit")
        assert(StatusTrackingBarManager.point[3]=="BOTTOM","experience stays below the class footer")
        for i=1,4 do PartyFrame["MemberFrame"..i]:Show() end
        active.scripts.OnEvent(active,"GROUP_ROSTER_UPDATE")
        local lastRight=0
        for i=1,4 do
            local member=PartyFrame["MemberFrame"..i]
            local s=member:GetEffectiveScale()/UIParent:GetEffectiveScale()
            local x=width/2+member.point[4]*s
            local top=height/2+member.point[5]*s
            assert(member:GetParent()==PartyFrame and member.point[3]=="CENTER", "party targeting stays on the stock unit frames")
            assert(x>=lastRight and x+128*s<=width*.80,"party members stay separate and clear of the minimap")
            assert(height-top>=height*.179 and top-104*s>=height*.694,"party pets and auras stay above chat and movement")
            exportGeometry(member,width,height,"party",104)
            lastRight=x+128*s
        end
        close(ChatFrame1.point[5],-height*.02,"group chat stays below the party row and above the stick")
        local chatTop=height*.48+ChatFrame1:GetHeight()
        local chatButtonsTop=height*.48+ChatFrame1ButtonFrame:GetHeight()*ChatFrame1ButtonFrame:GetEffectiveScale()/UIParent:GetEffectiveScale()
        assert(chatTop+32<=height*.694 and chatButtonsTop<=height*.694,
            "chat tabs and the complete stock button column stay below party pets and auras")
        ChatFrame1ButtonFrame:SetHeight(220)
        ChatFrame1ButtonFrame:Layout()
        assert(height*.48+220*ChatFrame1ButtonFrame:GetEffectiveScale()/UIParent:GetEffectiveScale()<=height*.694,
            "late chat utility buttons cannot grow into party controls")
        ChatFrame1ButtonFrame:SetHeight(184)
        PartyFrame:Layout()
        assert(PartyFrame.MemberFrame1.point[3]=="CENTER","stock party layout updates cannot cover the movement stick")
        for i=1,4 do PartyFrame["MemberFrame"..i]:Hide() end
        active.scripts.OnEvent(active,"GROUP_ROSTER_UPDATE")
        assert(ChatFrame1:GetHeight()==92 and ChatFrame1ButtonFrame:GetScale()==1,
            "solo chat regains its original utility button scale")
    end
end
if geometry then geometry:close() end
UIParent_ManageFramePositions()
assert(StanceBar.point[3]=="CENTER" and PetActionBar.point[3]=="CENTER","late stock frame management preserves class controls")
WeHavePhonesTouchDB.touchScale=1.5
UIParent:SetSize(1600,900)
active.scripts.OnEvent(active,"DISPLAY_SIZE_CHANGED")
local panelSizes={CharacterFrame={384,512},SpellBookFrame={384,512},QuestLogFrame={384,512},
    MerchantFrame={384,512},BankFrame={384,512},MailFrame={384,512},TradeFrame={384,512},
    AuctionFrame={840,512},TradeSkillFrame={384,512},CraftFrame={384,512},
    GossipFrame={384,512},QuestFrame={384,512},ClassTrainerFrame={384,512},
    WorldMapFrame={1024,768},LootFrame={256,300},TaxiFrame={384,512},
    PlayerTalentFrame={640,512},MacroFrame={720,512},FriendsFrame={384,512},PVPFrame={384,512},
    InspectFrame={384,512},DressUpFrame={384,512},PetStableFrame={384,512},ItemTextFrame={384,512},StackSplitFrame={200,100}}
for name,size in pairs(panelSizes) do
    local object=CreateFrame("Frame",name,UIParent)
    object:SetSize(unpack(size))
    object:SetPoint("TOPLEFT",UIParent,"TOPLEFT",10,-10)
    if name=="WorldMapFrame" then object.ignoreParentScale=true end
end
for i=1,12 do
    local object=_G["ContainerFrame"..i] or CreateFrame("Frame","ContainerFrame"..i,UIParent)
    object:SetSize(192,300+(i%3)*60)
    object.id=i-1
    object:SetPoint("BOTTOMRIGHT",UIParent,"BOTTOMRIGHT",0,0)
end
local function closeWorkspace()
    for name in pairs(panelSizes) do _G[name]:Hide() end
    for i=1,12 do _G["ContainerFrame"..i]:Hide() end
    ContainerFrame1.bags={}
    UpdateContainerFrameAnchors()
end
local function checkWorkspace(panelNames,bagCount)
    closeWorkspace()
    local visible={}
    for _,name in ipairs(panelNames) do _G[name]:Show() visible[#visible+1]=_G[name] end
    for i=1,bagCount do
        local object=_G["ContainerFrame"..i]
        object:Show()
        ContainerFrame1.bags[i]=object:GetName()
        visible[#visible+1]=object
    end
    UpdateContainerFrameAnchors()
    local rectangles={}
    for _,object in ipairs(visible) do
        local s=object:GetEffectiveScale()/UIParent:GetEffectiveScale()
        assert(object.point[3]=="CENTER" and s>0 and s<=1.001,"stock workspace panels are fitted without reparenting")
        local left=UIParent:GetWidth()/2+object.point[4]*s
        local top=UIParent:GetHeight()/2+object.point[5]*s
        local right,bottom=left+object:GetWidth()*s,top-object:GetHeight()*s
        assert(left>=-0.001 and right<=UIParent:GetWidth()+.001 and bottom>=UIParent:GetHeight()*.019,
            "all menu controls remain on screen")
        assert(top<=UIParent:GetHeight()*.801,"large windows stay below the native utility toolbar")
        rectangles[#rectangles+1]={left,bottom,right,top}
    end
    for i,a in ipairs(rectangles) do
        for j=i+1,#rectangles do
            local b=rectangles[j]
            assert(a[3]<=b[1]+.001 or b[3]<=a[1]+.001 or a[4]<=b[2]+.001 or b[4]<=a[2]+.001,
                "stock windows and bags never overlap their transfer destinations")
        end
    end
end
local workspaceScenarios=0
for _,scale in ipairs({1.25,1.5,1.75}) do
    WeHavePhonesTouchDB.touchScale=scale
    for _,aspect in ipairs({1,6/5,5/4,4/3,3/2,16/10,16/9,2,19.5/9,21/9,32/9}) do
        UIParent:SetSize(768/scale*aspect,768/scale)
        active.scripts.OnEvent(active,"DISPLAY_SIZE_CHANGED")
        for name in pairs(panelSizes) do checkWorkspace({name},0) workspaceScenarios=workspaceScenarios+1 end
        for _,name in ipairs({"CharacterFrame","MerchantFrame","MailFrame","TradeFrame","AuctionFrame","TradeSkillFrame","CraftFrame","DressUpFrame","StackSplitFrame"}) do
            checkWorkspace({name},5)
            workspaceScenarios=workspaceScenarios+1
        end
        checkWorkspace({},5)
        checkWorkspace({"BankFrame"},12)
        checkWorkspace({"CharacterFrame","SpellBookFrame"},0)
        workspaceScenarios=workspaceScenarios+3
    end
end
closeWorkspace()
for name in pairs(panelSizes) do
    assert(_G[name]:GetScale()==1 and _G[name].point[3]=="TOPLEFT","closing restores stock panel scale and anchors")
end
WeHavePhonesTouchDB.touchScale=1.5
UIParent:SetSize(1600,900)
active.scripts.OnEvent(active,"DISPLAY_SIZE_CHANGED")
local originalBagSlots=C_Container.GetContainerNumSlots
C_Container.GetContainerNumSlots=function() return 8 end
OpenAllBags()
assert(objects.WeHavePhonesTouchInventory.visible and #ContainerFrame1.bags==5,"inventory navigation appears with the stock bags")
cursor={"item",900}
objects.WeHavePhonesTouchBag2.scripts.OnClick()
assert(objects.WeHavePhonesTouchBag2.active and not objects.WeHavePhonesTouchAllBags.active,"the open bag page is highlighted")
assert(#ContainerFrame1.bags==1 and ContainerFrame3.visible and not ContainerFrame1.visible
    and cursor[2]==900,"bag pages retain a carried item without using or discarding it")
assert(ContainerFrame3:GetScale()==1,"a single bag uses full-size item controls when it fits")
objects.WeHavePhonesTouchAllBags.scripts.OnClick()
assert(#ContainerFrame1.bags==5 and cursor[2]==900,"All reopens every player bag after selecting one page")
KEYRING_CONTAINER=-2
local keyring=CreateFrame("Frame","TouchFixtureKeyring",UIParent)
keyring.id=-2
keyring:SetSize(192,256)
function CloseBag(id)
    if id==KEYRING_CONTAINER then
        keyring:Hide()
        for i=#ContainerFrame1.bags,1,-1 do
            if ContainerFrame1.bags[i]==keyring:GetName() then table.remove(ContainerFrame1.bags,i) end
        end
    end
end
function ToggleBag(id)
    assert(id==KEYRING_CONTAINER)
    keyring:Show()
    ContainerFrame1.bags[#ContainerFrame1.bags+1]=keyring:GetName()
    UpdateContainerFrameAnchors()
end
UpdateContainerFrameAnchors()
assert(objects.WeHavePhonesTouchKeys.visible,"Classic keyring access remains available beside inventory pages")
objects.WeHavePhonesTouchKeys.scripts.OnClick()
assert(keyring.visible and #ContainerFrame1.bags==1 and cursor[2]==900,"the keyring uses its stock bag behavior without clearing a carried item")
assert(objects.WeHavePhonesTouchKeys.active and not objects.WeHavePhonesTouchBag2.active,"Keys shows as the current page")
objects.WeHavePhonesTouchAllBags.scripts.OnClick()
assert(not keyring.visible and #ContainerFrame1.bags==5,"All closes the keyring and restores player bags")
assert(objects.WeHavePhonesTouchAllBags.active and not objects.WeHavePhonesTouchKeys.active,"All is highlighted with every bag open")
BankFrame:Show()
UpdateContainerFrameAnchors()
assert(objects.WeHavePhonesTouchBankBag5.visible and objects.WeHavePhonesTouchBankBag5.enabled,"bank bag navigation is available only at the bank")
objects.WeHavePhonesTouchBankBag5.scripts.OnClick()
assert(#ContainerFrame1.bags==1 and ContainerFrame6.visible and BankFrame.visible and cursor[2]==900,
    "bank pages keep the bank destination and carried item intact")
cursor=nil
closeWorkspace()
assert(not objects.WeHavePhonesTouchInventory.visible,"inventory navigation cannot block other windows after bags close")
C_Container.GetContainerNumSlots=originalBagSlots
pageClick("WeHavePhonesTouchPage1")
assert(actionPage==6 and objects.WeHavePhonesTouchPager.label.text=="6","previous wraps and shows only the page number")
assert(objects.WeHavePhonesTouchPager.dots[6].color[1]==.46 and objects.WeHavePhonesTouchPager.dots[1].color[1]==.55,
    "six page dots show which page is in use")
pageClick("WeHavePhonesTouchPage2")
assert(actionPage==1,"next wraps back to page one")
ChatFrame1:ApplySystemAnchor()
assert(ChatFrame1.point[3]=="CENTER" and ChatFrame1.detached, "late Edit Mode changes cannot move chat over the controls")
ChatFrame1:SetPoint("BOTTOMLEFT", UIParent, "BOTTOMLEFT", 0, 0)
assert(ChatFrame1.point[3]=="CENTER", "panel-driven anchor changes cannot cover the joystick")
ActionButton1:SetTooltip()
assert(GameTooltip.point[2]==ChatFrame1 and GameTooltip.point[3]=="BOTTOMLEFT","action tooltips cover the chat area")
assert(GameTooltip.anchor=="ANCHOR_NONE" and GameTooltip.contents=="Action 1" and GameTooltip.owner==ActionButton1,
    "tooltip relocation preserves the action text and owner")
GameTooltip_SetDefaultAnchor(GameTooltip)
assert(GameTooltip.point[2]==ChatFrame1,"default world and experience tooltips cannot cover the thumb controls")
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
menuChord("SHIFT-F6")
assert(UIParent:GetScale()==1.75 and WeHavePhonesTouchDB.touchScale==1.75,"the touch menu changes the interface size")
menuChord("SHIFT-F5")
local scale=UIParent:GetScale()
active.scripts.OnEvent(active,"PLAYER_ENTERING_WORLD")
assert(UIParent:GetScale()==scale,"re-entry never compounds scale")
boot(true)
assert(UIParent:GetScale()==scale,"reload never compounds scale")
boot(true,1.25)
assert(UIParent:GetScale()==1.25 and WeHavePhonesTouchDB.modeScale==1.25,"the app's interface size reaches each character at login")
menuChord("SHIFT-F6")
boot(true,1.25)
assert(UIParent:GetScale()==1.75,"an in-game size choice survives a reload until the app writes a new one")
boot(true,1.5)
assert(UIParent:GetScale()==scale,"a new app-wide size applies at the next launch")
ChatFrameUtil.ActivateChat(ChatFrame1EditBox)
assert(ChatFrame1EditBox.point[1]=="TOP" and ChatFrame1EditBox.point[3]=="TOP"
    and -ChatFrame1EditBox.point[5]>=UIParent:GetHeight()*.14 and -ChatFrame1EditBox.point[5]>=PlayerFrame:GetHeight()*PlayerFrame:GetScale(),
    "typing moves the chat box above the Android keyboard, below the unit frames and toolbar")
ChatFrameUtil.DeactivateChat(ChatFrame1EditBox)
assert(ChatFrame1EditBox.point[1]=="TOPLEFT" and ChatFrame1EditBox.point[2]==ChatFrame1 and ChatFrame1EditBox:GetWidth()==300,
    "the chat box returns to the chat frame after typing")
edit(ActionButton1)
combat=true
ChatFrame1:ApplySystemAnchor()
assert(ChatFrame1.point[3]=="CENTER","unprotected stock chat cannot move over the stick during combat")
ChatFrame1:SetSize(900,400)
assert(ChatFrame1:GetWidth()<=300 and ChatFrame1:GetHeight()==92,"combat chat resizing cannot cover movement or party controls")
ChatFrame1.protected=true
ChatFrame1:ApplySystemAnchor()
assert(ChatFrame1.point[3]=="BOTTOMLEFT","protected replacement chat frames remain untouched during combat")
ChatFrame1.protected=false
ChatFrame1:ApplySystemAnchor()
ActionButton1:SetTooltip()
assert(GameTooltip.point[2]==ChatFrame1,"action tooltips stay over chat in combat")
active.scripts.OnEvent(active,"PLAYER_REGEN_DISABLED")
assert(not objects.WeHavePhonesTouchEditor.visible,"combat closes the editor")
local debuffY=DebuffFrame.point[5]
buffs=12
active.scripts.OnEvent(active,"UNIT_AURA")
assert(DebuffFrame.point[5]<debuffY,"a second row of buffs moves debuffs down, in combat as well")
buffs=0
CharacterFrame:Show()
local oldScale=CharacterFrame:GetScale()
UpdateContainerFrameAnchors()
active.scripts.OnUpdate(active,.3)
assert(CharacterFrame:GetScale()==oldScale and CharacterFrame.point[3]=="TOPLEFT","window fitting never mutates secure frames in combat")
before=layout
pageClick("WeHavePhonesTouchPage2")
assert(actionPage==2 and ActionButton1.action==13 and layout==before,"secure paging works in combat without moving buttons")
active.scripts.OnEvent(active,"UI_SCALE_CHANGED")
assert(layout==before,"secure buttons untouched in combat")
active.scripts.OnEvent(active,"DISPLAY_SIZE_CHANGED")
assert(layout==before,"display changes defer secure layout until combat ends")
objects.WeHavePhonesTouchChoice1.scripts.OnClick(objects.WeHavePhonesTouchChoice1)
assert(actions[1][2]==101,"stale picker callbacks cannot edit in combat")
boot(false)
assert(values.GamePadEnable=="0","restoration waits until out of combat")
combat=false
active.scripts.OnEvent(active,"PLAYER_REGEN_ENABLED")
for k,v in pairs(original) do assert(values[k]==v,"restore CVar "..k) end
assert(UIParent:GetScale()==1 and not extraBars[1] and not extraBars[2] and extraBars[3],"root scale and extra bars restored without changing another bar")
assert(ChatFrame1.width==300 and ChatFrame1.height==150,"chat geometry restored")
assert(StanceBar:GetScale()==1 and PetActionBar:GetScale()==1 and StatusTrackingBarManager:GetScale()==1,
    "class and experience scaling restore on opt-out")
assert(MicroMenuContainer.visible and BagsBar.visible and MicroButtonAndBagsBar.visible,"stock shortcuts restore on opt-out")
assert(QuestWatchFrame.visible and Questie_BaseFrame:GetScale()==1 and Questie_BaseFrame.point[3]=="TOPRIGHT",
    "opting out restores stock and Questie tracker geometry and visibility")
assert(PartyFrame.MemberFrame1.point[2]==PartyFrame and PartyFrame.MemberFrame1.point[3]=="TOPLEFT"
    and PartyFrame.MemberFrame1:GetScale()==1 and PartyFrame.Background:GetAlpha()==.6,
    "pooled party frames and their original backdrop restore on opt-out")
assert(next(held)==nil and next(WeHavePhonesTouchDB.cvars)==nil)
WOW_PROJECT_ID=3
boot(true)
active.scripts.OnEvent(active,"QUEST_LOG_UPDATE")
assert(MicroMenuContainer.visible and BagsBar.visible and StanceBar:GetScale()==1,
    "other WoW editions retain their stock HUD even after quest events")
boot(false)
-- Retail has no legacy spellbook globals, a reagent bag and pooled stock
-- containers. Exercise the real branch with those old entry points absent.
WOW_PROJECT_ID=WOW_PROJECT_MAINLINE
WeHavePhonesTouchDB=nil
GetNumSpellTabs,GetSpellTabInfo,GetSpellBookItemInfo,IsPassiveSpell=nil,nil,nil,nil
GetSpellBookItemName,GetSpellBookItemTexture,PickupSpellBookItem=nil,nil,nil
GetItemInfo,PickupItem,PickupSpell,UnitBuff=nil,nil,nil,nil
Constants={InventoryConstants={NumBagSlots=4,NumReagentBagSlots=1}}
Enum={SpellBookSpellBank={Player=0},SpellBookItemType={Spell=1,FutureSpell=2,Flyout=4}}
local retailPickup
C_SpellBook={
    GetNumSpellBookSkillLines=function() return 3 end,
    GetSpellBookSkillLineInfo=function(tab)
        return {itemIndexOffset=(tab-1)*5,numSpellBookItems=5,shouldHide=tab==2,offSpecID=tab==3 and 71 or nil}
    end,
    GetSpellBookItemInfo=function(slot,bank)
        assert(bank==0 and slot<=5,"hidden and inactive skill lines are not enumerated")
        return {itemType=slot==3 and 2 or slot==5 and 4 or 1,name="Retail spell "..slot,iconID=slot,
            isPassive=slot==2,isOffSpec=slot==4}
    end,
    PickupSpellBookItem=function(slot,bank) assert(bank==0) retailPickup=slot cursor={"spell",200+slot} end,
}
C_Item={GetItemInfo=function() return "Retail reagent" end,PickupItem=function(id) cursor={"item",id} end}
C_Spell={PickupSpell=function(id) cursor={"spell",id} end}
local restrictedAuras, secretAuraIndex, deniedAuraAccess, auraQueries = false, false, false, 0
C_Secrets={ShouldAurasBeSecret=function() return restrictedAuras end,
    ShouldUnitAuraIndexBeSecret=function() return secretAuraIndex end}
C_UnitAuras={GetBuffDataByIndex=function(_,i)
    auraQueries=auraQueries+1
    assert(not restrictedAuras and not secretAuraIndex,"Retail aura data is restricted to the stock UI")
    if deniedAuraAccess then error("Aura access became unavailable") end
    if i<=buffs then return {} end
end}
MainActionBar=CreateFrame("Frame","MainActionBar",UIParent)
for _,name in ipairs({"ActionBarPageNumber","EndCaps","BorderArt"}) do
    MainActionBar[name]=CreateFrame("Frame",nil,MainActionBar)
    MainActionBar[name]:Show()
end
MicroMenu=CreateFrame("Frame","MicroMenu",UIParent)
MicroMenu:Show()
ObjectiveTrackerFrame=CreateFrame("Frame","ObjectiveTrackerFrame",UIParent)
ObjectiveTrackerFrame:SetSize(280,400)
ObjectiveTrackerFrame:SetPoint("TOPRIGHT",UIParent,"TOPRIGHT",-20,-200)
ObjectiveTrackerFrame:Show()
-- Retail Edit Mode overrides geometry methods with global layout callbacks.
-- Those callbacks can query restricted auras when an addon resizes a tracker.
local stockTrackerLayout
ObjectiveTrackerFrame.SetScaleBase=methods.SetScale
ObjectiveTrackerFrame.SetPointBase=methods.SetPoint
ObjectiveTrackerFrame.ClearAllPointsBase=methods.ClearAllPoints
for _,method in ipairs({"SetScale","SetPoint","ClearAllPoints"}) do
    local base=ObjectiveTrackerFrame[method.."Base"]
    ObjectiveTrackerFrame[method]=function(self,...)
        assert(stockTrackerLayout or not (combat or restrictedAuras),"Retail Edit Mode layout queried restricted auras")
        return base(self,...)
    end
end
local function stockTrackerAnchor(...)
    stockTrackerLayout=true
    ObjectiveTrackerFrame:SetPoint(...)
    stockTrackerLayout=false
end
QuestWatchFrame=nil
Questie_BaseFrame=nil
ContainerFrame1.bags=nil
KEYRING_CONTAINER=nil
ContainerFrameCombinedBags=CreateFrame("Frame","ContainerFrameCombinedBags",UIParent)
ContainerFrameCombinedBags:SetSize(550,500)
local retailContainers={ContainerFrameCombinedBags,ContainerFrame1,ContainerFrame2}
ContainerFrameUtil_EnumerateContainerFrames=function() return ipairs(retailContainers) end
CloseAllBags=function() for _,object in ipairs(retailContainers) do object:Hide() end end
CloseAllBags()
OpenBag=function(id)
    assert(id==5,"reagent bag uses its actual Retail ID")
    ContainerFrame2.id=id
    ContainerFrame2:Show()
    UpdateContainerFrameAnchors()
end
OpenAllBags=function() ContainerFrameCombinedBags:Show() UpdateContainerFrameAnchors() end
C_Container.GetContainerNumSlots=function(bag) return (bag==0 or bag==5) and 1 or 0 end
C_Container.GetContainerItemInfo=function(bag) if bag==5 then return {itemID=501,iconFileID=56} end end
C_Container.PickupContainerItem=function(bag,slot) assert(bag==5 and slot==1) cursor={"item",501} end
for i=1,12 do _G["ActionButton"..i]:SetSize(45,45) end
for name,size in pairs({PlayerSpellsFrame={1200,650},ProfessionsBookFrame={550,550},ProfessionsFrame={1000,650}}) do
    local object=CreateFrame("Frame",name,UIParent)
    object:SetSize(unpack(size))
    object:SetPoint("TOPLEFT",UIParent,"TOPLEFT",10,-10)
    panelSizes[name]=size
end
actionPage=1
C_ActionBar.SetActionBarPage(1)
UIParent:SetSize(1600,900)
boot(true)
assert(not MicroMenu.visible and not MainActionBar.EndCaps.visible and not MainActionBar.BorderArt.visible,
    "Retail desktop shortcuts and art leave the touch workspace free")
assert(ObjectiveTrackerFrame.point[3]=="CENTER" and PlayerFrame.point[3]=="TOPLEFT",
    "Retail's tracker and Edit Mode unit frames fit the touch layout")
close(ActionButton1:GetScale()*45,36*1.35,"Retail actions have the same physical size as Classic actions")
assert(ActionButton1.scripts.OnMouseUp,"Retail hotbars can be edited by holding")
assert(held["CTRL-SHIFT-F6"]=="TOGGLEPROFESSIONBOOK","Retail opens its profession book instead of the removed Skills binding")
if GEOMETRY_PATH then geometry=assert(io.open(GEOMETRY_PATH,"a")) end
for _,scale in ipairs({1.25,1.5,1.75}) do
    WeHavePhonesTouchDB.touchScale=scale
    for _,aspect in ipairs({1,6/5,5/4,4/3,3/2,16/10,16/9,2,19.5/9,21/9,32/9}) do
        local height=768/scale
        local width=height*aspect
        UIParent:SetSize(width,height)
        active.scripts.OnEvent(active,"DISPLAY_SIZE_CHANGED")
        for i=1,12 do exportGeometry(_G["ActionButton"..i],width,height,"action") end
        for _,info in ipairs({{"WeHavePhonesTouchPager","pager"},{"MinimapCluster","minimap"},{"PlayerFrame","unit"},
            {"TargetFrame","unit"},{"BuffFrame","aura"},{"DebuffFrame","aura"}}) do exportGeometry(_G[info[1]],width,height,info[2]) end
        local left,_,w=topRect(TargetFrame)
        local zone=aspect<1.59 and select(1,topRect(MinimapCluster)) or width-(.312+math.max(.42,math.min(.75,aspect-1.1)))*height
        assert(left+w<=zone,"Retail unit frames cannot reach the toolbar or minimap")
        for _,name in ipairs({"PlayerSpellsFrame","ProfessionsBookFrame","ProfessionsFrame","WorldMapFrame"}) do
            checkWorkspace({name},0)
            workspaceScenarios=workspaceScenarios+1
        end
    end
end
if geometry then geometry:close() end
closeWorkspace()
ContainerFrame1.bags=nil
UIParent:SetSize(1600,900)
WeHavePhonesTouchDB.touchScale=1.5
active.scripts.OnEvent(active,"DISPLAY_SIZE_CHANGED")
edit(ActionButton1)
assert(objects.WeHavePhonesTouchChoice1.entry.name=="Retail spell 1" and objects.WeHavePhonesTouchChoice2.entry==nil,
    "Retail spell picker excludes passive, future, flyout and off-spec entries")
objects.WeHavePhonesTouchChoice1.scripts.OnClick(objects.WeHavePhonesTouchChoice1)
assert(retailPickup==1 and actions[1][2]==201 and not cursor,"Retail spells retain their book slot and bank")
edit(ActionButton1)
clickText("Undo")
assert(actions[1][2]==101,"Retail spell Undo uses C_Spell without deprecated globals")
edit(ActionButton1)
clickText("Items")
assert(objects.WeHavePhonesTouchChoice1.entry.name=="Retail reagent","Retail item choices include the reagent bag")
objects.WeHavePhonesTouchChoice1.scripts.OnClick(objects.WeHavePhonesTouchChoice1)
assert(actions[1][2]==501)
edit(ActionButton1)
clickText("Clear slot")
edit(ActionButton1)
clickText("Undo")
assert(actions[1][2]==501,"Retail item Undo uses C_Item")
clickText("Cancel")
ContainerFrameCombinedBags:Show()
UpdateContainerFrameAnchors()
assert(ContainerFrameCombinedBags.point[3]=="CENTER" and not objects.WeHavePhonesTouchInventory.visible,
    "Retail combined inventory is fitted without pretending it is a single bag page")
CloseAllBags()
ContainerFrame1.id=0
ContainerFrame1:Show()
UpdateContainerFrameAnchors()
assert(objects.WeHavePhonesTouchBag5 and objects.WeHavePhonesTouchInventory.visible,
    "Retail separate bags include the reagent page")
cursor={"item",900}
objects.WeHavePhonesTouchBag5.scripts.OnClick()
assert(ContainerFrame2.visible and not ContainerFrame1.visible and cursor[2]==900,
    "Retail bag paging preserves a carried item")
assert(objects.WeHavePhonesTouchBag5.active,"Retail reagent page is highlighted")
cursor=nil
CloseAllBags()
local immediateAfter, pending = C_Timer.After, {}
C_Timer.After=function(_,fn) pending[#pending+1]=fn end
local function flushLayout()
    local callbacks=pending
    pending={}
    for _,fn in ipairs(callbacks) do fn() end
end
BuffFrame:SetSize(385,135)
buffs=12
active.scripts.OnEvent(active,"UNIT_AURA")
flushLayout()
local _,_,_,_,debuffY=DebuffFrame:GetPoint(1)
close(debuffY,-(900*.14+4+90),"Retail spacing counts accessible buffs rather than the stock frame's empty rows")
local publicQueries=auraQueries
restrictedAuras=true
active.scripts.OnEvent(active,"UNIT_AURA")
flushLayout()
assert(auraQueries==publicQueries,"aura restrictions are respected before combat lockdown begins")
restrictedAuras=false
secretAuraIndex=true
active.scripts.OnEvent(active,"UNIT_AURA")
flushLayout()
assert(auraQueries==publicQueries,"individually secret auras are never queried")
secretAuraIndex=false
deniedAuraAccess=true
active.scripts.OnEvent(active,"UNIT_AURA")
flushLayout()
local _,_,_,_,deniedDebuffY=DebuffFrame:GetPoint(1)
close(deniedDebuffY,debuffY,"a denied aura query retains the last accessible spacing")
deniedAuraAccess=false
restrictedAuras=true
combat=true
local beforeRetail=layout
stockTrackerAnchor("TOPRIGHT",UIParent,"TOPRIGHT",0,-300)
assert(ObjectiveTrackerFrame.point[3]=="TOPRIGHT","Retail tracker hooks let Blizzard finish its layout first")
flushLayout()
assert(ObjectiveTrackerFrame.point[3]=="CENTER","Retail's unprotected tracker stays clear of actions during combat")
ObjectiveTrackerFrame.protected=true
stockTrackerAnchor("TOPRIGHT",UIParent,"TOPRIGHT",0,-300)
flushLayout()
assert(ObjectiveTrackerFrame.point[3]=="TOPRIGHT","protected replacement trackers defer combat layout")
ObjectiveTrackerFrame.protected=false
-- Restricted aura information may arrive before InCombatLockdown becomes true.
active.scripts.OnEvent(active,"UNIT_AURA")
flushLayout()
assert(layout==beforeRetail,"secret aura updates never relayout protected hotbars")
C_Timer.After=immediateAfter
pageClick("WeHavePhonesTouchPage2")
active.scripts.OnEvent(active,"DISPLAY_SIZE_CHANGED")
assert(actionPage==2 and layout==beforeRetail,"Retail paging stays secure and geometry waits for combat to end")
active.scripts.OnEvent(active,"PLAYER_REGEN_DISABLED")
edit(ActionButton1)
assert(not objects.WeHavePhonesTouchEditor.visible,"Retail editor cannot open in combat")
combat=false
restrictedAuras=false
active.scripts.OnEvent(active,"PLAYER_REGEN_ENABLED")
boot(false)
assert(MicroMenu.visible and MainActionBar.EndCaps.visible and MainActionBar.BorderArt.visible,
    "Retail stock shortcuts and art restore on opt-out")
assert(ObjectiveTrackerFrame.point[3]=="TOPRIGHT" and ObjectiveTrackerFrame:GetScale()==1,
    "Retail tracker geometry restores on opt-out")
print("Touch addon PASS ("..workspaceScenarios.." window and inventory scenarios, paged actions, editing, combat and restoration)")

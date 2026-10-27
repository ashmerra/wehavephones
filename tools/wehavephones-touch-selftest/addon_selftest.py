"""Run the bundled Lua against a small client API fixture, using the host Lua library."""
import ctypes
import ctypes.util
from pathlib import Path

library = ctypes.util.find_library("lua5.4") or ctypes.util.find_library("lua5.2")
if not library:
    raise SystemExit("Install a Lua 5.2 or 5.4 shared library to run the addon check")
lua = ctypes.CDLL(library)
lua.luaL_newstate.restype = ctypes.c_void_p
lua.luaL_openlibs.argtypes = [ctypes.c_void_p]
lua.luaL_loadbufferx.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_size_t, ctypes.c_char_p, ctypes.c_char_p]
lua.lua_pcallk.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_ssize_t, ctypes.c_void_p]
lua.lua_tolstring.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p]
lua.lua_tolstring.restype = ctypes.c_char_p
lua.lua_close.argtypes = [ctypes.c_void_p]
script = Path(__file__).resolve().parents[2] / "app/src/wehavephones/assets/wehavephones/addons/WeHavePhonesTouch/Touch.lua"
source = r'''
local original = {GamePadEnable="1", InputDeviceInterfaceStyle="1", useUiScale="0", uiScale="0.64"}
local values = {}
for k,v in pairs(original) do values[k]=v end
local held, combat, layout = {}, false, 0
WOW_PROJECT_CLASSIC, WOW_PROJECT_ID = 2, 2
UIParent = {GetWidth=function() return 1600 end, GetHeight=function() return 900 end}
C_Timer = {After=function(_, fn) fn() end}
function GetCVar(k) return values[k] end
function SetCVar(k,v) values[k]=v end
function SetOverrideBinding(_, _, key, action) held[key]=action end
function ClearOverrideBindings() held={} end
function InCombatLockdown() return combat end
local active
function CreateFrame()
    active = {RegisterEvent=function() end, SetScript=function(self, _, fn) self.event=fn end}
    return active
end
for i=1,12 do
    _G["ActionButton"..i] = {
        SetScale=function() assert(not combat) end,
        ClearAllPoints=function() end,
        SetPoint=function() layout=layout+1 end,
        SetAttribute=function() assert(not combat) end,
        Show=function() end,
    }
end
local function boot(enabled)
    local chunk=assert(load(ADDON, "Touch.lua"))
    chunk("WeHavePhonesTouch", {enabled=enabled})
    active:event("PLAYER_LOGIN")
end
boot(false)
assert(next(WeHavePhonesTouchDB.cvars)==nil and next(held)==nil)
boot(true)
assert(values.GamePadEnable=="0" and values.useUiScale=="1")
assert(held.W=="MOVEFORWARD" and held.A=="STRAFELEFT" and held.F=="INTERACTTARGET" and held.N=="NEXTACTIONPAGE")
assert(layout==12 and tonumber(values.uiScale)>.64)
local enlarged=values.uiScale
active:event("PLAYER_ENTERING_WORLD")
assert(values.uiScale==enlarged)
boot(true)
assert(values.uiScale==enlarged, "reload must not compound UI scaling")
local before=layout
combat=true
active:event("UI_SCALE_CHANGED")
assert(layout==before, "never change secure buttons in combat")
boot(false)
assert(values.GamePadEnable=="0", "defer restoration in combat")
combat=false
active:event("PLAYER_REGEN_ENABLED")
for k,v in pairs(original) do assert(values[k]==v, "restore "..k) end
assert(next(WeHavePhonesTouchDB.cvars)==nil and next(held)==nil)
print("Touch addon PASS (enable, reload, combat deferral and restoration)")
'''
# A Lua long string keeps the actual addon byte-for-byte, without escaping its code.
payload = ("local ADDON = [====[" + script.read_text() + "]====]\n" + source).encode()
state = lua.luaL_newstate()
try:
    lua.luaL_openlibs(state)
    status = lua.luaL_loadbufferx(state, payload, len(payload), b"touch-addon-selftest", None)
    if status == 0:
        status = lua.lua_pcallk(state, 0, 0, 0, 0, None)
    if status:
        raise SystemExit(lua.lua_tolstring(state, -1, None).decode())
finally:
    lua.lua_close(state)

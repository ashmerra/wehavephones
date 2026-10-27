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
source = Path(__file__).with_name('addon_fixture.lua').read_text()
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

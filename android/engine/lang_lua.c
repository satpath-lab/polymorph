#include "volt.h"
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"
#include <string.h>

static int l_host(lua_State *L) { char *r = volt_host(luaL_checkstring(L, 1)); lua_pushstring(L, r); free(r); return 1; }
static int l_read(lua_State *L) { size_t n; char *b = volt_read(luaL_checkstring(L, 1), &n); if (!b) { lua_pushnil(L); return 1; } lua_pushlstring(L, b, n); free(b); return 1; }

static void *open_(const char *dir, const char *main, const char *harness, char **err) {
    lua_State *L = luaL_newstate();
    luaL_openlibs(L);
    lua_pushcfunction(L, l_host); lua_setglobal(L, "__host");
    lua_pushcfunction(L, l_read); lua_setglobal(L, "__read");
    lua_pushstring(L, dir); lua_setglobal(L, "__VOLT_DIR");
    lua_pushstring(L, main); lua_setglobal(L, "__VOLT_MAIN");
    if (luaL_loadfile(L, harness) || lua_pcall(L, 0, 0, 0)) { *err = volt_strdup(lua_tostring(L, -1)); lua_close(L); return NULL; }
    return L;
}

static char *call_(void *h, const char *fn, const char *arg) {
    lua_State *L = h;
    lua_getglobal(L, "__volt");
    lua_pushstring(L, fn); lua_pushstring(L, arg);
    if (lua_pcall(L, 2, 1, 0)) { lua_pop(L, 1); return volt_strdup("{\"error\":\"lua internal error\"}"); }
    char *o = volt_strdup(lua_tostring(L, -1)); lua_pop(L, 1);
    return o;
}

static void close_(void *h) { lua_close(h); }
const VoltLang VOLT_LANG = { open_, call_, close_ };

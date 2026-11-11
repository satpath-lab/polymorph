#include "volt.h"
#include "quickjs.h"
#include <string.h>

typedef struct { JSRuntime *rt; JSContext *ctx; } Eng;

static JSValue j_host(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    const char *s = JS_ToCString(ctx, argv[0]);
    char *r = volt_host(s ? s : "null");
    JS_FreeCString(ctx, s);
    JSValue v = JS_NewString(ctx, r); free(r); return v;
}

static JSValue j_read(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    const char *p = JS_ToCString(ctx, argv[0]);
    size_t n; char *b = p ? volt_read(p, &n) : NULL;
    JS_FreeCString(ctx, p);
    if (!b) return JS_NULL;
    JSValue v = JS_NewStringLen(ctx, b, n); free(b); return v;
}

static char *exc(JSContext *ctx) {
    JSValue e = JS_GetException(ctx);
    const char *s = JS_ToCString(ctx, e);
    char *o = volt_strdup(s ? s : "error");
    JS_FreeCString(ctx, s); JS_FreeValue(ctx, e);
    return o;
}

static void *open_(const char *dir, const char *main, const char *harness, char **err) {
    Eng *e = calloc(1, sizeof(Eng));
    e->rt = JS_NewRuntime();
    JS_SetMaxStackSize(e->rt, 4 * 1024 * 1024);
    e->ctx = JS_NewContext(e->rt);
    JSValue g = JS_GetGlobalObject(e->ctx);
    JS_SetPropertyStr(e->ctx, g, "__host", JS_NewCFunction(e->ctx, j_host, "__host", 1));
    JS_SetPropertyStr(e->ctx, g, "__read", JS_NewCFunction(e->ctx, j_read, "__read", 1));
    JS_SetPropertyStr(e->ctx, g, "__VOLT_DIR", JS_NewString(e->ctx, dir));
    JS_SetPropertyStr(e->ctx, g, "__VOLT_MAIN", JS_NewString(e->ctx, main));
    JS_FreeValue(e->ctx, g);
    size_t n; char *src = volt_read(harness, &n);
    if (!src) { *err = volt_strdup("harness missing"); return NULL; }
    JSValue r = JS_Eval(e->ctx, src, n, "harness.js", JS_EVAL_TYPE_GLOBAL);
    free(src);
    if (JS_IsException(r)) { *err = exc(e->ctx); JS_FreeContext(e->ctx); JS_FreeRuntime(e->rt); free(e); return NULL; }
    JS_FreeValue(e->ctx, r);
    return e;
}

static char *call_(void *h, const char *fn, const char *arg) {
    Eng *e = h;
    JS_UpdateStackTop(e->rt);
    JSValue g = JS_GetGlobalObject(e->ctx);
    JSValue f = JS_GetPropertyStr(e->ctx, g, "__volt");
    JSValue args[2] = { JS_NewString(e->ctx, fn), JS_NewString(e->ctx, arg) };
    JSValue r = JS_Call(e->ctx, f, g, 2, args);
    char *out;
    if (JS_IsException(r)) { char *m = exc(e->ctx); out = malloc(strlen(m) + 32); sprintf(out, "{\"error\":\"js engine error\"}"); free(m); }
    else { const char *s = JS_ToCString(e->ctx, r); out = volt_strdup(s ? s : "null"); JS_FreeCString(e->ctx, s); }
    JS_FreeValue(e->ctx, r); JS_FreeValue(e->ctx, args[0]); JS_FreeValue(e->ctx, args[1]); JS_FreeValue(e->ctx, f); JS_FreeValue(e->ctx, g);
    JSContext *pc; while (JS_ExecutePendingJob(e->rt, &pc) > 0) {}
    return out;
}

static void close_(void *h) { Eng *e = h; JS_FreeContext(e->ctx); JS_FreeRuntime(e->rt); free(e); }
const VoltLang VOLT_LANG = { open_, call_, close_ };

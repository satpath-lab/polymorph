#include "poly_apple.h"
#include "../core/volt.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

extern const VoltLang VOLT_LANG_lua __attribute__((weak));
extern const VoltLang VOLT_LANG_js __attribute__((weak));
extern const VoltLang VOLT_LANG_python __attribute__((weak));
extern const VoltLang VOLT_LANG_ruby __attribute__((weak));
extern const VoltLang VOLT_LANG_php __attribute__((weak));

static poly_host_fn host;
void poly_set_host(poly_host_fn fn) { host = fn; }
void poly_free(void *p) { free(p); }

static const VoltLang *pick(const char *l) {
    if (!strcmp(l, "lua")) return &VOLT_LANG_lua;
    if (!strcmp(l, "js")) return &VOLT_LANG_js;
    if (!strcmp(l, "python")) return &VOLT_LANG_python;
    if (!strcmp(l, "ruby")) return &VOLT_LANG_ruby;
    if (!strcmp(l, "php")) return &VOLT_LANG_php;
    return NULL;
}

int poly_has(const char *lang) { const VoltLang *v = pick(lang); return v && v->open; }

char *volt_strdup(const char *s) { size_t n = strlen(s); char *d = malloc(n + 1); memcpy(d, s, n + 1); return d; }

char *volt_read(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    char *b = malloc(n + 1);
    size_t got = fread(b, 1, n, f); fclose(f);
    b[got] = 0; if (len) *len = got;
    return b;
}

char *volt_host(const char *req) { return host ? host(req) : volt_strdup("{\"error\":\"no host\"}"); }

void *poly_open(const char *lang, const char *dir, const char *main, const char *harness, char **err) {
    const VoltLang *v = pick(lang);
    if (!v || !v->open) { *err = volt_strdup("this app was built without that language"); return NULL; }
    return v->open(dir, main, harness, err);
}

char *poly_call(const char *lang, void *h, const char *fn, const char *arg) { return pick(lang)->call(h, fn, arg); }
void poly_close(const char *lang, void *h) { pick(lang)->close(h); }

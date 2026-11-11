#include "volt.h"
#include <mruby.h>
#include <mruby/compile.h>
#include <mruby/string.h>
#include <mruby/variable.h>
#include <string.h>

static mrb_value r_host(mrb_state *mrb, mrb_value self) {
    char *s; mrb_get_args(mrb, "z", &s);
    char *r = volt_host(s);
    mrb_value o = mrb_str_new_cstr(mrb, r); free(r); return o;
}

static mrb_value r_read(mrb_state *mrb, mrb_value self) {
    char *p; mrb_get_args(mrb, "z", &p);
    size_t n; char *b = volt_read(p, &n);
    if (!b) return mrb_nil_value();
    mrb_value o = mrb_str_new(mrb, b, n); free(b); return o;
}

static mrb_value r_load(mrb_state *mrb, mrb_value self) {
    char *p; mrb_get_args(mrb, "z", &p);
    size_t n; char *b = volt_read(p, &n);
    if (!b) mrb_raisef(mrb, mrb_class_get(mrb, "ScriptError"), "cannot load such file -- %s", p);
    mrbc_context *c = mrbc_context_new(mrb);
    mrbc_filename(mrb, c, p);
    mrb_value v = mrb_load_nstring_cxt(mrb, b, n, c);
    mrbc_context_free(mrb, c); free(b);
    if (mrb->exc) { mrb_value e = mrb_obj_value(mrb->exc); mrb->exc = NULL; mrb_exc_raise(mrb, e); }
    return v;
}

static char *exc_str(mrb_state *mrb) {
    mrb_value e = mrb_obj_value(mrb->exc); mrb->exc = NULL;
    mrb_value s = mrb_inspect(mrb, e);
    return volt_strdup(mrb_string_value_cstr(mrb, &s));
}

static void *open_(const char *dir, const char *main, const char *harness, char **err) {
    mrb_state *mrb = mrb_open();
    if (!mrb) { *err = volt_strdup("mruby failed to start"); return NULL; }
    struct RClass *k = mrb->kernel_module;
    mrb_define_module_function(mrb, k, "__host", r_host, MRB_ARGS_REQ(1));
    mrb_define_module_function(mrb, k, "__read", r_read, MRB_ARGS_REQ(1));
    mrb_define_module_function(mrb, k, "__load", r_load, MRB_ARGS_REQ(1));
    mrb_gv_set(mrb, mrb_intern_lit(mrb, "$VOLT_DIR"), mrb_str_new_cstr(mrb, dir));
    mrb_gv_set(mrb, mrb_intern_lit(mrb, "$VOLT_MAIN"), mrb_str_new_cstr(mrb, main));
    size_t n; char *src = volt_read(harness, &n);
    if (!src) { *err = volt_strdup("harness missing"); mrb_close(mrb); return NULL; }
    mrbc_context *c = mrbc_context_new(mrb); mrbc_filename(mrb, c, "harness.rb");
    mrb_load_nstring_cxt(mrb, src, n, c);
    mrbc_context_free(mrb, c); free(src);
    if (mrb->exc) { *err = exc_str(mrb); mrb_close(mrb); return NULL; }
    return mrb;
}

static char *call_(void *h, const char *fn, const char *arg) {
    mrb_state *mrb = h;
    int ai = mrb_gc_arena_save(mrb);
    mrb_value r = mrb_funcall(mrb, mrb_top_self(mrb), "__volt", 2, mrb_str_new_cstr(mrb, fn), mrb_str_new_cstr(mrb, arg));
    char *out;
    if (mrb->exc) { char *e = exc_str(mrb); free(e); out = volt_strdup("{\"error\":\"ruby engine error\"}"); }
    else out = volt_strdup(mrb_string_p(r) ? RSTRING_CSTR(mrb, r) : "null");
    mrb_gc_arena_restore(mrb, ai);
    return out;
}

static void close_(void *h) { mrb_close(h); }
const VoltLang VOLT_LANG = { open_, call_, close_ };

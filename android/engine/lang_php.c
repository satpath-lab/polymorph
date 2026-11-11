#include "volt.h"
#include <sapi/embed/php_embed.h>
#include <main/php_main.h>
#include <Zend/zend_API.h>
#include <string.h>
#include <pthread.h>

static int inited;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

ZEND_BEGIN_ARG_INFO_EX(arginfo_one, 0, 0, 1)
    ZEND_ARG_INFO(0, s)
ZEND_END_ARG_INFO()

PHP_FUNCTION(__volt_host) {
    char *s; size_t n;
    ZEND_PARSE_PARAMETERS_START(1, 1) Z_PARAM_STRING(s, n) ZEND_PARSE_PARAMETERS_END();
    char *r = volt_host(s);
    RETVAL_STRING(r); free(r);
}

PHP_FUNCTION(__volt_read) {
    char *s; size_t n;
    ZEND_PARSE_PARAMETERS_START(1, 1) Z_PARAM_STRING(s, n) ZEND_PARSE_PARAMETERS_END();
    size_t len; char *b = volt_read(s, &len);
    if (!b) RETURN_NULL();
    RETVAL_STRINGL(b, len); free(b);
}

static const zend_function_entry volt_fns[] = {
    ZEND_FE(__volt_host, arginfo_one)
    ZEND_FE(__volt_read, arginfo_one)
    ZEND_FE_END
};

static size_t out_write(const char *s, size_t n) { return n; }

static void *open_(const char *dir, const char *main, const char *harness, char **err) {
    pthread_mutex_lock(&lock);
    setenv("VOLT_DIR", dir, 1); setenv("VOLT_MAIN", main, 1);
    if (!inited) {
        php_embed_module.additional_functions = volt_fns;
        php_embed_module.ub_write = out_write;
        php_embed_module.ini_entries = "display_errors=0\nlog_errors=0\nhtml_errors=0\nmemory_limit=256M\nmax_execution_time=0\nimplicit_flush=0\noutput_buffering=0\n";
        if (php_embed_init(0, NULL) != SUCCESS) { *err = volt_strdup("php failed to start"); pthread_mutex_unlock(&lock); return NULL; }
        inited = 1;
    } else {
        php_request_shutdown(NULL);
        php_request_startup();
    }
    int ok = 0;
    zend_first_try {
        zend_file_handle fh;
        zend_stream_init_filename(&fh, harness);
        ok = php_execute_script(&fh);
        zend_destroy_file_handle(&fh);
    } zend_end_try();
    pthread_mutex_unlock(&lock);
    if (!ok) { *err = volt_strdup("php harness failed to load"); return NULL; }
    return (void *)1;
}

static char *call_(void *h, const char *fn, const char *arg) {
    pthread_mutex_lock(&lock);
    char *out = NULL;
    zval fname, args[2], ret;
    ZVAL_STRING(&fname, "__volt");
    ZVAL_STRING(&args[0], fn);
    ZVAL_STRING(&args[1], arg);
    ZVAL_UNDEF(&ret);
    zend_try {
        if (call_user_function(CG(function_table), NULL, &fname, &ret, 2, args) == SUCCESS && Z_TYPE(ret) == IS_STRING)
            out = volt_strdup(Z_STRVAL(ret));
    } zend_catch {
        out = NULL;
    } zend_end_try();
    if (!out) out = volt_strdup("{\"error\":\"PHP fatal error\"}");
    zval_ptr_dtor(&fname); zval_ptr_dtor(&args[0]); zval_ptr_dtor(&args[1]); zval_ptr_dtor(&ret);
    pthread_mutex_unlock(&lock);
    return out;
}

static void close_(void *h) {}
const VoltLang VOLT_LANG = { open_, call_, close_ };

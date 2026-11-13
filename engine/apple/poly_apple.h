#ifndef POLY_APPLE_H
#define POLY_APPLE_H
#include <stddef.h>
typedef char *(*poly_host_fn)(const char *req);
void poly_set_host(poly_host_fn fn);
int poly_has(const char *lang);
void *poly_open(const char *lang, const char *dir, const char *main, const char *harness, char **err);
char *poly_call(const char *lang, void *h, const char *fn, const char *arg);
void poly_close(const char *lang, void *h);
void poly_free(void *p);
#endif

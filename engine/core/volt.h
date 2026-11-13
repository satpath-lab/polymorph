#ifndef VOLT_H
#define VOLT_H
#include <stdlib.h>
typedef struct VoltLang {
    void *(*open)(const char *dir, const char *main, const char *harness, char **err);
    char *(*call)(void *h, const char *fn, const char *arg);
    void (*close)(void *h);
} VoltLang;
extern const VoltLang VOLT_LANG;
char *volt_host(const char *req);
char *volt_read(const char *path, size_t *len);
char *volt_strdup(const char *s);
#endif

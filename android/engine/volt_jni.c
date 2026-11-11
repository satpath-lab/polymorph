#include <jni.h>
#include <string.h>
#include <stdio.h>
#include "volt.h"

static JavaVM *vm;
static jclass engineCls;
static jmethodID hostId;

JNIEXPORT jint JNI_OnLoad(JavaVM *v, void *r) {
    vm = v;
    JNIEnv *env;
    if ((*v)->GetEnv(v, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return -1;
    jclass c = (*env)->FindClass(env, "dev/volt/engine/VoltEngine");
    engineCls = (*env)->NewGlobalRef(env, c);
    hostId = (*env)->GetStaticMethodID(env, c, "host", "([B)[B");
    return JNI_VERSION_1_6;
}

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

static jbyteArray bytes(JNIEnv *env, const char *s) {
    jsize n = (jsize)strlen(s);
    jbyteArray a = (*env)->NewByteArray(env, n);
    (*env)->SetByteArrayRegion(env, a, 0, n, (const jbyte *)s);
    return a;
}

static char *cstr(JNIEnv *env, jbyteArray a) {
    jsize n = (*env)->GetArrayLength(env, a);
    char *s = malloc(n + 1);
    (*env)->GetByteArrayRegion(env, a, 0, n, (jbyte *)s);
    s[n] = 0;
    return s;
}

char *volt_host(const char *req) {
    JNIEnv *env; int attached = 0;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) { (*vm)->AttachCurrentThread(vm, &env, NULL); attached = 1; }
    jbyteArray jr = bytes(env, req);
    jbyteArray res = (jbyteArray)(*env)->CallStaticObjectMethod(env, engineCls, hostId, jr);
    char *out;
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); out = volt_strdup("{\"error\":\"host call failed\"}"); }
    else if (!res) out = volt_strdup("null");
    else { out = cstr(env, res); (*env)->DeleteLocalRef(env, res); }
    (*env)->DeleteLocalRef(env, jr);
    if (attached) (*vm)->DetachCurrentThread(vm);
    return out;
}

JNIEXPORT jlong JNICALL Java_dev_volt_engine_VoltEngine_nOpen(JNIEnv *env, jclass c, jstring dir, jstring main, jstring harness) {
    const char *d = (*env)->GetStringUTFChars(env, dir, NULL), *m = (*env)->GetStringUTFChars(env, main, NULL), *h = (*env)->GetStringUTFChars(env, harness, NULL);
    char *err = NULL;
    void *p = VOLT_LANG.open(d, m, h, &err);
    (*env)->ReleaseStringUTFChars(env, dir, d); (*env)->ReleaseStringUTFChars(env, main, m); (*env)->ReleaseStringUTFChars(env, harness, h);
    if (!p) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"), err ? err : "engine failed to start");
        free(err);
        return 0;
    }
    return (jlong)(intptr_t)p;
}

JNIEXPORT jbyteArray JNICALL Java_dev_volt_engine_VoltEngine_nCall(JNIEnv *env, jclass c, jlong h, jstring fn, jbyteArray arg) {
    const char *f = (*env)->GetStringUTFChars(env, fn, NULL);
    char *a = cstr(env, arg);
    char *r = VOLT_LANG.call((void *)(intptr_t)h, f, a);
    (*env)->ReleaseStringUTFChars(env, fn, f); free(a);
    jbyteArray out = bytes(env, r ? r : "{\"error\":\"no result\"}");
    free(r);
    return out;
}

JNIEXPORT void JNICALL Java_dev_volt_engine_VoltEngine_nClose(JNIEnv *env, jclass c, jlong h) { if (h) VOLT_LANG.close((void *)(intptr_t)h); }

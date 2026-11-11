#include <jni.h>
#include <stdint.h>
#include <string.h>

JNIEXPORT jlong JNICALL
Java_com_example_nativedemo_NativeHash_fnv1a(JNIEnv *env, jclass cls, jstring text, jint rounds) {
    const char *s = (*env)->GetStringUTFChars(env, text, NULL);
    size_t n = strlen(s);
    uint64_t h = 1469598103934665603ULL;
    for (int r = 0; r < rounds; r++)
        for (size_t i = 0; i < n; i++) { h ^= (unsigned char) s[i]; h *= 1099511628211ULL; }
    (*env)->ReleaseStringUTFChars(env, text, s);
    return (jlong) h;
}

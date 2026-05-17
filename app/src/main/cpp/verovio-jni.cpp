// JNI shim around Verovio's C wrapper.
//
// We don't link against Verovio's C++ public API directly; instead we go
// through the C wrapper in tools/c_wrapper.cpp which already returns
// stable `const char *` strings and `void *` opaque handles. That keeps
// this file tiny and lets us swap in a newer Verovio source drop without
// touching JNI code.

#include <jni.h>
#include <android/log.h>
#include <string>

#include "c_wrapper.h"

#define LOG_TAG "ScoreReader/VerovioJni"
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__))
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))

namespace {

inline void *handleFromJlong(jlong h) {
    return reinterpret_cast<void *>(static_cast<uintptr_t>(h));
}

inline jstring newJstring(JNIEnv *env, const char *s) {
    return env->NewStringUTF(s ? s : "");
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_scorereader_VerovioNative_nativeCreate(
        JNIEnv *env, jclass, jstring resourcePath) {
    const char *path = env->GetStringUTFChars(resourcePath, nullptr);
    void *tk = vrvToolkit_constructorResourcePath(path);
    env->ReleaseStringUTFChars(resourcePath, path);
    LOGI("nativeCreate -> %p (resourcePath=set)", tk);
    return reinterpret_cast<jlong>(tk);
}

JNIEXPORT void JNICALL
Java_com_example_scorereader_VerovioNative_nativeDestroy(
        JNIEnv *, jclass, jlong handle) {
    if (!handle) return;
    vrvToolkit_destructor(handleFromJlong(handle));
}

JNIEXPORT jboolean JNICALL
Java_com_example_scorereader_VerovioNative_nativeSetResourcePath(
        JNIEnv *env, jclass, jlong handle, jstring path) {
    if (!handle) return JNI_FALSE;
    const char *p = env->GetStringUTFChars(path, nullptr);
    bool ok = vrvToolkit_setResourcePath(handleFromJlong(handle), p);
    env->ReleaseStringUTFChars(path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_scorereader_VerovioNative_nativeSetOptions(
        JNIEnv *env, jclass, jlong handle, jstring jsonOptions) {
    if (!handle) return JNI_FALSE;
    const char *o = env->GetStringUTFChars(jsonOptions, nullptr);
    bool ok = vrvToolkit_setOptions(handleFromJlong(handle), o);
    env->ReleaseStringUTFChars(jsonOptions, o);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_scorereader_VerovioNative_nativeLoadData(
        JNIEnv *env, jclass, jlong handle, jstring data) {
    if (!handle) return JNI_FALSE;
    const char *d = env->GetStringUTFChars(data, nullptr);
    bool ok = vrvToolkit_loadData(handleFromJlong(handle), d);
    env->ReleaseStringUTFChars(data, d);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_scorereader_VerovioNative_nativeLoadZipBuffer(
        JNIEnv *env, jclass, jlong handle, jbyteArray data) {
    if (!handle) return JNI_FALSE;
    jbyte *raw = env->GetByteArrayElements(data, nullptr);
    jsize len = env->GetArrayLength(data);
    bool ok = vrvToolkit_loadZipDataBuffer(
            handleFromJlong(handle),
            reinterpret_cast<const unsigned char *>(raw),
            static_cast<int>(len));
    env->ReleaseByteArrayElements(data, raw, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_example_scorereader_VerovioNative_nativeGetPageCount(
        JNIEnv *, jclass, jlong handle) {
    if (!handle) return 0;
    return vrvToolkit_getPageCount(handleFromJlong(handle));
}

JNIEXPORT jstring JNICALL
Java_com_example_scorereader_VerovioNative_nativeRenderToSvg(
        JNIEnv *env, jclass, jlong handle, jint pageNo) {
    if (!handle) return newJstring(env, "");
    const char *svg = vrvToolkit_renderToSVG(handleFromJlong(handle), pageNo, false);
    return newJstring(env, svg);
}

JNIEXPORT jstring JNICALL
Java_com_example_scorereader_VerovioNative_nativeGetVersion(
        JNIEnv *env, jclass, jlong handle) {
    if (!handle) return newJstring(env, "");
    return newJstring(env, vrvToolkit_getVersion(handleFromJlong(handle)));
}

JNIEXPORT jstring JNICALL
Java_com_example_scorereader_VerovioNative_nativeGetLog(
        JNIEnv *env, jclass, jlong handle) {
    if (!handle) return newJstring(env, "");
    return newJstring(env, vrvToolkit_getLog(handleFromJlong(handle)));
}

JNIEXPORT void JNICALL
Java_com_example_scorereader_VerovioNative_nativeEnableLog(
        JNIEnv *, jclass, jboolean enabled) {
    enableLog(enabled == JNI_TRUE);
}

} // extern "C"

#include <jni.h>

static void event(JNIEnv *env, const char *name) {
    jclass test = (*env)->FindClass(env, "com/wehavephones/alsaserver/ALSAClientSelfTest");
    jmethodID method = (*env)->GetStaticMethodID(env, test, "onEvent", "(Ljava/lang/String;)V");
    jstring value = (*env)->NewStringUTF(env, name);
    (*env)->CallStaticVoidMethod(env, test, method, value);
}

JNIEXPORT jlong JNICALL Java_com_wehavephones_alsaserver_ALSAClient_create(
        JNIEnv *env, jobject obj, jint format, jbyte channels, jint rate, jint size) {
    (void)obj; (void)format; (void)channels; (void)rate; (void)size;
    event(env, "create");
    return 1;
}

JNIEXPORT jint JNICALL Java_com_wehavephones_alsaserver_ALSAClient_write(
        JNIEnv *env, jobject obj, jlong stream, jobject buffer, jint frames) {
    (void)obj; (void)stream;
    if (!(*env)->GetDirectBufferAddress(env, buffer)) return -1;
    jclass test = (*env)->FindClass(env, "com/wehavephones/alsaserver/ALSAClientSelfTest");
    jmethodID method = (*env)->GetStaticMethodID(env, test, "onWrite", "(I)I");
    return (*env)->CallStaticIntMethod(env, test, method, frames);
}

#define LIFECYCLE(name) \
    JNIEXPORT void JNICALL Java_com_wehavephones_alsaserver_ALSAClient_##name( \
            JNIEnv *env, jobject obj, jlong stream) { \
        (void)obj; if (stream) event(env, #name); \
    }
LIFECYCLE(start)
LIFECYCLE(stop)
LIFECYCLE(pause)
LIFECYCLE(flush)
LIFECYCLE(close)

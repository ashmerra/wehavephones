#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/socket.h>
#include <unistd.h>

JNIEXPORT jint JNICALL Java_com_wehavephones_xconnector_XSocketOutput_duplicateSocket(
        JNIEnv *env, jclass cls, jint fd) {
    (void)env; (void)cls;
    return fcntl(fd, F_DUPFD_CLOEXEC, 0);
}

JNIEXPORT jint JNICALL Java_com_wehavephones_xconnector_XSocketOutput_writeFully(
        JNIEnv *env, jclass cls, jint fd, jobject data, jint length) {
    (void)cls;
    const char *bytes = (*env)->GetDirectBufferAddress(env, data);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, data);
    if (!bytes || length < 0 || length > capacity) return -EINVAL;
    int sent = 0;
    while (sent < length) {
        ssize_t count = send(fd, bytes + sent, length - sent, MSG_NOSIGNAL);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) return -errno;
        if (count == 0) return -EPIPE;
        sent += (int)count;
    }
    return sent;
}

JNIEXPORT void JNICALL Java_com_wehavephones_xconnector_XSocketOutput_shutdownSocket(
        JNIEnv *env, jclass cls, jint fd) {
    (void)env; (void)cls;
    shutdown(fd, SHUT_RDWR);
}

JNIEXPORT void JNICALL Java_com_wehavephones_xconnector_XSocketOutput_closeSocket(
        JNIEnv *env, jclass cls, jint fd) {
    (void)env; (void)cls;
    close(fd);
}

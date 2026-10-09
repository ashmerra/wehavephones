#define _GNU_SOURCE
#include <errno.h>
#include <jni.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <unistd.h>

// Compile the production writer with send=test_send to force partial sends.
ssize_t test_send(int fd, const void *bytes, size_t length, int flags) {
    size_t chunk = length > 65536 ? 65536 : length;
    return syscall(SYS_sendto, fd, bytes, chunk, flags, NULL, 0);
}

JNIEXPORT jintArray JNICALL Java_com_wehavephones_xconnector_XSocketOutputSelfTest_socketPair(
        JNIEnv *env, jclass cls) {
    (void)cls;
    int fds[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, fds)) return NULL;
    int size = 4096;
    struct timeval timeout = {.tv_sec = 3};
    setsockopt(fds[0], SOL_SOCKET, SO_SNDBUF, &size, sizeof(size));
    setsockopt(fds[1], SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    jintArray result = (*env)->NewIntArray(env, 2);
    (*env)->SetIntArrayRegion(env, result, 0, 2, fds);
    return result;
}

JNIEXPORT jint JNICALL Java_com_wehavephones_xconnector_XSocketOutputSelfTest_available(
        JNIEnv *env, jclass cls, jint fd) {
    (void)env; (void)cls;
    int size = 0;
    return ioctl(fd, FIONREAD, &size) ? -1 : size;
}

JNIEXPORT jbyteArray JNICALL Java_com_wehavephones_xconnector_XSocketOutputSelfTest_readExactly(
        JNIEnv *env, jclass cls, jint fd, jint length) {
    (void)cls;
    char *data = malloc(length);
    if (!data) return NULL;
    int count = 0;
    while (count < length) {
        ssize_t n = read(fd, data + count, length - count);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { free(data); return NULL; }
        count += (int)n;
    }
    jbyteArray result = (*env)->NewByteArray(env, length);
    (*env)->SetByteArrayRegion(env, result, 0, length, (jbyte *)data);
    free(data);
    return result;
}

JNIEXPORT jboolean JNICALL Java_com_wehavephones_xconnector_XSocketOutputSelfTest_drainToEof(
        JNIEnv *env, jclass cls, jint fd) {
    (void)env; (void)cls;
    char bytes[4096];
    ssize_t n;
    do { n = read(fd, bytes, sizeof(bytes)); } while (n > 0 || (n < 0 && errno == EINTR));
    return n == 0;
}

JNIEXPORT void JNICALL Java_com_wehavephones_xconnector_XSocketOutputSelfTest_closeFd(
        JNIEnv *env, jclass cls, jint fd) {
    (void)env; (void)cls;
    if (fd >= 0) close(fd);
}

JNIEXPORT void JNICALL Java_com_wehavephones_xconnector_XSocketOutputSelfTest_sendProbe(
        JNIEnv *env, jclass cls, jint fd) {
    (void)env; (void)cls;
    const char bytes[] = {9, 8, 7, 6};
    syscall(SYS_sendto, fd, bytes, sizeof(bytes), MSG_NOSIGNAL, NULL, 0);
}

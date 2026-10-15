#include <assert.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include "../../app/src/main/cpp/wehavephones/xconnector_epoll.c"

typedef struct {
    int epoll_fd;
    int fds[2];
    int observed[2];
    int count;
    int first;
} Connector;

static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t changed = PTHREAD_COND_INITIALIZER;
static int first_waiting = 0;
static int second_done = 0;

static jclass object_class(JNIEnv *env, jobject object) {
    return (jclass)object;
}

static jmethodID method_id(JNIEnv *env, jclass cls, const char *name, const char *signature) {
    return (jmethodID)(uintptr_t)1;
}

static void callback(JNIEnv *env, jobject object, jmethodID method, ...) {
    Connector *connector = (Connector *)object;
    va_list args;
    va_start(args, method);
    int fd = va_arg(args, int);
    va_end(args);
    assert(connector->count < 2);
    connector->observed[connector->count++] = fd;

    // Let the second connector finish its entire batch inside the first
    // connector's callback. Its next event must still belong to its own batch.
    if (connector->first && connector->count == 1) {
        pthread_mutex_lock(&mutex);
        first_waiting = 1;
        pthread_cond_broadcast(&changed);
        while (!second_done) pthread_cond_wait(&changed, &mutex);
        pthread_mutex_unlock(&mutex);
    }
}

static const struct JNINativeInterface_ interface = {
    .GetObjectClass = object_class,
    .GetMethodID = method_id,
    .CallVoidMethod = callback
};

static void dispatch(Connector *connector) {
    JNIEnv env = &interface;
    assert(Java_com_wehavephones_xconnector_XConnectorEpoll_doEpollIndefinitely(
        &env, (jobject)connector, connector->epoll_fd, -1, JNI_FALSE));
}

static void *second_thread(void *data) {
    pthread_mutex_lock(&mutex);
    while (!first_waiting) pthread_cond_wait(&changed, &mutex);
    pthread_mutex_unlock(&mutex);
    dispatch(data);
    pthread_mutex_lock(&mutex);
    second_done = 1;
    pthread_cond_broadcast(&changed);
    pthread_mutex_unlock(&mutex);
    return NULL;
}

static Connector create_connector(int first) {
    Connector result = {.epoll_fd = epoll_create1(0), .first = first};
    assert(result.epoll_fd >= 0);
    for (int i = 0; i < 2; i++) {
        result.fds[i] = eventfd(1, EFD_NONBLOCK);
        assert(result.fds[i] >= 0);
        struct epoll_event event = {.events = EPOLLIN, .data.fd = result.fds[i]};
        assert(!epoll_ctl(result.epoll_fd, EPOLL_CTL_ADD, result.fds[i], &event));
    }
    return result;
}

int main(void) {
    alarm(5);
    Connector first = create_connector(1);
    Connector second = create_connector(0);
    pthread_t thread;
    assert(!pthread_create(&thread, NULL, second_thread, &second));
    dispatch(&first);
    assert(!pthread_join(thread, NULL));
    int valid = first.count == 2 && second.count == 2;
    for (int i = 0; i < 2; i++) {
        if (first.observed[i] != first.fds[0] && first.observed[i] != first.fds[1]) valid = 0;
        if (second.observed[i] != second.fds[0] && second.observed[i] != second.fds[1]) valid = 0;
        close(first.fds[i]);
        close(second.fds[i]);
    }
    close(first.epoll_fd);
    close(second.epoll_fd);
    if (!valid) {
        fputs("FAIL: concurrent connectors dispatched another connector's file descriptor\n", stderr);
        return 1;
    }
    puts("XConnectorIsolationSelfTest PASS: concurrent event batches remain isolated");
    return 0;
}

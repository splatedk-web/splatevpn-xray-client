/*
 ============================================================================
 Name        : hev-jni.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2019 - 2023 hev
 Description : Jave Native Interface
 ============================================================================
 */

#include "hev-jni.h"

#ifdef ANDROID

#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>

#include <stdio.h>
#include <stdlib.h>
#include <signal.h>
#include <string.h>

#include "hev-main.h"

#include "hev-jni.h"

/* clang-format off */
#ifndef PKGNAME
#define PKGNAME hev/htproxy
#endif
#ifndef CLSNAME
#define CLSNAME TProxyService
#endif
/* clang-format on */

#define STR(s) STR_ARG (s)
#define STR_ARG(c) #c
#define N_ELEMENTS(arr) (sizeof (arr) / sizeof ((arr)[0]))

typedef struct _ThreadData ThreadData;

struct _ThreadData
{
    char *path;
    int fd;
};

static atomic_int is_running;
static int thread_joinable;
static JavaVM *java_vm;
static pthread_t work_thread;
static pthread_mutex_t mutex;
static pthread_key_t current_jni_env;
/* Mutated only with mutex held, after joining the sole worker. */
static jobject flow_resolver;
static jmethodID flow_resolve_method;
typedef struct {
    int protocol;
    char local_address[46];
    char remote_address[46];
    int local_port;
    int remote_port;
    char user[256];
    int result;
} FlowIdentityRequest;

/* ART must execute on a genuine pthread stack, never a HEV coroutine stack. */
static pthread_mutex_t identity_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t identity_cond = PTHREAD_COND_INITIALIZER;
static pthread_t identity_thread;
static int identity_thread_joinable;
static int identity_ready, identity_available, identity_quit;
static FlowIdentityRequest *identity_request;

static int resolve_flow_on_java_thread (int protocol, const char *local_address,
                                         int local_port, const char *remote_address,
                                         int remote_port, char *user, size_t user_size);

static void *
identity_thread_handler (void *unused)
{
    JNIEnv *env = NULL;
    int available = (*java_vm)->AttachCurrentThread (java_vm, &env, NULL) == JNI_OK;
    if (available && pthread_setspecific (current_jni_env, env) != 0) {
        (*java_vm)->DetachCurrentThread (java_vm);
        available = 0;
    }
    pthread_mutex_lock (&identity_mutex);
    identity_available = available;
    identity_ready = 1;
    pthread_cond_broadcast (&identity_cond);
    while (available && !identity_quit) {
        FlowIdentityRequest *request;
        while (!identity_request && !identity_quit)
            pthread_cond_wait (&identity_cond, &identity_mutex);
        if (identity_quit)
            break;
        request = identity_request;
        pthread_mutex_unlock (&identity_mutex);
        request->result = resolve_flow_on_java_thread (
            request->protocol, request->local_address, request->local_port,
            request->remote_address, request->remote_port, request->user,
            sizeof (request->user));
        pthread_mutex_lock (&identity_mutex);
        identity_request = NULL;
        pthread_cond_broadcast (&identity_cond);
    }
    pthread_mutex_unlock (&identity_mutex);
    return NULL;
}

static void
stop_identity_thread (void)
{
    if (!identity_thread_joinable)
        return;
    pthread_mutex_lock (&identity_mutex);
    identity_quit = 1;
    pthread_cond_broadcast (&identity_cond);
    pthread_mutex_unlock (&identity_mutex);
    pthread_join (identity_thread, NULL);
    identity_thread_joinable = 0;
}

static int
start_identity_thread (void)
{
    int available;
    pthread_mutex_lock (&identity_mutex);
    identity_ready = identity_available = identity_quit = 0;
    identity_request = NULL;
    if (pthread_create (&identity_thread, NULL, identity_thread_handler, NULL) != 0) {
        pthread_mutex_unlock (&identity_mutex);
        return -1;
    }
    identity_thread_joinable = 1;
    while (!identity_ready)
        pthread_cond_wait (&identity_cond, &identity_mutex);
    available = identity_available;
    pthread_mutex_unlock (&identity_mutex);
    if (!available)
        stop_identity_thread ();
    return available ? 0 : -1;
}

static jboolean native_start_service (JNIEnv *env, jobject thiz,
                                      jstring conig_path, jint fd, jobject resolver);
static jboolean native_stop_service (JNIEnv *env, jobject thiz);
static void native_start_legacy (JNIEnv *env, jobject thiz, jstring config_path, jint fd);
static void native_stop_legacy (JNIEnv *env, jobject thiz);
static jboolean native_is_running (JNIEnv *env, jobject thiz);
static jlongArray native_get_stats (JNIEnv *env, jobject thiz);
static jint native_identity_api_version (JNIEnv *env, jobject thiz);

static JNINativeMethod native_methods[] = {
    { "TProxyStartService", "(Ljava/lang/String;I)V", (void *)native_start_legacy },
    { "TProxyStopService", "()V", (void *)native_stop_legacy },
    { "TProxyStartServiceWithIdentity", "(Ljava/lang/String;ILjava/lang/Object;)Z",
      (void *)native_start_service },
    { "TProxyStopServiceWithIdentity", "()Z", (void *)native_stop_service },
    { "TProxyIsRunning", "()Z", (void *)native_is_running },
    { "TProxyGetStats", "()[J", (void *)native_get_stats },
    { "TProxyIdentityApiVersion", "()I", (void *)native_identity_api_version },
};

static void
detach_current_thread (void *env)
{
    (*java_vm)->DetachCurrentThread (java_vm);
}

jint
JNI_OnLoad (JavaVM *vm, void *reserved)
{
    JNIEnv *env = NULL;
    jclass klass;
    jint res;

    java_vm = vm;
    res = (*vm)->GetEnv (vm, (void **)&env, JNI_VERSION_1_4);
    if (res != JNI_OK)
        return JNI_ERR;

    klass = (*env)->FindClass (env, STR (PKGNAME) "/" STR (CLSNAME));
    if (!klass)
        return JNI_ERR;
    res = (*env)->RegisterNatives (env, klass, native_methods,
                                   N_ELEMENTS (native_methods));
    (*env)->DeleteLocalRef (env, klass);
    if (res < 0)
        return JNI_ERR;

    pthread_key_create (&current_jni_env, detach_current_thread);
    pthread_mutex_init (&mutex, NULL);

    return JNI_VERSION_1_4;
}

static void *
thread_handler (void *data)
{
    ThreadData *tdata = data;

    hev_socks5_tunnel_main (tdata->path, tdata->fd);

    atomic_store_explicit (&is_running, 0, memory_order_release);

    free (tdata->path);
    free (tdata);

    return NULL;
}

static jboolean
native_start_service (JNIEnv *env, jobject thiz, jstring config_path, jint fd,
                      jobject resolver)
{
    const jbyte *bytes;
    ThreadData *tdata;
    int res;
    jboolean result = JNI_FALSE;

    pthread_mutex_lock (&mutex);

    if (atomic_load_explicit (&is_running, memory_order_acquire))
        goto exit;

    if (thread_joinable) {
        pthread_join (work_thread, NULL);
        thread_joinable = 0;
    }
    stop_identity_thread ();

    if (flow_resolver) {
        (*env)->DeleteGlobalRef (env, flow_resolver);
        flow_resolver = NULL;
        flow_resolve_method = NULL;
    }
    if (resolver) {
        jclass klass = (*env)->GetObjectClass (env, resolver);
        if (!klass)
            goto exit;
        flow_resolve_method = (*env)->GetMethodID (
            env, klass, "resolve", "(ILjava/lang/String;ILjava/lang/String;I)Ljava/lang/String;");
        (*env)->DeleteLocalRef (env, klass);
        if (!flow_resolve_method) {
            if ((*env)->ExceptionCheck (env))
                (*env)->ExceptionClear (env);
            goto exit;
        }
        flow_resolver = (*env)->NewGlobalRef (env, resolver);
        if (!flow_resolver)
            goto exit;
        if (start_identity_thread () < 0)
            goto exit;
    }

    tdata = malloc (sizeof (ThreadData));
    if (!tdata)
        goto exit;
    tdata->fd = fd;

    bytes = (const jbyte *)(*env)->GetStringUTFChars (env, config_path, NULL);
    if (!bytes) {
        free (tdata);
        goto exit;
    }
    tdata->path = strdup ((const char *)bytes);
    (*env)->ReleaseStringUTFChars (env, config_path, (const char *)bytes);
    if (!tdata->path) {
        free (tdata);
        goto exit;
    }

    atomic_store_explicit (&is_running, 1, memory_order_release);
    res = pthread_create (&work_thread, NULL, thread_handler, tdata);
    if (res != 0) {
        atomic_store_explicit (&is_running, 0, memory_order_release);
        free (tdata->path);
        free (tdata);
        goto exit;
    }

    thread_joinable = 1;
    result = JNI_TRUE;
exit:
    if (result != JNI_TRUE && !thread_joinable && flow_resolver) {
        stop_identity_thread ();
        (*env)->DeleteGlobalRef (env, flow_resolver);
        flow_resolver = NULL;
        flow_resolve_method = NULL;
    }
    pthread_mutex_unlock (&mutex);
    return result;
}

static jboolean
native_stop_service (JNIEnv *env, jobject thiz)
{
    int res = 0;

    pthread_mutex_lock (&mutex);

    if (!thread_joinable)
        goto exit;

    if (atomic_load_explicit (&is_running, memory_order_acquire))
        hev_socks5_tunnel_quit ();
    res = pthread_join (work_thread, NULL);

    thread_joinable = 0;
    atomic_store_explicit (&is_running, 0, memory_order_release);
exit:
    stop_identity_thread ();
    if (flow_resolver) {
        (*env)->DeleteGlobalRef (env, flow_resolver);
        flow_resolver = NULL;
        flow_resolve_method = NULL;
    }
    pthread_mutex_unlock (&mutex);
    return res == 0 ? JNI_TRUE : JNI_FALSE;
}

static jboolean
native_is_running (JNIEnv *env, jobject thiz)
{
    return atomic_load_explicit (&is_running, memory_order_acquire) ? JNI_TRUE :
                                                                      JNI_FALSE;
}

static void
native_start_legacy (JNIEnv *env, jobject thiz, jstring config_path, jint fd)
{
    native_start_service (env, thiz, config_path, fd, NULL);
}

static void
native_stop_legacy (JNIEnv *env, jobject thiz)
{
    native_stop_service (env, thiz);
}

static jlongArray
native_get_stats (JNIEnv *env, jobject thiz)
{
    size_t tx_packets, rx_packets, tx_bytes, rx_bytes;
    jlongArray res;
    jlong array[4];

    hev_socks5_tunnel_stats (&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);
    array[0] = tx_packets;
    array[1] = tx_bytes;
    array[2] = rx_packets;
    array[3] = rx_bytes;

    res = (*env)->NewLongArray (env, 4);
    (*env)->SetLongArrayRegion (env, res, 0, 4, array);

    return res;
}

static jint
native_identity_api_version (JNIEnv *env, jobject thiz)
{
    return 1;
}

static int
resolve_flow_on_java_thread (int protocol, const char *local_address, int local_port,
                          const char *remote_address, int remote_port,
                          char *user, size_t user_size)
{
    JNIEnv *env;
    jstring local = NULL, remote = NULL, result = NULL;
    const char *bytes;
    size_t length, i;
    int ok = -1;

    if (!flow_resolver)
        return 0;
    env = pthread_getspecific (current_jni_env);
    if (!env) {
        if ((*java_vm)->AttachCurrentThread (java_vm, &env, NULL) != JNI_OK)
            return -1;
        if (pthread_setspecific (current_jni_env, env) != 0) {
            (*java_vm)->DetachCurrentThread (java_vm);
            return -1;
        }
    }

    local = (*env)->NewStringUTF (env, local_address);
    remote = (*env)->NewStringUTF (env, remote_address);
    if (!local || !remote)
        goto exit;
    result = (jstring)(*env)->CallObjectMethod (env, flow_resolver, flow_resolve_method,
                                               protocol, local, local_port, remote,
                                               remote_port);
    if ((*env)->ExceptionCheck (env) || !result)
        goto exit;
    length = (*env)->GetStringUTFLength (env, result);
    if (length == 0 || length > 255 || length >= user_size)
        goto exit;
    bytes = (*env)->GetStringUTFChars (env, result, NULL);
    if (!bytes)
        goto exit;
    for (i = 0; i < length; i++) {
        if ((unsigned char)bytes[i] < 33 || (unsigned char)bytes[i] > 126)
            break;
    }
    if (i == length) {
        memcpy (user, bytes, length);
        user[length] = '\0';
        ok = 1;
    }
    (*env)->ReleaseStringUTFChars (env, result, bytes);
exit:
    /* Resolution failure must drop only this flow and must not poison JNI. */
    if ((*env)->ExceptionCheck (env))
        (*env)->ExceptionClear (env);
    if (result)
        (*env)->DeleteLocalRef (env, result);
    if (local)
        (*env)->DeleteLocalRef (env, local);
    if (remote)
        (*env)->DeleteLocalRef (env, remote);
    return ok;
}

int
hev_splate_resolve_flow (int protocol, const char *local_address, int local_port,
                          const char *remote_address, int remote_port,
                          char *user, size_t user_size)
{
    FlowIdentityRequest request = {0};
    size_t length;
    if (!flow_resolver)
        return 0;
    if (!local_address || !remote_address || strlen (local_address) >= 46 ||
        strlen (remote_address) >= 46)
        return -1;
    request.protocol = protocol;
    request.local_port = local_port;
    request.remote_port = remote_port;
    strcpy (request.local_address, local_address);
    strcpy (request.remote_address, remote_address);
    pthread_mutex_lock (&identity_mutex);
    while (identity_request && !identity_quit)
        pthread_cond_wait (&identity_cond, &identity_mutex);
    if (!identity_available || identity_quit) {
        pthread_mutex_unlock (&identity_mutex);
        return -1;
    }
    identity_request = &request;
    pthread_cond_broadcast (&identity_cond);
    while (identity_request == &request)
        pthread_cond_wait (&identity_cond, &identity_mutex);
    pthread_mutex_unlock (&identity_mutex);
    if (request.result != 1)
        return -1;
    length = strlen (request.user);
    if (length >= user_size)
        return -1;
    memcpy (user, request.user, length + 1);
    return 1;
}

#else
int
hev_splate_resolve_flow (int protocol, const char *local_address, int local_port,
                          const char *remote_address, int remote_port,
                          char *user, size_t user_size)
{
    return 0;
}
#endif /* ANDROID */

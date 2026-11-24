/*
 ============================================================================
 Name        : hev-jni.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2019 - 2023 hev
 Description : Java Native Interface
 ============================================================================
 */

#ifndef __HEV_JNI_H__
#define __HEV_JNI_H__

#include <stddef.h>

/* 0: no resolver; 1: authenticated identity; -1: reject this flow. */
int hev_splate_resolve_flow (int protocol, const char *local_address, int local_port,
                              const char *remote_address, int remote_port,
                              char *user, size_t user_size);

#endif /* __HEV_JNI_H__ */

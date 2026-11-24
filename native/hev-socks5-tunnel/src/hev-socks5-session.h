/*
 ============================================================================
 Name        : hev-socks5-session.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2023 hev
 Description : Socks5 Session
 ============================================================================
 */

#ifndef __HEV_SOCKS5_SESSION_H__
#define __HEV_SOCKS5_SESSION_H__

#include <hev-task.h>
#include <lwip/ip_addr.h>

#include "hev-list.h"

#define HEV_SOCKS5_SESSION(p) ((HevSocks5Session *)p)
#define HEV_SOCKS5_SESSION_IFACE(p) ((HevSocks5SessionIface *)p)
#define HEV_SOCKS5_SESSION_TYPE (hev_socks5_session_iface ())

typedef void HevSocks5Session;
typedef struct _HevSocks5SessionData HevSocks5SessionData;
typedef struct _HevSocks5SessionIface HevSocks5SessionIface;

struct _HevSocks5SessionData
{
    HevListNode node;
    HevTask *task;
    HevSocks5Session *self;
    /* Original Android socket tuple, before lwIP/SOCKS recreates the flow. */
    int protocol;
    char local_address[46];
    char remote_address[46];
    int local_port;
    int remote_port;
};

struct _HevSocks5SessionIface
{
    void (*splicer) (HevSocks5Session *self);
    HevTask *(*get_task) (HevSocks5Session *self);
    void (*set_task) (HevSocks5Session *self, HevTask *task);
    HevListNode *(*get_node) (HevSocks5Session *self);
};

void *hev_socks5_session_iface (void);

void hev_socks5_session_run (HevSocks5Session *self);
void hev_socks5_session_terminate (HevSocks5Session *self);

void hev_socks5_session_set_task (HevSocks5Session *self, HevTask *task);
void hev_socks5_session_set_flow (HevSocks5SessionData *data, int protocol,
                                 const ip_addr_t *local_address, int local_port,
                                 const ip_addr_t *remote_address, int remote_port);
HevListNode *hev_socks5_session_get_node (HevSocks5Session *self);

int hev_socks5_session_bind (HevSocks5 *self, int fd,
                             const struct sockaddr *dest);

#endif /* __HEV_SOCKS5_SESSION_H__ */

/*
 ============================================================================
 Name        : hev-socks5-session.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2023 hev
 Description : Socks5 Session
 ============================================================================
 */

#include <string.h>

#include "hev-utils.h"
#include "hev-logger.h"
#include "hev-config.h"
#include "hev-socks5-client.h"
#include "hev-jni.h"
#include "hev-compiler.h"

#include "hev-socks5-session.h"

void
hev_socks5_session_run (HevSocks5Session *self)
{
    HevSocks5SessionIface *iface;
    HevConfigServer *srv;
    HevSocks5SessionData *data;
    char flow_user[256];
    const char *user;
    int res;

    LOG_D ("%p socks5 session run", self);

    srv = hev_config_get_socks5_server ();
    iface = HEV_OBJECT_GET_IFACE (self, HEV_SOCKS5_SESSION_TYPE);
    data = container_of (iface->get_node (self), HevSocks5SessionData, node);
    res = hev_splate_resolve_flow (data->protocol, data->local_address,
                                  data->local_port, data->remote_address,
                                  data->remote_port, flow_user, sizeof (flow_user));
    if (res < 0)
        return; /* An installed resolver must never silently lose app identity. */
    user = res > 0 ? flow_user : srv->user;
    if (res > 0 && (!srv->pass || !*srv->pass))
        return;

    res = hev_socks5_client_connect (HEV_SOCKS5_CLIENT (self), srv->addr,
                                     srv->port);
    if (res < 0) {
        LOG_I ("%p socks5 session connect", self);
        return;
    }

    if (user && srv->pass) {
        hev_socks5_client_set_auth (HEV_SOCKS5_CLIENT (self), user,
                                    srv->pass);
    }

    res = hev_socks5_client_handshake (HEV_SOCKS5_CLIENT (self), srv->pipeline);
    if (res < 0) {
        LOG_I ("%p socks5 session handshake", self);
        return;
    }

    iface = HEV_OBJECT_GET_IFACE (self, HEV_SOCKS5_SESSION_TYPE);
    iface->splicer (self);
}

void
hev_socks5_session_set_flow (HevSocks5SessionData *data, int protocol,
                             const ip_addr_t *local_address, int local_port,
                             const ip_addr_t *remote_address, int remote_port)
{
    data->protocol = protocol;
    data->local_port = local_port;
    data->remote_port = remote_port;
    ipaddr_ntoa_r (local_address, data->local_address, sizeof (data->local_address));
    ipaddr_ntoa_r (remote_address, data->remote_address, sizeof (data->remote_address));
}

void
hev_socks5_session_terminate (HevSocks5Session *self)
{
    HevSocks5SessionIface *iface;

    LOG_D ("%p socks5 session terminate", self);

    iface = HEV_OBJECT_GET_IFACE (self, HEV_SOCKS5_SESSION_TYPE);
    hev_socks5_set_timeout (HEV_SOCKS5 (self), 0);
    hev_task_wakeup (iface->get_task (self));
}

void
hev_socks5_session_set_task (HevSocks5Session *self, HevTask *task)
{
    HevSocks5SessionIface *iface;

    iface = HEV_OBJECT_GET_IFACE (self, HEV_SOCKS5_SESSION_TYPE);
    iface->set_task (self, task);
}

HevListNode *
hev_socks5_session_get_node (HevSocks5Session *self)
{
    HevSocks5SessionIface *iface;

    iface = HEV_OBJECT_GET_IFACE (self, HEV_SOCKS5_SESSION_TYPE);
    return iface->get_node (self);
}

int
hev_socks5_session_bind (HevSocks5 *self, int fd, const struct sockaddr *dest)
{
    HevConfigServer *srv;
    unsigned int mark;

    LOG_D ("%p socks5 session bind", self);

    srv = hev_config_get_socks5_server ();
    mark = srv->mark;

    if (mark) {
        int res;

        res = set_sock_mark (fd, mark);
        if (res < 0)
            return -1;
    }

    set_sock_tcp_fastopen (fd, srv->fastopen);

    return 0;
}

void *
hev_socks5_session_iface (void)
{
    static char type;

    return &type;
}

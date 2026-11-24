// SPDX-License-Identifier: MIT
package engine

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"sync"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

type Config struct {
	Addresses   []string `json:"addresses"`
	DNS         []string `json:"dns"`
	MTU         int      `json:"mtu"`
	UAPI        string   `json:"uapi"`
	Username    string   `json:"username"`
	Password    string   `json:"password"`
	ProtectPath string   `json:"protectPath"`
}

type Engine struct {
	Device      *device.Device
	Net         *netstack.Net
	listener    net.Listener
	cancel      context.CancelFunc
	mu          sync.Mutex
	connections map[net.Conn]struct{}
	wg          sync.WaitGroup
	once        sync.Once
}

func Start(c Config, protect func(int) error) (*Engine, error) {
	if c.MTU < 576 || c.MTU > 9000 || len(c.Addresses) == 0 || len(c.Addresses) > 16 || len(c.DNS) > 8 {
		return nil, errors.New("invalid network configuration")
	}
	parse := func(values []string) ([]netip.Addr, error) {
		out := make([]netip.Addr, 0, len(values))
		for _, s := range values {
			a, e := netip.ParseAddr(s)
			if e != nil {
				return nil, e
			}
			out = append(out, a)
		}
		return out, nil
	}
	addresses, err := parse(c.Addresses)
	if err != nil {
		return nil, err
	}
	dns, err := parse(c.DNS)
	if err != nil {
		return nil, err
	}
	t, n, err := netstack.CreateNetTUN(addresses, dns, c.MTU)
	if err != nil {
		return nil, err
	}
	b := conn.NewDefaultBind()
	if protect != nil {
		b = &protectedBind{Bind: b, protect: protect}
	}
	d := device.NewDevice(t, b, device.NewLogger(device.LogLevelSilent, ""))
	if err = d.IpcSet(c.UAPI); err != nil {
		d.Close()
		return nil, errors.New("AWG configuration rejected")
	}
	if err = d.Up(); err != nil {
		d.Close()
		return nil, err
	}
	return &Engine{Device: d, Net: n, connections: make(map[net.Conn]struct{})}, nil
}

// Device receives no UDP until all opened sockets have been protected by VpnService.
type protectedBind struct {
	conn.Bind
	protect func(int) error
}

func (b *protectedBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	funcs, p, err := b.Bind.Open(port)
	if err != nil {
		return nil, 0, err
	}
	sockets, ok := b.Bind.(conn.PeekLookAtSocketFd)
	if !ok {
		b.Bind.Close()
		return nil, 0, errors.New("socket protection unavailable")
	}
	protected := 0
	for _, get := range []func() (int, error){sockets.PeekLookAtSocketFd4, sockets.PeekLookAtSocketFd6} {
		// Upstream accessors dereference absent address families. A missing family is not an unprotected socket.
		fd, e := safeSocket(get)
		if e != nil {
			continue
		}
		if e = b.protect(fd); e != nil {
			b.Bind.Close()
			return nil, 0, e
		}
		protected++
	}
	if protected == 0 {
		b.Bind.Close()
		return nil, 0, errors.New("no protected socket")
	}
	return funcs, p, nil
}
func safeSocket(get func() (int, error)) (fd int, err error) {
	defer func() {
		if recover() != nil {
			fd = -1
			err = errors.New("address family unavailable")
		}
	}()
	return get()
}

func (e *Engine) Serve(username, password string) (int, error) {
	if len(username) < 16 || len(password) < 16 || len(username) > 255 || len(password) > 255 {
		return 0, errors.New("SOCKS credentials required")
	}
	l, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	e.listener = l
	ctx, cancel := context.WithCancel(context.Background())
	e.cancel = cancel
	e.wg.Add(1)
	go func() {
		defer e.wg.Done()
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			e.mu.Lock()
			if ctx.Err() != nil {
				e.mu.Unlock()
				c.Close()
				return
			}
			if len(e.connections) >= 128 {
				e.mu.Unlock()
				c.Close()
				continue
			}
			e.connections[c] = struct{}{}
			e.wg.Add(1)
			e.mu.Unlock()
			go func() {
				defer e.wg.Done()
				defer c.Close()
				defer func() { e.mu.Lock(); delete(e.connections, c); e.mu.Unlock() }()
				e.handle(ctx, c, username, password)
			}()
		}
	}()
	return l.Addr().(*net.TCPAddr).Port, nil
}
func (e *Engine) Close() {
	e.once.Do(func() {
		if e.cancel != nil {
			e.cancel()
		}
		if e.listener != nil {
			e.listener.Close()
		}
		e.mu.Lock()
		for c := range e.connections {
			c.Close()
		}
		e.mu.Unlock()
		e.Device.Close()
		e.wg.Wait()
	})
}
func (e *Engine) Dial(ctx context.Context, network, address string) (net.Conn, error) {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	return e.Net.DialContext(ctx, network, address)
}

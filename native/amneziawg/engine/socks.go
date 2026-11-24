// SPDX-License-Identifier: MIT
package engine

import (
	"context"
	"crypto/subtle"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"sync"
	"time"
)

func readByte(r io.Reader) (byte, error) { b := []byte{0}; _, e := io.ReadFull(r, b); return b[0], e }
func address(r io.Reader) (string, error) {
	t, e := readByte(r)
	if e != nil {
		return "", e
	}
	var host string
	switch t {
	case 1:
		b := make([]byte, 4)
		if _, e = io.ReadFull(r, b); e != nil {
			return "", e
		}
		host = net.IP(b).String()
	case 4:
		b := make([]byte, 16)
		if _, e = io.ReadFull(r, b); e != nil {
			return "", e
		}
		host = net.IP(b).String()
	case 3:
		n, e := readByte(r)
		if e != nil || n == 0 {
			return "", errors.New("invalid hostname")
		}
		b := make([]byte, int(n))
		if _, e = io.ReadFull(r, b); e != nil {
			return "", e
		}
		host = string(b)
	default:
		return "", errors.New("invalid address")
	}
	b := make([]byte, 2)
	if _, e = io.ReadFull(r, b); e != nil {
		return "", e
	}
	return net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(b)))), nil
}
func writeReply(c net.Conn, code byte, port int) error {
	_, e := c.Write([]byte{5, code, 0, 1, 127, 0, 0, 1, byte(port >> 8), byte(port)})
	return e
}
func (e *Engine) handle(ctx context.Context, c net.Conn, user, pass string) {
	c.SetDeadline(time.Now().Add(8 * time.Second))
	v, err := readByte(c)
	if err != nil || v != 5 {
		return
	}
	n, err := readByte(c)
	if err != nil || n == 0 {
		return
	}
	methods := make([]byte, int(n))
	if _, err = io.ReadFull(c, methods); err != nil {
		return
	}
	auth := false
	for _, m := range methods {
		auth = auth || m == 2
	}
	if !auth {
		c.Write([]byte{5, 255})
		return
	}
	if _, err = c.Write([]byte{5, 2}); err != nil {
		return
	}
	v, err = readByte(c)
	if err != nil || v != 1 {
		return
	}
	n, err = readByte(c)
	if err != nil {
		return
	}
	u := make([]byte, int(n))
	if _, err = io.ReadFull(c, u); err != nil {
		return
	}
	n, err = readByte(c)
	if err != nil {
		return
	}
	p := make([]byte, int(n))
	if _, err = io.ReadFull(c, p); err != nil {
		return
	}
	if subtle.ConstantTimeCompare(u, []byte(user)) != 1 || subtle.ConstantTimeCompare(p, []byte(pass)) != 1 {
		c.Write([]byte{1, 1})
		return
	}
	if _, err = c.Write([]byte{1, 0}); err != nil {
		return
	}
	header := make([]byte, 3)
	if _, err = io.ReadFull(c, header); err != nil || header[0] != 5 || header[2] != 0 {
		return
	}
	target, err := address(c)
	if err != nil {
		return
	}
	// Authentication/request parsing has a short deadline. A real AWG handshake
	// and tunnel DNS have their own 15-second budget and must not inherit it.
	c.SetDeadline(time.Now().Add(20 * time.Second))
	switch header[1] {
	case 1:
		remote, err := e.Dial(ctx, "tcp", target)
		if err != nil {
			writeReply(c, 4, 0)
			return
		}
		defer remote.Close()
		if writeReply(c, 0, 0) != nil {
			return
		}
		c.SetDeadline(time.Time{})
		done := make(chan struct{})
		go func() {
			select {
			case <-ctx.Done():
				remote.Close()
			case <-done:
			}
		}()
		defer close(done)
		copied := make(chan struct{})
		go func() {
			io.Copy(remote, c)
			if t, ok := remote.(interface{ CloseWrite() error }); ok {
				t.CloseWrite()
			}
			close(copied)
		}()
		io.Copy(c, remote)
		c.Close()
		remote.Close()
		<-copied
	case 3:
		e.udp(ctx, c, target)
	default:
		writeReply(c, 7, 0)
	}
}

// One authenticated TCP association owns each relay. Packets from another process/port are rejected.
func (e *Engine) udp(parent context.Context, control net.Conn, requested string) {
	relay, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return
	}
	defer relay.Close()
	expected, err := net.ResolveUDPAddr("udp", requested)
	if err != nil {
		return
	}
	if !expected.IP.IsUnspecified() && !expected.IP.IsLoopback() {
		writeReply(control, 2, 0)
		return
	}
	if writeReply(control, 0, relay.LocalAddr().(*net.UDPAddr).Port) != nil {
		return
	}
	control.SetDeadline(time.Time{})
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	var mu sync.Mutex
	remotes := map[string]net.Conn{}
	var wg sync.WaitGroup
	go func() { io.Copy(io.Discard, control); cancel(); relay.Close() }()
	defer func() {
		cancel()
		control.Close()
		mu.Lock()
		for _, r := range remotes {
			r.Close()
		}
		mu.Unlock()
		wg.Wait()
	}()
	buf := make([]byte, 65535)
	var source *net.UDPAddr
	for {
		relay.SetReadDeadline(time.Now().Add(90 * time.Second))
		n, from, err := relay.ReadFromUDP(buf)
		if err != nil {
			return
		}
		if ctx.Err() != nil {
			return
		}
		if !from.IP.IsLoopback() || expected.Port != 0 && from.Port != expected.Port {
			continue
		}
		if source == nil {
			source = from
		}
		if source.String() != from.String() || n < 4 || buf[0] != 0 || buf[1] != 0 || buf[2] != 0 {
			continue
		}
		r := &packetReader{data: buf[3:n]}
		target, err := address(r)
		if err != nil {
			continue
		}
		offset := n - len(r.data)
		if offset >= n {
			continue
		}
		payload := append([]byte(nil), buf[offset:n]...)
		prefix := append([]byte(nil), buf[:offset]...)
		mu.Lock()
		remote := remotes[target]
		mu.Unlock()
		if remote == nil {
			mu.Lock()
			full := len(remotes) >= 64
			mu.Unlock()
			if full {
				continue
			}
			remote, err = e.Dial(ctx, "udp", target)
			if err != nil {
				continue
			}
			mu.Lock()
			remotes[target] = remote
			mu.Unlock()
			wg.Add(1)
			go func(dst string, conn net.Conn, header []byte, client *net.UDPAddr) {
				defer wg.Done()
				defer conn.Close()
				defer func() { mu.Lock(); delete(remotes, dst); mu.Unlock() }()
				reply := make([]byte, 65535)
				for {
					conn.SetReadDeadline(time.Now().Add(60 * time.Second))
					n, er := conn.Read(reply)
					if er != nil {
						return
					}
					packet := append(append([]byte(nil), header...), reply[:n]...)
					if len(packet) > 65507 {
						continue
					}
					if _, er = relay.WriteToUDP(packet, client); er != nil {
						return
					}
				}
			}(target, remote, prefix, source)
		}
		remote.SetWriteDeadline(time.Now().Add(5 * time.Second))
		if _, err = remote.Write(payload); err != nil {
			remote.Close()
		}
	}
}

type packetReader struct{ data []byte }

func (r *packetReader) Read(p []byte) (int, error) {
	if len(r.data) == 0 {
		return 0, io.EOF
	}
	n := copy(p, r.data)
	r.data = r.data[n:]
	return n, nil
}

//go:build unix

// SPDX-License-Identifier: MIT
package main

import (
	"errors"
	"golang.org/x/sys/unix"
	"net"
	"time"
)

func socketProtector(path string) (func(int) error, error) {
	if path == "" {
		return nil, nil
	}
	return func(fd int) error {
		c, e := net.DialUnix("unix", nil, &net.UnixAddr{Name: "\x00" + path, Net: "unix"})
		if e != nil {
			return e
		}
		defer c.Close()
		c.SetDeadline(time.Now().Add(3 * time.Second))
		_, _, e = c.WriteMsgUnix([]byte{1}, unix.UnixRights(fd), nil)
		if e != nil {
			return e
		}
		b := []byte{0}
		if _, e = c.Read(b); e != nil {
			return e
		}
		if b[0] != 1 {
			return errors.New("socket protection rejected")
		}
		return nil
	}, nil
}

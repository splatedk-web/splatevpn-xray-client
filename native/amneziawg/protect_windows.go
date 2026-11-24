//go:build windows

package main

import "errors"

func socketProtector(path string) (func(int) error, error) {
	if path != "" {
		return nil, errors.New("Android protection unavailable")
	}
	return nil, nil
}

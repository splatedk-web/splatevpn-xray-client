// SPDX-License-Identifier: MIT
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/signal"
	"splate.local/amneziawg/engine"
	"strings"
	"syscall"
)

func run() error {
	input := bufio.NewReaderSize(os.Stdin, 128*1024)
	line, err := input.ReadString('\n')
	if err != nil {
		return err
	}
	if len(line) > 128*1024 {
		return fmt.Errorf("configuration too large")
	}
	var c engine.Config
	decoder := json.NewDecoder(strings.NewReader(line))
	decoder.DisallowUnknownFields()
	if err = decoder.Decode(&c); err != nil {
		return fmt.Errorf("invalid configuration")
	}
	protect, err := socketProtector(c.ProtectPath)
	if err != nil {
		return err
	}
	e, err := engine.Start(c, protect)
	if err != nil {
		return err
	}
	defer e.Close()
	port, err := e.Serve(c.Username, c.Password)
	if err != nil {
		return err
	}
	fmt.Printf("{\"ready\":true,\"port\":%d}\n", port)
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(sig)
	commands := make(chan string)
	go func() {
		s := bufio.NewScanner(input)
		for s.Scan() {
			commands <- s.Text()
		}
		close(commands)
	}()
	for {
		select {
		case <-sig:
			return nil
		case command, ok := <-commands:
			if !ok {
				return nil
			}
			switch command {
			case "STOP":
				return nil
			case "REBIND":
				if err = e.Device.BindUpdate(); err != nil {
					return fmt.Errorf("socket rebind failed")
				}
			case "STATS":
				stats, er := e.Device.IpcGet()
				if er != nil {
					return er
				}
				out := map[string]string{}
				for _, s := range strings.Split(stats, "\n") {
					k, v, ok := strings.Cut(s, "=")
					if ok && (k == "last_handshake_time_sec" || k == "rx_bytes" || k == "tx_bytes") {
						out[k] = v
					}
				}
				json.NewEncoder(os.Stdout).Encode(out)
			}
		}
	}
}
func main() {
	if err := run(); err != nil {
		io.WriteString(os.Stderr, "AWG startup/runtime failed\n")
		os.Exit(1)
	}
}

// Test peer, built separately and never packaged in the app.
package main

import (
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"golang.org/x/net/dns/dnsmessage"
	"math/big"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/signal"
	"splate.local/amneziawg/engine"
	"strconv"
	"syscall"
	"time"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
func run() error {
	a, _ := ecdh.X25519().GenerateKey(rand.Reader)
	b, _ := ecdh.X25519().GenerateKey(rand.Reader)
	socket, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return err
	}
	port := socket.LocalAddr().(*net.UDPAddr).Port
	socket.Close()
	obfuscation := "jc=2\njmin=40\njmax=80\ns1=16\ns2=24\ns3=8\ns4=8\nh1=100001\nh2=100002\nh3=100003\nh4=100004\n"
	e, err := engine.Start(engine.Config{Addresses: []string{"10.77.0.1"}, MTU: 1280, UAPI: fmt.Sprintf("private_key=%s\nlisten_port=%d\n%spublic_key=%s\nallowed_ip=10.77.0.2/32\n", hex.EncodeToString(a.Bytes()), port, obfuscation, hex.EncodeToString(b.PublicKey().Bytes()))}, nil)
	if err != nil {
		return err
	}
	defer e.Close()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "awg-test.invalid"}, DNSNames: []string{"awg-test.invalid"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, IsCA: true, BasicConstraintsValid: true}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		return err
	}
	cert := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyDER, _ := x509.MarshalECPrivateKey(key)
	pair, err := tls.X509KeyPair(cert, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}))
	if err != nil {
		return err
	}
	l, err := e.Net.ListenTCPAddrPort(netip.MustParseAddrPort("10.77.0.1:443"))
	if err != nil {
		return err
	}
	server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/generate_204" {
			w.WriteHeader(204)
		} else {
			w.Header().Set("Content-Type", "application/octet-stream")
			w.Write(make([]byte, 512*1024))
		}
	})}
	defer server.Close()
	go server.Serve(tls.NewListener(l, &tls.Config{Certificates: []tls.Certificate{pair}, MinVersion: tls.VersionTLS12}))
	dns, err := e.Net.ListenUDPAddrPort(netip.MustParseAddrPort("10.77.0.1:53"))
	if err != nil {
		return err
	}
	defer dns.Close()
	go func() {
		buf := make([]byte, 4096)
		for {
			n, addr, err := dns.ReadFrom(buf)
			if err != nil {
				return
			}
			var q dnsmessage.Message
			if q.Unpack(buf[:n]) != nil || len(q.Questions) != 1 {
				continue
			}
			answer := dnsmessage.Message{Header: dnsmessage.Header{ID: q.ID, Response: true, RecursionAvailable: true}, Questions: q.Questions}
			if q.Questions[0].Type == dnsmessage.TypeA {
				answer.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: q.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 1}, Body: &dnsmessage.AResource{A: [4]byte{10, 77, 0, 1}}}}
			}
			data, _ := answer.Pack()
			dns.WriteTo(data, addr)
		}
	}()
	udp, err := e.Net.ListenUDPAddrPort(netip.MustParseAddrPort("10.77.0.1:8081"))
	if err != nil {
		return err
	}
	defer udp.Close()
	go func() {
		buf := make([]byte, 2048)
		for {
			n, addr, err := udp.ReadFrom(buf)
			if err != nil {
				return
			}
			udp.WriteTo(buf[:n], addr)
		}
	}()
	conf := fmt.Sprintf("[Interface]\nPrivateKey = %s\nAddress = 10.77.0.2/32\nDNS = 10.77.0.1\nJc = 2\nJmin = 40\nJmax = 80\nS1 = 16\nS2 = 24\nS3 = 8\nS4 = 8\nH1 = 100001\nH2 = 100002\nH3 = 100003\nH4 = 100004\n[Peer]\nPublicKey = %s\nEndpoint = 127.0.0.1:%d\nAllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 1\n", base64.StdEncoding.EncodeToString(b.Bytes()), base64.StdEncoding.EncodeToString(a.PublicKey().Bytes()), port)
	json.NewEncoder(os.Stdout).Encode(map[string]string{"pid": strconv.Itoa(os.Getpid()), "profile": base64.StdEncoding.EncodeToString([]byte(conf)), "certificate": base64.StdEncoding.EncodeToString(cert)})
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
	<-signals
	return nil
}

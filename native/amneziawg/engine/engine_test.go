package engine

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"golang.org/x/net/dns/dnsmessage"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"testing"
	"time"
)

func pair(t *testing.T) (string, string) {
	t.Helper()
	k, e := ecdh.X25519().GenerateKey(rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	return hex.EncodeToString(k.Bytes()), hex.EncodeToString(k.PublicKey().Bytes())
}
func fixture(t *testing.T) (*Engine, *Engine) {
	t.Helper()
	a, ap := pair(t)
	b, bp := pair(t)
	socket, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	port := socket.LocalAddr().(*net.UDPAddr).Port
	socket.Close()
	// Non-default AWG padding, headers and junk require both peers to agree.
	obfuscation := "jc=2\njmin=40\njmax=80\ns1=16\ns2=24\ns3=8\ns4=8\nh1=100001\nh2=100002\nh3=100003\nh4=100004\n"
	server, e := Start(Config{Addresses: []string{"10.77.0.1"}, MTU: 1280, UAPI: fmt.Sprintf("private_key=%s\nlisten_port=%d\n%spublic_key=%s\nallowed_ip=10.77.0.2/32\n", a, port, obfuscation, bp)}, nil)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(server.Close)
	client, e := Start(Config{Addresses: []string{"10.77.0.2"}, DNS: []string{"10.77.0.1"}, MTU: 1280, UAPI: fmt.Sprintf("private_key=%s\n%spublic_key=%s\nendpoint=127.0.0.1:%d\nallowed_ip=0.0.0.0/0\npersistent_keepalive_interval=1\n", b, obfuscation, ap, port)}, nil)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(client.Close)
	return server, client
}
func startDNS(t *testing.T, e *Engine) {
	t.Helper()
	l, er := e.Net.ListenUDPAddrPort(netip.MustParseAddrPort("10.77.0.1:53"))
	if er != nil {
		t.Fatal(er)
	}
	t.Cleanup(func() { l.Close() })
	go func() {
		buf := make([]byte, 4096)
		for {
			n, addr, err := l.ReadFrom(buf)
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
			data, err := answer.Pack()
			if err == nil {
				l.WriteTo(data, addr)
			}
		}
	}()
}
func socks(t *testing.T, port int, command byte, target string) net.Conn {
	t.Helper()
	c, e := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), time.Second)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { c.Close() })
	c.SetDeadline(time.Now().Add(12 * time.Second))
	c.Write([]byte{5, 1, 2})
	buf := make([]byte, 2)
	if _, e = io.ReadFull(c, buf); e != nil || buf[1] != 2 {
		t.Fatal("SOCKS method", e)
	}
	u := strings.Repeat("u", 16)
	p := strings.Repeat("p", 16)
	c.Write(append(append(append([]byte{1, 16}, u...), 16), p...))
	if _, e = io.ReadFull(c, buf); e != nil || buf[1] != 0 {
		t.Fatal("SOCKS auth", e)
	}
	host, portString, _ := net.SplitHostPort(target)
	var dstPort int
	fmt.Sscan(portString, &dstPort)
	request := append([]byte{5, command, 0, 3, byte(len(host))}, []byte(host)...)
	request = append(request, byte(dstPort>>8), byte(dstPort))
	c.Write(request)
	reply := make([]byte, 10)
	if _, e = io.ReadFull(c, reply); e != nil || reply[1] != 0 {
		t.Fatalf("SOCKS request to %s: %v %v", target, reply, e)
	}
	return &socksConnection{Conn: c, relay: int(binary.BigEndian.Uint16(reply[8:]))}
}

type socksConnection struct {
	net.Conn
	relay int
}

func TestEncryptedTCPDNSUDPAndRebind(t *testing.T) {
	server, client := fixture(t)
	startDNS(t, server)
	listener, err := server.Net.ListenTCPAddrPort(netip.MustParseAddrPort("10.77.0.1:8080"))
	if err != nil {
		t.Fatal(err)
	}
	httpServer := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, "encrypted AWG data") })}
	go httpServer.Serve(listener)
	t.Cleanup(func() { httpServer.Close() })
	udp, err := server.Net.ListenUDPAddrPort(netip.MustParseAddrPort("10.77.0.1:8081"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { udp.Close() })
	go func() {
		buf := make([]byte, 2048)
		for {
			n, a, e := udp.ReadFrom(buf)
			if e != nil {
				return
			}
			udp.WriteTo(buf[:n], a)
		}
	}()
	port, err := client.Serve(strings.Repeat("u", 16), strings.Repeat("p", 16))
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
        t.Logf("HTTP attempt %d", i)
		c := socks(t, port, 1, "awg-test.invalid:8080")
		fmt.Fprint(c, "GET / HTTP/1.0\r\nHost: awg-test.invalid\r\n\r\n")
		b, e := io.ReadAll(c)
		c.Close()
		if e != nil || !strings.Contains(string(b), "encrypted AWG data") {
			t.Fatalf("encrypted HTTP/DNS failed %v", e)
		}
		if e = client.Device.BindUpdate(); e != nil {
			t.Fatal(e)
		}
	}
	control := socks(t, port, 3, "0.0.0.0:0").(*socksConnection)
	defer control.Close()
	datagrams, err := net.DialUDP("udp4", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: control.relay})
	if err != nil {
		t.Fatal(err)
	}
	defer datagrams.Close()
	datagrams.SetDeadline(time.Now().Add(8 * time.Second))
	host := []byte("awg-test.invalid")
	packet := append(append([]byte{0, 0, 0, 3, byte(len(host))}, host...), 31, 145)
	packet = append(packet, []byte("encrypted UDP")...)
	datagrams.Write(packet)
	buf := make([]byte, 2048)
	n, err := datagrams.Read(buf)
	if err != nil || !strings.HasSuffix(string(buf[:n]), "encrypted UDP") {
		t.Fatalf("UDP failed %v", err)
	}
	stats, err := client.Device.IpcGet()
	if err != nil || strings.Contains(stats, "last_handshake_time_sec=0\n") {
		t.Fatal("no handshake")
	}
}
func TestRejectUnauthenticatedAndUnreachablePeer(t *testing.T) {
	_, client := fixture(t)
	port, err := client.Serve(strings.Repeat("u", 16), strings.Repeat("p", 16))
	if err != nil {
		t.Fatal(err)
	}
	c, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(time.Second))
	c.Write([]byte{5, 1, 0})
	b := make([]byte, 2)
	io.ReadFull(c, b)
	if b[1] != 255 {
		t.Fatal("anonymous SOCKS accepted")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()
	if conn, err := client.Dial(ctx, "tcp", "203.0.113.42:443"); err == nil {
		conn.Close()
		t.Fatal("unexpected direct fallback")
	}
}

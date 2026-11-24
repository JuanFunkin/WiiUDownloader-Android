package wiiu

import (
	"context"
	"net"
	"net/http"
	"strings"
	"time"
)

const (
	dialTimeout           = 30 * time.Second
	dialKeepAlive         = 30 * time.Second
	fallbackDNSTimeout    = 10 * time.Second
	fallbackDNSResolver   = "1.1.1.1:53"
	tlsHandshakeTimeout   = 10 * time.Second
	responseHeaderTimeout = 15 * time.Second
	idleConnTimeout       = 90 * time.Second
)

// newHTTPClient mirrors the desktop client, including the DNS fallback: some
// Android networks hand Go's resolver nothing usable.
func newHTTPClient() *http.Client {
	return &http.Client{
		Transport: &http.Transport{
			Proxy: http.ProxyFromEnvironment,
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				dialer := &net.Dialer{Timeout: dialTimeout, KeepAlive: dialKeepAlive}
				conn, err := dialer.DialContext(ctx, network, addr)
				if err == nil {
					return conn, nil
				}
				if !strings.Contains(err.Error(), "no such host") && !strings.Contains(err.Error(), "lookup") {
					return nil, err
				}
				resolver := &net.Resolver{
					PreferGo: true,
					Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
						d := net.Dialer{Timeout: fallbackDNSTimeout}
						return d.DialContext(ctx, "udp", fallbackDNSResolver)
					},
				}
				host, port, splitErr := net.SplitHostPort(addr)
				if splitErr != nil {
					return nil, err
				}
				ips, lookupErr := resolver.LookupIPAddr(ctx, host)
				if lookupErr != nil || len(ips) == 0 {
					return nil, err
				}
				return dialer.DialContext(ctx, network, net.JoinHostPort(ips[0].String(), port))
			},
			MaxIdleConns:          32,
			MaxIdleConnsPerHost:   16,
			MaxConnsPerHost:       16,
			IdleConnTimeout:       idleConnTimeout,
			TLSHandshakeTimeout:   tlsHandshakeTimeout,
			ResponseHeaderTimeout: responseHeaderTimeout,
			ExpectContinueTimeout: time.Second,
		},
	}
}

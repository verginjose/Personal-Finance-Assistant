#!/usr/bin/env python3
"""
Dev HTTP server with reverse-proxy for /dev-health/* and /api/* routes.
Serves the frontend files AND proxies backend calls so ClickHouse, Grafana,
Prometheus and the Spring services are reachable without CORS issues.

Usage:
    python3 server.py
    Open http://localhost:8001/dev.html
"""

import http.server
import socketserver
import urllib.request
import urllib.error
import os
import sys

PORT = 8001

# Proxy map: URL prefix -> upstream base URL
PROXY_ROUTES = {
    "/dev-health/clickhouse/": "http://localhost:8123/",
    "/dev-health/grafana/":    "http://localhost:3000/",
    "/dev-health/prometheus/": "http://localhost:9090/",
    "/dev-health/fluent-bit/": "http://localhost:2020/",
    "/dev-health/auth/":       "http://localhost:8082/",
    "/dev-health/upsert/":     "http://localhost:8081/",
    "/dev-health/analytics/":  "http://localhost:8084/",
    "/dev-health/ocr/":        "http://localhost:8083/",
    "/dev-health/gateway/":    "http://localhost:8080/",
    "/api/":                   "http://localhost:8080/api/",
}

CORS_HEADERS = {
    "Access-Control-Allow-Origin":  "*",
    "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
}


class DevServerHandler(http.server.SimpleHTTPRequestHandler):
    """Serves static files and proxies /dev-health/* and /api/* upstream."""

    def log_message(self, fmt, *args):
        # Quieter logging — only print non-200 responses to reduce noise
        code = args[1] if len(args) > 1 else "-"
        if str(code) not in ("200", "304"):
            super().log_message(fmt, *args)

    def _match_proxy(self):
        """Return (prefix, upstream_base) or (None, None)."""
        for prefix, upstream in PROXY_ROUTES.items():
            if self.path.startswith(prefix):
                return prefix, upstream
        return None, None

    def _send_cors(self):
        for k, v in CORS_HEADERS.items():
            self.send_header(k, v)

    def do_OPTIONS(self):
        self.send_response(200)
        self._send_cors()
        self.end_headers()

    def do_GET(self):
        prefix, upstream = self._match_proxy()
        if prefix:
            self._proxy(prefix, upstream)
        else:
            super().do_GET()

    def do_POST(self):
        prefix, upstream = self._match_proxy()
        if prefix:
            self._proxy(prefix, upstream)
        else:
            self.send_error(405, "Method Not Allowed")

    def _proxy(self, prefix, upstream_base):
        """Forward the request to the upstream service and relay the response."""
        tail = self.path[len(prefix):]
        target = upstream_base + tail

        # Read request body (for POST/PUT)
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length > 0 else None

        # Build upstream request, forwarding content-type
        headers = {}
        for h in ("Content-Type", "Authorization", "X-Clickhouse-User", "X-Clickhouse-Key"):
            v = self.headers.get(h)
            if v:
                headers[h] = v

        try:
            req = urllib.request.Request(target, data=body, headers=headers, method=self.command)
            with urllib.request.urlopen(req, timeout=10) as resp:
                resp_body = resp.read()
                self.send_response(resp.status)
                self._send_cors()
                for k, v in resp.headers.items():
                    if k.lower() not in ("transfer-encoding", "connection", "access-control-allow-origin"):
                        self.send_header(k, v)
                self.end_headers()
                self.wfile.write(resp_body)
        except urllib.error.HTTPError as e:
            body = e.read()
            self.send_response(e.code)
            self._send_cors()
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(body)
        except Exception as e:
            self.send_response(502)
            self._send_cors()
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(f"Proxy error: {e}".encode())

    def end_headers(self):
        self._send_cors()
        super().end_headers()


if __name__ == "__main__":
    os.chdir(os.path.dirname(os.path.abspath(__file__)))

    with socketserver.TCPServer(("", PORT), DevServerHandler) as httpd:
        httpd.allow_reuse_address = True
        print(f"┌─────────────────────────────────────────────┐")
        print(f"│  Finance Assistant — Dev Server             │")
        print(f"│                                             │")
        print(f"│  Frontend  → http://localhost:{PORT}          │")
        print(f"│  Dev Panel → http://localhost:{PORT}/dev.html │")
        print(f"│                                             │")
        print(f"│  Proxied routes:                            │")
        for p in PROXY_ROUTES:
            print(f"│    {p:<41}│")
        print(f"└─────────────────────────────────────────────┘")
        print("Press Ctrl+C to stop.\n")

        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nServer stopped.")
            sys.exit(0)

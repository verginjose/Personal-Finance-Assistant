#!/usr/bin/env python3
"""
security_check.py — black-box + configuration security checks for Varavu.

Runs the checks that map to the findings in docs/security-audit.md plus a
broader set of everyday web/API security checks. It is a scanner, not a
penetration test: it demonstrates whether the controls are *present* and
behaving; it does not try to exploit real accounts or exfiltrate data.

Design rules
------------
* Read-only by default. Mutating tests (uploads, bulk IDOR writes) only run
  with --write-tests.
* The rate-limit / lockout probe uses a randomly generated, non-existent
  email, never the account passed to --email.
* Tokens and passwords are never printed. Only status codes and safe details.
* Unreachable target = exit 2 without a wall of failures.

Usage
-----
    # Public app origin (frontend nginx -> gateway)
    python3 scripts/security_check.py --base-url https://finance.example.com \
        --email me@example.com --password '***'

    # Local direct gateway
    python3 scripts/security_check.py --base-url http://localhost:8080 --no-tls

    # Also exercise uploads and the write-side IDOR checks
    python3 scripts/security_check.py --base-url https://... \
        --email me@example.com --password '***' --write-tests

    # Add in-cluster checks (needs kubectl context with access to the pfa ns)
    python3 scripts/security_check.py --base-url https://... --k8s

Exit codes: 0 = no high/medium failures, 1 = high/medium failures,
            2 = target unreachable or bad invocation.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import socket
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from typing import Any, Iterable, Optional

# ── Tiny HTTP client ────────────────────────────────────────────────────────


class TargetUnreachable(RuntimeError):
    pass


@dataclass
class Response:
    status: int
    headers: Any          # email.message.Message (keeps duplicate Set-Cookie)
    body: bytes
    url: str
    elapsed_ms: float

    def header(self, name: str, default: str = "") -> str:
        return self.headers.get(name, default) or default

    def header_all(self, name: str) -> list[str]:
        return list(self.headers.get_all(name) or [])

    @property
    def text(self) -> str:
        return self.body.decode("utf-8", "replace")

    def json(self) -> Any:
        try:
            return json.loads(self.body or b"null")
        except Exception:
            return None

    def content_type(self) -> str:
        return self.header("Content-Type").split(";")[0].strip().lower()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D401
        return None


class Client:
    def __init__(self, base_url: str, *, insecure: bool = False, timeout: float = 15.0):
        self.base = base_url.rstrip("/")
        self.timeout = timeout
        self.access_token: Optional[str] = None
        self.refresh_cookie: Optional[str] = None
        self.user_id: Optional[str] = None
        self.email: Optional[str] = None
        handlers: list[Any] = []
        if insecure:
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            handlers.append(urllib.request.HTTPSHandler(context=ctx))
        self._opener = urllib.request.build_opener(*handlers)
        self._no_redirect = urllib.request.build_opener(NoRedirect, *handlers)

    def url(self, path: str) -> str:
        if path.startswith("http://") or path.startswith("https://"):
            return path
        return self.base + (path if path.startswith("/") else "/" + path)

    def request(self, method: str, path: str, *, body: Any = None,
                headers: Optional[dict[str, str]] = None,
                timeout: Optional[float] = None,
                follow_redirects: bool = True,
                auth: bool = True) -> Response:
        hdrs = {"User-Agent": "varavu-security-check/1.0"}
        if headers:
            hdrs.update(headers)
        if auth and self.access_token and "Authorization" not in hdrs:
            hdrs["Authorization"] = "Bearer " + self.access_token

        data: Optional[bytes] = None
        if body is not None:
            if isinstance(body, (bytes, bytearray)):
                data = bytes(body)
            else:
                data = json.dumps(body).encode()
                hdrs.setdefault("Content-Type", "application/json")

        req = urllib.request.Request(self.url(path), data=data, method=method, headers=hdrs)
        opener = self._opener if follow_redirects else self._no_redirect
        started = time.monotonic()
        try:
            resp = opener.open(req, timeout=timeout or self.timeout)
            raw = resp.read()
            elapsed = (time.monotonic() - started) * 1000
            return Response(resp.status, resp.headers, raw, resp.geturl(), elapsed)
        except urllib.error.HTTPError as e:
            raw = e.read()
            elapsed = (time.monotonic() - started) * 1000
            return Response(e.code, e.headers, raw, e.geturl(), elapsed)
        except (urllib.error.URLError, socket.timeout, TimeoutError, ssl.SSLError) as e:
            raise TargetUnreachable(str(e)) from e

    def get(self, path: str, **kw) -> Response:
        return self.request("GET", path, **kw)

    def post(self, path: str, body: Any = None, **kw) -> Response:
        return self.request("POST", path, body=body, **kw)

    def put(self, path: str, body: Any = None, **kw) -> Response:
        return self.request("PUT", path, body=body, **kw)

    def multipart(self, path: str, filename: str, content: bytes,
                  content_type: str, *, extra_headers: Optional[dict[str, str]] = None) -> Response:
        boundary = "----varavu" + uuid.uuid4().hex
        parts = [
            f"--{boundary}\r\n".encode(),
            (f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
             f"Content-Type: {content_type}\r\n\r\n").encode(),
            content,
            f"\r\n--{boundary}--\r\n".encode(),
        ]
        hdrs = {"Content-Type": f"multipart/form-data; boundary={boundary}"}
        if extra_headers:
            hdrs.update(extra_headers)
        return self.request("POST", path, body=b"".join(parts), headers=hdrs)


# ── Reporting ───────────────────────────────────────────────────────────────

PASS, FAIL, WARN, SKIP, INFO = "PASS", "FAIL", "WARN", "SKIP", "INFO"
COLORS = {PASS: "\033[32m", FAIL: "\033[31m", WARN: "\033[33m",
          SKIP: "\033[90m", INFO: "\033[36m"}
RESET = "\033[0m"


@dataclass
class Finding:
    severity: str          # high | medium | low | info
    name: str
    status: str
    detail: str = ""
    category: str = ""


@dataclass
class Report:
    findings: list[Finding] = field(default_factory=list)

    def add(self, severity: str, name: str, status: str, detail: str = "", category: str = "") -> None:
        self.findings.append(Finding(severity, name, status, detail, category))

    def check(self, severity: str, name: str, ok: bool, detail: str = "", category: str = "") -> None:
        self.add(severity, name, PASS if ok else FAIL, "" if ok else detail, category)

    def section(self, title: str) -> None:
        self.findings.append(Finding("info", f"── {title} ──", INFO))

    def counts(self) -> dict[str, int]:
        out = {PASS: 0, FAIL: 0, WARN: 0, SKIP: 0}
        for f in self.findings:
            if f.status in out:
                out[f.status] += 1
        return out

    def failures(self, strict: bool) -> list[Finding]:
        hard = {"high", "medium"} if not strict else {"high", "medium", "low"}
        return [f for f in self.findings if f.status == FAIL and f.severity in hard]

    def render(self, color: bool) -> None:
        for f in self.findings:
            if f.status == INFO:
                print(f"\n{f.name}")
                continue
            tag = f.status
            if color:
                tag = COLORS.get(f.status, "") + tag + RESET
            suffix = f"  {f.detail}" if f.detail else ""
            print(f"  [{tag:<12}] [{f.severity:<6}] {f.name}{suffix}")

    def to_json(self) -> list[dict[str, Any]]:
        return [{"severity": f.severity, "status": f.status, "name": f.name,
                 "detail": f.detail, "category": f.category}
                for f in self.findings if f.status != INFO]


# ── Helpers ─────────────────────────────────────────────────────────────────

UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", re.I)
TINY_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)


def rand_uuid() -> str:
    return str(uuid.uuid4())


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def jwt_none_forgery(user_id: str, email: str) -> str:
    header = b64url(json.dumps({"alg": "none", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = b64url(json.dumps({
        "sub": email, "userId": user_id, "role": "USER",
        "exp": int(time.time()) + 3600,
    }, separators=(",", ":")).encode())
    return f"{header}.{payload}."   # empty signature


def tamper(token: str) -> str:
    if token.count(".") != 2:
        return token + "x"
    head, payload, sig = token.split(".")
    if not sig:
        return f"{head}.{payload}.x"
    last = "A" if sig[-1] != "A" else "B"
    return f"{head}.{payload}.{sig[:-1]}{last}"


def parse_cookie_attrs(set_cookie: str) -> dict[str, str]:
    parts = [p.strip() for p in set_cookie.split(";")]
    attrs: dict[str, str] = {}
    if parts:
        first = parts[0]
        if "=" in first:
            k, v = first.split("=", 1)
            attrs[k.strip().lower()] = v
    for p in parts[1:]:
        if "=" in p:
            k, v = p.split("=", 1)
            attrs[k.strip().lower()] = v.strip()
        else:
            attrs[p.strip().lower()] = ""
    return attrs


def looks_like_stack(text: str) -> bool:
    return bool(re.search(r"(java\.|org\.springframework\.|at [a-zA-Z0-9_$.]+\(|Traceback \(most recent)", text))


def parse_csp(value: str) -> dict[str, str]:
    directives: dict[str, str] = {}
    for part in value.split(";"):
        bits = part.strip().split()
        if bits:
            directives[bits[0].lower()] = " ".join(bits[1:]).lower()
    return directives


# ── Checks ──────────────────────────────────────────────────────────────────

def preflight(client: Client, report: Report) -> None:
    try:
        r = client.get("/api/auth/health", auth=False, timeout=10)
    except TargetUnreachable as e:
        raise TargetUnreachable(f"cannot reach {client.base}: {e}") from e
    if r.status != 200:
        raise TargetUnreachable(f"/api/auth/health returned {r.status}; is this the app origin?")
    report.add("info", "Target reachable: " + client.base, INFO)


def check_headers(client: Client, report: Report, *, is_https: bool) -> None:
    report.section("HTTP security headers")
    try:
        r = client.get("/", auth=False)
    except TargetUnreachable as e:
        report.add("medium", "Fetch / for header checks", SKIP, str(e))
        return

    if r.content_type() != "text/html":
        report.add("low", "Header checks need the frontend origin", SKIP,
                   "the base URL did not return HTML; run against the public app origin")
        return

    csp = r.header("Content-Security-Policy")
    if not csp:
        report.add("high", "Content-Security-Policy present", FAIL, "no CSP on the app document")
    else:
        report.add("high", "Content-Security-Policy present", PASS)
        directives = parse_csp(csp)
        script_src = directives.get("script-src", "")
        if not script_src:
            report.add("high", "CSP defines script-src", FAIL, "script-src is not restricted")
        elif "'unsafe-inline'" in script_src or "*" in script_src.split():
            report.add("high", "CSP does not allow inline/any script", FAIL,
                       "script-src contains 'unsafe-inline' or *")
        else:
            report.add("high", "CSP does not allow inline/any script", PASS)
        if directives.get("frame-ancestors", "") not in ("'none'", "none"):
            report.add("medium", "CSP frame-ancestors 'none'", FAIL,
                       f"frame-ancestors={directives.get('frame-ancestors')!r}")
        else:
            report.add("medium", "CSP frame-ancestors 'none'", PASS)
        if "object-src" not in directives:
            report.add("low", "CSP object-src restricted", WARN, "object-src is not set")
        else:
            report.add("low", "CSP object-src restricted", PASS)

    report.check("medium", "X-Content-Type-Options: nosniff",
                 r.header("X-Content-Type-Options").lower() == "nosniff",
                 "header missing or not 'nosniff'")

    xfo = r.header("X-Frame-Options").lower()
    fa = parse_csp(r.header("Content-Security-Policy")).get("frame-ancestors", "")
    report.check("medium", "Clickjacking protection",
                 xfo in ("deny", "sameorigin") or fa in ("'none'", "none"),
                 "neither X-Frame-Options nor frame-ancestors is set")

    report.check("low", "Referrer-Policy present", bool(r.header("Referrer-Policy")),
                 "suggest 'no-referrer'")

    server = r.header("Server")
    if server and not re.match(r"^(nginx|cloudflare|envoy|traefik)(/|$)", server, re.I):
        report.add("low", "Server header disclosure", WARN, server)

    if is_https:
        hsts = r.header("Strict-Transport-Security")
        report.check("medium", "Strict-Transport-Security", bool(hsts),
                     "no HSTS header on an HTTPS origin")
    else:
        report.add("low", "HSTS", SKIP, "target is not HTTPS")
        report.add("medium", "Target uses HTTPS", WARN, "testing an HTTP origin; cookies cannot be Secure")


def check_cors(client: Client, report: Report) -> None:
    report.section("CORS")
    try:
        r = client.request(
            "OPTIONS", "/api/auth/login", auth=False,
            headers={
                "Origin": "https://evil.example",
                "Access-Control-Request-Method": "POST",
                "Access-Control-Request-Headers": "authorization,content-type",
            },
        )
    except TargetUnreachable as e:
        report.add("medium", "CORS preflight", SKIP, str(e))
        return
    acao = r.header("Access-Control-Allow-Origin")
    acac = r.header("Access-Control-Allow-Credentials").lower()
    if acao == "*":
        report.add("medium", "CORS does not allow any origin", FAIL, "Access-Control-Allow-Origin: *")
    elif "evil.example" in acao:
        report.add("medium", "CORS does not reflect arbitrary origins", FAIL, f"reflected {acao}")
    else:
        report.add("medium", "CORS does not reflect arbitrary origins", PASS,
                   f"status {r.status}, allow-origin={acao or '<none>'}")
    if acao == "*" and acac == "true":
        report.add("high", "CORS credentials not wildcarded", FAIL, "* with credentials")


def check_info_disclosure(client: Client, report: Report) -> None:
    report.section("Error handling / information disclosure")
    try:
        r = client.post("/api/auth/login", body=b"{not json", auth=False,
                        headers={"Content-Type": "application/json"})
        leaked = looks_like_stack(r.text)
        report.check("low", "Malformed login does not leak a stack trace", not leaked,
                     f"status {r.status} body looks like a stack trace")
        report.check("low", "Malformed login is rejected", 400 <= r.status < 500,
                     f"status {r.status}")
    except TargetUnreachable as e:
        report.add("low", "Malformed request handling", SKIP, str(e))

    try:
        r = client.get("/api/definitely-not-a-route", auth=False)
        report.check("low", "Unknown route does not leak a stack trace",
                     not looks_like_stack(r.text), f"status {r.status}")
    except TargetUnreachable as e:
        report.add("low", "Unknown route handling", SKIP, str(e))


def check_actuator(client: Client, report: Report) -> None:
    report.section("Actuator exposure")
    for path in ("/actuator/prometheus", "/actuator/env", "/actuator/health"):
        try:
            r = client.get(path, auth=False)
        except TargetUnreachable as e:
            report.add("high", f"Actuator {path} not publicly exposed", SKIP, str(e))
            continue
        ct = r.content_type()
        if r.status == 200 and ct.startswith("text/html"):
            # The SPA fallback served index.html; this is not the actuator.
            report.add("high", f"Actuator {path} not publicly exposed", PASS,
                       "SPA fallback, not actuator")
        elif r.status == 200:
            leaked = any(k in r.text for k in ("jvm", "process_", "heap", "systemProperties"))
            report.add("high", f"Actuator {path} not publicly exposed", FAIL,
                       "200 from the actuator" + (" with data" if leaked else ""))
        else:
            report.add("high", f"Actuator {path} not publicly exposed", PASS, f"status {r.status}")


def check_unauthenticated(client: Client, report: Report) -> None:
    report.section("Authentication required")
    other = rand_uuid()
    probes = [
        ("GET", "/api/auth/me", None),
        ("GET", f"/api/upsert/entries?userId={other}", None),
        ("GET", f"/api/upsert/summary?userId={other}", None),
        ("POST", "/api/upsert/create", {"userId": other, "name": "x", "amount": 1,
                                        "type": "EXPENSE", "currency": "INR"}),
        ("POST", "/api/auth/logout", {}),
        ("POST", "/api/auth/stream-ticket", {}),
        ("GET", "/api/upsert/notifications/stream", None),
    ]
    for method, path, body in probes:
        try:
            r = client.request(method, path, body=body, auth=False, timeout=10)
        except TargetUnreachable as e:
            report.add("high", f"Unauthenticated {method} {path.split('?')[0]}", SKIP, str(e))
            continue
        # A stream endpoint that hangs would have raised; a 401 is correct.
        report.check("high", f"Unauthenticated {method} {path.split('?')[0]} rejected",
                     r.status == 401, f"status {r.status}", category="auth")


def login(client: Client, report: Report, email: str, password: str) -> bool:
    try:
        r = client.post("/api/auth/login", body={"email": email, "password": password}, auth=False)
    except TargetUnreachable as e:
        report.add("high", "Login", SKIP, str(e))
        return False
    if r.status != 200:
        report.add("high", "Login with the provided credentials", SKIP,
                   f"status {r.status}; authenticated checks skipped")
        return False

    data = r.json() or {}
    client.access_token = data.get("token") or None
    client.user_id = data.get("userId") or None
    client.email = data.get("email") or email

    report.section("Session handling")
    report.check("high", "Login returns an access token", bool(client.access_token),
                 "no token in the response")

    # The refresh token must not be readable from JavaScript.
    if data.get("refreshToken"):
        report.add("high", "Refresh token not exposed in the JSON body", FAIL,
                   "login response contains refreshToken")
    else:
        report.add("high", "Refresh token not exposed in the JSON body", PASS)

    cookies = [c for c in r.header_all("Set-Cookie") if c.lower().startswith("pfa_refresh=")]
    if not cookies:
        report.add("high", "Refresh cookie set", FAIL, "no pfa_refresh Set-Cookie")
        client.refresh_cookie = None
    else:
        report.add("high", "Refresh cookie set", PASS)
        attrs = parse_cookie_attrs(cookies[0])
        client.refresh_cookie = attrs.get("pfa_refresh")
        report.check("high", "Refresh cookie is HttpOnly", "httponly" in attrs,
                     "cookie is readable by script")
        same_site = attrs.get("samesite", "").lower()
        report.check("medium", "Refresh cookie SameSite=Strict", same_site == "strict",
                     f"samesite={same_site or '<none>'}")
        report.check("medium", "Refresh cookie path scoped to /api/auth",
                     attrs.get("path", "/") == "/api/auth", f"path={attrs.get('path')}")
        if client.base.startswith("https://"):
            report.check("high", "Refresh cookie is Secure", "secure" in attrs,
                         "Secure is missing on an HTTPS origin")
        else:
            report.add("medium", "Refresh cookie Secure", SKIP, "HTTP origin in this test")

    if client.access_token:
        try:
            me = client.get("/api/auth/me")
            report.check("high", "Access token authenticates /api/auth/me", me.status == 200,
                         f"status {me.status}")
            tampered = tamper(client.access_token)
            r2 = client.get("/api/auth/me", headers={"Authorization": "Bearer " + tampered})
            report.check("high", "Tampered token rejected", r2.status == 401, f"status {r2.status}")
            if client.user_id:
                forged = jwt_none_forgery(client.user_id, client.email or "x@example.com")
                r3 = client.get("/api/auth/me", headers={"Authorization": "Bearer " + forged})
                report.check("high", "alg=none forgery rejected", r3.status == 401, f"status {r3.status}")
        except TargetUnreachable as e:
            report.add("high", "Token validation", SKIP, str(e))
    return True


def check_authorization(client: Client, report: Report, *, write_tests: bool) -> None:
    report.section("Authorization / IDOR")
    if not (client.access_token and client.user_id):
        report.add("high", "Authorization checks", SKIP, "no authenticated session")
        return
    other = rand_uuid()

    # A client-supplied X-User-Id must be overwritten by the gateway from the JWT.
    try:
        r = client.get(f"/api/upsert/entries?userId={client.user_id}",
                       headers={"X-User-Id": other})
        report.check("high", "Client X-User-Id is ignored (gateway stamps the JWT id)",
                     r.status == 200, f"status {r.status}; the spoofed header was trusted")
    except TargetUnreachable as e:
        report.add("high", "X-User-Id spoof resistance", SKIP, str(e))

    # Asking for another user's ledger must be forbidden.
    try:
        r = client.get(f"/api/upsert/entries?userId={other}")
        report.check("high", "Query for another user's entries rejected",
                     r.status == 403, f"status {r.status}")
    except TargetUnreachable as e:
        report.add("high", "Cross-user query", SKIP, str(e))

    # Password change must revoke live access tokens. We do not change the real
    # password here; the blacklist/session-version path is covered by logout.
    if not write_tests:
        report.add("high", "Bulk create rejects mixed user ids", SKIP, "--write-tests not set")
        report.add("high", "Upload endpoints reject a foreign userId", SKIP, "--write-tests not set")
        return

    own = {"userId": client.user_id, "name": "Security check", "amount": 1.00,
           "type": "EXPENSE", "category": "OTHERS", "currency": "INR"}
    foreign = dict(own, userId=other)
    try:
        r = client.post("/api/upsert/create/bulk", body=[own, foreign])
        if r.status == 403:
            report.add("high", "Bulk create rejects mixed user ids", PASS)
        elif r.status in (200, 201):
            report.add("high", "Bulk create rejects mixed user ids", FAIL,
                       f"status {r.status}: rows may have been written for another user")
        else:
            report.add("high", "Bulk create rejects mixed user ids", WARN,
                       f"inconclusive status {r.status}")
    except TargetUnreachable as e:
        report.add("high", "Bulk create IDOR", SKIP, str(e))

    try:
        r = client.multipart(f"/api/receipt/upload/{other}", "x.png", TINY_PNG, "image/png")
        report.check("high", "Receipt upload rejects a foreign path userId",
                     r.status == 403, f"status {r.status}")
    except TargetUnreachable as e:
        report.add("high", "Receipt upload ownership", SKIP, str(e))

    try:
        r = client.multipart(f"/api/bill/process/{other}", "x.png", TINY_PNG, "image/png")
        report.check("high", "Bill upload rejects a foreign path userId",
                     r.status == 403, f"status {r.status}")
    except TargetUnreachable as e:
        report.add("high", "Bill upload ownership", SKIP, str(e))


def check_user_privacy(client: Client, report: Report) -> None:
    report.section("Profile data minimization")
    if not (client.access_token and client.user_id):
        report.add("medium", "Email disclosure checks", SKIP, "no authenticated session")
        return
    try:
        q = urllib.parse.quote((client.email or "a")[0])
        r = client.get(f"/api/auth/users/search?q={q}&limit=20&page=0")
        users = r.json() if isinstance(r.json(), list) else []
        offenders = [u for u in users
                     if u.get("email") and u.get("userId") != client.user_id]
        report.check("medium", "Search returns email only to the owner", not offenders,
                     f"{len(offenders)} other user(s) leaked an email" if offenders else "")
    except TargetUnreachable as e:
        report.add("medium", "Search email disclosure", SKIP, str(e))

    try:
        r2 = client.post("/api/auth/users/bulk", body=[client.user_id, rand_uuid()])
        rows = r2.json() if isinstance(r2.json(), list) else []
        offenders = [u for u in rows
                     if u.get("email") and u.get("userId") != client.user_id]
        self_row = next((u for u in rows if u.get("userId") == client.user_id), None)
        report.check("medium", "Bulk lookup hides other users' email", not offenders,
                     f"{len(offenders)} row(s) leaked an email" if offenders else "")
        if self_row is not None:
            report.check("low", "Bulk lookup still returns the caller's own email",
                         bool(self_row.get("email")), "own email missing")
    except TargetUnreachable as e:
        report.add("medium", "Bulk email disclosure", SKIP, str(e))


def check_stream_auth(client: Client, report: Report) -> None:
    report.section("Notification stream authentication")
    if not client.access_token:
        report.add("high", "SSE token handling", SKIP, "no authenticated session")
        return
    token_q = urllib.parse.quote(client.access_token)
    # The old design accepted the access token as ?token=; it must now be 401.
    try:
        r = client.get(f"/api/upsert/notifications/stream?token={token_q}", auth=False, timeout=8)
        report.check("high", "Access token in the query string is rejected",
                     r.status == 401, f"status {r.status}")
    except TargetUnreachable as e:
        if "timed out" in str(e).lower():
            report.add("high", "Access token in the query string is rejected", FAIL,
                       "the stream accepted ?token and stayed open")
        else:
            report.add("high", "SSE ?token rejection", SKIP, str(e))

    try:
        r = client.get("/api/upsert/notifications/stream?ticket=not-a-ticket",
                       auth=False, timeout=8)
        report.check("high", "Bogus stream ticket is rejected", r.status == 401,
                     f"status {r.status}")
    except TargetUnreachable as e:
        report.add("high", "Bogus stream ticket", SKIP, str(e))

    try:
        r = client.post("/api/auth/stream-ticket", body={})
        data = r.json() or {}
        report.check("medium", "Stream ticket endpoint issues a short-lived ticket",
                     r.status == 200 and isinstance(data.get("ticket"), str),
                     f"status {r.status}")
    except TargetUnreachable as e:
        report.add("medium", "Stream ticket endpoint", SKIP, str(e))


def check_storage(client: Client, report: Report, *, write_tests: bool) -> None:
    report.section("Object storage")
    for path, label in (
        # Backslashes survive nginx path normalisation, so this actually reaches
        # the controller's traversal guard instead of being collapsed to a clean
        # path (which the SPA would answer with 200).
        ("/api/storage/profiles/%2e%2e%5c%2e%2e%5cetc%5cpasswd", "Path traversal is rejected"),
        ("/api/storage/secrets/anything.png", "Unknown bucket is rejected"),
        ("/api/storage/profiles/does-not-exist.png", "Missing object is 404"),
    ):
        try:
            r = client.get(path, auth=False)
            report.check("high" if "traversal" in label or "bucket" in label else "low",
                         label, r.status in (400, 403, 404), f"status {r.status}")
        except TargetUnreachable as e:
            report.add("high", label, SKIP, str(e))

    if not write_tests:
        report.add("high", "Upload content validation", SKIP, "--write-tests not set")
        return
    if not client.access_token:
        report.add("high", "Upload content validation", SKIP, "no authenticated session")
        return

    try:
        r = client.multipart("/api/profile/upload", "x.html",
                             b"<script>alert(1)</script>", "text/html")
        report.check("high", "HTML upload is rejected", r.status == 415, f"status {r.status}")
    except TargetUnreachable as e:
        report.add("high", "HTML upload rejection", SKIP, str(e))

    try:
        r = client.multipart("/api/profile/upload", "x.png",
                             b"this is not a png", "image/png")
        report.check("high", "Content that lies about its type is rejected",
                     r.status == 415, f"status {r.status}")
    except TargetUnreachable as e:
        report.add("high", "Magic-byte validation", SKIP, str(e))

    try:
        r = client.multipart("/api/profile/upload", "x.png", TINY_PNG, "image/png")
        report.check("high", "A valid image upload succeeds", r.status == 200, f"status {r.status}")
        url = (r.json() or {}).get("url") if r.status == 200 else None
        if url:
            obj = client.get(url, auth=False)
            report.check("high", "Stored object served with nosniff",
                         obj.header("X-Content-Type-Options").lower() == "nosniff",
                         "missing X-Content-Type-Options")
            report.check("high", "Stored object served with a sandbox CSP",
                         "sandbox" in obj.header("Content-Security-Policy").lower(),
                         "missing sandbox CSP")
            report.check("medium", "Images are served inline",
                         obj.header("Content-Disposition").lower().startswith("inline"),
                         obj.header("Content-Disposition") or "no Content-Disposition")
            report.check("medium", "Stored object content type is fixed by the server",
                         obj.content_type() == "image/png", obj.content_type())
    except TargetUnreachable as e:
        report.add("high", "Image upload round-trip", SKIP, str(e))


def check_rate_limit(client: Client, report: Report) -> None:
    report.section("Login throttling / lockout")
    probe_email = f"sec-probe-{uuid.uuid4().hex[:12]}@example.invalid"
    statuses: list[int] = []
    try:
        for _ in range(15):
            r = client.post("/api/auth/login",
                            body={"email": probe_email, "password": "not-the-password"},
                            auth=False, timeout=10)
            statuses.append(r.status)
            if r.status == 429:
                break
            time.sleep(0.05)
    except TargetUnreachable as e:
        report.add("medium", "Account lockout after repeated failures", SKIP, str(e))
        return

    if 429 in statuses:
        idx = statuses.index(429) + 1
        report.add("medium", "Account lockout after repeated failures", PASS,
                   f"429 on attempt {idx}")
    else:
        report.add("medium", "Account lockout after repeated failures", WARN,
                   f"no 429 in {len(statuses)} attempts (statuses {sorted(set(statuses))}); "
                   "the gateway IP bucket may not have been hit, or lockout is missing")
    report.add("info", f"Rate-limit probe used the throwaway address {probe_email}", INFO)


def check_optional_services(client: Client, report: Report, args: argparse.Namespace) -> None:
    if not (args.ocr_url or args.command_url):
        return
    report.section("Internal service credentials")
    if args.ocr_url:
        base = args.ocr_url.rstrip("/")
        try:
            h = urllib.request.urlopen(base + "/health", timeout=8)
            report.check("medium", "OCR /health is reachable", h.status == 200, f"status {h.status}")
        except Exception as e:  # noqa: BLE001
            report.add("medium", "OCR /health", SKIP, str(e))
        for label, headers in (
            ("OCR /analyze rejects a missing token", {}),
            ("OCR /analyze rejects a wrong token", {"X-Internal-Token": "wrong-" + uuid.uuid4().hex}),
        ):
            try:
                data = json.dumps({"transactions": []}).encode()
                req = urllib.request.Request(base + "/analyze", data=data, method="POST",
                                             headers={"Content-Type": "application/json", **headers})
                resp = urllib.request.urlopen(req, timeout=8)
                report.add("high", label, FAIL, f"status {resp.status}")
            except urllib.error.HTTPError as e:
                report.check("high", label, e.code in (401, 403, 503), f"status {e.code}")
            except Exception as e:  # noqa: BLE001
                report.add("high", label, SKIP, str(e))

    if args.command_url:
        base = args.command_url.rstrip("/")
        for label, headers in (
            ("command /internal rejects a missing token", {}),
            ("command /internal rejects a wrong token", {"X-Internal-Token": "wrong-" + uuid.uuid4().hex}),
        ):
            try:
                data = json.dumps({"userId": rand_uuid(), "status": "INFO", "message": "sec"}).encode()
                req = urllib.request.Request(base + "/internal/ocr-complete", data=data,
                                             method="POST",
                                             headers={"Content-Type": "application/json", **headers})
                resp = urllib.request.urlopen(req, timeout=8)
                report.add("high", label, FAIL, f"status {resp.status}")
            except urllib.error.HTTPError as e:
                report.check("high", label, e.code in (401, 403, 503), f"status {e.code}")
            except Exception as e:  # noqa: BLE001
                report.add("high", label, SKIP, str(e))


# ── Repository checks ───────────────────────────────────────────────────────

def _read(path: str) -> str:
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError:
        return ""


def check_repository(repo_root: str, report: Report) -> None:
    report.section("Repository / configuration")
    if not os.path.isdir(os.path.join(repo_root, ".git")):
        report.add("info", "Repository checks skipped (not a git work tree)", SKIP)
        return

    # .env must be ignored.
    try:
        rc = subprocess.run(["git", "-C", repo_root, "check-ignore", "-q", ".env"],
                            capture_output=True).returncode
        report.check("high", ".env is gitignored", rc == 0, "git does not ignore .env")
    except OSError:
        report.add("high", ".env gitignore check", SKIP, "git not available")

    nginx = _read(os.path.join(repo_root, "frontend/nginx.conf"))
    report.check("high", "nginx sets a Content-Security-Policy",
                 "Content-Security-Policy" in nginx, "no CSP in frontend/nginx.conf")
    report.check("medium", "nginx sets nosniff",
                 'X-Content-Type-Options "nosniff"' in nginx or "X-Content-Type-Options nosniff" in nginx,
                 "no nosniff in frontend/nginx.conf")

    index = _read(os.path.join(repo_root, "frontend/index.html"))
    inline_scripts = re.findall(r"<script(?![^>]*\bsrc=)[^>]*>", index, re.I)
    report.check("high", "index.html has no inline <script>", not inline_scripts,
                 f"{len(inline_scripts)} inline script block(s)")
    inline_handlers = re.findall(r"\son\w+\s*=", index, re.I)
    report.check("medium", "index.html has no inline event handlers", not inline_handlers,
                 f"{len(inline_handlers)} on* attribute(s)")

    js_dir = os.path.join(repo_root, "frontend/js")
    js_hits: list[str] = []
    for root, _dirs, files in os.walk(js_dir):
        for name in files:
            if not name.endswith(".js"):
                continue
            text = _read(os.path.join(root, name))
            for pattern in ("pfa_token", "pfa_refresh"):
                if re.search(r"localStorage\.(setItem|getItem)\(\s*['\"]" + pattern, text):
                    js_hits.append(os.path.relpath(os.path.join(root, name), repo_root))
    report.check("high", "No token is stored in localStorage", not js_hits,
                 f"found in {sorted(set(js_hits))}")

    policy = _read(os.path.join(repo_root, "k8s/network-policies.yaml"))
    report.check("high", "NetworkPolicy manifest present",
                 "kind: NetworkPolicy" in policy, "k8s/network-policies.yaml missing")

    sealed_dir = os.path.join(repo_root, "k8s/sealed")
    sealed = [n for n in os.listdir(sealed_dir) if n.endswith(".yaml")] if os.path.isdir(sealed_dir) else []
    plaintext = [n for n in sealed
                 if "encryptedData" not in _read(os.path.join(sealed_dir, n))]
    report.check("high", "All sealed secrets are encrypted", bool(sealed) and not plaintext,
                 f"unencrypted or empty: {plaintext}" if plaintext else "no sealed secrets found")

    compose = _read(os.path.join(repo_root, "docker-compose.yml"))
    report.check("medium", "No privileged containers in compose",
                 "privileged: true" not in compose, "privileged: true in docker-compose.yml")

    apps_dir = os.path.join(repo_root, "k8s/apps")
    latest: list[str] = []
    if os.path.isdir(apps_dir):
        for name in os.listdir(apps_dir):
            if name.endswith(".yaml"):
                text = _read(os.path.join(apps_dir, name))
                if re.search(r"image:\s*\S+:latest\b", text):
                    latest.append(name)
    report.check("low", "Application images are pinned (not :latest)", not latest,
                 f"latest tags in {latest}" if latest else "")

    # A literal credential in a committed per-service env file is a smell.
    literal: list[str] = []
    env_dir = os.path.join(repo_root, "env")
    if os.path.isdir(env_dir):
        for name in os.listdir(env_dir):
            if not name.endswith(".env"):
                continue
            for line in _read(os.path.join(env_dir, name)).splitlines():
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                if re.search(r"(PASSWORD|SECRET|TOKEN|KEY)$", key) and value and \
                        value.lower() not in ("guest", "change-me", ""):
                    literal.append(f"{name}:{key}")
    report.check("medium", "env/*.env carry no literal credentials", not literal,
                 f"committed values: {sorted(set(literal))}")


def check_k8s(report: Report, namespace: str) -> None:
    report.section(f"Kubernetes (namespace {namespace})")

    def kubectl(*args: str) -> Optional[dict]:
        try:
            out = subprocess.run(["kubectl", "-n", namespace, *args, "-o", "json"],
                                 capture_output=True, text=True, timeout=25)
        except (OSError, subprocess.TimeoutExpired) as e:
            report.add("info", f"kubectl {args[0]}: {e}", SKIP)
            return None
        if out.returncode != 0:
            report.add("info", f"kubectl {args[0]} failed: {out.stderr.strip()[:160]}", SKIP)
            return None
        try:
            return json.loads(out.stdout)
        except json.JSONDecodeError:
            return None

    nps = kubectl("get", "networkpolicy")
    if nps is not None:
        items = nps.get("items", [])
        report.check("high", "NetworkPolicies exist", len(items) >= 4,
                     f"only {len(items)} NetworkPolicy object(s)")

    pods = kubectl("get", "pods")
    if pods is not None:
        privileged, host_net, latest, no_limits = [], [], [], []
        for pod in pods.get("items", []):
            name = pod.get("metadata", {}).get("name", "?")
            spec = pod.get("spec", {})
            if spec.get("hostNetwork"):
                host_net.append(name)
            if spec.get("hostPID"):
                host_net.append(name + " (hostPID)")
            for c in spec.get("containers", []) + spec.get("initContainers", []):
                sc = c.get("securityContext", {}) or {}
                if sc.get("privileged"):
                    privileged.append(f"{name}/{c.get('name')}")
                if str(c.get("image", "")).endswith(":latest"):
                    latest.append(f"{name}/{c.get('name')}")
                if not c.get("resources", {}).get("limits"):
                    no_limits.append(f"{name}/{c.get('name')}")
        report.check("high", "No privileged containers", not privileged, f"{privileged}")
        report.check("medium", "No hostNetwork/hostPID pods", not host_net, f"{host_net}")
        report.check("low", "No :latest images", not latest, f"{latest}")
        report.check("low", "Every container has resource limits", not no_limits,
                     ", ".join(no_limits[:6]) + (" …" if len(no_limits) > 6 else ""))

    svcs = kubectl("get", "services")
    if svcs is not None:
        exposed = [f"{s['metadata']['name']}:{s['spec'].get('type')}"
                   for s in svcs.get("items", [])
                   if s.get("spec", {}).get("type") in ("NodePort", "LoadBalancer")
                   # registry and argocd-server are intentionally reachable on a
                   # node port; anything else should sit behind the ingress.
                   and s["metadata"]["name"] not in ("argocd-server", "registry")]
        report.check("medium", "Only expected services are NodePort/LoadBalancer",
                     not exposed, f"exposed: {exposed}")

    secrets = kubectl("get", "secrets")
    if secrets is not None:
        unmanaged = []
        for s in secrets.get("items", []):
            name = s.get("metadata", {}).get("name", "")
            if not name.endswith("-secrets"):
                continue
            owners = s.get("metadata", {}).get("ownerReferences") or []
            if not any(o.get("kind") == "SealedSecret" for o in owners):
                unmanaged.append(name)
        report.check("medium", "Application Secrets are owned by SealedSecret",
                     not unmanaged, f"not sealed: {unmanaged}")


# ── Main ────────────────────────────────────────────────────────────────────

def main(argv: Optional[Iterable[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Security checks for Varavu (black-box + configuration).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python3 scripts/security_check.py --base-url https://finance.example.com \\
      --email me@example.com --password '***'
  python3 scripts/security_check.py --base-url http://localhost:8080
  python3 scripts/security_check.py --static-only
  python3 scripts/security_check.py --base-url https://... --email ... --password ... \\
      --write-tests --k8s

Never run the rate-limit probe against a real inbox: this script always uses a
throwaway address. Mutating tests require --write-tests.
""")
    parser.add_argument("--base-url", default="http://localhost:8080",
                        help="App origin (frontend nginx or the gateway). Default: %(default)s")
    parser.add_argument("--email", help="Test account email (enables authenticated checks)")
    parser.add_argument("--password", help="Test account password")
    parser.add_argument("--write-tests", action="store_true",
                        help="Also run mutating tests (uploads, bulk-write IDOR)")
    parser.add_argument("--no-rate-limit", action="store_true",
                        help="Skip the login-throttling probe")
    parser.add_argument("--ocr-url", help="Direct OCR base URL, e.g. http://127.0.0.1:8100")
    parser.add_argument("--command-url", help="Direct command-service base URL")
    parser.add_argument("--static-only", action="store_true",
                        help="Only run repository/k8s checks (no network target)")
    parser.add_argument("--k8s", action="store_true", help="Run in-cluster kubectl checks")
    parser.add_argument("--namespace", default="pfa", help="Namespace for --k8s. Default: %(default)s")
    parser.add_argument("--insecure", action="store_true", help="Do not verify TLS (self-signed)")
    parser.add_argument("--timeout", type=float, default=15.0, help="Request timeout in seconds")
    parser.add_argument("--json", metavar="PATH", help="Write findings as JSON")
    parser.add_argument("--strict", action="store_true",
                        help="Low-severity failures also make the run exit non-zero")
    parser.add_argument("--no-color", action="store_true", help="Disable ANSI colours")
    parser.add_argument("--repo-root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        help="Repository root for static checks")
    args = parser.parse_args(list(argv) if argv is not None else None)

    if bool(args.email) != bool(args.password):
        parser.error("--email and --password must be supplied together")

    color = sys.stdout.isatty() and not args.no_color
    report = Report()
    client = Client(args.base_url, insecure=args.insecure, timeout=args.timeout)
    is_https = args.base_url.lower().startswith("https://")

    try:
        if args.static_only:
            report.add("info", "Static-only mode: network checks skipped", INFO)
            check_repository(args.repo_root, report)
            if args.k8s:
                check_k8s(report, args.namespace)
            return _finish(report, args, color)

        preflight(client, report)
        check_headers(client, report, is_https=is_https)
        check_cors(client, report)
        check_info_disclosure(client, report)
        check_actuator(client, report)
        check_unauthenticated(client, report)

        authenticated = False
        if args.email and args.password:
            authenticated = login(client, report, args.email, args.password)
        else:
            report.section("Session handling")
            report.add("high", "Authenticated checks", SKIP,
                       "pass --email and --password to enable them")

        check_authorization(client, report, write_tests=args.write_tests)
        check_user_privacy(client, report)
        check_stream_auth(client, report)
        check_storage(client, report, write_tests=args.write_tests)
        if not args.no_rate_limit:
            check_rate_limit(client, report)
        check_optional_services(client, report, args)
        check_repository(args.repo_root, report)
        if args.k8s:
            check_k8s(report, args.namespace)

        if authenticated and client.access_token:
            # Last: this blacklists the test session's access token.
            try:
                r = client.post("/api/auth/logout", body={})
                report.section("Logout")
                report.check("medium", "Logout succeeds", r.status == 200, f"status {r.status}")
                after = client.get("/api/auth/me")
                report.check("high", "Logout blacklists the access token", after.status == 401,
                             f"status {after.status}")
            except TargetUnreachable as e:
                report.add("medium", "Logout", SKIP, str(e))

    except TargetUnreachable as e:
        print(f"error: {e}", file=sys.stderr)
        print("Hint: pass the app origin (frontend) or the gateway, e.g. "
              "--base-url http://localhost:8080", file=sys.stderr)
        return 2

    return _finish(report, args, color)


def _finish(report: Report, args: argparse.Namespace, color: bool) -> int:
    print("\n" + "=" * 70)
    print("SECURITY CHECK RESULTS")
    print("=" * 70)
    report.render(color)

    counts = report.counts()
    failures = report.failures(args.strict)
    print("\n" + "-" * 70)
    print(f"  PASS {counts[PASS]}   FAIL {counts[FAIL]}   WARN {counts[WARN]}   SKIP {counts[SKIP]}")
    if failures:
        print("\nBlocking findings:")
        for f in failures:
            print(f"  - [{f.severity}] {f.name}" + (f" — {f.detail}" if f.detail else ""))
    print("-" * 70)

    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump({"base_url": args.base_url, "counts": counts,
                       "findings": report.to_json()}, fh, indent=2)
        print(f"JSON written to {args.json}")

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""
Minimal standalone OIDC **client app** that integrates with Mini SSO (the IdP) over the
Authorization-Code + PKCE flow. Run it, open the page, click "Login with Mini SSO", and you are
redirected to the IdP, authenticate, and land back here with your profile — i.e. SSO into this app.

Config via env (defaults target the local IdP + the demo client registered by the test script):
  SSO_ISSUER (http://127.0.0.1:9000), CLIENT_ID, CLIENT_SECRET, REDIRECT_URI, PORT
"""
import base64
import hashlib
import http.server
import os
import secrets
import urllib.parse

import requests

ISSUER = os.environ.get("SSO_ISSUER", "http://127.0.0.1:9000")
CLIENT_ID = os.environ.get("CLIENT_ID", "portal-demo-app")
CLIENT_SECRET = os.environ.get("CLIENT_SECRET", "portal-demo-secret")
REDIRECT_URI = os.environ.get("REDIRECT_URI", "http://127.0.0.1:8088/callback")
PORT = int(os.environ.get("PORT", "8088"))

_pending = {}  # state -> code_verifier (single-process demo store)


def _decode_jwt(jwt):
    """Decode a JWT payload (no signature verification — display only)."""
    try:
        import json
        payload = jwt.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return json.loads(base64.urlsafe_b64decode(payload))
    except Exception:
        return {}


def _page(title, body):
    return (f"<!doctype html><meta charset=utf-8><title>{title}</title>"
            "<style>body{font-family:system-ui,sans-serif;max-width:640px;margin:64px auto;padding:0 20px}"
            ".btn{display:inline-block;background:#4f46e5;color:#fff;padding:10px 18px;border-radius:8px;"
            "text-decoration:none;font-weight:600}.card{border:1px solid #e2e8f0;border-radius:12px;padding:24px}"
            "pre{background:#f8fafc;padding:14px;border-radius:8px;overflow:auto}</style>"
            f"<div class=card><h1>🧩 Demo App</h1>{body}</div>").encode()


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, html, headers=None):
        self.send_response(code)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        if html is not None:
            self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        if html is not None:
            self.wfile.write(html)

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/":
            self._send(200, _page("Demo App", '<p>A sample application protected by Mini SSO.</p>'
                                  '<p><a class=btn href="/login">Login with Mini SSO →</a></p>'))
        elif path == "/login":
            verifier = secrets.token_urlsafe(48)
            challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
            state = secrets.token_urlsafe(16)
            _pending[state] = verifier
            params = urllib.parse.urlencode({
                "response_type": "code", "client_id": CLIENT_ID, "redirect_uri": REDIRECT_URI,
                "scope": "openid profile email", "state": state,
                "code_challenge": challenge, "code_challenge_method": "S256",
            })
            self._send(302, None, {"Location": f"{ISSUER}/oauth2/authorize?{params}"})
        elif path.startswith("/callback"):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if "error" in q:
                self._send(400, _page("Error", f"<p>Authorization failed: {q.get('error')}</p>"))
                return
            code, state = q.get("code", [""])[0], q.get("state", [""])[0]
            verifier = _pending.pop(state, None)
            if not code or verifier is None:
                self._send(400, _page("Error", "<p>Invalid callback (state/code).</p>"))
                return
            token = requests.post(f"{ISSUER}/oauth2/token", auth=(CLIENT_ID, CLIENT_SECRET), data={
                "grant_type": "authorization_code", "code": code,
                "redirect_uri": REDIRECT_URI, "code_verifier": verifier,
            }).json()
            import json
            claims = _decode_jwt(token.get("id_token", ""))
            self._send(200, _page("Signed in", "<p>✅ <b>Signed in via Mini SSO.</b></p>"
                                  f"<p>Welcome, <b>{claims.get('name') or claims.get('preferred_username', 'user')}</b> "
                                  f"({claims.get('email', '')})</p><h3>ID token claims</h3>"
                                  f"<pre>{json.dumps(claims, indent=2)}</pre><p><a href=\"/\">Home</a></p>"))
        else:
            self._send(404, _page("Not found", "<p>Not found.</p>"))


if __name__ == "__main__":
    print(f"Demo OIDC client on http://127.0.0.1:{PORT}  (client_id={CLIENT_ID}, issuer={ISSUER})")
    http.server.HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()

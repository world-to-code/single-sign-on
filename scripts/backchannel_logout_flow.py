#!/usr/bin/env python3
"""
Live check for OIDC Back-Channel Logout. A tiny local HTTP server stands in for a relying party's
back-channel endpoint; we register a client pointing at it, complete an authorization-code + PKCE flow
(asserting the id_token carries a `sid`), then prove the IdP POSTs a valid `logout_token` when the OP
session ends — both on explicit logout and on idle expiry (the latter with NO request, via Redis TTL).

Requires: server on :9000 (with Redis), `pip install pyjwt requests`.
Usage: python3 scripts/backchannel_logout_flow.py
"""
import base64
import hashlib
import json
import re
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs

import jwt
import requests
from jwt import PyJWKClient

from sso_auth import authenticate, cleanup, elevate, totp, _csrf_headers

BASE = "http://localhost:9000"
RECEIVER_PORT = 8899
RECEIVER = f"http://127.0.0.1:{RECEIVER_PORT}"
EVENT_TYPE = "http://schemas.openid.net/event/backchannel-logout"

# Captured logout_tokens, newest last. Guarded by the GIL (simple append/read).
received: list[str] = []


class Receiver(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode()
        token = parse_qs(body).get("logout_token", [None])[0]
        if token:
            received.append(token)
        self.send_response(200)
        self.end_headers()

    def log_message(self, *args):
        pass  # quiet


def start_receiver() -> HTTPServer:
    server = HTTPServer(("127.0.0.1", RECEIVER_PORT), Receiver)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def pkce() -> tuple[str, str]:
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    return verifier, challenge


def id_token_via_authcode(session: requests.Session, client_id: str) -> dict:
    """Runs authorize + PKCE + token exchange for a public client; returns the decoded id_token claims."""
    verifier, challenge = pkce()
    redirect_uri = f"{RECEIVER}/callback"
    authz = session.get(f"{BASE}/oauth2/authorize", params={
        "response_type": "code", "client_id": client_id, "scope": "openid profile",
        "redirect_uri": redirect_uri, "state": "s", "nonce": "n",
        "code_challenge": challenge, "code_challenge_method": "S256",
    }, allow_redirects=False)
    location = authz.headers.get("Location", "")
    code = re.search(r"[?&]code=([^&]+)", location)
    if not code:
        raise SystemExit(f"no authorization code: {authz.status_code} {location} {authz.text[:200]}")
    token = requests.post(f"{BASE}/oauth2/token", data={
        "grant_type": "authorization_code", "code": code.group(1),
        "redirect_uri": redirect_uri, "client_id": client_id, "code_verifier": verifier,
    })
    if token.status_code != 200 or "id_token" not in token.json():
        raise SystemExit(f"token exchange failed: {token.status_code} {token.text}")
    payload = token.json()["id_token"].split(".")[1]
    payload += "=" * ((-len(payload)) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


def mfa_login(session: requests.Session, base: str, username: str, password: str, secret: str) -> None:
    """Re-establishes an MFA session for an ALREADY-enrolled user (login + TOTP verify, no re-enrollment)."""
    window = int(time.time() // 30)  # avoid TOTP replay: use a code not spent in this window
    while int(time.time() // 30) == window:
        time.sleep(1)
    session.get(f"{base}/api/auth/session")
    login = session.post(f"{base}/api/auth/login", json={"username": username, "password": password},
                         headers=_csrf_headers(session))
    if login.status_code != 200:
        raise SystemExit(f"re-login failed: {login.status_code} {login.text}")
    verify = session.post(f"{base}/api/auth/factors/totp/verify", json={"code": totp(secret)},
                          headers=_csrf_headers(session))
    if verify.status_code != 200 or verify.json().get("next") != "DONE":
        raise SystemExit(f"re-login MFA failed: {verify.status_code} {verify.text}")


def wait_for_logout_token(since: int, timeout: float) -> str | None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if len(received) > since:
            return received[-1]
        time.sleep(0.5)
    return None


def validate(logout_token: str, client_id: str, expected_sid: str) -> None:
    key = PyJWKClient(f"{BASE}/oauth2/jwks").get_signing_key_from_jwt(logout_token)
    claims = jwt.decode(logout_token, key.key, algorithms=["RS256"], audience=client_id)
    assert claims["iss"] == BASE, claims
    assert claims["sub"], "logout_token needs a sub"
    assert claims.get("sid") == expected_sid, f"sid mismatch: {claims.get('sid')} != {expected_sid}"
    assert EVENT_TYPE in claims.get("events", {}), f"missing back-channel-logout event: {claims}"
    assert claims.get("jti"), "logout_token needs a jti"
    assert "nonce" not in claims, "logout_token must NOT carry a nonce"
    print(f"[ok] logout_token validated: aud={client_id} sub={claims['sub']} sid={claims['sid']}")


def main() -> int:
    start_receiver()
    admin = requests.Session()
    secret = authenticate(admin, BASE)
    admin.headers["Authorization"] = f"Bearer {elevate(admin, BASE, secret)}"
    print("[ok] admin session established + elevated")

    stamp = int(time.time())
    client_id = f"bcl-client-{stamp}"
    created = admin.post(f"{BASE}/api/admin/clients", headers=_csrf_headers(admin), json={
        "clientId": client_id, "clientName": "BCL test", "redirectUris": [f"{RECEIVER}/callback"],
        "scopes": ["openid", "profile"], "grantTypes": ["authorization_code"],
        "publicClient": True, "requireConsent": False, "requireProofKey": True,
        "backchannelLogoutUri": f"{RECEIVER}/backchannel", "backchannelLogoutSessionRequired": True,
    })
    if created.status_code != 201:
        raise SystemExit(f"client registration failed: {created.status_code} {created.text}")
    print(f"[ok] registered public client {client_id} with a back-channel logout URI")

    tw_id = None
    policy_id = None
    try:
        # --- All admin setup FIRST (the admin session is logged out in Check A) ---
        tw_name = f"bcl-idle-{stamp}"
        tw = admin.post(f"{BASE}/api/admin/users", headers=_csrf_headers(admin), json={
            "username": tw_name, "email": f"{tw_name}@example.com", "displayName": "Idle",
            "password": "Idle-pass-1!", "roles": ["ROLE_USER"]})
        assert tw.status_code == 201, f"create throwaway: {tw.status_code} {tw.text}"
        tw_id = tw.json()["id"]
        # A session policy with the minimum idle timeout (1 min) targeting the throwaway user.
        pol = admin.post(f"{BASE}/api/admin/session-policies", headers=_csrf_headers(admin), json={
            "name": f"bcl-idle-pol-{stamp}", "priority": 500, "enabled": True,
            "absoluteTimeoutMinutes": 480, "idleTimeoutMinutes": 1, "reauthIntervalMinutes": 1440,
            "reauthFactors": "TOTP,FIDO2", "sensitiveReauthWindowMinutes": 2, "stepUpFactors": "TOTP,FIDO2",
            "bindClient": True, "maxConcurrentSessions": 0, "rotateOnReauth": True, "cookieSameSite": "Lax",
            "assignedUserIds": [tw_id], "assignedRoleIds": [], "ipRules": []})
        assert pol.status_code == 201, f"create idle policy: {pol.status_code} {pol.text}"
        policy_id = pol.json()["id"]

        # --- Check B setup: the throwaway's own idle session + a token from the client ---
        tws = requests.Session()
        authenticate(tws, BASE, tw_name, "Idle-pass-1!")
        tws.get(f"{BASE}/api/me")  # a request under the 1-min-idle policy -> Redis TTL ~120s
        idle_sid = id_token_via_authcode(tws, client_id)["sid"]
        print(f"[ok] throwaway idle session established (sid={idle_sid}); idle clock running")

        # --- Check A: explicit logout propagates a valid logout_token (admin session ends here) ---
        sid = id_token_via_authcode(admin, client_id).get("sid")
        assert sid, "admin id_token is missing sid"
        print(f"[ok] admin id_token carries sid={sid}")
        before = len(received)
        admin.post(f"{BASE}/api/auth/logout", headers=_csrf_headers(admin))
        token = wait_for_logout_token(before, timeout=15)
        assert token, "no logout_token arrived after explicit logout"
        validate(token, client_id, sid)
        print("[PASS] explicit logout -> back-channel logout_token delivered + validated")

        # --- Check B: idle expiry propagates with NO request (Redis TTL -> SessionExpiredEvent) ---
        print("[..] waiting for the throwaway's idle expiry (no requests, ~2 min)...")
        before = len(received)  # after Check A's token, so we wait for the NEXT (idle) one
        token = wait_for_logout_token(before, timeout=180)  # 1-min idle + 60s grace + margin
        assert token, "no logout_token arrived from idle expiry"
        validate(token, client_id, idle_sid)
        print("[PASS] idle expiry (no request) -> back-channel logout_token delivered + validated")

        print("\nALL BACK-CHANNEL LOGOUT CHECKS PASSED")
        return 0
    finally:
        recover = requests.Session()
        mfa_login(recover, BASE, "admin", "admin123!", secret)
        recover.headers["Authorization"] = f"Bearer {elevate(recover, BASE, secret)}"
        try:
            row = next((c for c in recover.get(f"{BASE}/api/admin/clients?size=200").json()["items"]
                        if c["clientId"] == client_id), None)
            if row:
                recover.delete(f"{BASE}/api/admin/clients/{row['id']}", headers=_csrf_headers(recover))
            if policy_id:
                recover.delete(f"{BASE}/api/admin/session-policies/{policy_id}", headers=_csrf_headers(recover))
            if tw_id:
                recover.delete(f"{BASE}/api/admin/users/{tw_id}", headers=_csrf_headers(recover))
        except Exception as e:
            print("[warn] cleanup issue:", e)
        cleanup(recover, BASE)


if __name__ == "__main__":
    sys.exit(main())

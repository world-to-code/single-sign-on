"""
Shared helper: drive the JSON auth flow (session cookie + CSRF) to a fully MFA-authenticated
session. Uses the seeded admin (email pre-verified). TOTP is (re)enrolled each run so the
script always holds the secret and the flow is repeatable.
"""
import base64
import hashlib
import hmac
import re
import secrets
import struct
import time

import requests

ADMIN_CLIENT = "admin-console"
ADMIN_REDIRECT = "http://localhost:9000/admin/callback"


def totp(secret_b32: str) -> str:
    key = base64.b32decode(secret_b32 + "=" * ((-len(secret_b32)) % 8))
    digest = hmac.new(key, struct.pack(">Q", int(time.time() // 30)), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    return f"{(struct.unpack('>I', digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1_000_000:06d}"


def _csrf_headers(session: requests.Session) -> dict:
    token = session.cookies.get("XSRF-TOKEN")
    return {"X-XSRF-TOKEN": token} if token else {}


def _mailhog_code() -> str:
    messages = requests.get("http://localhost:8025/api/v2/messages").json()
    body = messages["items"][0]["Content"]["Body"]
    return re.search(r"(\d{6})", body).group(1)


def authenticate(session: requests.Session, base: str,
                 username: str = "admin", password: str = "admin123!", org: str = "default") -> str:
    """Logs in and completes MFA, returning the TOTP secret used. Raises on failure."""
    session.get(f"{base}/api/auth/session")  # establishes the XSRF cookie
    # Tenant-first: select the organization before the account (the org is the tenant).
    org_resp = session.post(f"{base}/api/auth/organization",
                            json={"slug": org}, headers=_csrf_headers(session))
    if org_resp.status_code != 200:
        raise SystemExit(f"organization selection failed: {org_resp.status_code}")
    resp = session.post(f"{base}/api/auth/login",
                        json={"username": username, "password": password},
                        headers=_csrf_headers(session))
    if resp.status_code != 200:
        raise SystemExit(f"login failed: {resp.status_code}")

    # Default policy is password -> TOTP; prepare issues the enrollment secret + QR, verify confirms it.
    prepare = session.post(f"{base}/api/auth/factors/TOTP/prepare", headers=_csrf_headers(session)).json()
    secret = prepare["secret"]
    confirm = session.post(f"{base}/api/auth/factors/TOTP/verify",
                           json={"code": totp(secret)}, headers=_csrf_headers(session))
    if confirm.status_code != 200 or confirm.json().get("next") != "DONE":
        raise SystemExit(f"MFA completion failed: {confirm.status_code} {confirm.text}")
    return secret


def elevate(session: requests.Session, base: str, totp_secret: str) -> str:
    """
    Acquire the admin-console elevation bearer the /api/admin/** gate (AdminElevationFilter) requires:
    a DELIBERATE step-up (stamps stepup_time) followed by the admin-console OIDC + PKCE flow. Returns the
    access token; attach it as `Authorization: Bearer <token>` on admin API requests.
    """
    # TOTP replay protection rejects a code already spent (login just used this 30s window's code);
    # wait for a fresh window so the deliberate step-up presents an unused code.
    window = int(time.time() // 30)
    while int(time.time() // 30) == window:
        time.sleep(1)
    reauth = session.post(f"{base}/api/auth/reauth/TOTP/verify",
                          json={"code": totp(totp_secret)}, headers=_csrf_headers(session))
    if reauth.status_code != 200:
        raise SystemExit(f"deliberate step-up failed: {reauth.status_code} {reauth.text}")

    verifier = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    authz = session.get(f"{base}/oauth2/authorize", params={
        "response_type": "code", "client_id": ADMIN_CLIENT, "scope": "openid profile admin",
        "redirect_uri": ADMIN_REDIRECT, "state": "elev",
        "code_challenge": challenge, "code_challenge_method": "S256",
    }, allow_redirects=False)
    if authz.status_code == 200 and "consent" in authz.text.lower():
        state = re.search(r'name="state"[^>]*value="([^"]+)"', authz.text).group(1)
        authz = session.post(f"{base}/oauth2/authorize",
                             data={"client_id": ADMIN_CLIENT, "state": state, "scope": ["profile", "admin"]},
                             allow_redirects=False)
    location = authz.headers.get("Location", "")
    code = re.search(r"[?&]code=([^&]+)", location)
    if not code:
        raise SystemExit(f"no admin authorization code: {authz.status_code} {location} {authz.text[:200]}")

    token = requests.post(f"{base}/oauth2/token", data={
        "grant_type": "authorization_code", "code": code.group(1),
        "redirect_uri": ADMIN_REDIRECT, "client_id": ADMIN_CLIENT, "code_verifier": verifier,
    })
    if token.status_code != 200 or "access_token" not in token.json():
        raise SystemExit(f"admin token exchange failed: {token.status_code} {token.text}")
    return token.json()["access_token"]


def cleanup(session: requests.Session, base: str, username: str = "admin") -> None:
    """Resets the user's MFA so a human isn't left with a script-enrolled TOTP secret."""
    try:
        users = session.get(f"{base}/api/admin/users?size=100").json()["items"]
        target = next((u for u in users if u["username"] == username), None)
        if target:
            session.post(f"{base}/api/admin/users/{target['id']}/reset-mfa", headers=_csrf_headers(session))
    except Exception:
        pass

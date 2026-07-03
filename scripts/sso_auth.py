"""
Shared helper: drive the JSON auth flow (session cookie + CSRF) to a fully MFA-authenticated
session. Uses the seeded admin (email pre-verified). TOTP is (re)enrolled each run so the
script always holds the secret and the flow is repeatable.
"""
import base64
import hashlib
import hmac
import re
import struct
import time

import requests


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
                 username: str = "admin", password: str = "admin123!") -> str:
    """Logs in and completes MFA, returning the TOTP secret used. Raises on failure."""
    session.get(f"{base}/api/auth/session")  # establishes the XSRF cookie
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


def cleanup(session: requests.Session, base: str, username: str = "admin") -> None:
    """Resets the user's MFA so a human isn't left with a script-enrolled TOTP secret."""
    try:
        users = session.get(f"{base}/api/admin/users?size=100").json()["items"]
        target = next((u for u in users if u["username"] == username), None)
        if target:
            session.post(f"{base}/api/admin/users/{target['id']}/reset-mfa", headers=_csrf_headers(session))
    except Exception:
        pass

#!/usr/bin/env python3
"""
OIDC authorization-code + PKCE flow against the live Mini SSO server, reusing an
MFA-authenticated browser session. Verifies a valid ID token is issued.

Usage: python3 scripts/oidc_authcode_flow.py  (server running on :9000)
"""
import base64
import hashlib
import json
import re
import secrets
import sys
from urllib.parse import parse_qs, urlparse

import requests

from sso_auth import _csrf_headers, authenticate, cleanup

BASE = "http://localhost:9000"
CLIENT_ID, CLIENT_SECRET = "demo-client", "demo-secret"
REDIRECT_URI = "http://127.0.0.1:8080/login/oauth2/code/demo"


def main() -> int:
    s = requests.Session()
    authenticate(s, BASE)
    print("[ok] MFA session established")

    verifier = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    authz = s.get(f"{BASE}/oauth2/authorize", params={
        "response_type": "code", "client_id": CLIENT_ID, "scope": "openid profile email",
        "redirect_uri": REDIRECT_URI, "state": "xyz",
        "code_challenge": challenge, "code_challenge_method": "S256",
    }, allow_redirects=False)

    # Consent may be served inline (200 with a form) or as a redirect to the SPA consent page
    # (302 to /oauth2/consent?...&state=...). Handle both, then POST the granted scopes back.
    consent_location = authz.headers.get("Location", "")
    if authz.status_code == 200 and "consent" in authz.text.lower():
        state = re.search(r'name="state"[^>]*value="([^"]+)"', authz.text).group(1)
    elif "/oauth2/consent" in consent_location:
        state = parse_qs(urlparse(consent_location).query)["state"][0]
    else:
        state = None
    if state is not None:
        authz = s.post(f"{BASE}/oauth2/authorize",
                       data={"client_id": CLIENT_ID, "state": state, "scope": ["profile", "email"]},
                       headers=_csrf_headers(s), allow_redirects=False)

    location = authz.headers.get("Location", "")
    match = re.search(r"[?&]code=([^&]+)", location)
    if not match:
        raise SystemExit(f"no authorization code: {authz.status_code} {location}")
    print("[ok] received authorization code (PKCE + consent)")

    token = requests.post(f"{BASE}/oauth2/token", auth=(CLIENT_ID, CLIENT_SECRET), data={
        "grant_type": "authorization_code", "code": match.group(1),
        "redirect_uri": REDIRECT_URI, "code_verifier": verifier,
    })
    if token.status_code != 200:
        raise SystemExit(f"token exchange failed: {token.status_code} {token.text}")

    payload = token.json()["id_token"].split(".")[1]
    payload += "=" * ((-len(payload)) % 4)
    claims = json.loads(base64.urlsafe_b64decode(payload))
    print(f"[ok] id_token claims: iss={claims['iss']} sub={claims['sub']} aud={claims['aud']}")
    print(f"[claims] amr={claims.get('amr')} acr={claims.get('acr')} auth_time={claims.get('auth_time')}")
    assert claims["iss"] == BASE and claims["sub"] == "admin"
    cleanup(s, BASE)
    print("\nPASS: OIDC authorization-code + PKCE + MFA flow issued a valid ID token.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

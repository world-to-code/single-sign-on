#!/usr/bin/env python3
"""
Tenant-first login -> OIDC ID token proof. Signs in through the organization-first flow and asserts the
issued ID token carries the `org` claim (the tenant id this session logged into), which is symmetric with
the SAML `org` assertion attribute. Proves that tenant selection propagates all the way into the token a
relying party receives.

Usage: python3 scripts/tenant_login_flow.py  (server running on :9000)
"""
import base64
import hashlib
import json
import re
import secrets
import sys
import uuid
from urllib.parse import parse_qs, urlparse

import requests

from sso_auth import _csrf_headers, authenticate, cleanup

BASE = "http://localhost:9000"
CLIENT_ID, CLIENT_SECRET = "demo-client", "demo-secret"
REDIRECT_URI = "http://127.0.0.1:8080/login/oauth2/code/demo"
ORG = "default"


def main() -> int:
    s = requests.Session()
    authenticate(s, BASE, org=ORG)
    print(f"[ok] tenant-first MFA session established (org={ORG})")

    verifier = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    authz = s.get(f"{BASE}/oauth2/authorize", params={
        "response_type": "code", "client_id": CLIENT_ID, "scope": "openid profile email",
        "redirect_uri": REDIRECT_URI, "state": "xyz",
        "code_challenge": challenge, "code_challenge_method": "S256",
    }, allow_redirects=False)

    # Grant consent whether served inline (200) or as a redirect to the SPA consent page (302).
    location = authz.headers.get("Location", "")
    if authz.status_code == 200 and "consent" in authz.text.lower():
        state = re.search(r'name="state"[^>]*value="([^"]+)"', authz.text).group(1)
    elif "/oauth2/consent" in location:
        state = parse_qs(urlparse(location).query)["state"][0]
    else:
        state = None
    if state is not None:
        authz = s.post(f"{BASE}/oauth2/authorize",
                       data={"client_id": CLIENT_ID, "state": state, "scope": ["profile", "email"]},
                       headers=_csrf_headers(s), allow_redirects=False)

    match = re.search(r"[?&]code=([^&]+)", authz.headers.get("Location", ""))
    if not match:
        raise SystemExit(f"no authorization code: {authz.status_code} {authz.headers.get('Location', '')}")

    token = requests.post(f"{BASE}/oauth2/token", auth=(CLIENT_ID, CLIENT_SECRET), data={
        "grant_type": "authorization_code", "code": match.group(1),
        "redirect_uri": REDIRECT_URI, "code_verifier": verifier,
    })
    if token.status_code != 200:
        raise SystemExit(f"token exchange failed: {token.status_code} {token.text}")

    payload = token.json()["id_token"].split(".")[1]
    payload += "=" * ((-len(payload)) % 4)
    claims = json.loads(base64.urlsafe_b64decode(payload))
    org = claims.get("org")
    print(f"[claims] sub={claims['sub']} iss={claims['iss']} org={org}")

    assert org is not None, "the ID token is missing the `org` (tenant) claim after a tenant-first login"
    uuid.UUID(org)  # the claim must be the organization's id, not a slug or marker
    cleanup(s, BASE)
    print("\nPASS: tenant-first login issued an ID token carrying the `org` (tenant) claim.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

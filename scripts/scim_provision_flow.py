#!/usr/bin/env python3
"""
SCIM 2.0 inbound-provisioning smoke test.

Acts as an external IdP/HR system pushing a user into this IdP over SCIM, then proves the
provisioned user is a real, identifiable account (and that deprovisioning removes them).
No container needed — SCIM is inbound, so a bearer-authenticated client is all it takes.

Usage:  python3 scripts/scim_provision_flow.py
Env:    SSO_BASE (default http://localhost:9000), SCIM_TOKEN (default dev-scim-token)
"""
import http.cookiejar
import json
import os
import sys
import urllib.request

BASE = os.environ.get("SSO_BASE", "http://localhost:9000")
TOKEN = os.environ.get("SCIM_TOKEN", "dev-scim-token")
SCIM = f"{BASE}/scim/v2"
USER = "scim.alice@example.com"


def call(method, url, body=None, token=TOKEN, ctype="application/scim+json"):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    if data:
        req.add_header("Content-Type", ctype)
    req.add_header("Accept", "application/scim+json")
    try:
        with urllib.request.urlopen(req) as r:
            txt = r.read().decode()
            return r.status, (json.loads(txt) if txt else {})
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            return e.code, json.loads(txt)
        except Exception:
            return e.code, {"raw": txt[:200]}


def identify(email, org="default"):
    # The app's identifier-first endpoint: 200 => active account exists, 404 => unknown/disabled.
    # It lives on the CSRF-protected session chain, so do the double-submit handshake: GET /session
    # to obtain the XSRF-TOKEN cookie, then echo it in the X-XSRF-TOKEN header. Login is tenant-first,
    # so select the organization the SCIM token provisions into before probing the account.
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    opener.open(f"{BASE}/api/auth/session")
    xsrf = next((c.value for c in jar if c.name == "XSRF-TOKEN"), None)

    def post(path, body):
        r = urllib.request.Request(f"{BASE}{path}", data=json.dumps(body).encode(), method="POST")
        r.add_header("Content-Type", "application/json")
        if xsrf:
            r.add_header("X-XSRF-TOKEN", xsrf)
        return r

    opener.open(post("/api/auth/organization", {"slug": org}))  # tenant-first: bind the org
    try:
        with opener.open(post("/api/auth/identify", {"email": email})) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


def main():
    print(f"SCIM endpoint: {SCIM}\n")

    print("[1] ServiceProviderConfig (capabilities)")
    st, cfg = call("GET", f"{SCIM}/ServiceProviderConfig")
    print(f"    GET ServiceProviderConfig -> {st} | patch={cfg.get('patch', {}).get('supported')} "
          f"filter={cfg.get('filter', {}).get('supported')}")

    print("\n[2] Provision (create) a user")
    payload = {
        "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
        "userName": USER,
        "name": {"givenName": "Alice", "familyName": "Lee"},
        "displayName": "Alice Lee",
        "emails": [{"value": USER, "primary": True}],
        "active": True,
    }
    st, created = call("POST", f"{SCIM}/Users", payload)
    print(f"    POST Users -> {st} | id={created.get('id')} active={created.get('active')}")
    if st not in (200, 201):
        print("    create failed:", created)
        sys.exit(1)
    uid = created["id"]

    print("\n[3] Provisioned user is now a real, identifiable account")
    print(f"    POST /api/auth/identify({USER}) -> {identify(USER)} (expect 200)")

    print("\n[4] Read + filter")
    st, got = call("GET", f"{SCIM}/Users/{uid}")
    print(f"    GET Users/{{id}} -> {st} | userName={got.get('userName')}")
    st, page = call("GET", f"{SCIM}/Users?filter=userName%20eq%20%22{USER}%22")
    print(f"    GET Users?filter=userName eq -> {st} | totalResults={page.get('totalResults')}")

    print("\n[5] Update (PATCH active=false => account disabled)")
    patch = {
        "schemas": ["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
        "Operations": [{"op": "replace", "path": "active", "value": False}],
    }
    st, _ = call("PATCH", f"{SCIM}/Users/{uid}", patch)
    print(f"    PATCH active=false -> {st}")
    print(f"    POST /api/auth/identify({USER}) -> {identify(USER)} (expect 404: disabled)")

    print("\n[6] Deprovision (DELETE)")
    st, _ = call("DELETE", f"{SCIM}/Users/{uid}")
    print(f"    DELETE Users/{{id}} -> {st} (expect 204)")
    print(f"    POST /api/auth/identify({USER}) -> {identify(USER)} (expect 404: gone)")

    print("\n[7] AuthZ: a request without a bearer token is rejected")
    st, _ = call("GET", f"{SCIM}/Users", token=None)
    print(f"    GET Users (no token) -> {st} (expect 401)")

    print("\nDone.")


if __name__ == "__main__":
    main()

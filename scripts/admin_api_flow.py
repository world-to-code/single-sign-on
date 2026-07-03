#!/usr/bin/env python3
"""
Admin API via the session (the console authenticates with the IdP session, not a token).
Verifies ROLE_ADMIN access and the user-lifecycle + provisioning endpoints.

Usage: python3 scripts/admin_api_flow.py  (server running on :9000)
"""
import sys

import requests

from sso_auth import authenticate, cleanup, _csrf_headers

BASE = "http://localhost:9000"


def main() -> int:
    s = requests.Session()
    authenticate(s, BASE)
    print("[ok] MFA session established (ROLE_ADMIN)")

    # Anonymous request is rejected.
    if requests.get(f"{BASE}/api/admin/users").status_code != 401:
        raise SystemExit("admin API did not reject anonymous request")
    print("[ok] admin API rejects anonymous (401)")

    for path in ("/api/admin/users", "/api/admin/clients",
                 "/api/admin/saml/relying-parties", "/api/admin/audit"):
        r = s.get(f"{BASE}{path}")
        if r.status_code != 200:
            raise SystemExit(f"{path} failed: {r.status_code} {r.text}")
        print(f"[ok] {path} -> {r.json()['total']} rows")

    issued = s.post(f"{BASE}/api/admin/scim/tokens",
                    json={"description": "console-issued", "ttlDays": 30},
                    headers=_csrf_headers(s))
    if issued.status_code != 200 or "token" not in issued.json():
        raise SystemExit(f"scim token issue failed: {issued.status_code} {issued.text}")
    print("[ok] issued a SCIM token via admin API")

    # RBAC + PBAC: roles carry permissions.
    roles = s.get(f"{BASE}/api/admin/roles").json()
    admin_role = next(r for r in roles if r["name"] == "ROLE_ADMIN")
    print(f"[ok] ROLE_ADMIN permissions: {admin_role['permissions']}")
    assert "user:write" in admin_role["permissions"]

    # User lifecycle: create -> disable -> delete.
    import time as _t
    uname = f"lifecycle-{int(_t.time())}"
    created = s.post(f"{BASE}/api/admin/users", headers=_csrf_headers(s), json={
        "username": uname, "email": f"{uname}@example.com",
        "displayName": "Lifecycle", "password": "Temp-pass-1!", "roles": ["ROLE_USER"]})
    if created.status_code != 201:
        raise SystemExit(f"create user failed: {created.status_code} {created.text}")
    user_id = created.json()["id"]
    print(f"[ok] created user {uname}")

    disabled = s.post(f"{BASE}/api/admin/users/{user_id}/enabled",
                      headers=_csrf_headers(s), json={"enabled": False})
    assert disabled.status_code == 200 and disabled.json()["enabled"] is False
    print("[ok] disabled user")

    deleted = s.delete(f"{BASE}/api/admin/users/{user_id}", headers=_csrf_headers(s))
    assert deleted.status_code == 204
    print("[ok] deleted user")

    # OAuth2/OIDC client registration (for Keycloak/RP integration).
    perms = s.get(f"{BASE}/api/admin/permissions").json()
    assert "client:write" in perms
    cid = f"rp-{int(_t.time())}"
    created_client = s.post(f"{BASE}/api/admin/clients", headers=_csrf_headers(s), json={
        "clientId": cid, "clientName": "Test RP",
        "redirectUris": ["https://app.example.com/login/oauth2/code/mini"],
        "scopes": ["openid", "profile"], "grantTypes": ["authorization_code", "refresh_token"],
        "publicClient": False, "requireConsent": True})
    assert created_client.status_code == 201 and created_client.json()["clientSecret"]
    print(f"[ok] registered OAuth2 client {cid} (secret returned once)")
    row = next(c for c in s.get(f"{BASE}/api/admin/clients?size=100").json()["items"] if c["clientId"] == cid)
    assert "app.example.com" in row["redirectUris"]
    assert s.delete(f"{BASE}/api/admin/clients/{row['id']}", headers=_csrf_headers(s)).status_code == 204
    print("[ok] deleted client")

    # Per-user direct permissions (Okta/AWS-style).
    pname = f"perm-{int(_t.time())}"
    puser = s.post(f"{BASE}/api/admin/users", headers=_csrf_headers(s), json={
        "username": pname, "email": f"{pname}@example.com", "displayName": "P",
        "password": "Temp-pass-1!", "roles": ["ROLE_USER"]}).json()
    updated = s.put(f"{BASE}/api/admin/users/{puser['id']}/permissions",
                    headers=_csrf_headers(s), json={"permissions": ["audit:read"]}).json()
    assert "audit:read" in updated["directPermissions"]
    print(f"[ok] set direct permissions on user: {updated['directPermissions']}")
    s.delete(f"{BASE}/api/admin/users/{puser['id']}", headers=_csrf_headers(s))

    cleanup(s, BASE)
    print("\nPASS: admin API (session, ROLE_ADMIN, RBAC+PBAC, user lifecycle) works end-to-end.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

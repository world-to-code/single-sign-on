#!/usr/bin/env python3
"""
Live check for Network Zones + per-policy IP enforcement.

To avoid locking out the admin (the IP filter has no exempt paths — blocking your own IP means you
cannot un-block via the API), enforcement is exercised against a THROWAWAY user, while the admin
session (which resolves to the Default policy, no IP rules) does all setup/teardown and stays reachable.

Verifies: zone CRUD + validation; a policy referencing zones (allow/block, first-match) round-trips;
the throwaway user is ALLOWED from loopback and, after flipping to BLOCK, is refused 403 on /api AND on
the OIDC /oauth2/authorize chain WITHOUT losing its session; a zone in use cannot be deleted (409); the
admin is never affected.

Usage: python3 scripts/network_zone_flow.py   (server on :9000)
"""
import sys
import time

import requests

from sso_auth import authenticate, cleanup, elevate, _csrf_headers

BASE = "http://localhost:9000"


def h(s):
    return _csrf_headers(s)


def main() -> int:
    admin = requests.Session()
    secret = authenticate(admin, BASE)
    admin.headers["Authorization"] = f"Bearer {elevate(admin, BASE, secret)}"  # /api/admin/** elevation gate
    print("[ok] admin session established + elevated (resolves to Default — never IP-restricted here)")

    stamp = int(time.time())
    tw_name = f"iptest-{stamp}"
    tw_pass = "Temp-pass-1!"
    tw_id = None
    policy_id = None
    zone_ids = []
    tw = requests.Session()
    try:
        # --- 1. Zone CRUD + validation (admin) ---
        bad = admin.post(f"{BASE}/api/admin/network-zones", headers=h(admin),
                         json={"name": f"bad-{stamp}", "cidrs": ["not-a-cidr"]})
        assert bad.status_code == 400 and "invalid CIDR" in bad.text, f"expected 400 invalid CIDR: {bad.status_code} {bad.text}"
        print("[ok] zone create rejects invalid CIDR (400):", bad.json().get("detail"))

        empty = admin.post(f"{BASE}/api/admin/network-zones", headers=h(admin),
                           json={"name": f"empty-{stamp}", "cidrs": []})
        assert empty.status_code == 400, f"expected 400 empty cidrs: {empty.status_code}"
        print("[ok] zone create rejects empty CIDR list (400)")

        loop = admin.post(f"{BASE}/api/admin/network-zones", headers=h(admin),
                          json={"name": f"Loopback-{stamp}", "description": "this host",
                                "cidrs": ["127.0.0.1/32", "::1/128"]})
        assert loop.status_code == 201, f"create loopback zone: {loop.status_code} {loop.text}"
        loop_id = loop.json()["id"]; zone_ids.append(loop_id)
        every = admin.post(f"{BASE}/api/admin/network-zones", headers=h(admin),
                           json={"name": f"Everywhere-{stamp}", "cidrs": ["0.0.0.0/0", "::/0"]})
        assert every.status_code == 201, f"create everywhere zone: {every.text}"
        every_id = every.json()["id"]; zone_ids.append(every_id)
        print(f"[ok] created zones: Loopback {loop.json()['cidrs']}, Everywhere (v4+v6 catch-all)")

        # --- 2. Throwaway user + its own MFA session ---
        created = admin.post(f"{BASE}/api/admin/users", headers=h(admin), json={
            "username": tw_name, "email": f"{tw_name}@example.com", "displayName": "IP Test",
            "password": tw_pass, "roles": ["ROLE_USER"]})
        assert created.status_code == 201, f"create throwaway user: {created.status_code} {created.text}"
        tw_id = created.json()["id"]
        authenticate(tw, BASE, tw_name, tw_pass)
        assert tw.get(f"{BASE}/api/me").status_code == 200, "throwaway should reach /api/me before any IP rule"
        print(f"[ok] throwaway user {tw_name} created + MFA session established")

        # --- 3. Policy for the throwaway user: ALLOW Loopback, then BLOCK Everywhere (first-match) ---
        def policy(ip_rules):
            return {
                "name": f"IT-IP-{stamp}", "priority": 100, "enabled": True,
                "absoluteTimeoutMinutes": 480, "idleTimeoutMinutes": 30, "reauthIntervalMinutes": 5,
                "reauthFactors": "TOTP,FIDO2", "sensitiveReauthWindowMinutes": 2, "stepUpFactors": "TOTP,FIDO2",
                "bindClient": True, "maxConcurrentSessions": 0, "rotateOnReauth": True, "cookieSameSite": "Lax",
                "assignedUserIds": [tw_id], "assignedRoleIds": [], "ipRules": ip_rules,
            }

        allow_first = [{"zoneId": loop_id, "action": "ALLOW", "priority": 0},
                       {"zoneId": every_id, "action": "BLOCK", "priority": 1}]
        pol = admin.post(f"{BASE}/api/admin/session-policies", headers=h(admin), json=policy(allow_first))
        assert pol.status_code == 201, f"create policy: {pol.status_code} {pol.text}"
        policy_id = pol.json()["id"]
        assert [r["action"] for r in pol.json()["ipRules"]] == ["ALLOW", "BLOCK"], pol.json()["ipRules"]
        assert [r["zoneId"] for r in pol.json()["ipRules"]] == [loop_id, every_id]
        print("[ok] policy round-trips zone rules in order (ALLOW Loopback, BLOCK Everywhere)")

        # Throwaway is on loopback → matches ALLOW first → permitted on /api and OIDC.
        assert tw.get(f"{BASE}/api/me").status_code == 200, "loopback ALLOW should permit /api/me"
        authz = tw.get(f"{BASE}/oauth2/authorize",
                       params={"response_type": "code", "client_id": "nope"}, allow_redirects=False)
        assert authz.status_code != 403, f"loopback ALLOW should not 403 /oauth2/authorize: {authz.status_code}"
        # Admin (Default policy) is unaffected throughout.
        assert admin.get(f"{BASE}/api/admin/users").status_code == 200, "admin must never be IP-restricted"
        print(f"[ok] loopback ALLOWED: throwaway /api/me 200, /oauth2/authorize {authz.status_code}; admin unaffected")

        # --- 4. A zone in use cannot be deleted (409) ---
        used = admin.delete(f"{BASE}/api/admin/network-zones/{loop_id}", headers=h(admin))
        assert used.status_code == 409, f"expected 409 deleting referenced zone: {used.status_code} {used.text}"
        print("[ok] referenced zone delete refused (409):", used.json().get("detail"))

        # --- 5. Flip to BLOCK Loopback → throwaway is 403 on BOTH chains, session NOT invalidated ---
        upd = admin.put(f"{BASE}/api/admin/session-policies/{policy_id}", headers=h(admin),
                        json=policy([{"zoneId": loop_id, "action": "BLOCK", "priority": 0}]))
        assert upd.status_code == 200, f"policy update: {upd.status_code} {upd.text}"
        blocked = tw.get(f"{BASE}/api/me")
        assert blocked.status_code == 403 and "network" in blocked.text.lower(), f"expected IP 403: {blocked.status_code} {blocked.text}"
        authz2 = tw.get(f"{BASE}/oauth2/authorize",
                        params={"response_type": "code", "client_id": "nope"}, allow_redirects=False)
        assert authz2.status_code == 403, f"blocked IP should 403 /oauth2/authorize: {authz2.status_code}"
        # 403 (IP), not 401 (dead session): the session object is untouched. Admin still fine.
        sess = tw.get(f"{BASE}/api/auth/session")
        assert sess.status_code == 403, f"blocked → 403 (not a 401 logout): {sess.status_code}"
        assert admin.get(f"{BASE}/api/admin/users").status_code == 200, "admin still unaffected while throwaway blocked"
        print("[ok] loopback BLOCKED: throwaway /api 403 AND /oauth2/authorize 403 (session kept); admin still 200")

        print("\nALL NETWORK-ZONE CHECKS PASSED")
        return 0
    finally:
        try:
            if policy_id:
                admin.delete(f"{BASE}/api/admin/session-policies/{policy_id}", headers=h(admin))
            for zid in zone_ids:
                admin.delete(f"{BASE}/api/admin/network-zones/{zid}", headers=h(admin))
            if tw_id:
                admin.delete(f"{BASE}/api/admin/users/{tw_id}", headers=h(admin))
        except Exception as e:
            print("[warn] cleanup issue:", e)
        cleanup(admin, BASE)


if __name__ == "__main__":
    sys.exit(main())

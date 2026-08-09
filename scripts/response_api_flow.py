#!/usr/bin/env python3
"""
The machine response API (XDR/ITDR), end to end against a running server.

This flow cannot be a MockMvc test: it needs a REAL client_credentials token from /oauth2/token, and
what it is actually verifying is that the token this IdP mints is the one the response filter accepts.
A test that hands the filter a token it built itself proves the filter parses its own fixtures.

What it pins, in order:
  1. an admin registers a machine client carrying response scopes (needs user:update, not just
     oidc-client:create — registering a client is not authority to act on accounts);
  2. that client gets a client_credentials token and places a hold on a user;
  3. the hold reads back, carrying the correlation id that joins it to the detection;
  4. the held user's next sign-in is REFUSED — they have no second factor, so the password alone
     cannot finish. This is the property the whole feature exists for;
  5. lifting releases them, and the sign-in completes again;
  6. the refusals: no token, no correlation id, and a scope the client was not granted.

Usage: python3 scripts/response_api_flow.py   (server on :9000, `docker compose up -d`)
"""
import sys
import time

import requests

from sso_auth import authenticate, cleanup, elevate, _csrf_headers

BASE = "http://localhost:9000"
CORRELATION = "X-Correlation-Id"


def admin_session():
    session = requests.Session()
    secret = authenticate(session, BASE)
    session.headers["Authorization"] = f"Bearer {elevate(session, BASE, secret)}"
    return session, secret


def register_response_client(admin, client_id):
    created = admin.post(f"{BASE}/api/admin/clients", headers=_csrf_headers(admin), json={
        "clientId": client_id,
        "clientName": "Live-flow XDR",
        "scopes": ["response:hold", "response:hold-lift", "response:session-terminate"],
        "grantTypes": ["client_credentials"],
        "clientAuthenticationMethods": ["client_secret_basic"],
    })
    if created.status_code != 201:
        raise SystemExit(f"client registration failed: {created.status_code} {created.text}")
    return created.json()["clientSecret"]


def machine_token(client_id, secret, scope):
    token = requests.post(f"{BASE}/oauth2/token",
                          auth=(client_id, secret),
                          data={"grant_type": "client_credentials", "scope": scope})
    if token.status_code != 200 or "access_token" not in token.json():
        raise SystemExit(f"client_credentials token failed: {token.status_code} {token.text}")
    return token.json()["access_token"]


def response_call(method, path, token, correlation="live-flow-1", body=None):
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if correlation:
        headers[CORRELATION] = correlation
    return requests.request(method, f"{BASE}/api/response/v1{path}", headers=headers, json=body)


def create_target(admin, username):
    """A user with a password and NO second factor — the account a hold must refuse outright."""
    # The username IS the address: the sign-in screen asks for an email, so anything else reads as a
    # broken account to whoever is looking at it.
    created = admin.post(f"{BASE}/api/admin/users", headers=_csrf_headers(admin), json={
        "username": username, "email": username,
        "displayName": "Response Target", "password": "Target-pass-1!", "roles": ["ROLE_USER"]})
    if created.status_code != 201:
        raise SystemExit(f"create target failed: {created.status_code} {created.text}")
    user_id = created.json()["id"]

    # Membership is separate from creation, and tenant-first login needs it: without it the account
    # simply does not exist as far as the org's sign-in is concerned (a 401, not a hold).
    orgs = admin.get(f"{BASE}/api/admin/organizations").json()["items"]
    default_org = next(o for o in orgs if o["slug"] == "default")
    joined = admin.post(f"{BASE}/api/admin/organizations/{default_org['id']}/members",
                        headers=_csrf_headers(admin), json={"userId": user_id})
    if joined.status_code not in (200, 201, 204):
        raise SystemExit(f"org membership failed: {joined.status_code} {joined.text}")
    return user_id


def password_only_policy(admin, user_id, stamp):
    """
    A login policy for THIS user whose only step is the password.

    The seeded org runs password -> TOTP, and against that a hold is unobservable: the second factor was
    going to be demanded anyway, and the account would sit at that step whether or not any of this code
    existed. Password-only is the configuration where the hold is the only thing between a stolen
    password and a session, which is the thing worth verifying live.
    """
    created = admin.post(f"{BASE}/api/admin/auth-policies", headers=_csrf_headers(admin), json={
        "name": f"live-flow-password-only-{stamp}",
        "priority": 900 + (stamp % 90),
        "enabled": True,
        "appliesToLogin": True,
        "allowEnrollmentAtLogin": False,
        "steps": [["PASSWORD"]],
        "assignedUserIds": [user_id],
    })
    if created.status_code != 201:
        raise SystemExit(f"password-only policy failed: {created.status_code} {created.text}")
    return created.json()["id"]


def sign_in(username):
    """Drives the sign-in as far as it goes, and reports the state it stopped at."""
    session = requests.Session()
    session.get(f"{BASE}/api/auth/session")
    session.post(f"{BASE}/api/auth/organization", json={"slug": "default"},
                 headers=_csrf_headers(session))
    resp = session.post(f"{BASE}/api/auth/login",
                        json={"username": username, "password": "Target-pass-1!"},
                        headers=_csrf_headers(session))
    if resp.status_code != 200:
        raise SystemExit(f"sign-in call failed: {resp.status_code} {resp.text}")
    return resp.json().get("next")


def main() -> int:
    admin, _ = admin_session()
    print("[ok] admin session established + elevated")

    stamp = int(time.time())
    client_id, username = f"live-xdr-{stamp}", f"resp-target-{stamp}@example.com"
    user_id = create_target(admin, username)
    policy_id = password_only_policy(admin, user_id, stamp)
    print(f"[ok] target user {username} created under a password-only policy (no second factor)")

    secret = register_response_client(admin, client_id)
    print(f"[ok] registered machine client {client_id} with response scopes")

    token = machine_token(client_id, secret, "response:hold response:hold-lift")
    print("[ok] client_credentials token issued")

    # 1. The response API accepts the token this IdP just minted.
    placed = response_call("PUT", f"/users/{user_id}/hold", token,
                           body={"reason": "live-flow: suspected credential theft", "durationMinutes": 60})
    if placed.status_code != 200 or not placed.json()["held"]:
        raise SystemExit(f"hold not placed: {placed.status_code} {placed.text}")
    print("[ok] hold placed by the machine principal")

    read = response_call("GET", f"/users/{user_id}/hold", token)
    if read.json().get("correlationId") != "live-flow-1":
        raise SystemExit(f"correlation id lost: {read.text}")
    print("[ok] the hold carries the caller's correlation id")

    # 2. The point of the feature: a held account with no second factor cannot finish signing in.
    state = sign_in(username)
    if state != "ACCOUNT_HELD":
        raise SystemExit(f"a held account signed in anyway (next={state})")
    print("[ok] the held account's sign-in is REFUSED with ACCOUNT_HELD")

    # 3. Reversible: lifting releases them.
    #
    # After the lift this account reports MUST_RESET_PASSWORD, because an admin-created user holds a
    # TEMPORARY password. That is the point worth pinning rather than an inconvenience: while HELD the
    # same account reported ACCOUNT_HELD, so the hold outranks the reset gate — which is the ordering
    # the login state was deliberately given (a held account is the stronger fact about it).
    lifted = response_call("DELETE", f"/users/{user_id}/hold", token)
    if lifted.status_code != 204:
        raise SystemExit(f"lift failed: {lifted.status_code} {lifted.text}")
    after = sign_in(username)
    if after != "MUST_RESET_PASSWORD":
        raise SystemExit(f"the account did not recover after the hold was lifted (next={after})")
    print("[ok] lifting the hold restores the sign-in (and the hold outranked the reset gate)")

    # 4. The refusals, each for its own reason.
    if response_call("PUT", f"/users/{user_id}/hold", None,
                     body={"reason": "x", "durationMinutes": 5}).status_code != 401:
        raise SystemExit("the response API accepted a call with no token")
    print("[ok] no token -> 401")

    no_correlation = response_call("PUT", f"/users/{user_id}/hold", token, correlation=None,
                                   body={"reason": "x", "durationMinutes": 5})
    if no_correlation.status_code != 400:
        raise SystemExit(f"a call with no correlation id was not a 400: {no_correlation.status_code}")
    print("[ok] no correlation id -> 400 (a bad request, not a bad credential)")

    # A token narrowed to one scope must not reach the others: the separation is the whole point of
    # having three scopes rather than one.
    narrow = machine_token(client_id, secret, "response:hold")
    if response_call("DELETE", f"/users/{user_id}/hold", narrow).status_code != 403:
        raise SystemExit("a token without response:hold-lift was allowed to lift")
    print("[ok] a token scoped to hold cannot lift -> 403")

    admin.delete(f"{BASE}/api/admin/auth-policies/{policy_id}", headers=_csrf_headers(admin))
    admin.delete(f"{BASE}/api/admin/users/{user_id}", headers=_csrf_headers(admin))
    clients = admin.get(f"{BASE}/api/admin/clients?size=200").json()["items"]
    row = next((c for c in clients if c["clientId"] == client_id), None)
    if row:
        admin.delete(f"{BASE}/api/admin/clients/{row['id']}", headers=_csrf_headers(admin))
    cleanup(admin, BASE)
    print("\nAll response-API checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

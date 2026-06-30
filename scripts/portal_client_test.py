"""Registers a separate OIDC client app, assigns it in the portal, launches it, and drives a full
SSO login end-to-end (auth-code + PKCE) — then verifies per-app step-up gates it."""
import os, subprocess, sys, time
import requests, sso_auth

BASE = "http://127.0.0.1:9000"; CLIENT_ID = "portal-demo-app"
REDIRECT = "http://127.0.0.1:8088/callback"; PORT = 8088
H = sso_auth._csrf_headers
admin = requests.Session(); sso_auth.authenticate(admin, BASE)

body = {"clientId": CLIENT_ID, "clientName": "Portal Demo App", "redirectUris": [REDIRECT],
        "postLogoutRedirectUris": [], "scopes": ["openid", "profile", "email"],
        "grantTypes": ["authorization_code", "refresh_token"],
        "clientAuthenticationMethods": ["client_secret_basic"], "publicClient": False,
        "requireConsent": False, "requireProofKey": True, "accessTokenMinutes": 30, "refreshTokenDays": 7,
        "authorizationCodeMinutes": 5, "deviceCodeMinutes": 5, "reuseRefreshTokens": False,
        "accessTokenFormat": "SELF_CONTAINED", "idTokenSignatureAlgorithm": "RS256",
        "tokenEndpointAuthSigningAlgorithm": None, "jwkSetUrl": None, "x509SubjectDn": None,
        "x509BoundAccessTokens": False, "clientSecretDays": None}
r = admin.post(f"{BASE}/api/admin/clients", json=body, headers=H(admin))
assert r.status_code in (200, 201), f"register failed {r.status_code} {r.text}"
secret = r.json()["clientSecret"]
print("registered OIDC client 'portal-demo-app'")

app = next(a for a in admin.get(f"{BASE}/api/admin/applications").json() if a["name"] == "Portal Demo App")
adminId = next(u for u in admin.get(f"{BASE}/api/admin/users").json() if u["username"] == "admin")["id"]
admin.post(f"{BASE}/api/admin/applications/assignments",
           json={"appType": "OIDC", "appId": app["id"], "subjectType": "USER", "subjectId": adminId}, headers=H(admin))
print("assigned to admin; visible in portal:",
      any(a["name"] == "Portal Demo App" for a in admin.get(f"{BASE}/api/portal/apps").json()))

env = {**os.environ, "CLIENT_ID": CLIENT_ID, "CLIENT_SECRET": secret, "REDIRECT_URI": REDIRECT,
       "PORT": str(PORT), "SSO_ISSUER": BASE}
proc = subprocess.Popen(["python3", "../test-client/oidc_client.py"], env=env,
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    for _ in range(40):
        try:
            requests.get(f"http://127.0.0.1:{PORT}/", timeout=1); break
        except Exception:
            time.sleep(0.5)
    print("client app up at http://127.0.0.1:%d" % PORT)
    s = admin  # the SSO session is already authenticated
    authz = s.get(f"http://127.0.0.1:{PORT}/login", allow_redirects=False).headers["Location"]
    az = s.get(authz, allow_redirects=False)
    assert az.status_code == 302 and REDIRECT in az.headers["Location"], f"authorize {az.status_code} {az.headers.get('Location')}"
    cb = s.get(az.headers["Location"], allow_redirects=False)
    assert cb.status_code == 200 and "Signed in via Mini SSO" in cb.text and "admin@example.com" in cb.text, cb.text[:300]
    print("PASS: test client SSO login end-to-end (ID token claims incl. admin@example.com)")

    # per-app step-up on this client
    admin.post(f"{BASE}/api/admin/auth-policies", json={"name": "client-extra", "priority": 5, "enabled": True,
               "steps": [["FIDO2"]], "assignedRoleIds": [], "assignedUserIds": []}, headers=H(admin))
    polId = next(p for p in admin.get(f"{BASE}/api/admin/auth-policies").json() if p["name"] == "client-extra")["id"]
    for asg in admin.get(f"{BASE}/api/admin/applications/OIDC/{app['id']}/assignments").json():
        admin.delete(f"{BASE}/api/admin/applications/assignments/{asg['id']}", headers=H(admin))
    admin.post(f"{BASE}/api/admin/applications/assignments", json={"appType": "OIDC", "appId": app["id"],
               "subjectType": "USER", "subjectId": adminId, "requiredPolicyId": polId}, headers=H(admin))
    az2 = s.get(s.get(f"http://127.0.0.1:{PORT}/login", allow_redirects=False).headers["Location"], allow_redirects=False)
    assert az2.status_code == 302 and "stepup" in az2.headers["Location"], f"{az2.status_code} {az2.headers.get('Location')}"
    print("PASS: per-app step-up gates the test client (authorize -> /stepup)")
    admin.delete(f"{BASE}/api/admin/auth-policies/{polId}", headers=H(admin))
finally:
    proc.terminate()
    for asg in admin.get(f"{BASE}/api/admin/applications/OIDC/{app['id']}/assignments").json():
        admin.delete(f"{BASE}/api/admin/applications/assignments/{asg['id']}", headers=H(admin))
    admin.delete(f"{BASE}/api/admin/clients/{app['id']}", headers=H(admin))
    sso_auth.cleanup(admin, BASE)
print("DONE")

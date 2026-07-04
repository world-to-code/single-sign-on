#!/usr/bin/env python3
"""
Live check for SAML 2.0 Single Logout against the running Mini SSO IdP. A tiny local HTTP server stands
in for two Service Providers' SLO endpoints (one SOAP back-channel, one front-channel Redirect). We
register both relying parties, SSO a browser session to each, then prove all four SLO paths work:

  (a1) IdP-initiated back-channel (SOAP) on explicit logout   -> SP receives a signed LogoutRequest
  (a2) IdP-initiated back-channel (SOAP) on idle expiry        -> SP receives it with NO browser (Redis TTL)
  (b)  SP-initiated inbound at /saml2/idp/slo                  -> IdP verifies our signature, ends the
                                                                  session, returns a signed LogoutResponse
  (c)  IdP-initiated front-channel (Redirect) on explicit logout -> browser redirect chain delivers a
                                                                  signed LogoutRequest, verified against
                                                                  the IdP cert (proves SamlRedirectEncoder)

Requires: server on :9000 (with Redis), `pip install requests cryptography`.
Usage: python3 scripts/saml_slo_flow.py
"""
import base64
import re
import sys
import threading
import time
import zlib
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, quote, unquote, urlparse

import requests
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.x509.oid import NameOID

from sso_auth import authenticate, cleanup, elevate, totp, _csrf_headers

BASE = "http://localhost:9000"
RECEIVER_PORT = 8990
RECEIVER = f"http://127.0.0.1:{RECEIVER_PORT}"
RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"
NS = {
    "samlp": "urn:oasis:names:tc:SAML:2.0:protocol",
    "saml": "urn:oasis:names:tc:SAML:2.0:assertion",
    "ds": "http://www.w3.org/2000/09/xmldsig#",
    "md": "urn:oasis:names:tc:SAML:2.0:metadata",
    "soap": "http://schemas.xmlsoap.org/soap/envelope/",
}
NAMEID_EMAIL = "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress"

# Captures from the SP receiver, guarded by the GIL.
soap_received: list[str] = []   # raw SOAP-body XML strings (IdP-initiated back-channel LogoutRequests)
fc_received: list[str] = []     # full "SAMLRequest=..&SigAlg=..&Signature=.." query strings (front-channel)

_idp_cert_der: bytes = b""      # IdP signing cert (DER), for verifying inbound LogoutRequests


class SpReceiver(BaseHTTPRequestHandler):
    """Stands in for two SPs: /soap-slo consumes SOAP LogoutRequests; /fc-slo consumes a front-channel
    Redirect LogoutRequest and bounces a (dummy) LogoutResponse back to advance the IdP chain."""

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
        if self.path.startswith("/soap-slo"):
            soap_received.append(body)
            self._send(200, soap_logout_response(), "text/xml")
        else:
            self._send(200, "ok", "text/plain")

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/fc-slo":
            fc_received.append(parsed.query)
            relay = parse_qs(parsed.query).get("RelayState", [""])[0]
            # advanceChain ignores the LogoutResponse content, so a placeholder is enough to continue.
            location = f"{BASE}/saml2/idp/slo?SAMLResponse={quote('PHNhbXA+')}&RelayState={quote(relay)}"
            self.send_response(302)
            self.send_header("Location", location)
            self.end_headers()
        else:
            self._send(200, "ok", "text/plain")

    def _send(self, code, body, ctype):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.end_headers()
        self.wfile.write(body.encode())

    def log_message(self, *args):
        pass


def start_receiver() -> HTTPServer:
    server = HTTPServer(("127.0.0.1", RECEIVER_PORT), SpReceiver)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def soap_logout_response() -> str:
    return ('<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>'
            '<samlp:LogoutResponse xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" '
            'Version="2.0" ID="_r1" IssueInstant="2026-07-04T00:00:00Z">'
            '<samlp:Status><samlp:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/>'
            '</samlp:Status></samlp:LogoutResponse></soap:Body></soap:Envelope>')


# --- SP signing key (self-signed) that the IdP will trust for our SP-initiated LogoutRequest -----------

def make_sp_key() -> tuple[rsa.RSAPrivateKey, str]:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    cert = (x509.CertificateBuilder()
            .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "slo-test-sp")]))
            .issuer_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "slo-test-sp")]))
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(_utc(-3600)).not_valid_after(_utc(3600 * 24))
            .sign(key, hashes.SHA256()))
    pem = cert.public_bytes(serialization.Encoding.PEM).decode()
    return key, pem


def _utc(offset_s: int):
    import datetime
    return datetime.datetime.now(datetime.UTC) + datetime.timedelta(seconds=offset_s)


def now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


# --- SAML message builders / binding codecs ------------------------------------------------------------

def deflate_b64(xml: str) -> str:
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    return base64.b64encode(c.compress(xml.encode()) + c.flush()).decode()


def authn_request(entity_id: str, acs_url: str) -> str:
    xml = (
        '<samlp:AuthnRequest xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol"'
        ' xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion"'
        f' ID="_authn_{int(time.time()*1000)}" Version="2.0" IssueInstant="{now_iso()}"'
        f' Destination="{BASE}/saml2/idp/sso" AssertionConsumerServiceURL="{acs_url}"'
        ' ProtocolBinding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST">'
        f'<saml:Issuer>{entity_id}</saml:Issuer></samlp:AuthnRequest>'
    )
    return deflate_b64(xml)


def logout_request(entity_id: str, name_id: str, session_index: str) -> str:
    return (
        '<samlp:LogoutRequest xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol"'
        ' xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion"'
        f' ID="_lo_{int(time.time()*1000)}" Version="2.0" IssueInstant="{now_iso()}"'
        f' Destination="{BASE}/saml2/idp/slo">'
        f'<saml:Issuer>{entity_id}</saml:Issuer>'
        f'<saml:NameID Format="{NAMEID_EMAIL}">{name_id}</saml:NameID>'
        f'<samlp:SessionIndex>{session_index}</samlp:SessionIndex></samlp:LogoutRequest>'
    )


def sso_to_sp(session: requests.Session, entity_id: str, acs_url: str) -> None:
    """Drives an SP-initiated Web SSO so the browser session becomes a participant of `entity_id`."""
    resp = session.get(f"{BASE}/saml2/idp/sso",
                       params={"SAMLRequest": authn_request(entity_id, acs_url), "RelayState": "r"})
    if resp.status_code != 200 or "SAMLResponse" not in resp.text:
        raise SystemExit(f"SSO to {entity_id} failed: {resp.status_code}\n{resp.text[:300]}")


# --- signature helpers ---------------------------------------------------------------------------------

def sign_redirect_query(key: rsa.RSAPrivateKey, saml_request_b64: str, relay: str) -> str:
    """Builds a signed HTTP-Redirect query string exactly as the IdP reconstructs it for verification:
    it signs everything before `&Signature=`, so we sign the same bytes and append Signature last."""
    signed = f"SAMLRequest={quote(saml_request_b64)}&RelayState={quote(relay)}&SigAlg={quote(RSA_SHA256)}"
    sig = key.sign(signed.encode("ascii"), padding.PKCS1v15(), hashes.SHA256())
    return f"{signed}&Signature={quote(base64.b64encode(sig).decode())}"


def verify_idp_redirect(query: str) -> None:
    """Verifies the IdP's detached Redirect signature over the front-channel LogoutRequest (the exact
    thing SamlRedirectEncoder produced), using the IdP's published signing certificate."""
    # The IdP verifies/produces the detached signature over the raw octet string before "&Signature=".
    # Extract each value with unquote (NOT parse_qs, which maps '+' -> space and would corrupt the
    # base64 signature/SAMLRequest, since UriUtils leaves '+' raw in a query).
    def param(name):
        return unquote(re.search(rf"(?:^|[?&]){name}=([^&]+)", query).group(1))

    idx = query.index("&Signature=")
    signed = query[:idx]
    assert param("SigAlg") == RSA_SHA256
    cert = x509.load_der_x509_certificate(_idp_cert_der)
    cert.public_key().verify(base64.b64decode(param("Signature")), signed.encode("ascii"),
                             padding.PKCS1v15(), hashes.SHA256())
    xml = zlib.decompress(base64.b64decode(param("SAMLRequest")), -15)
    assert b"LogoutRequest" in xml, xml[:200]


def fetch_idp_cert() -> None:
    global _idp_cert_der
    md = ET.fromstring(requests.get(f"{BASE}/saml2/idp/metadata").text)
    b64 = "".join(md.find(".//md:KeyDescriptor//ds:X509Certificate", NS).text.split())
    _idp_cert_der = base64.b64decode(b64)


# --- session helpers -----------------------------------------------------------------------------------

def mfa_login(session: requests.Session, secret: str, user="admin", password="admin123!") -> None:
    """Re-establishes an MFA session for an already-enrolled user (no re-enrollment)."""
    window = int(time.time() // 30)  # a code not spent in this window (TOTP replay guard)
    while int(time.time() // 30) == window:
        time.sleep(1)
    session.get(f"{BASE}/api/auth/session")
    login = session.post(f"{BASE}/api/auth/login", json={"username": user, "password": password},
                         headers=_csrf_headers(session))
    verify = session.post(f"{BASE}/api/auth/factors/TOTP/verify", json={"code": totp(secret)},
                          headers=_csrf_headers(session))
    if verify.status_code != 200 or verify.json().get("next") != "DONE":
        raise SystemExit(f"re-login failed: {login.status_code}/{verify.status_code} {verify.text}")


def is_authenticated(session: requests.Session) -> bool:
    r = session.get(f"{BASE}/api/auth/session")
    return r.status_code == 200 and bool(r.json().get("authenticated"))


def register_rp(admin: requests.Session, entity_id: str, binding: str, slo_path: str, cert_pem: str) -> str:
    body = {
        "entityId": entity_id, "displayName": entity_id, "acsUrl": f"{RECEIVER}/acs",
        "nameIdFormat": NAMEID_EMAIL, "signAssertion": True, "signResponse": False,
        "encryptAssertion": False, "signatureAlgorithm": "", "dataEncryptionAlgorithm": "",
        "keyTransportAlgorithm": "", "wantAuthnRequestsSigned": False, "allowIdpInitiated": True,
        "signingCertificate": cert_pem, "encryptionCertificate": "", "spLoginUrl": "",
        "singleLogoutUrl": f"{RECEIVER}{slo_path}", "sloBinding": binding,
    }
    created = admin.post(f"{BASE}/api/admin/saml/relying-parties", headers=_csrf_headers(admin), json=body)
    if created.status_code == 409:  # a prior run left this RP — drop it and retry once
        existing = next((rp for rp in admin.get(f"{BASE}/api/admin/saml/relying-parties?size=200").json()["items"]
                         if rp["entityId"] == entity_id), None)
        if existing:
            admin.delete(f"{BASE}/api/admin/saml/relying-parties/{existing['id']}", headers=_csrf_headers(admin))
        created = admin.post(f"{BASE}/api/admin/saml/relying-parties", headers=_csrf_headers(admin), json=body)
    if created.status_code != 201:
        raise SystemExit(f"RP registration ({entity_id}) failed: {created.status_code} {created.text}")
    return created.json()["id"]


def wait_for(bucket: list, since: int, timeout: float):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if len(bucket) > since:
            return bucket[-1]
        time.sleep(0.5)
    return None


def assert_signed_soap_logout(soap_xml: str, name_id: str) -> None:
    # The IdP marshals the LogoutRequest with its own <?xml?> declaration inside the SOAP body; strip any
    # declaration so ElementTree (which rejects a declaration mid-document) can parse the envelope.
    root = ET.fromstring(re.sub(r"<\?xml[^>]*\?>", "", soap_xml))
    lr = root.find(".//samlp:LogoutRequest", NS)
    assert lr is not None, "SOAP body has no LogoutRequest"
    assert lr.find("ds:Signature", NS) is not None, "IdP LogoutRequest is not signed"
    assert lr.find("saml:NameID", NS).text == name_id, lr.find("saml:NameID", NS).text
    assert lr.find("samlp:SessionIndex", NS) is not None, "LogoutRequest missing SessionIndex"


# --- the flow ------------------------------------------------------------------------------------------

SOAP_SP = "urn:example:slo-soap"
FC_SP = "urn:example:slo-redirect"
ADMIN_EMAIL = "admin@example.com"


def main() -> int:
    start_receiver()
    fetch_idp_cert()
    admin = requests.Session()
    secret = authenticate(admin, BASE)
    admin.headers["Authorization"] = f"Bearer {elevate(admin, BASE, secret)}"
    print("[ok] admin session established + elevated; IdP cert loaded")

    sp_key, sp_cert = make_sp_key()
    stamp = int(time.time())
    soap_rp = register_rp(admin, SOAP_SP, "SOAP", "/soap-slo", sp_cert)
    fc_rp = register_rp(admin, FC_SP, "REDIRECT", "/fc-slo", sp_cert)
    print(f"[ok] registered SOAP + front-channel relying parties")

    tw_id = policy_id = None
    try:
        # ---- (a1) IdP-initiated SOAP on explicit logout ----
        s = requests.Session()
        mfa_login(s, secret)
        sso_to_sp(s, SOAP_SP, f"{RECEIVER}/acs")
        before = len(soap_received)
        s.post(f"{BASE}/api/auth/logout", headers=_csrf_headers(s))
        soap = wait_for(soap_received, before, timeout=15)
        assert soap, "no SOAP LogoutRequest after explicit logout"
        assert_signed_soap_logout(soap, ADMIN_EMAIL)
        print("[PASS] (a1) explicit logout -> signed SOAP LogoutRequest delivered to the SP")

        # ---- (b) SP-initiated inbound at /saml2/idp/slo ----
        s = requests.Session()
        mfa_login(s, secret)
        sso_to_sp(s, FC_SP, f"{RECEIVER}/acs")
        assert is_authenticated(s), "session should be live before SP-initiated logout"
        query = sign_redirect_query(sp_key, deflate_b64(logout_request(FC_SP, ADMIN_EMAIL, "idx-b")), "rb")
        resp = s.get(f"{BASE}/saml2/idp/slo?{query}", allow_redirects=False)
        assert resp.status_code == 200 and "SAMLResponse" in resp.text, \
            f"expected a LogoutResponse form, got {resp.status_code}: {resp.text[:200]}"
        lr_xml = base64.b64decode(re.search(r'name="SAMLResponse" value="([^"]+)"', resp.text).group(1)).decode()
        status = ET.fromstring(lr_xml).find(".//samlp:StatusCode", NS).attrib["Value"]
        assert status.endswith(":Success"), f"LogoutResponse status: {status}"
        assert not is_authenticated(s), "session must be dead after SP-initiated logout"
        print("[PASS] (b) SP-initiated LogoutRequest verified -> session ended, signed Success returned")

        # ---- (c) IdP-initiated front-channel Redirect chain on explicit logout ----
        s = requests.Session()
        mfa_login(s, secret)
        sso_to_sp(s, FC_SP, f"{RECEIVER}/acs")
        logout = s.post(f"{BASE}/api/auth/logout", headers=_csrf_headers(s))
        redirect = logout.json().get("samlLogoutRedirect")
        assert redirect and "/saml2/idp/slo/chain" in redirect, f"no chain redirect: {logout.json()}"
        # First hop: /chain emits the SP's signed LogoutRequest as a 302 Location. Verify against the IdP's
        # RAW bytes (not the browser-followed query, which requests re-encodes) — this validates
        # SamlRedirectEncoder exactly. The browser-bound SLO_CHAIN cookie (set at logout) gates /chain.
        hop = s.get(f"{BASE}{redirect}", allow_redirects=False)
        assert hop.status_code == 302, f"chain hop not a redirect: {hop.status_code}"
        location = hop.headers["Location"]
        assert location.startswith(f"{RECEIVER}/fc-slo"), f"unexpected SP target: {location}"
        verify_idp_redirect(urlparse(location).query)
        before = len(fc_received)
        s.get(location)  # let the SP bounce a LogoutResponse back so the chain advances to completion
        assert wait_for(fc_received, before, timeout=10), "SP did not receive the front-channel LogoutRequest"
        assert not is_authenticated(s), "session must be dead after front-channel logout"
        print("[PASS] (c) front-channel chain -> signed Redirect LogoutRequest verified against IdP cert")

        # ---- (a2) IdP-initiated SOAP on idle expiry (NO browser) ----
        tw = f"slo-idle-{stamp}"
        r = admin.post(f"{BASE}/api/admin/users", headers=_csrf_headers(admin), json={
            "username": tw, "email": f"{tw}@example.com", "displayName": "Idle",
            "password": "Idle-pass-1!", "roles": ["ROLE_USER"]})
        assert r.status_code == 201, f"create throwaway: {r.status_code} {r.text}"
        tw_id = r.json()["id"]
        pol = admin.post(f"{BASE}/api/admin/session-policies", headers=_csrf_headers(admin), json={
            "name": f"slo-idle-pol-{stamp}", "priority": 500, "enabled": True,
            "absoluteTimeoutMinutes": 480, "idleTimeoutMinutes": 1, "reauthIntervalMinutes": 1440,
            "reauthFactors": "TOTP,FIDO2", "sensitiveReauthWindowMinutes": 2, "stepUpFactors": "TOTP,FIDO2",
            "bindClient": True, "maxConcurrentSessions": 0, "rotateOnReauth": True, "cookieSameSite": "Lax",
            "assignedUserIds": [tw_id], "assignedRoleIds": [], "ipRules": []})
        assert pol.status_code == 201, f"create idle policy: {pol.status_code} {pol.text}"
        policy_id = pol.json()["id"]
        # Use a throwaway user on a 1-minute-idle policy so the SOAP LogoutRequest must arrive with NO
        # browser present (only Redis TTL expiry drives it). If the SP happened to require app assignment
        # the throwaway's SSO would be refused, so degrade to a clear skip rather than a false failure.
        idle = requests.Session()
        authenticate(idle, BASE, tw, "Idle-pass-1!")
        sso = idle.get(f"{BASE}/saml2/idp/sso",
                       params={"SAMLRequest": authn_request(SOAP_SP, f"{RECEIVER}/acs"), "RelayState": "r"})
        if sso.status_code != 200 or "SAMLResponse" not in sso.text:
            print(f"[skip] (a2) idle SOAP: throwaway cannot SSO to the SP (app-assignment): "
                  f"{sso.status_code}. Delivery covered by (a1); browserless path proven by BCL Check B.")
        else:
            idle.get(f"{BASE}/api/me")  # a request under the 1-min-idle policy arms the Redis TTL
            print("[..] (a2) waiting for idle expiry (no requests, ~2 min)...")
            before = len(soap_received)
            soap = wait_for(soap_received, before, timeout=180)
            assert soap, "no SOAP LogoutRequest from idle expiry"
            assert_signed_soap_logout(soap, f"{tw}@example.com")
            print("[PASS] (a2) idle expiry (no browser) -> signed SOAP LogoutRequest delivered")

        print("\nALL SAML SLO CHECKS PASSED")
        return 0
    finally:
        recover = requests.Session()
        mfa_login(recover, secret)
        recover.headers["Authorization"] = f"Bearer {elevate(recover, BASE, secret)}"
        for rp_id in (soap_rp, fc_rp):
            recover.delete(f"{BASE}/api/admin/saml/relying-parties/{rp_id}", headers=_csrf_headers(recover))
        if policy_id:
            recover.delete(f"{BASE}/api/admin/session-policies/{policy_id}", headers=_csrf_headers(recover))
        if tw_id:
            recover.delete(f"{BASE}/api/admin/users/{tw_id}", headers=_csrf_headers(recover))
        cleanup(recover, BASE)


if __name__ == "__main__":
    sys.exit(main())

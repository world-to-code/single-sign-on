#!/usr/bin/env python3
"""
INBOUND SAML federation against the live Mini SSO server: this script plays the UPSTREAM IdP.

The reverse of saml_sso_flow.py. There we are the IdP and a script is the SP; here a tenant of ours
federates OUT to a corporate IdP, and this script is that IdP — it holds the signing key, receives the
AuthnRequest, and posts a signed assertion back to our assertion consumer service.

Why it exists: the ACS is the one state-changing POST in this product that is CSRF-exempt and
cookie-less by design (the upstream makes the BROWSER post cross-site, so SameSite=Lax sends no session
cookie), and MockMvc cannot exercise a real signed assertion. Everything the unit tests can only assert
about a fixture — signature validity, the InResponseTo the SP itself minted, the RelayState correlation
being single-use — is checked here against the running server.

It also proves the negative half: replaying the same assertion, tampering after signing, and swapping in
a key the tenant never registered must all be refused.

Usage: python3 scripts/saml_inbound_flow.py   (server running on :9000, docker compose up)
"""
import base64
import datetime
import re
import sys
import urllib.parse
import uuid
import zlib

import requests
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.x509.oid import NameOID
from lxml import etree

from sso_auth import authenticate, cleanup, elevate

BASE = "http://localhost:9000"
ORG = "default"
ALIAS = "corp-saml"
IDP_ENTITY_ID = "urn:example:upstream-idp"
IDP_SSO_URL = "https://upstream-idp.example/sso"  # never fetched: the browser is the only transport
NAME_ID = "upstream-subject-0001"
FEDERATED_USER = "saml.inbound@example.com"

NS = {
    "samlp": "urn:oasis:names:tc:SAML:2.0:protocol",
    "saml": "urn:oasis:names:tc:SAML:2.0:assertion",
    "ds": "http://www.w3.org/2000/09/xmldsig#",
}
EXC_C14N = "http://www.w3.org/2001/10/xml-exc-c14n#"
ENVELOPED = "http://www.w3.org/2000/09/xmldsig#enveloped-signature"
RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"
SHA256 = "http://www.w3.org/2001/04/xmlenc#sha256"
PERSISTENT = "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent"


# --------------------------------------------------------------------------------------------------
# The upstream IdP's key material and signer. Hand-rolled because neither signxml nor xmlsec is
# installed here; the shape below is plain XML-DSig — exclusive c14n, an enveloped-signature transform,
# RSA-SHA256 — which is what OpenSAML validates on the other end.
# --------------------------------------------------------------------------------------------------

class UpstreamIdp:
    def __init__(self):
        self.key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "upstream-idp.example")])
        now = datetime.datetime.now(datetime.timezone.utc)
        self.cert = (x509.CertificateBuilder()
                     .subject_name(name).issuer_name(name)
                     .public_key(self.key.public_key())
                     .serial_number(x509.random_serial_number())
                     .not_valid_before(now - datetime.timedelta(days=1))
                     .not_valid_after(now + datetime.timedelta(days=1))
                     .sign(self.key, hashes.SHA256()))

    def certificate_b64(self) -> str:
        """Bare base64 DER — what a ds:X509Certificate element carries."""
        return base64.b64encode(self.cert.public_bytes(serialization.Encoding.DER)).decode()

    def certificate_pem(self) -> str:
        """PEM — what the admin API stores as the connection's trust anchor."""
        return self.cert.public_bytes(serialization.Encoding.PEM).decode()

    def sign(self, element, key=None):
        """Inserts an enveloped signature over `element`, right after its Issuer child."""
        digest = _sha256(_c14n(element))
        signed_info = _signed_info(element.get("ID"), digest)
        signature = etree.SubElement(element, f"{{{NS['ds']}}}Signature", nsmap={"ds": NS["ds"]})
        signature.append(signed_info)
        value = (key or self.key).sign(_c14n(signed_info), padding.PKCS1v15(), hashes.SHA256())
        etree.SubElement(signature, f"{{{NS['ds']}}}SignatureValue").text = base64.b64encode(value).decode()
        key_info = etree.SubElement(signature, f"{{{NS['ds']}}}KeyInfo")
        x509_data = etree.SubElement(key_info, f"{{{NS['ds']}}}X509Data")
        etree.SubElement(x509_data, f"{{{NS['ds']}}}X509Certificate").text = self.certificate_b64()
        # Schema order: Issuer, Signature, Subject, ... — OpenSAML unmarshals strictly.
        element.remove(signature)
        element.insert(1, signature)


def _c14n(element) -> bytes:
    return etree.tostring(element, method="c14n", exclusive=True, with_comments=False)


def _sha256(data: bytes) -> str:
    digest = hashes.Hash(hashes.SHA256())
    digest.update(data)
    return base64.b64encode(digest.finalize()).decode()


def _signed_info(reference_id: str, digest: str):
    info = etree.Element(f"{{{NS['ds']}}}SignedInfo", nsmap={"ds": NS["ds"]})
    etree.SubElement(info, f"{{{NS['ds']}}}CanonicalizationMethod", Algorithm=EXC_C14N)
    etree.SubElement(info, f"{{{NS['ds']}}}SignatureMethod", Algorithm=RSA_SHA256)
    reference = etree.SubElement(info, f"{{{NS['ds']}}}Reference", URI=f"#{reference_id}")
    transforms = etree.SubElement(reference, f"{{{NS['ds']}}}Transforms")
    etree.SubElement(transforms, f"{{{NS['ds']}}}Transform", Algorithm=ENVELOPED)
    etree.SubElement(transforms, f"{{{NS['ds']}}}Transform", Algorithm=EXC_C14N)
    etree.SubElement(reference, f"{{{NS['ds']}}}DigestMethod", Algorithm=SHA256)
    etree.SubElement(reference, f"{{{NS['ds']}}}DigestValue").text = digest
    return info


def build_response(idp: UpstreamIdp, request_id: str, acs_url: str, sp_entity_id: str,
                   name_id: str = NAME_ID, sign_with=None):
    """A SAML Response whose ASSERTION is signed — the shape our SP requires."""
    now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    stamp = now.strftime("%Y-%m-%dT%H:%M:%SZ")
    expiry = (now + datetime.timedelta(minutes=5)).strftime("%Y-%m-%dT%H:%M:%SZ")
    xml = f"""<samlp:Response xmlns:samlp="{NS['samlp']}" xmlns:saml="{NS['saml']}"
  ID="_res{uuid.uuid4().hex}" Version="2.0" IssueInstant="{stamp}"
  Destination="{acs_url}" InResponseTo="{request_id}">
  <saml:Issuer>{IDP_ENTITY_ID}</saml:Issuer>
  <samlp:Status><samlp:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/></samlp:Status>
  <saml:Assertion xmlns:saml="{NS['saml']}" ID="_ass{uuid.uuid4().hex}" Version="2.0"
    IssueInstant="{stamp}">
    <saml:Issuer>{IDP_ENTITY_ID}</saml:Issuer>
    <saml:Subject>
      <saml:NameID Format="{PERSISTENT}">{name_id}</saml:NameID>
      <saml:SubjectConfirmation Method="urn:oasis:names:tc:SAML:2.0:cm:bearer">
        <saml:SubjectConfirmationData NotOnOrAfter="{expiry}" Recipient="{acs_url}"
          InResponseTo="{request_id}"/>
      </saml:SubjectConfirmation>
    </saml:Subject>
    <saml:Conditions NotBefore="{stamp}" NotOnOrAfter="{expiry}">
      <saml:AudienceRestriction><saml:Audience>{sp_entity_id}</saml:Audience></saml:AudienceRestriction>
    </saml:Conditions>
    <saml:AuthnStatement AuthnInstant="{stamp}">
      <saml:AuthnContext><saml:AuthnContextClassRef>
        urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport
      </saml:AuthnContextClassRef></saml:AuthnContext>
    </saml:AuthnStatement>
    <saml:AttributeStatement>
      <saml:Attribute Name="email"><saml:AttributeValue>{FEDERATED_USER}</saml:AttributeValue></saml:Attribute>
    </saml:AttributeStatement>
  </saml:Assertion>
</samlp:Response>"""
    response = etree.fromstring(xml.encode())
    idp.sign(response.find("saml:Assertion", NS), key=sign_with)
    return response


def encode(response) -> str:
    return base64.b64encode(etree.tostring(response)).decode()


# --------------------------------------------------------------------------------------------------
# Setup through the admin/SCIM APIs, then the flow itself.
# --------------------------------------------------------------------------------------------------

def csrf(session):
    token = session.cookies.get("XSRF-TOKEN")
    return {"X-XSRF-TOKEN": token} if token else {}


def drill_into_tenant(admin):
    """The seeded admin is a PLATFORM super-admin, so an unqualified write lands org-less — a provider no
    tenant login can see. Drilling in is what makes the setup belong to the organization signing in."""
    resp = admin.get(f"{BASE}/api/admin/organizations?size=100")
    orgs = resp.json().get("items", resp.json()) if resp.status_code == 200 else []
    org = next((o for o in orgs if o.get("slug") == ORG), None)
    if not org:
        raise SystemExit(f"organization '{ORG}' not found: {resp.status_code} {resp.text[:300]}")
    admin.headers["X-Org-Context"] = org["id"]


def register_provider(admin, idp: UpstreamIdp):
    body = {
        "displayName": "Corp SAML",
        "protocol": "SAML",
        "idpEntityId": IDP_ENTITY_ID,
        "ssoUrl": IDP_SSO_URL,
        "signingCertificate": idp.certificate_pem(),
        "nameIdFormat": PERSISTENT,
        "allowJitProvisioning": False,
        "enabled": True,
    }
    resp = admin.put(f"{BASE}/api/admin/identity-providers/{ALIAS}", json=body, headers=csrf(admin))
    if resp.status_code not in (200, 201):
        raise SystemExit(f"provider registration failed: {resp.status_code}\n{resp.text[:400]}")


def issue_scim_token(admin) -> str:
    """externalId is writable only through SCIM, so the script mints its own token rather than depending on
    one being configured — which also keeps it scoped to the tenant the admin is acting in."""
    resp = admin.post(f"{BASE}/api/admin/scim/tokens", json={"description": "saml inbound flow", "ttlDays": 1},
                      headers=csrf(admin))
    if resp.status_code not in (200, 201):
        raise SystemExit(f"SCIM token issue failed: {resp.status_code}\n{resp.text[:400]}")
    return resp.json()["token"]


def provision_federated_user(scim_token: str):
    """The account the assertion resolves to. SAML has no JIT path without a directory identifier, so the
    NameID has to already be somebody's SCIM externalId — which is the deterministic branch by design."""
    headers = {"Authorization": f"Bearer {scim_token}", "Content-Type": "application/scim+json"}
    body = {
        "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
        "userName": FEDERATED_USER,
        "externalId": NAME_ID,
        "emails": [{"value": FEDERATED_USER, "primary": True}],
        "active": True,
    }
    resp = requests.post(f"{BASE}/scim/v2/Users", json=body, headers=headers)
    if resp.status_code == 409:
        return  # a previous run already provisioned it; the externalId is what matters and it is stable
    if resp.status_code not in (200, 201):
        raise SystemExit(f"SCIM provisioning failed: {resp.status_code}\n{resp.text[:400]}")


def begin_login(session):
    """Starts the login as a browser would and returns (AuthnRequest, RelayState)."""
    session.get(f"{BASE}/api/auth/session")
    org = session.post(f"{BASE}/api/auth/organization", json={"slug": ORG}, headers=csrf(session))
    if org.status_code != 200:
        raise SystemExit(f"organization selection failed: {org.status_code}")
    resp = session.get(f"{BASE}/api/auth/federation/{ALIAS}/start", allow_redirects=False)
    if resp.status_code not in (302, 303):
        raise SystemExit(f"start failed: {resp.status_code}\n{resp.text[:400]}")
    # NOT parse_qs: it unquotes with unquote_plus, and '+' here is a real base64 character rather than
    # a form-encoded space — decoding it as one leaves an undecompressible SAMLRequest.
    query = {k: urllib.parse.unquote(v) for k, v in
             (pair.split("=", 1) for pair in urllib.parse.urlparse(resp.headers["Location"]).query.split("&"))}
    inflated = zlib.decompress(base64.b64decode(query["SAMLRequest"]), -15)
    return etree.fromstring(inflated), query["RelayState"]


def post_assertion(session, response, relay_state, acs_url):
    return session.post(acs_url, data={"SAMLResponse": encode(response), "RelayState": relay_state},
                        allow_redirects=False)


def resolved_subject(session):
    """
    Who the session says it is — which is the security-relevant outcome, not whether /api/me answers.

    A federated login satisfies the FIRST factor only; the tenant's auth policy still demands its second,
    so a fully accepted assertion still leaves /api/me at 403 with `next: FACTOR`. Keying the assertions
    on /api/me would therefore have made every REFUSAL test pass for the same reason the SUCCESS test
    failed — five green checks proving nothing. Federation resolves an identity; it does not skip MFA.
    """
    return session.get(f"{BASE}/api/auth/session").json().get("username")


def check(label, condition, detail=""):
    print(f"[{'ok' if condition else 'FAIL'}] {label}{(' | ' + detail) if detail else ''}")
    return condition


def main() -> int:
    admin = requests.Session()
    try:
        return run(admin)
    finally:
        # Always, even on a failed run: the admin is left holding a TOTP secret only this process knew,
        # so skipping this locks the next run (and a human) out of the dev tenant. Drop the drill-in first
        # — the seeded admin is org-less, so a tenant-scoped user list does not contain the account to reset.
        admin.headers.pop("X-Org-Context", None)
        cleanup(admin, BASE)


def run(admin) -> int:
    idp = UpstreamIdp()
    secret = authenticate(admin, BASE)
    admin.headers["Authorization"] = f"Bearer {elevate(admin, BASE, secret)}"  # /api/admin/** elevation gate
    drill_into_tenant(admin)
    print(f"[ok] admin session established + elevated, drilled into '{ORG}'")
    register_provider(admin, idp)
    provision_federated_user(issue_scim_token(admin))
    print(f"[ok] provider '{ALIAS}' registered and NameID={NAME_ID} provisioned as externalId")

    passed = True

    # --- the happy path -----------------------------------------------------------------------------
    browser = requests.Session()
    request, relay_state = begin_login(browser)
    request_id = request.get("ID")
    acs_url = request.get("AssertionConsumerServiceURL")
    sp_entity_id = request.find("saml:Issuer", NS).text
    passed &= check("AuthnRequest issued", request_id.startswith("_"),
                    f"ID={request_id} ACS={acs_url} SP={sp_entity_id}")
    passed &= check("the browser holds a correlation cookie",
                    any("saml" in name.lower() for name in browser.cookies.keys()),
                    ", ".join(browser.cookies.keys()))

    resp = post_assertion(browser, build_response(idp, request_id, acs_url, sp_entity_id),
                          relay_state, acs_url)
    passed &= check("a signed assertion is accepted", resp.status_code == 302,
                    f"-> {resp.headers.get('Location')}")
    passed &= check("it resolves to the local account holding that NameID as its externalId",
                    resolved_subject(browser) == FEDERATED_USER, resolved_subject(browser) or "nobody")

    # --- and the refusals ---------------------------------------------------------------------------
    # Replay: the SAME assertion again, on a fresh browser, must not sign anybody in. Two independent
    # controls should stop it (the single-use RelayState and the assertion-id replay guard); this asserts
    # the OUTCOME, so it holds whichever of them fires.
    replay = requests.Session()
    replay_request, replay_relay = begin_login(replay)
    replayed = build_response(idp, request_id, acs_url, sp_entity_id)  # same InResponseTo as the first
    post_assertion(replay, replayed, replay_relay, acs_url)
    passed &= check("a replayed assertion is refused", resolved_subject(replay) != FEDERATED_USER)

    # A key the tenant never registered. This is the whole trust anchor: without it, anyone who can reach
    # the ACS is every user of the tenant.
    forger = requests.Session()
    forged_request, forged_relay = begin_login(forger)
    stranger = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    post_assertion(forger, build_response(idp, forged_request.get("ID"), acs_url, sp_entity_id,
                                          sign_with=stranger), forged_relay, acs_url)
    passed &= check("an assertion signed by an unregistered key is refused", resolved_subject(forger) != FEDERATED_USER)

    # Tampering after signing — the NameID swapped for a different subject, signature left in place.
    tamperer = requests.Session()
    tampered_request, tampered_relay = begin_login(tamperer)
    tampered = build_response(idp, tampered_request.get("ID"), acs_url, sp_entity_id)
    tampered.find(".//saml:NameID", NS).text = "somebody-else"
    post_assertion(tamperer, tampered, tampered_relay, acs_url)
    passed &= check("an assertion edited after signing is refused", resolved_subject(tamperer) != FEDERATED_USER)

    # A validly signed assertion answering a request this product never made. The InResponseTo is what
    # ties the POST to a login we started; without it the ACS is an unauthenticated login endpoint.
    unsolicited = requests.Session()
    _, unsolicited_relay = begin_login(unsolicited)
    post_assertion(unsolicited, build_response(idp, "_never-issued", acs_url, sp_entity_id),
                   unsolicited_relay, acs_url)
    passed &= check("an assertion for an unissued request is refused", resolved_subject(unsolicited) != FEDERATED_USER)

    # A NameID nothing maps to. SAML has no JIT path here, so this must refuse rather than provision.
    unknown = requests.Session()
    unknown_request, unknown_relay = begin_login(unknown)
    post_assertion(unknown, build_response(idp, unknown_request.get("ID"), acs_url, sp_entity_id,
                                           name_id="nobody-here"), unknown_relay, acs_url)
    passed &= check("an unknown subject is refused rather than provisioned", resolved_subject(unknown) != FEDERATED_USER)

    print("\nAll checks passed." if passed else "\nSOME CHECKS FAILED.")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())

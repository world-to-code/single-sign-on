#!/usr/bin/env python3
"""
SAML 2.0 IdP SSO against the live Mini SSO server, reusing an MFA-authenticated session.
Validates the signed Response (Status, Assertion, Audience, Subject, signing cert).

Usage: python3 scripts/saml_sso_flow.py  (server running on :9000)
"""
import base64
import re
import sys
import zlib
import xml.etree.ElementTree as ET

import requests

from sso_auth import authenticate, cleanup

BASE = "http://localhost:9000"
SP_ENTITY_ID = "urn:example:sp"
ACS_URL = "http://127.0.0.1:8090/acs"
REQUEST_ID = "_req_0123456789abcdef"

NS = {
    "samlp": "urn:oasis:names:tc:SAML:2.0:protocol",
    "saml": "urn:oasis:names:tc:SAML:2.0:assertion",
    "ds": "http://www.w3.org/2000/09/xmldsig#",
    "md": "urn:oasis:names:tc:SAML:2.0:metadata",
}


def authn_request() -> str:
    xml = (
        '<samlp:AuthnRequest xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol"'
        ' xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion"'
        f' ID="{REQUEST_ID}" Version="2.0" IssueInstant="2026-06-29T00:00:00Z"'
        f' Destination="{BASE}/saml2/idp/sso" AssertionConsumerServiceURL="{ACS_URL}"'
        ' ProtocolBinding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST">'
        f'<saml:Issuer>{SP_ENTITY_ID}</saml:Issuer></samlp:AuthnRequest>'
    )
    compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
    return base64.b64encode(compressor.compress(xml.encode()) + compressor.flush()).decode()


def main() -> int:
    s = requests.Session()
    authenticate(s, BASE)
    print("[ok] MFA session established")

    resp = s.get(f"{BASE}/saml2/idp/sso", params={"SAMLRequest": authn_request(), "RelayState": "r1"})
    if resp.status_code != 200 or "SAMLResponse" not in resp.text:
        raise SystemExit(f"SSO failed: {resp.status_code}\n{resp.text[:400]}")

    xml = base64.b64decode(re.search(r'name="SAMLResponse" value="([^"]+)"', resp.text).group(1)).decode()
    root = ET.fromstring(xml)
    status = root.find(".//samlp:Status/samlp:StatusCode", NS).attrib["Value"]
    assertion = root.find("saml:Assertion", NS)
    name_id = assertion.find("saml:Subject/saml:NameID", NS).text
    audience = assertion.find("saml:Conditions/saml:AudienceRestriction/saml:Audience", NS).text
    cert = assertion.find("ds:Signature//ds:X509Certificate", NS).text.strip()
    print(f"[ok] status={status.split(':')[-1]} NameID={name_id} Audience={audience} signed=yes")
    assert status.endswith(":Success") and audience == SP_ENTITY_ID and name_id == "admin@example.com"

    md = ET.fromstring(s.get(f"{BASE}/saml2/idp/metadata").text)
    md_cert = md.find(".//md:KeyDescriptor//ds:X509Certificate", NS).text.strip()
    assert "".join(cert.split()) == "".join(md_cert.split())
    print("[ok] assertion signing cert matches IdP metadata")
    cleanup(s, BASE)
    print("\nPASS: SAML2 IdP issued a signed assertion via MFA-gated SSO.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""
Prepare the SAML SP (SimpleSAMLphp) test: fetch the IdP's SAML signing certificate from its
published metadata and write it to the repo-root .env, which docker-compose.test.yml feeds to
SimpleSAMLphp so it can verify the IdP's signed assertions.

Run the IdP first (cd sso-backend && ./gradlew bootRun), then:
    python3 scripts/setup_saml_test.py
    docker compose -f docker-compose.test.yml up
"""
import os
import re
import sys
import urllib.request

BASE = os.environ.get("SSO_BASE", "http://localhost:9000")
METADATA = f"{BASE}/saml2/idp/metadata"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV_FILE = os.path.join(REPO_ROOT, ".env")


def fetch_cert():
    try:
        with urllib.request.urlopen(METADATA, timeout=10) as r:
            xml = r.read().decode()
    except Exception as e:
        sys.exit(f"ERROR: could not fetch {METADATA} — is the IdP running? ({e})")
    m = re.search(r"<[^>]*X509Certificate>(.*?)</[^>]*X509Certificate>", xml, re.DOTALL)
    if not m:
        sys.exit("ERROR: no <X509Certificate> found in IdP metadata.")
    return "".join(m.group(1).split())  # strip all whitespace/newlines -> single base64 line


def upsert_env(key, value):
    lines, found = [], False
    if os.path.exists(ENV_FILE):
        with open(ENV_FILE) as f:
            for line in f:
                if line.startswith(key + "="):
                    lines.append(f"{key}={value}\n")
                    found = True
                else:
                    lines.append(line)
    if not found:
        lines.append(f"{key}={value}\n")
    with open(ENV_FILE, "w") as f:
        f.writelines(lines)


def main():
    cert = fetch_cert()
    upsert_env("SSO_SAML_IDP_CERT", cert)
    print(f"Wrote SSO_SAML_IDP_CERT ({len(cert)} chars) to {ENV_FILE}")
    print("\nNext:")
    print("  docker compose -f docker-compose.test.yml up")
    print("  open http://localhost:8088/simplesaml  -> Authentication -> Test sources -> default-sp")
    print("\nNote: if SimpleSAMLphp reports a different SP entityID/ACS than the seeded relying party,")
    print("      copy them from its metadata page into the IdP admin console (SAML relying parties),")
    print("      or set sso.integration-test.saml-sp-{entity-id,acs-url} and restart the IdP.")


if __name__ == "__main__":
    main()

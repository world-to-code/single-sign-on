# Federation integration testing

Exercise the IdP as a real **OIDC provider**, **SAML IdP**, and **SCIM server** using lightweight
test clients — no Keycloak, no heavy setup.

| Protocol | Test client | What you do |
|---|---|---|
| **OIDC** | `oauth2-proxy` (container) | Browse a protected page → log in via the IdP → land back authenticated. |
| **SAML** | `SimpleSAMLphp` SP (container) | Start at the SP → redirected to the IdP → log in → SP shows your attributes. |
| **SCIM** | `scripts/scim_provision_flow.py` | Provision/read/patch/deprovision a user; confirm it becomes a real account. |

The matching IdP-side fixtures (an OIDC client + a SAML relying party) are **auto-seeded** when
`sso.integration-test.enabled=true` (the default in dev; off in prod).

## 0. Prerequisites

```bash
docker compose up -d                         # PostgreSQL + MailHog
cd sso-backend && ./gradlew bootRun          # IdP at http://localhost:9000  (leave running)
```

Sign-in for the flows below: `admin` / `admin123!` (first login also does email-code + a strong factor).

---

## 1. SCIM — inbound provisioning (no container)

```bash
python3 scripts/scim_provision_flow.py
```

It creates a user over SCIM, shows the user becomes **identifiable** (`/api/auth/identify` → 200),
patches it inactive (→ 404), deletes it (→ 404), and checks that an unauthenticated SCIM call is 401.
Point at a different token with `SCIM_TOKEN=...` (default `dev-scim-token`).

---

## 2. OIDC — log in to an app via the IdP

```bash
docker compose -f docker-compose.test.yml up oauth2-proxy
```

Open <http://localhost:4180>. oauth2-proxy redirects you to the IdP login; after MFA you land on a
"200 Authenticated" page. oauth2-proxy completed a full **authorization-code** exchange against the
IdP (discovery → `/authorize` → `/token` → JWKS). The seeded client:

| clientId | secret | redirect URI | scopes |
|---|---|---|---|
| `oauth2-proxy` | `oauth2-proxy-secret` | `http://localhost:4180/oauth2/callback` | openid profile email |

> Uses **host networking** so the container reaches the IdP at the same `localhost:9000` the browser
> uses (oauth2-proxy requires the discovered `issuer` to match its configured issuer URL).

---

## 3. SAML — log in to an SP via the IdP

```bash
python3 scripts/setup_saml_test.py                       # fetch the IdP signing cert -> .env
docker compose -f docker-compose.test.yml up simplesamlphp
```

Open <http://localhost:8088/simplesaml> → **Authentication → Test configured sources → `default-sp`**.
You're redirected to the IdP, log in, and SimpleSAMLphp shows the **signed assertion's attributes**
(NameID = email). The seeded relying party:

| SP entityID | ACS URL |
|---|---|
| `urn:test:simplesamlphp` | `http://localhost:8088/simplesaml/module.php/saml/sp/saml2-acs.php/default-sp` |

> SAML Web SSO is browser-mediated, so the SP only needs the IdP's **signing certificate** (supplied
> via `.env`) to verify assertions — no server-to-server call. IdP-initiated SSO and unsigned
> `AuthnRequest`s are allowed for this SP, so no SP-side signing setup is required.

---

## Troubleshooting

- **oauth2-proxy: "issuer did not match"** — the IdP's `sso.issuer` must equal `http://localhost:9000`
  (the configured `OAUTH2_PROXY_OIDC_ISSUER_URL`). Host networking keeps both sides on `localhost`.
- **SAML: signature/verification error** — re-run `setup_saml_test.py` (the IdP cert rotates if the
  keystore is regenerated) and restart the `simplesamlphp` container.
- **SAML: "unknown SP" / ACS mismatch** — open the SP metadata page; if its entityID/ACS differ from
  the seeded values, set `sso.integration-test.saml-sp-{entity-id,acs-url}` and restart the IdP, or
  register the SP from the admin console (SAML relying parties).
- **Not on Linux?** swap `network_mode: host` for a port mapping and use `host.docker.internal:9000`
  as the issuer URL (and register that as an allowed value).

## Cleanup

```bash
docker compose -f docker-compose.test.yml down
```

The fixtures are dev-only (`sso.integration-test.enabled=false` in prod, so they are never seeded there).

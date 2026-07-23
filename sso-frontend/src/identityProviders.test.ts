import { describe, expect, it } from "vitest";
import { matchPreset, resolvePresetIssuer, type IdentityProviderPreset } from "./identityProviders";

const preset = (over: Partial<IdentityProviderPreset>): IdentityProviderPreset => ({
  id: "x", displayName: "X", issuerTemplate: "https://x", defaultScopes: "openid", fields: [], ...over,
});

const GOOGLE = preset({ id: "google", displayName: "Google", issuerTemplate: "https://accounts.google.com" });
const ENTRA = preset({
  id: "entra", displayName: "Microsoft Entra ID",
  issuerTemplate: "https://login.microsoftonline.com/{tenant}/v2.0",
  fields: [{ key: "tenant", label: "Directory (tenant) ID", placeholder: "a GUID" }],
});
const OKTA = preset({ id: "okta", displayName: "Okta", issuerTemplate: "https://{domain}" });

describe("resolvePresetIssuer", () => {
  it("substitutes a {key} placeholder from the field values", () => {
    expect(resolvePresetIssuer(ENTRA.issuerTemplate, { tenant: "contoso.onmicrosoft.com" }))
      .toBe("https://login.microsoftonline.com/contoso.onmicrosoft.com/v2.0");
  });

  it("leaves a fixed template (no placeholder) unchanged", () => {
    expect(resolvePresetIssuer(GOOGLE.issuerTemplate, {})).toBe("https://accounts.google.com");
  });

  it("substitutes a missing field with empty, so the resolved issuer is visibly incomplete", () => {
    expect(resolvePresetIssuer(ENTRA.issuerTemplate, {})).toBe("https://login.microsoftonline.com//v2.0");
  });

  it("trims the substituted value", () => {
    expect(resolvePresetIssuer(OKTA.issuerTemplate, { domain: "  dev-1.okta.com  " })).toBe("https://dev-1.okta.com");
  });
});

describe("matchPreset", () => {
  const presets = [GOOGLE, ENTRA, OKTA];

  it("matches a fixed issuer exactly", () => {
    expect(matchPreset("https://accounts.google.com", presets)).toBe(GOOGLE);
  });

  it("matches a templated issuer on its literal parts, regardless of the placeholder segment", () => {
    expect(matchPreset("https://login.microsoftonline.com/any-tenant-guid/v2.0", presets)).toBe(ENTRA);
  });

  it("does not badge a bare-host template (Okta), which is unidentifiable by issuer", () => {
    // https://{domain} would otherwise compile to ^https://.+$ and match every provider — so it matches none.
    expect(matchPreset("https://dev-12345.okta.com", presets)).toBeNull();
    expect(matchPreset("https://idp.acme.example", presets)).toBeNull();
  });

  it("anchors the match, so a look-alike host is not a Google provider", () => {
    expect(matchPreset("https://accounts.google.com.evil.example", presets)).toBeNull();
  });
});

import { describe, expect, it } from "vitest";
import { presetById, resolvePresetIssuer, type IdentityProviderPreset } from "./identityProviders";

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

describe("presetById", () => {
  const presets = [GOOGLE, ENTRA, OKTA];

  it("resolves the vendor by its stored id — including a bare-host one like Okta that issuer-matching could not", () => {
    expect(presetById("google", presets)).toBe(GOOGLE);
    expect(presetById("okta", presets)).toBe(OKTA);
  });

  it("is null for a custom connection (no preset id)", () => {
    expect(presetById(null, presets)).toBeNull();
  });

  it("is null when the stored id names a preset this deployment no longer configures", () => {
    expect(presetById("onelogin", presets)).toBeNull();
  });
});

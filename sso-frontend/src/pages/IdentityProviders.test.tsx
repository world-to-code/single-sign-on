import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import IdentityProviders from "./IdentityProviders";
import { ConfirmProvider } from "@/components/ConfirmProvider";
import { apiGet } from "@/api";
import { saveIdentityProvider, type IdentityProvider, type IdentityProviderPreset } from "@/identityProviders";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string, opts?: { name?: string }) => (opts?.name ? `${key}:${opts.name}` : key),
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
  Trans: ({ i18nKey }: { i18nKey: string }) => i18nKey,
}));

// Keep the pure helpers (resolvePresetIssuer/matchPreset) real; only the network write is stubbed.
vi.mock("@/identityProviders", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/identityProviders")>()),
  saveIdentityProvider: vi.fn().mockResolvedValue({}),
}));

vi.mock("@/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/api")>()),
  apiGet: vi.fn(),
}));

const PRESETS: IdentityProviderPreset[] = [
  { id: "google", displayName: "Google", issuerTemplate: "https://accounts.google.com",
    defaultScopes: "openid email profile", fields: [] },
  { id: "entra", displayName: "Microsoft Entra ID",
    issuerTemplate: "https://login.microsoftonline.com/{tenant}/v2.0", defaultScopes: "openid email profile",
    fields: [{ key: "tenant", label: "Directory (tenant) ID", placeholder: "a GUID" }] },
  { id: "okta", displayName: "Okta", issuerTemplate: "https://{domain}", defaultScopes: "openid email profile",
    fields: [{ key: "domain", label: "Okta domain", placeholder: "dev-1.okta.com" }] },
];

const provider = (over: Partial<IdentityProvider>): IdentityProvider => ({
  alias: "a", displayName: "A", issuerUri: "https://idp.acme.example", clientId: "c", scopes: "openid",
  allowJitProvisioning: false, linkByVerifiedEmail: false, enabled: true, presetId: null, ...over,
});

const PROVIDERS: IdentityProvider[] = [
  provider({ alias: "corp-google", displayName: "Corp Google", issuerUri: "https://accounts.google.com", presetId: "google" }),
  provider({ alias: "legacy", displayName: "Legacy", issuerUri: "https://sso.legacy.example", presetId: null }),
];

const renderPage = () => render(<ConfirmProvider><IdentityProviders /></ConfirmProvider>);

/**
 * The card-driven create flow: a one-click card per preset and a dashed custom card; a preset pre-fills the
 * dialog and, where the issuer is templated, builds it from the extra fields. The list badges each provider
 * with the vendor its issuer matches. Pure helpers are covered in identityProviders.test.ts.
 */
describe("IdentityProviders", () => {
  beforeEach(() => {
    vi.mocked(apiGet).mockImplementation((url: string) =>
      Promise.resolve((url.endsWith("/presets") ? PRESETS : PROVIDERS) as never));
  });

  it("shows a one-click card per preset and a custom card", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByRole("button", { name: /Google/ })).toBeInTheDocument());

    expect(screen.getByRole("button", { name: /Microsoft Entra ID/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Okta/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /idpCustomCard/ })).toBeInTheDocument();
  });

  it("pre-fills the dialog from a fixed-issuer preset card", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByRole("button", { name: /Google/ })).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: /Google/ }));

    await waitFor(() => expect(screen.getByText("idpDialogCreatePreset:Google")).toBeInTheDocument());
    expect(screen.getByLabelText("idpAliasLabel")).toHaveValue("google");
    expect(screen.getByLabelText("idpNameLabel")).toHaveValue("Google");
    expect(screen.getByLabelText("idpIssuerLabel")).toHaveValue("https://accounts.google.com");
  });

  it("builds the issuer from a templated preset's extra field", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByRole("button", { name: /Microsoft Entra ID/ })).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: /Microsoft Entra ID/ }));
    await waitFor(() => expect(screen.getByLabelText("Directory (tenant) ID")).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText("Directory (tenant) ID"), { target: { value: "contoso" } });

    // The resolved issuer (read-only) is rebuilt from the template + field.
    expect(screen.getByLabelText("idpIssuerLabel")).toHaveValue("https://login.microsoftonline.com/contoso/v2.0");
    expect(screen.getByLabelText("idpIssuerLabel")).toHaveAttribute("readonly");
  });

  it("opens a blank, editable issuer for a custom connection", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByRole("button", { name: /idpCustomCard/ })).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: /idpCustomCard/ }));

    await waitFor(() => expect(screen.getByText("idpDialogCreate")).toBeInTheDocument());
    const issuer = screen.getByLabelText("idpIssuerLabel");
    expect(issuer).toHaveValue("");
    expect(issuer).not.toHaveAttribute("readonly");
  });

  it("badges each provider with the vendor its stored preset id names", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText("Corp Google")).toBeInTheDocument());

    const googleRow = screen.getAllByRole("row").find((r) => r.textContent?.includes("Corp Google"))!;
    expect(googleRow.textContent).toContain("Google"); // presetId "google" → catalog display name

    const legacyRow = screen.getAllByRole("row").find((r) => r.textContent?.includes("Legacy"))!;
    expect(legacyRow.textContent).toContain("idpVendorCustom"); // presetId null → custom
  });

  it("sends the preset id when creating from a vendor card", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByRole("button", { name: /Google/ })).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: /Google/ }));
    await waitFor(() => expect(screen.getByLabelText("idpClientIdLabel")).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText("idpClientIdLabel"), { target: { value: "client-1" } });
    fireEvent.change(screen.getByLabelText("idpClientSecretLabel"), { target: { value: "s3cret" } });
    fireEvent.click(screen.getByRole("button", { name: /idpCreate/ }));

    await waitFor(() => expect(saveIdentityProvider).toHaveBeenCalled());
    const body = vi.mocked(saveIdentityProvider).mock.calls[0][1];
    expect(body.presetId).toBe("google");
    expect(body.issuerUri).toBe("https://accounts.google.com"); // prefilled from the fixed-issuer preset
  });
});

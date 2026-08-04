import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import AuthLayout from "./AuthLayout";
import { BrandingContextValue } from "../BrandingProvider";
import type { Branding, ScreenCopy } from "@/branding";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

/**
 * A tenant's own wording has to WIN over the screen's built-in strings, and has to lose gracefully: a screen
 * the tenant has not written, or a field it left blank, must fall back rather than render an empty heading.
 * That per-field fallback is the whole contract here, so each direction gets its own case.
 */
describe("AuthLayout tenant wording", () => {
  function branding(copy: Partial<Record<string, ScreenCopy>>): Branding {
    return {
      identity: { logoUrl: null, logoUrlDark: null, faviconUrl: null, productName: "Acme" },
      theme: {
        accentColor: null, backgroundColor: null, backgroundImageUrl: null,
        font: null, corner: null, layout: null,
      },
      copy: copy as Branding["copy"],
    };
  }

  function renderWith(value: Branding, screenName?: Parameters<typeof AuthLayout>[0]["screen"]) {
    return render(
      <BrandingContextValue.Provider value={value}>
        <AuthLayout title="Built-in title" description="Built-in description" screen={screenName}>
          <p>form</p>
        </AuthLayout>
      </BrandingContextValue.Provider>,
    );
  }

  it("uses the tenant's headline and subtext for that screen", () => {
    renderWith(branding({ LOGIN: { headline: "Sign in to Acme", subtext: "Use your work account", footer: null, helpUrl: null } }), "LOGIN");

    expect(screen.getByText("Sign in to Acme")).toBeInTheDocument();
    expect(screen.getByText("Use your work account")).toBeInTheDocument();
    expect(screen.queryByText("Built-in title")).not.toBeInTheDocument();
  });

  it("keeps the built-in wording for a field the tenant left blank", () => {
    renderWith(branding({ LOGIN: { headline: "Sign in to Acme", subtext: null, footer: null, helpUrl: null } }), "LOGIN");

    expect(screen.getByText("Sign in to Acme")).toBeInTheDocument();
    expect(screen.getByText("Built-in description")).toBeInTheDocument();
  });

  it("keeps the built-in wording for a screen the tenant has not written", () => {
    renderWith(branding({ MFA: { headline: "Verify", subtext: null, footer: null, helpUrl: null } }), "LOGIN");

    expect(screen.getByText("Built-in title")).toBeInTheDocument();
    expect(screen.getByText("Built-in description")).toBeInTheDocument();
  });

  it("keeps the built-in wording when the layout is not told which screen it is", () => {
    renderWith(branding({ LOGIN: { headline: "Sign in to Acme", subtext: null, footer: null, helpUrl: null } }));

    expect(screen.getByText("Built-in title")).toBeInTheDocument();
  });

  it("renders the tenant's footer note and help link", () => {
    renderWith(branding({ LOGIN: { headline: null, subtext: null, footer: "Trouble signing in?", helpUrl: "https://help.acme.example" } }), "LOGIN");

    expect(screen.getByText("Trouble signing in?")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /help/i })).toHaveAttribute("href", "https://help.acme.example");
  });

  /** A footer with no link must not draw an empty anchor, and a link with no footer must still be reachable. */
  it("renders a help link without a footer note", () => {
    renderWith(branding({ LOGIN: { headline: null, subtext: null, footer: null, helpUrl: "https://help.acme.example" } }), "LOGIN");

    expect(screen.getByRole("link", { name: /help/i })).toBeInTheDocument();
  });

  it("draws no link when the tenant set only a footer note", () => {
    renderWith(branding({ LOGIN: { headline: null, subtext: null, footer: "Trouble signing in?", helpUrl: null } }), "LOGIN");

    expect(screen.getByText("Trouble signing in?")).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  /** Tenant content is TEXT. Markup in it is shown, never interpreted — the property the backend relies on. */
  it("renders markup in the tenant's wording as text", () => {
    renderWith(branding({ LOGIN: { headline: "<img src=x onerror=alert(1)>", subtext: null, footer: null, helpUrl: null } }), "LOGIN");

    expect(screen.getByText("<img src=x onerror=alert(1)>")).toBeInTheDocument();
    expect(document.querySelector("img")).toBeNull();
  });
});

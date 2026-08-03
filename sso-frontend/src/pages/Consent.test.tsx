/**
 * @vitest-environment-options { "url": "http://octatco.localhost:5173/consent?client_id=consent-demo&scope=openid%20profile%20email%20offline_access&state=xyz-state" }
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import Consent from "./Consent";
import { getConsent } from "../consent";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

vi.mock("../consent", () => ({ getConsent: vi.fn() }));

vi.mock("@/branding", () => ({
  getBranding: vi.fn().mockResolvedValue({ logoUrl: null, accentColor: null, productName: null }),
}));

const MODEL = {
  clientName: "Grafana",
  redirectHost: "grafana.acme.io",
  thirdParty: false,
  toApprove: [
    { scope: "profile", description: "Your basic profile" },
    { scope: "email", description: "Your email address" },
    { scope: "offline_access", description: "Access while you are away" },
  ],
  previouslyGranted: [],
};

/**
 * The consent screen posts a real form to `/oauth2/authorize`, which owns the grant — so the contract this
 * test pins is the one the authorization endpoint reads, not the layout. It used to be asserted against the
 * Thymeleaf template in `ConsentPageRenderIT`; the page moved to the SPA and the contract moved with it.
 */
describe("Consent", () => {
  beforeEach(() => {
    vi.mocked(getConsent).mockResolvedValue(MODEL);
    document.cookie = "XSRF-TOKEN=tok-123";
  });

  const allowForm = (c: HTMLElement) => c.querySelector<HTMLFormElement>("#allow-form")!;
  const cancelForm = (c: HTMLElement) => c.querySelector<HTMLFormElement>("#cancel-form")!;

  it("posts the granted scopes back to the authorization endpoint", async () => {
    const { container } = render(<Consent />);
    await waitFor(() => expect(allowForm(container)).toBeTruthy());

    for (const form of [allowForm(container), cancelForm(container)]) {
      expect(form.getAttribute("method")).toBe("post");
      expect(form.getAttribute("action")).toBe("/oauth2/authorize");
      // The endpoint binds these; state is the OAuth request correlation, so losing it aborts the flow.
      expect(form.querySelector('input[name="client_id"]')).toHaveValue("consent-demo");
      expect(form.querySelector('input[name="state"]')).toHaveValue("xyz-state");
      // /oauth2/authorize is NOT in the CSRF ignore list — a form without this token is rejected outright.
      expect(form.querySelector('input[name="_csrf"]')).toHaveValue("tok-123");
    }
  });

  it("offers each approvable scope as a checkbox and leaves durable access opt-in", async () => {
    const { container } = render(<Consent />);
    await waitFor(() => expect(allowForm(container)).toBeTruthy());
    const box = (scope: string) =>
      allowForm(container).querySelector<HTMLInputElement>(`input[name="scope"][value="${scope}"]`)!;

    expect(box("profile").type).toBe("checkbox");
    expect(box("profile").checked).toBe(true);
    expect(box("email").checked).toBe(true);
    // offline_access grants access when nobody is watching; pre-ticking it would be consent by inattention.
    expect(box("offline_access").checked).toBe(false);
  });

  /** openid is implicit for OIDC and re-added by the server — a toggle would imply it is refusable. */
  it("never offers openid as a scope input", async () => {
    const { container } = render(<Consent />);
    await waitFor(() => expect(allowForm(container)).toBeTruthy());

    expect(container.querySelector('input[value="openid"]')).toBeNull();
  });

  /** Denial is a POST carrying no scopes, so cancel must not share the allow form's checkboxes. */
  it("cancels by posting no scopes at all", async () => {
    const { container } = render(<Consent />);
    await waitFor(() => expect(cancelForm(container)).toBeTruthy());

    expect(cancelForm(container).querySelectorAll('input[name="scope"]')).toHaveLength(0);
  });

  /** A consent screen that hides where it is about to send you is complicit in phishing. */
  it("names the destination", async () => {
    render(<Consent />);

    expect(await screen.findByText("grafana.acme.io")).toBeInTheDocument();
  });

  /** A failed load must say so — silently rendering an empty approval list would be worse than an error. */
  it("surfaces a failed load instead of an empty form", async () => {
    vi.mocked(getConsent).mockRejectedValue(new Error("boom"));
    const { container } = render(<Consent />);

    expect(await screen.findByText("consentLoadFailed")).toBeInTheDocument();
    expect(allowForm(container)).toBeNull();
  });
});

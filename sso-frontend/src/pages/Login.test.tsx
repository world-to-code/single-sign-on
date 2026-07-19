/**
 * @vitest-environment-options { "url": "http://octatco.localhost:5173/login" }
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import Login from "./Login";
import type { SessionView } from "../auth";
import { getSession, logout } from "../auth";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

vi.mock("../auth", () => ({
  getSession: vi.fn(),
  identify: vi.fn(),
  logout: vi.fn(),
  startFederation: vi.fn(),
}));

vi.mock("../webauthn", () => ({
  webAuthnSupported: () => false,
  conditionalMediationAvailable: vi.fn().mockResolvedValue(false),
  conditionalPasswordlessLogin: vi.fn(),
  passwordlessLogin: vi.fn(),
}));

vi.mock("@/branding", () => ({
  getBranding: vi.fn().mockResolvedValue({ logoUrl: null, accentColor: null, productName: null }),
}));

const APEX_SESSION: SessionView = {
  authenticated: false, username: null, totpEnrolled: false, fido2Enrolled: false,
  factors: [], roles: [], permissions: [], next: "ORGANIZATION", pendingFactors: [],
  mfaEnrollmentAllowed: true, org: null, passwordlessLoginAllowed: false, federationProviders: [],
};

function sessionFor(org: string | null): SessionView {
  return { ...APEX_SESSION, next: "IDENTIFY", org };
}

/** Replaces window.location so the test controls the host and observes navigation instead of performing it. */
function atHost(host: string) {
  const assign = vi.fn();
  Object.defineProperty(window, "location", {
    configurable: true,
    value: { protocol: "http:", host, pathname: "/login", assign },
  });
  return assign;
}

const clickUseDifferentOrg = async () => {
  const button = await screen.findByRole("button", { name: "useDifferentOrg" });
  button.click();
};

describe("Login — leaving for a different organization", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(getSession).mockResolvedValue(APEX_SESSION);
    vi.mocked(logout).mockResolvedValue({ samlLogoutRedirect: null });
  });

  // The reported bug: on the tenant's own subdomain the backend re-resolves the org from the Host header, so
  // clearing the selection and re-probing landed the user straight back on this same screen.
  it("leaves the tenant subdomain for the platform host's picker", async () => {
    const assign = atHost("octatco.localhost:5173");
    const onDone = vi.fn();
    render(<Login session={sessionFor("octatco")} onDone={onDone} />);

    await clickUseDifferentOrg();

    await waitFor(() => expect(assign).toHaveBeenCalledWith("http://localhost:5173/login"));
    expect(onDone).not.toHaveBeenCalled(); // re-probing here would just re-resolve the same org
  });

  it("clears the server-side selection before navigating away", async () => {
    const order: string[] = [];
    const assign = atHost("octatco.localhost:5173");
    vi.mocked(logout).mockImplementation(async () => {
      order.push("logout");
      return { samlLogoutRedirect: null };
    });
    assign.mockImplementation(() => { order.push("assign"); });
    render(<Login session={sessionFor("octatco")} onDone={vi.fn()} />);

    await clickUseDifferentOrg();

    await waitFor(() => expect(order).toEqual(["logout", "assign"]));
  });

  // The catch around logout() is load-bearing: an already-expired session must not strand the user here.
  it("still navigates away when clearing the selection fails", async () => {
    const assign = atHost("octatco.localhost:5173");
    vi.mocked(logout).mockRejectedValue(new Error("session already gone"));
    render(<Login session={sessionFor("octatco")} onDone={vi.fn()} />);

    await clickUseDifferentOrg();

    await waitFor(() => expect(assign).toHaveBeenCalledWith("http://localhost:5173/login"));
  });

  it("stays on the platform host and re-probes, where the picker already lives", async () => {
    const assign = atHost("localhost:5173");
    const onDone = vi.fn();
    render(<Login session={sessionFor("octatco")} onDone={onDone} />);

    await clickUseDifferentOrg();

    await waitFor(() => expect(onDone).toHaveBeenCalledWith(APEX_SESSION));
    expect(assign).not.toHaveBeenCalled();
  });

  it("re-probes rather than navigating when no organization is resolved", async () => {
    const assign = atHost("octatco.localhost:5173");
    const onDone = vi.fn();
    render(<Login session={sessionFor(null)} onDone={onDone} />);

    await clickUseDifferentOrg();

    await waitFor(() => expect(onDone).toHaveBeenCalledWith(APEX_SESSION));
    expect(assign).not.toHaveBeenCalled();
  });
});

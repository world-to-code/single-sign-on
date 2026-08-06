import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";

/**
 * Which screen each page tells AuthLayout it is.
 *
 * <p>This guards a defect that SHIPPED. `screen` was wired by matching the first `<AuthLayout` in each file,
 * and on three of five pages that was the error, spinner or done branch rather than the one that renders the
 * form — so a tenant's wording silently did nothing on those screens. The fix touched the pages; the tests
 * added with it lived in AuthLayout.test.tsx, which receives `screen` as a prop and therefore cannot see
 * which branch passed it.
 *
 * <p><b>The assertion has to be about the SETTLED render, and getting that wrong makes this file theatre.</b>
 * A first attempt recorded every render's props and waited for one of them to carry the expected value. That
 * passes the moment ANY render matches — so with `screen` moved onto the spinner branch, the spinner's own
 * render satisfied the wait before the real one ever happened, and the mutation survived. It only failed on
 * the pages whose data resolved before the first poll, which made the whole file a timing coincidence.
 *
 * <p>So the mock puts `screen` in the DOM instead, where only the CURRENT render is visible, and each case
 * first waits for something the settled branch alone draws. Asserting after that reads the branch the user
 * is actually looking at.
 */

vi.mock("@/components/layout/AuthLayout", () => ({
  default: (props: { screen?: string; children?: ReactNode }) => (
    <div data-testid="layout" data-screen={props.screen ?? ""}>{props.children}</div>
  ),
}));

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

vi.mock("../consent", () => ({ getConsent: vi.fn(), approvalForm: vi.fn(() => ({})) }));
vi.mock("../onboarding", () => ({ setInvitationPassword: vi.fn() }));
vi.mock("../portal", () => ({ getStepUp: vi.fn() }));

const { getConsent } = await import("../consent");
const { getStepUp } = await import("../portal");
const Consent = (await import("./Consent")).default;
const SetPassword = (await import("./SetPassword")).default;
const AppStepUp = (await import("./AppStepUp")).default;

/** The screen the page is telling AuthLayout right now — not one it passed on the way here. */
function screenOnDisplay(): string | null {
  return screen.getByTestId("layout").getAttribute("data-screen");
}

describe("each auth page tells AuthLayout which screen it is", () => {
  beforeEach(() => vi.clearAllMocks());

  /**
   * Consent matters most: its `screen` had landed on the ERROR branch, so the real consent card — the one
   * naming the client asking for access — was the only screen with no wording binding at all, and the title
   * lock that protects that name had nothing to apply to.
   */
  it("Consent passes CONSENT on the card that renders the request", async () => {
    vi.mocked(getConsent).mockResolvedValue({
      clientName: "Grafana", redirectHost: "grafana.acme.io", thirdParty: false,
      toApprove: [{ scope: "profile", description: "basic profile" }], previouslyGranted: [],
    } as never);

    render(<Consent />);
    // The destination card. The client NAME goes to AuthLayout's title, which the mock drops, so the marker
    // is the host beside it — either way the spinner and error branches draw neither.
    await screen.findByText("grafana.acme.io");

    expect(screenOnDisplay()).toBe("CONSENT");
  });

  /** SetPassword's had landed on the "done" state, which a user only reaches after submitting. */
  it("SetPassword passes RESET on the form, not only on the done state", async () => {
    render(<SetPassword />);
    await screen.findByLabelText("newPassword"); // the done state has text and a button, no field

    expect(screenOnDisplay()).toBe("RESET");
  });

  /**
   * The third page with more than one AuthLayout, and the last one this defect could still reach — Login,
   * MfaStep and ForcePasswordReset each render exactly one, so there is no wrong branch for `screen` to land
   * on. Here the other branch is the spinner shown while the pending factors load, which is the branch the
   * original bug preferred: it is the one written first.
   */
  it("AppStepUp passes STEPUP on the challenge, not on the spinner before it", async () => {
    vi.mocked(getStepUp).mockResolvedValue({
      ready: false, pendingFactors: ["TOTP"], returnUrl: "/portal",
    });

    render(<AppStepUp />);
    await screen.findByRole("button"); // the factor chooser; the spinner branch draws no control

    expect(screenOnDisplay()).toBe("STEPUP");
  });
});

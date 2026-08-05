import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, waitFor } from "@testing-library/react";
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
 * <p>So the assertion here is about the page's NORMAL state specifically. AuthLayout is replaced by a spy
 * that records the props it was rendered with — the page's own data mocks are all that is needed to drive it
 * past the loading and error branches, and nothing about the layout itself is under test.
 */

const layoutProps: Array<Record<string, unknown>> = [];

vi.mock("@/components/layout/AuthLayout", () => ({
  default: (props: { children?: ReactNode }) => {
    layoutProps.push(props as Record<string, unknown>);
    return <div>{props.children}</div>;
  },
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

const { getConsent } = await import("../consent");
const Consent = (await import("./Consent")).default;
const SetPassword = (await import("./SetPassword")).default;

/** The prop the page passed on the render that actually drew a form — the last one, once settled. */
function screenOfTheRenderedScreen(): unknown {
  return layoutProps.at(-1)?.screen;
}

describe("each auth page tells AuthLayout which screen it is", () => {
  beforeEach(() => {
    layoutProps.length = 0;
    vi.clearAllMocks();
  });

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

    await waitFor(() => expect(screenOfTheRenderedScreen()).toBe("CONSENT"));
  });

  /** SetPassword's had landed on the "done" state, which a user only reaches after submitting. */
  it("SetPassword passes RESET on the form, not only on the done state", async () => {
    render(<SetPassword />);

    await waitFor(() => expect(screenOfTheRenderedScreen()).toBe("RESET"));
  });
});

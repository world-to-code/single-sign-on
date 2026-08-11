import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import { StepUpProvider } from "./StepUpProvider";
import { ApiError, triggerStepUp } from "@/api";
import { getSessionConfig } from "@/portal";
import { reauthVerify } from "@/auth";
import type { SessionView } from "@/auth";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

vi.mock("@/portal", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/portal")>()),
  getSessionConfig: vi.fn(),
}));

vi.mock("@/auth", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/auth")>()),
  reauthVerify: vi.fn(),
}));

const session: SessionView = {
  authenticated: true, username: "ada", totpEnrolled: true, fido2Enrolled: false,
  factors: ["FACTOR_PASSWORD"], roles: [], permissions: [], next: "DONE", pendingFactors: [],
  mfaEnrollmentAllowed: true, org: "acme", passwordlessLoginAllowed: false, federationProviders: [],
};

const config = (reauthFactors: string[]) => ({ idleTimeoutMinutes: 30, reauthFactors });

/**
 * A PROACTIVE step-up (entering the admin console) has no server challenge naming the allowed factors, so the
 * modal asks the policy itself. What it does when that ask FAILS is the case under test.
 */
describe("StepUpProvider", () => {
  beforeEach(() => {
    vi.mocked(getSessionConfig).mockResolvedValue(config(["PASSWORD", "TOTP"]) as never);
  });

  const open = async (): Promise<void> => {
    render(<StepUpProvider session={session}>{null}</StepUpProvider>);
    // Awaited inside act so the prefetch settles before the prompt reads it.
    await act(async () => { void triggerStepUp("action"); });
  };

  it("offers the factors the policy allows", async () => {
    await open();

    await waitFor(() => expect(screen.getByText("factorPassword")).toBeInTheDocument());
    expect(screen.getByText("factorTotp")).toBeInTheDocument();
  });

  /**
   * Failing closed here is correct — an unknown policy must not let the chooser offer a method the server
   * then rejects. But an empty set renders as "no allowed factor is available: set up an authenticator",
   * which sends the user to enrol a factor they already have and leaves them exactly as stuck.
   */
  it("says the policy could not be read rather than blaming the user's enrolment", async () => {
    vi.mocked(getSessionConfig).mockRejectedValue(new Error("network down"));
    await open();

    await waitFor(() => expect(screen.getByText("reauthPolicyUnavailable")).toBeInTheDocument());
    expect(screen.queryByText("reauthNoMethods")).not.toBeInTheDocument();
    expect(screen.queryByText("reauthNoMethodsElevation")).not.toBeInTheDocument();
  });

  /** Still fails closed: nothing is offered while the policy is unknown. */
  it("offers no factor at all while the policy is unknown", async () => {
    vi.mocked(getSessionConfig).mockRejectedValue(new Error("network down"));
    await open();

    await waitFor(() => expect(screen.getByText("reauthPolicyUnavailable")).toBeInTheDocument());
    expect(screen.queryByText("factorPassword")).not.toBeInTheDocument();
    expect(screen.queryByText("factorTotp")).not.toBeInTheDocument();
  });

  /**
   * The server distinguishes a spent code from a wrong one; the modal used to map every ApiError onto its
   * own fixed key ("Invalid code — try again."), so the distinction could not reach the screen at all.
   */
  describe("the reason shown for a failed verification", () => {
    const openTotp = async (): Promise<void> => {
      await open();
      await waitFor(() => expect(screen.getByText("factorTotp")).toBeInTheDocument());
      await act(async () => { screen.getByText("factorTotp").click(); });
      await act(async () => {
        const form = document.querySelector("form")!;
        form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
      });
    };

    it("shows what the server said, not a fixed local string", async () => {
      vi.mocked(reauthVerify).mockRejectedValue(
        new ApiError(400, "That code has already been used or has expired."));
      await openTotp();

      await waitFor(() =>
        expect(screen.getByText("That code has already been used or has expired.")).toBeInTheDocument());
    });

    /** A request that never got an answer has no server detail to prefer, so a local key is right there. */
    it("falls back to a local message when the request never reached the server", async () => {
      vi.mocked(reauthVerify).mockRejectedValue(new TypeError("network down"));
      await openTotp();

      await waitFor(() => expect(screen.getByText("reauthFailed")).toBeInTheDocument());
    });
  });

  it("recovers when the policy load is retried", async () => {
    vi.mocked(getSessionConfig).mockRejectedValue(new Error("network down"));
    await open();
    await waitFor(() => expect(screen.getByText("reauthPolicyUnavailable")).toBeInTheDocument());

    vi.mocked(getSessionConfig).mockResolvedValue(config(["PASSWORD"]) as never);
    await act(async () => { screen.getByRole("button", { name: "reauthRetryPolicy" }).click(); });

    await waitFor(() => expect(screen.getByText("factorPassword")).toBeInTheDocument());
    expect(screen.queryByText("reauthPolicyUnavailable")).not.toBeInTheDocument();
  });
});

import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { AccountHoldCard } from "./AccountHoldCard";
import type { AccountHoldStatus } from "@/users";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

const getUserHold = vi.fn();
const holdUser = vi.fn();
const liftUserHold = vi.fn();
vi.mock("@/users", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/users")>()),
  getUserHold: (id: string) => getUserHold(id),
  holdUser: (id: string, reason: string, minutes: number) => holdUser(id, reason, minutes),
  liftUserHold: (id: string) => liftUserHold(id),
}));

const NOT_HELD: AccountHoldStatus = {
  held: false, reason: null, placedAt: null, expiresAt: null, placedBy: null, correlationId: null,
};
const HELD: AccountHoldStatus = {
  held: true, reason: "impossible travel", placedAt: "2026-08-09T00:00:00Z",
  expiresAt: "2026-08-09T04:00:00Z", placedBy: null, correlationId: "xdr-42",
};

/**
 * The card an administrator uses to hold and release an account.
 *
 * <p>Two behaviours are worth pinning rather than eyeballing. A hold with no reason must not be placeable —
 * the reason is the only thing that tells whoever finds it later whether to lift it. And a failed READ must
 * be visible: an error swallowed into "not held" would show the release form for an account that is held,
 * which is the one wrong answer an administrator would act on.
 */
describe("AccountHoldCard", () => {
  beforeEach(() => {
    getUserHold.mockReset();
    holdUser.mockReset();
    liftUserHold.mockReset();
  });

  it("offers to place a hold when the account is not held", async () => {
    getUserHold.mockResolvedValue(NOT_HELD);

    render(<AccountHoldCard userId="u1" />);

    await waitFor(() => expect(screen.getByLabelText("userDetailHoldReason")).toBeInTheDocument());
    expect(screen.queryByText("userDetailHoldActive")).not.toBeInTheDocument();
  });

  it("refuses to place a hold with no reason", async () => {
    getUserHold.mockResolvedValue(NOT_HELD);
    render(<AccountHoldCard userId="u1" />);
    await waitFor(() => expect(screen.getByLabelText("userDetailHoldReason")).toBeInTheDocument());

    expect(screen.getByRole("button", { name: "userDetailHoldPlace" })).toBeDisabled();

    // Whitespace is not a reason.
    fireEvent.change(screen.getByLabelText("userDetailHoldReason"), { target: { value: "  " } });
    expect(screen.getByRole("button", { name: "userDetailHoldPlace" })).toBeDisabled();
  });

  it("sends the reason and the chosen duration", async () => {
    getUserHold.mockResolvedValue(NOT_HELD);
    holdUser.mockResolvedValue(HELD);
    render(<AccountHoldCard userId="u1" />);
    await waitFor(() => expect(screen.getByLabelText("userDetailHoldReason")).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText("userDetailHoldReason"), { target: { value: "phishing report" } });
    fireEvent.change(screen.getByLabelText("userDetailHoldDuration"), { target: { value: "720" } });
    fireEvent.click(screen.getByRole("button", { name: "userDetailHoldPlace" }));

    await waitFor(() => expect(holdUser).toHaveBeenCalledWith("u1", "phishing report", 720));
  });

  /** A machine's hold names the detection, because inventing a person for it would misattribute a decision. */
  it("shows a machine-placed hold by its correlation id", async () => {
    getUserHold.mockResolvedValue(HELD);

    render(<AccountHoldCard userId="u1" />);

    await waitFor(() => expect(screen.getByText("xdr-42")).toBeInTheDocument());
    expect(screen.getByText("impossible travel")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "userDetailHoldLift" })).toBeInTheDocument();
  });

  it("shows a failed read instead of rendering the account as un-held", async () => {
    getUserHold.mockRejectedValue(new Error("nope"));

    render(<AccountHoldCard userId="u1" />);

    await waitFor(() => expect(screen.queryByLabelText("userDetailHoldReason")).not.toBeInTheDocument());
    expect(screen.getByRole("alert")).toBeInTheDocument();
  });
});

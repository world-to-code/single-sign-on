import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import AccountHeld from "./AccountHeld";
import type { SessionView } from "../auth";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

const logout = vi.fn().mockResolvedValue(undefined);
const getSession = vi.fn();
vi.mock("../auth", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../auth")>()),
  logout: () => logout(),
  getSession: () => getSession(),
}));

/**
 * The refused sign-in's last screen. Two things matter and neither is cosmetic: it must render at all (a
 * missing string here is a dead end nobody can read their way out of), and it must offer NO way forward —
 * anything actionable would be a route past the hold using the password it exists to distrust.
 */
describe("AccountHeld", () => {
  const session = { username: "someone", org: "acme" } as SessionView;

  it("explains the refusal and offers only the way back", () => {
    render(<AccountHeld session={session} onDone={vi.fn()} />);

    expect(screen.getByText("accountHeldTitle")).toBeInTheDocument();
    expect(screen.getByText("accountHeldGuidance")).toBeInTheDocument();
    // Nothing to submit: no form, no inputs, nothing that could carry a second attempt.
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("abandons the half-authenticated session on the way back", async () => {
    const onDone = vi.fn();
    const refreshed = { next: "IDENTIFY" } as SessionView;
    getSession.mockResolvedValue(refreshed);

    render(<AccountHeld session={session} onDone={onDone} />);
    fireEvent.click(screen.getAllByRole("button", { name: "accountHeldBack" })[0]);

    await waitFor(() => expect(onDone).toHaveBeenCalledWith(refreshed));
    expect(logout).toHaveBeenCalled();
  });
});

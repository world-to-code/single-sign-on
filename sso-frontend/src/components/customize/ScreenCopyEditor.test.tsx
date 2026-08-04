import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ScreenCopyEditor } from "./ScreenCopyEditor";
import { deleteScreenCopy, getScreenCopy, updateScreenCopy } from "@/branding";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));
vi.mock("@/branding", () => ({
  getScreenCopy: vi.fn(),
  updateScreenCopy: vi.fn(),
  deleteScreenCopy: vi.fn(),
  AUTH_SCREENS: ["LOGIN", "MFA", "STEPUP", "CONSENT", "RESET"],
}));
vi.mock("../ToastProvider", () => ({ useToast: () => vi.fn() }));
vi.mock("../ConfirmProvider", () => ({ useConfirm: () => vi.fn().mockResolvedValue(true) }));

/**
 * The editor writes ONE screen at a time, so the case that matters most is that switching screens does not
 * carry the previous screen's text into the next one's form — that would silently overwrite wording the
 * tenant never touched, on a screen they were only looking at.
 */
describe("ScreenCopyEditor", () => {
  const load = vi.mocked(getScreenCopy);
  const save = vi.mocked(updateScreenCopy);
  const drop = vi.mocked(deleteScreenCopy);

  beforeEach(() => {
    vi.clearAllMocks();
    load.mockResolvedValue({
      LOGIN: { headline: "Sign in to Acme", subtext: null, footer: null, helpUrl: null },
      MFA: { headline: "Verify it is you", subtext: null, footer: null, helpUrl: null },
    });
    save.mockResolvedValue({});
    drop.mockResolvedValue();
  });

  async function renderEditor() {
    render(<ScreenCopyEditor />);
    await waitFor(() => expect(screen.getByDisplayValue("Sign in to Acme")).toBeInTheDocument());
  }

  it("shows the wording of the screen being edited", async () => {
    await renderEditor();

    expect(screen.getByDisplayValue("Sign in to Acme")).toBeInTheDocument();
  });

  it("swaps the form to the selected screen's own wording", async () => {
    await renderEditor();

    fireEvent.click(screen.getByRole("radio", { name: "brandingScreen_MFA" }));

    await waitFor(() => expect(screen.getByDisplayValue("Verify it is you")).toBeInTheDocument());
    expect(screen.queryByDisplayValue("Sign in to Acme")).not.toBeInTheDocument();
  });

  /** A screen with no row yet must open EMPTY, not holding whatever the last screen showed. */
  it("opens an unwritten screen with empty fields", async () => {
    await renderEditor();

    fireEvent.click(screen.getByRole("radio", { name: "brandingScreen_CONSENT" }));

    await waitFor(() =>
      expect(screen.getByLabelText("brandingScreenHeadline")).toHaveValue(""));
  });

  it("saves only the screen currently selected", async () => {
    await renderEditor();

    fireEvent.click(screen.getByRole("radio", { name: "brandingScreen_MFA" }));
    await waitFor(() => expect(screen.getByDisplayValue("Verify it is you")).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText("brandingScreenHeadline"), { target: { value: "Confirm" } });
    fireEvent.click(screen.getByRole("button", { name: /save/i }));

    await waitFor(() => expect(save).toHaveBeenCalledTimes(1));
    expect(save).toHaveBeenCalledWith("MFA", expect.objectContaining({ headline: "Confirm" }));
  });

  /** A blank field must go over the wire as null — that is how the server returns a piece to inheriting. */
  it("sends a cleared field as null rather than an empty string", async () => {
    await renderEditor();

    fireEvent.change(screen.getByLabelText("brandingScreenHeadline"), { target: { value: "  " } });
    fireEvent.click(screen.getByRole("button", { name: /save/i }));

    await waitFor(() => expect(save).toHaveBeenCalled());
    expect(save).toHaveBeenCalledWith("LOGIN", expect.objectContaining({ headline: null }));
  });

  it("reverts the selected screen to the inherited wording", async () => {
    await renderEditor();

    fireEvent.click(screen.getByRole("button", { name: /reset/i }));

    await waitFor(() => expect(drop).toHaveBeenCalledWith("LOGIN"));
  });

  /**
   * A failed load must NOT fall through to an empty form: a blank editor reads as "nothing is configured",
   * and saving from it would wipe wording the tenant still has.
   */
  it("does not render an editable form when the wording cannot be loaded", async () => {
    load.mockRejectedValue(new Error("nope"));

    render(<ScreenCopyEditor />);

    await waitFor(() => expect(screen.queryByLabelText("brandingScreenHeadline")).toBeNull());
    expect(screen.queryByRole("button", { name: /save/i })).toBeNull();
  });
});

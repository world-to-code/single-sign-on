import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useFactorVerification } from "./useFactorVerification";
import { ApiError } from "@/api";
import { verifyFactor } from "@/auth";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

vi.mock("@/auth", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/auth")>()),
  verifyFactor: vi.fn(),
  prepareFactor: vi.fn(),
  factorDeliveryFailed: vi.fn().mockResolvedValue(false),
}));

const submit = { preventDefault: vi.fn() } as unknown as React.FormEvent;

/**
 * The screens' error copy used to be a literal passed in at the call site — substituted for EVERY ApiError,
 * so the server's reason never rendered, and in English regardless of the user's language.
 */
describe("useFactorVerification", () => {
  beforeEach(() => {
    vi.mocked(verifyFactor).mockReset();
  });

  const start = () => renderHook(() =>
    useFactorVerification({ initialFactor: "TOTP", onSuccess: vi.fn() }));

  it("shows the reason the server gave, not a fixed local string", async () => {
    vi.mocked(verifyFactor).mockRejectedValue(
      new ApiError(400, "That code has already been used or has expired."));
    const { result } = start();

    await act(async () => { await result.current.submitCode(submit); });

    await waitFor(() =>
      expect(result.current.error).toBe("That code has already been used or has expired."));
  });

  it("distinguishes a wrong password from a wrong code, because the server does", async () => {
    vi.mocked(verifyFactor).mockRejectedValue(new ApiError(400, "Incorrect password. Try again."));
    const { result } = start();

    await act(async () => { await result.current.submitPassword(submit); });

    await waitFor(() => expect(result.current.error).toBe("Incorrect password. Try again."));
  });

  /** No response means no detail to prefer, so the hook's own (localized) key is correct here. */
  it("falls back to a local message when the request never reached the server", async () => {
    vi.mocked(verifyFactor).mockRejectedValue(new TypeError("network down"));
    const { result } = start();

    await act(async () => { await result.current.submitCode(submit); });

    await waitFor(() => expect(result.current.error).toBe("factorVerifyFailed"));
  });
});

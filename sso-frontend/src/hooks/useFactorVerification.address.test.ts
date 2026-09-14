import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useFactorVerification } from "./useFactorVerification";
import { ApiError } from "@/api";
import { prepareFactor } from "@/auth";
import { confirmEmail, requestEmailCode } from "@/profile";

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

vi.mock("@/profile", () => ({ requestEmailCode: vi.fn(), confirmEmail: vi.fn() }));

const refusedUnproven = new ApiError(403, "This address has not been verified.");
const sentChallenge = { prepared: true, secret: null, qrDataUri: null, publicKeyOptions: null, expiresInSeconds: 300 };

/**
 * An account whose address was never proven is refused the EMAIL factor, and the sign-in screen is as far as
 * it can get. The proof code therefore has to be ENTERED on that screen too — mailing it and then offering
 * nowhere to type it left new tenant admins holding a code they could not use.
 */
describe("useFactorVerification address proof", () => {
  beforeEach(() => {
    vi.mocked(prepareFactor).mockReset();
    vi.mocked(requestEmailCode).mockReset().mockResolvedValue(undefined);
    vi.mocked(confirmEmail).mockReset();
  });

  const startOnEmail = () => renderHook(() =>
    useFactorVerification({ initialFactor: "EMAIL", onSuccess: vi.fn() }));

  it("marks the address unverified when the email factor refuses to send", async () => {
    vi.mocked(prepareFactor).mockRejectedValue(refusedUnproven);
    const { result } = startOnEmail();

    await act(async () => { await result.current.sendCode(); });

    expect(result.current.addressUnverified).toBe(true);
    expect(result.current.codeSent).toBe(false);
  });

  it("confirms the mailbox code, unlocks the factor and sends the sign-in code", async () => {
    vi.mocked(prepareFactor).mockRejectedValueOnce(refusedUnproven).mockResolvedValueOnce(sentChallenge);
    vi.mocked(confirmEmail).mockResolvedValue(undefined);
    const { result } = startOnEmail();

    await act(async () => { await result.current.sendCode(); });
    await act(async () => { await result.current.sendAddressVerification(); });
    await act(async () => { await result.current.confirmAddressVerification("123456"); });

    expect(confirmEmail).toHaveBeenCalledWith("123456");
    await waitFor(() => expect(result.current.addressUnverified).toBe(false));
    expect(result.current.addressVerificationSent).toBe(false);
    expect(prepareFactor).toHaveBeenCalledTimes(2);
    expect(result.current.codeSent).toBe(true);
    expect(result.current.error).toBeNull();
  });

  it("keeps the proof step open and shows the server's reason when the mailbox code is wrong", async () => {
    vi.mocked(prepareFactor).mockRejectedValue(refusedUnproven);
    vi.mocked(confirmEmail).mockRejectedValue(new ApiError(400, "That code is incorrect or has expired."));
    const { result } = startOnEmail();

    await act(async () => { await result.current.sendCode(); });
    await act(async () => { await result.current.sendAddressVerification(); });
    await act(async () => { await result.current.confirmAddressVerification("000000"); });

    expect(result.current.addressUnverified).toBe(true);
    expect(result.current.addressVerificationSent).toBe(true);
    expect(result.current.error).toBe("That code is incorrect or has expired.");
    expect(prepareFactor).toHaveBeenCalledTimes(1);
  });
});

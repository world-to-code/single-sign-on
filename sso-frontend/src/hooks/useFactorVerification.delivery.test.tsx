import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useFactorVerification } from "./useFactorVerification";
import { factorDeliveryFailed, prepareFactor } from "@/auth";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({ t: (key: string) => key, i18n: { language: "en", changeLanguage: vi.fn() } }),
}));

vi.mock("@/auth", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/auth")>()),
  prepareFactor: vi.fn().mockResolvedValue({ prepared: true }),
  verifyFactor: vi.fn(),
  factorDeliveryFailed: vi.fn(),
}));
vi.mock("@/profile", () => ({ requestEmailCode: vi.fn() }));
vi.mock("@/webauthn", () => ({ assertFactorCredential: vi.fn(), registerFactorCredential: vi.fn() }));

/**
 * The screen asks for a code and is answered immediately, because the send is deliberately off the request
 * thread. So when the send then fails, nothing on the page knows — the person waits for a text that is never
 * coming, and the only report used to be on a code they would have had to invent in order to submit it.
 */
describe("useFactorVerification delivery watch", () => {
  beforeEach(() => vi.clearAllMocks());

  const session = { next: "FACTOR", allowedFactors: ["SMS"] } as never;

  it("tells the person when the code never went out", async () => {
    vi.mocked(factorDeliveryFailed).mockResolvedValue(true);
    const { result } = renderHook(() => useFactorVerification(session));

    await act(async () => { await result.current.sendCode(); });

    await waitFor(() => expect(result.current.error).toBe("factorCodeNotDelivered"), { timeout: 4000 });
  });

  it("says nothing while the send is fine", async () => {
    vi.mocked(factorDeliveryFailed).mockResolvedValue(false);
    const { result } = renderHook(() => useFactorVerification(session));

    await act(async () => { await result.current.sendCode(); });

    await new Promise((resume) => setTimeout(resume, 2200));
    expect(result.current.error).toBeNull();
    expect(prepareFactor).toHaveBeenCalled();
  });

  /** A check that itself fails says nothing about the send; it must not invent a delivery failure. */
  it("stays quiet when the check itself cannot be made", async () => {
    vi.mocked(factorDeliveryFailed).mockRejectedValue(new Error("offline"));
    const { result } = renderHook(() => useFactorVerification(session));

    await act(async () => { await result.current.sendCode(); });

    await new Promise((resume) => setTimeout(resume, 2200));
    expect(result.current.error).toBeNull();
  });
});

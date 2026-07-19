import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { FederatedIdentities } from "./FederatedIdentities";
import { unlinkFederatedIdentity } from "@/federatedIdentities";
import { useApiData } from "@/useApiData";
import { useConfirm } from "@/components/ConfirmProvider";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) =>
      (opts?.provider ? `${key}:${String(opts.provider)}` : key),
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

vi.mock("@/useApiData", () => ({ useApiData: vi.fn() }));
vi.mock("@/components/ConfirmProvider", () => ({ useConfirm: vi.fn() }));
vi.mock("@/federatedIdentities", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/federatedIdentities")>()),
  unlinkFederatedIdentity: vi.fn().mockResolvedValue(undefined),
}));

const USER = "11111111-1111-1111-1111-111111111111";
const identity = {
  id: "22222222-2222-2222-2222-222222222222",
  providerAlias: "okta",
  issuer: "https://corp.okta.test",
  subjectHint: "00u1a2b3…",
  linkedAt: "2026-07-19T00:00:00Z",
};

function withData(data: unknown, reload = vi.fn()) {
  vi.mocked(useApiData).mockReturnValue({ data, error: null, cause: null, loading: false, reload } as never);
  return reload;
}

describe("FederatedIdentities", () => {
  beforeEach(() => {
    vi.mocked(useConfirm).mockReturnValue(vi.fn().mockResolvedValue(true) as never);
  });

  it("lists an identity with the provider, issuer and abbreviated subject", () => {
    withData([identity]);
    render(<FederatedIdentities userId={USER} />);

    expect(screen.getByText("okta")).toBeInTheDocument();
    expect(screen.getByText("https://corp.okta.test")).toBeInTheDocument();
    expect(screen.getByText("00u1a2b3…")).toBeInTheDocument();
  });

  it("says so when the account has no federated identity", () => {
    withData([]);
    render(<FederatedIdentities userId={USER} />);

    expect(screen.getByText("federatedIdentitiesNone")).toBeInTheDocument();
  });

  it("unlinks after confirmation and reloads the list", async () => {
    const reload = withData([identity]);
    render(<FederatedIdentities userId={USER} />);

    screen.getByRole("button", { name: "federatedUnlinkOne:okta" }).click();

    await waitFor(() => expect(unlinkFederatedIdentity).toHaveBeenCalledWith(USER, identity.id));
    await waitFor(() => expect(reload).toHaveBeenCalled());
  });

  /** Revoking a credential signs the user out everywhere, so a mis-click must not do it silently. */
  it("does not unlink when the confirmation is declined", async () => {
    withData([identity]);
    vi.mocked(useConfirm).mockReturnValue(vi.fn().mockResolvedValue(false) as never);
    render(<FederatedIdentities userId={USER} />);

    screen.getByRole("button", { name: "federatedUnlinkOne:okta" }).click();

    await waitFor(() => expect(unlinkFederatedIdentity).not.toHaveBeenCalled());
  });
});

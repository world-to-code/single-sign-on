import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import Applications from "./Applications";
import { apiGet } from "../api";

vi.mock("react-i18next", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-i18next")>();
  return {
    ...actual,
    // Interpolate values so a test can assert the NUMBER reached the copy, not just the key.
    useTranslation: () => ({
      t: (key: string, opts?: Record<string, unknown>) =>
        opts && "minutes" in opts ? `${key}:${String(opts.minutes)}` : key,
      i18n: { language: "en", changeLanguage: vi.fn() },
    }),
    Trans: ({ i18nKey, values }: { i18nKey: string; values?: Record<string, unknown> }) =>
      <span>{values?.minutes != null ? `${i18nKey}:${String(values.minutes)}` : i18nKey}</span>,
  };
});

vi.mock("../api", () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  errorMessage: (e: unknown) => String(e),
}));

vi.mock("@/usePaginated", () => ({
  usePaginated: () => ({
    // The admin console is a SYSTEM OIDC client, not a PORTAL row — the PORTAL row is the end-user portal,
    // whose settings dialog deliberately has none of the console-only knobs.
    items: [{ id: "admin-console", type: "OIDC", name: "Admin Console", launchUrl: null, system: true,
              requiredPolicyId: null, requiredPolicyName: null }],
    total: 1, page: 0, setPage: vi.fn(), size: 20, error: null, reload: vi.fn(),
  }),
}));

vi.mock("@/hooks/useDeleteConfirm", () => ({ useDeleteConfirm: () => vi.fn() }));

const CONSOLE_SETTINGS = {
  sessionPolicyId: null,
  inheritedSessionPolicyName: "Default",
  elevationTokenTtlMinutes: 240,
  adminAllowedCidrs: null,
  effectiveSensitiveReauthWindowMinutes: 2,
};

/**
 * The console's three clocks are the recurring support question: an administrator sets a 240-minute elevation
 * TTL, then watches destructive actions re-prompt after two minutes, and concludes their setting was ignored.
 * The dialog now states the window that actually governs those actions, so this pins that it is plumbed
 * through and rendered — not merely present in the response.
 */
describe("Applications — admin console settings", () => {
  beforeEach(() => {
    vi.mocked(apiGet).mockImplementation((path: string) =>
      path.startsWith("/api/admin/portal-settings")
        ? Promise.resolve(CONSOLE_SETTINGS)
        : Promise.resolve({ items: [], total: 0 }) as never);
  });

  async function openConsoleSettings() {
    render(<Applications />);
    fireEvent.click(await screen.findByRole("button", { name: /applicationsPortalSettings/ }));
    await waitFor(() => expect(screen.getByLabelText("applicationsElevationTtl")).toBeInTheDocument());
  }

  it("states the sensitive-action window that actually governs deletes and grants", async () => {
    await openConsoleSettings();

    expect(screen.getByText("applicationsSensitiveWindowNotice:2")).toBeInTheDocument();
  });

  /** Absent rather than zero: claiming "0 min" would be a worse lie than saying nothing. */
  it("omits the notice when the governing window cannot be resolved", async () => {
    vi.mocked(apiGet).mockImplementation((path: string) =>
      path.startsWith("/api/admin/portal-settings")
        ? Promise.resolve({ ...CONSOLE_SETTINGS, effectiveSensitiveReauthWindowMinutes: null })
        : Promise.resolve({ items: [], total: 0 }) as never);

    await openConsoleSettings();

    expect(screen.queryByText(/applicationsSensitiveWindowNotice/)).toBeNull();
  });
});

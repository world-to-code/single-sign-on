import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { BrandingProvider } from "./BrandingProvider";
import { Brand } from "./Brand";
import LoadingScreen from "./LoadingScreen";
import AppShell from "./layout/AppShell";
import { getBranding } from "@/branding";
import { applyAccent } from "@/lib/prefs";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));
vi.mock("@/branding", () => ({ getBranding: vi.fn() }));
// AppShell is the surface the console and the user portal SHARE, and the one that was reported as unbranded.
// Everything mocked here is navigation and session plumbing that has nothing to do with the mark it draws.
vi.mock("@/auth", () => ({ isPlatformAdmin: () => false, logout: vi.fn() }));
vi.mock("@/drillIn", () => ({ setDrillIn: vi.fn(), useDrillIn: () => null }));
vi.mock("@/adminPortal", () => ({ clearAdminUnlock: vi.fn(), startAdminOidc: vi.fn(), getAdminToken: () => null }));
vi.mock("@/hooks/useAdminConsoleAccess", () => ({ useAdminConsoleAccess: () => false }));
vi.mock("@/lib/prefs", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/prefs")>()),
  applyAccent: vi.fn(),
}));

/**
 * A tenant that sets a logo has said what this deployment is called, and that answer does not stop being true
 * after sign-in. It used to: branding was fetched inside the auth layout, so it reached the sign-in screens
 * and nothing else — the console, the user portal and the splash all drew the built-in shield and the word
 * "Mini SSO", and the accent reverted to the default the moment a session existed.
 */
describe("BrandingProvider", () => {
  const branding = vi.mocked(getBranding);

  /** jsdom reports every media query as non-matching, which collapses the shell to its icon rail. */
  function wideViewport(): void {
    vi.stubGlobal("matchMedia", (query: string) => ({
      matches: query.includes("min-width: 1280px"),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));
  }

  beforeEach(() => {
    vi.clearAllMocks();
    branding.mockResolvedValue({
      logoUrl: "https://cdn.example.com/logo.png",
      accentColor: "#123456",
      productName: "Acme ID",
    });
  });

  it("brands a surface outside the sign-in screens", async () => {
    // Queried as an element rather than by role: the mark carries alt="" because the product name sits beside
    // it, so it is decorative to a screen reader and deliberately absent from the accessibility tree.
    const { container } = render(<BrandingProvider><LoadingScreen /></BrandingProvider>);

    await waitFor(() =>
      expect(container.querySelector("img")).toHaveAttribute("src", "https://cdn.example.com/logo.png"));
    expect(screen.getByText("Acme ID")).toBeInTheDocument();
  });

  /**
   * The console, which is what was reported. It shares AppShell with the user portal, so one mark serves both
   * — and both drew the built-in shield while the sign-in screen right before them drew the tenant's logo.
   */
  it("brands the console and the user portal shell", async () => {
    // A WIDE viewport, so the sidebar renders the full wordmark rather than the collapsed icon rail — the two
    // draw the mark through different branches and only one of them was the reported symptom.
    wideViewport();
    const { container } = render(
      <MemoryRouter>
        <BrandingProvider>
          <AppShell session={{ next: "DONE", permissions: [] } as never} variant="admin">{null}</AppShell>
        </BrandingProvider>
      </MemoryRouter>);

    await waitFor(() =>
      expect(container.querySelector("img")).toHaveAttribute("src", "https://cdn.example.com/logo.png"));
  });

  /** The accent is half the branding, and it was the half that silently reverted once a session existed. */
  it("applies the tenant accent for the whole app, not only the auth screens", async () => {
    render(<BrandingProvider><LoadingScreen /></BrandingProvider>);

    await waitFor(() => expect(applyAccent).toHaveBeenCalledWith("#123456"));
  });

  /** Several surfaces mount and unmount as the session progresses; they must not each re-request it. */
  it("fetches once however many surfaces consume it", async () => {
    render(
      <BrandingProvider>
        <LoadingScreen />
        <Brand />
      </BrandingProvider>);

    await waitFor(() => expect(screen.getAllByText("Acme ID").length).toBeGreaterThan(0));
    expect(branding).toHaveBeenCalledTimes(1);
  });

  /** An unbranded tenant and a failed request mean the same thing: the built-in mark, and no error shown. */
  it("falls back to the built-in mark when branding cannot be resolved", async () => {
    branding.mockRejectedValue(new Error("offline"));

    const { container } = render(<BrandingProvider><LoadingScreen /></BrandingProvider>);

    await waitFor(() => expect(screen.getByText("appName")).toBeInTheDocument());
    expect(container.querySelector("img")).toBeNull();
  });
});

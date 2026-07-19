import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import OrgSelect from "./OrgSelect";

// The screen is exercised for its ORGANIZATION-memory behaviour, so i18n, the API and the layout's branding
// fetch are stubbed down to identity/no-ops; localStorage and the picker's own logic stay real.
vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) => (opts?.slug ? `${key}:${String(opts.slug)}` : key),
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

vi.mock("../auth", () => ({ goHome: vi.fn(), organization: vi.fn() }));

vi.mock("@/branding", () => ({
  getBranding: vi.fn().mockResolvedValue({ logoUrl: null, accentColor: null, productName: null }),
}));

const KEY = "sso.recentOrgs";
const forgetButton = (slug: string) => screen.getByRole("button", { name: `orgForget:${slug}` });

describe("OrgSelect — remembered organizations", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("lists every remembered organization, most recent first", async () => {
    localStorage.setItem(KEY, JSON.stringify(["octatco", "acme"]));
    render(<OrgSelect />);

    await waitFor(() => expect(screen.getByText("octatco")).toBeDefined());
    expect(screen.getByText("acme")).toBeDefined();
    // The slug input belongs to the manual path and must not be on screen while the list is offered.
    expect(screen.queryByLabelText("orgLabel")).toBeNull();
  });

  it("asks for a slug when nothing is remembered", async () => {
    render(<OrgSelect />);
    await waitFor(() => expect(screen.getByLabelText("orgLabel")).toBeDefined());
  });

  it("drops only the forgotten organization and keeps the rest listed", async () => {
    localStorage.setItem(KEY, JSON.stringify(["octatco", "acme"]));
    render(<OrgSelect />);
    await waitFor(() => expect(screen.getByText("octatco")).toBeDefined());

    forgetButton("octatco").click();

    await waitFor(() => expect(screen.queryByText("octatco")).toBeNull());
    expect(screen.getByText("acme")).toBeDefined();
    expect(JSON.parse(localStorage.getItem(KEY) ?? "null")).toEqual(["acme"]);
  });

  // Without the fallback the user would be left staring at an empty card list with no way to name an org.
  it("falls back to the slug input once the last organization is forgotten", async () => {
    localStorage.setItem(KEY, JSON.stringify(["octatco"]));
    render(<OrgSelect />);
    await waitFor(() => expect(screen.getByText("octatco")).toBeDefined());

    forgetButton("octatco").click();

    await waitFor(() => expect(screen.getByLabelText("orgLabel")).toBeDefined());
    expect(screen.queryByText("octatco")).toBeNull();
  });

  it("survives corrupt stored memory by offering the slug input", async () => {
    localStorage.setItem(KEY, "{{{not json");
    render(<OrgSelect />);
    await waitFor(() => expect(screen.getByLabelText("orgLabel")).toBeDefined());
  });
});

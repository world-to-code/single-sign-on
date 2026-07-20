import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import ProfileAttributes from "./ProfileAttributes";
import { ConfirmProvider } from "@/components/ConfirmProvider";
import { listProfiles } from "@/attributeDefinitions";
import { apiGet } from "@/api";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

vi.mock("@/attributeDefinitions", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/attributeDefinitions")>()),
  listProfiles: vi.fn(),
}));

vi.mock("@/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/api")>()),
  apiGet: vi.fn(),
}));

const TENANT = "11111111-1111-1111-1111-111111111111";
const LDAP = "22222222-2222-2222-2222-222222222222";

/** The page uses the shared confirm dialog for deletes, so it needs the provider around it. */
const renderPage = () => render(<ConfirmProvider><ProfileAttributes /></ConfirmProvider>);

const profile = (id: string, name: string, kind: string) =>
  ({ id, name, kind, connectorId: null, system: kind === "TENANT", defaultForCreation: kind === "TENANT" });

/**
 * A person's attributes belong to a profile, so the page has to ask which one before it can show anything.
 * The tenant's own is the sensible default; the point of the picker is that a source profile — what a
 * directory PROVIDES — is visible in the same place, which is what makes a mapping legible at all.
 */
describe("ProfileAttributes", () => {
  beforeEach(() => {
    vi.mocked(listProfiles).mockResolvedValue([
      profile(TENANT, "acme.com", "TENANT"),
      profile(LDAP, "corp LDAP", "LDAP"),
    ] as never);
    vi.mocked(apiGet).mockResolvedValue([] as never);
  });

  it("defaults to the tenant's own profile and lists its attributes", async () => {
    renderPage();

    await waitFor(() => expect(screen.getByDisplayValue("acme.com")).toBeInTheDocument());
    expect(apiGet).toHaveBeenCalledWith(`/api/admin/profiles/${TENANT}/attributes`);
  });

  it("shows every profile, so a source schema sits beside the tenant's", async () => {
    renderPage();

    await waitFor(() => expect(screen.getByText("corp LDAP · LDAP")).toBeInTheDocument());
  });

  it("loads the selected profile's attributes when the picker changes", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByDisplayValue("acme.com")).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText("profileAttrProfile"), { target: { value: LDAP } });

    await waitFor(() =>
      expect(apiGet).toHaveBeenCalledWith(`/api/admin/profiles/${LDAP}/attributes`));
  });
});

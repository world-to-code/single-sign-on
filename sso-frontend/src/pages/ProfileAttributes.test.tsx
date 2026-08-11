import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import ProfileAttributes from "./ProfileAttributes";
import { ConfirmProvider } from "@/components/ConfirmProvider";
import { listProfiles, deleteAttributeDefinition, type AttributeDefinition } from "@/attributeDefinitions";
import { ApiError, apiGet } from "@/api";

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
  deleteAttributeDefinition: vi.fn().mockResolvedValue(undefined),
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

  const attr = (over: Partial<AttributeDefinition>): AttributeDefinition => ({
    id: "a-1", entityKind: "USER", key: "custom", displayName: "Custom", description: "",
    dataType: "STRING", enumValues: [], multiValued: false, required: false, source: "LOCAL",
    sortOrder: 0, base: false, ...over,
  });

  /** A base attribute is an app_user column shown for context; there is no declaration to edit or delete. */
  it("shows no edit or delete on a base attribute", async () => {
    vi.mocked(apiGet).mockResolvedValue([
      attr({ id: null as never, key: "email", displayName: "Email", base: true }),
    ] as never);
    renderPage();

    await waitFor(() => expect(screen.getByText("Email")).toBeInTheDocument());
    // The action cell is empty for a base row — the only buttons on the page are the picker and "add".
    expect(screen.queryByRole("button", { name: /pencil|edit/i })).not.toBeInTheDocument();
    expect(screen.getAllByRole("row").filter((r) => r.textContent?.includes("Email"))[0]
      .querySelectorAll("button")).toHaveLength(0);
  });

  /** A custom attribute opens the editor with its key locked, since the key is the identity and cannot change. */
  it("edits a custom attribute with the key field locked", async () => {
    vi.mocked(apiGet).mockResolvedValue([attr({ key: "clearance", displayName: "Clearance" })] as never);
    renderPage();
    await waitFor(() => expect(screen.getByText("Clearance")).toBeInTheDocument());

    const row = screen.getAllByRole("row").find((r) => r.textContent?.includes("Clearance"))!;
    fireEvent.click(row.querySelectorAll("button")[0]);

    await waitFor(() => expect(screen.getByLabelText("profileAttrKeyLabel")).toBeInTheDocument());
    expect(screen.getByLabelText("profileAttrKeyLabel")).toBeDisabled();
    expect(screen.getByLabelText("profileAttrKeyLabel")).toHaveValue("clearance");
  });

  /** Deleting a custom attribute goes through the confirm dialog to deleteAttributeDefinition by id. */
  it("deletes a custom attribute by id", async () => {
    vi.mocked(apiGet).mockResolvedValue([attr({ id: "a-9", key: "team", displayName: "Team" })] as never);
    renderPage();
    await waitFor(() => expect(screen.getByText("Team")).toBeInTheDocument());

    const row = screen.getAllByRole("row").find((r) => r.textContent?.includes("Team"))!;
    fireEvent.click(row.querySelectorAll("button")[1]);

    await waitFor(() => expect(screen.getByText("profileAttrDeleteTitle")).toBeInTheDocument());
    fireEvent.click(document.querySelector("[data-confirm]") as HTMLElement);

    await waitFor(() => expect(deleteAttributeDefinition).toHaveBeenCalledWith("a-9"));
  });

  /**
   * A refused profile list is not "this organization has no profiles". Swallowed into an empty array it
   * dropped the picker — the only control that says which schema is on screen — and left the page asking the
   * profile-LESS endpoint, which answers a different question and looks perfectly healthy doing it.
   */
  describe("when the profile list cannot be loaded", () => {
    beforeEach(() => {
      vi.mocked(listProfiles).mockRejectedValue(new ApiError(403, "You may not read profiles"));
    });

    it("renders the refusal instead of silently dropping the picker", async () => {
      renderPage();

      await waitFor(() => expect(screen.getByText("You may not read profiles")).toBeInTheDocument());
      expect(screen.queryByLabelText("profileAttrProfile")).not.toBeInTheDocument();
    });

    it("does not show USER attributes answered by the profile-less endpoint", async () => {
      vi.mocked(apiGet).mockResolvedValue([attr({ key: "elsewhere", displayName: "Elsewhere" })] as never);
      renderPage();

      await waitFor(() => expect(screen.getByText("You may not read profiles")).toBeInTheDocument());
      expect(screen.queryByText("Elsewhere")).not.toBeInTheDocument();
    });

    /** Declaring one here would attach it to no profile at all — a write the admin cannot see or undo. */
    it("refuses to declare a USER attribute while the profile is unknown", async () => {
      renderPage();

      await waitFor(() => expect(screen.getByText("You may not read profiles")).toBeInTheDocument());
      expect(screen.getByRole("button", { name: "profileAttrNew" })).toBeDisabled();
    });

    it("still lists GROUP attributes, which do not live in a profile", async () => {
      vi.mocked(apiGet).mockResolvedValue([
        attr({ entityKind: "GROUP", key: "tier", displayName: "Tier" }),
      ] as never);
      renderPage();
      await waitFor(() => expect(screen.getByText("You may not read profiles")).toBeInTheDocument());

      fireEvent.click(screen.getByText("profileAttrKind_GROUP"));

      await waitFor(() => expect(screen.getByText("Tier")).toBeInTheDocument());
      expect(screen.getByRole("button", { name: "profileAttrNew" })).toBeEnabled();
    });
  });
});

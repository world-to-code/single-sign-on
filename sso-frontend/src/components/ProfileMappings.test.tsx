import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { ProfileMappings } from "./ProfileMappings";
import {
  listAttributeDefinitions,
  type AttributeDefinition,
  type Profile,
  type ProfileMapping,
} from "@/attributeDefinitions";
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
  listAttributeDefinitions: vi.fn(),
}));

vi.mock("@/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/api")>()),
  apiGet: vi.fn(),
}));

const profile = (id: string, name: string, kind: Profile["kind"]): Profile =>
  ({ id, name, kind, connectorId: null, system: kind === "TENANT", defaultForCreation: kind === "TENANT" });

const SOURCE = profile("s-1", "corp LDAP", "LDAP");
const TENANT = profile("t-1", "acme.com", "TENANT");

const target = (over: Partial<AttributeDefinition>): AttributeDefinition => ({
  id: "d-1", entityKind: "USER", key: "department", displayName: "Department", description: "",
  dataType: "STRING", enumValues: [], multiValued: false, required: false, source: "DIRECTORY",
  sortOrder: 0, base: false, ...over,
});

const mapping = (over: Partial<ProfileMapping>): ProfileMapping => ({
  id: "m-1", sourceProfileId: SOURCE.id, sourceKey: "dept", targetProfileId: TENANT.id,
  targetKey: "department", ...over,
});

/**
 * A mapping is what makes a source profile act on anything, and it can only be made against a target the
 * tenant's schema actually declares — so the target picker IS the form.
 */
describe("ProfileMappings", () => {
  beforeEach(() => {
    vi.mocked(apiGet).mockResolvedValue([] as never);
    vi.mocked(listAttributeDefinitions).mockResolvedValue([target({})]);
  });

  const renderMappings = () => render(<ProfileMappings source={SOURCE} tenant={TENANT} />);

  it("lists what the source already fills", async () => {
    vi.mocked(apiGet).mockResolvedValue([mapping({})] as never);
    renderMappings();

    await waitFor(() => expect(screen.getByText("dept")).toBeInTheDocument());
    expect(screen.getByText("department")).toBeInTheDocument();
  });

  it("offers only the directory-owned attributes a sync may write", async () => {
    vi.mocked(listAttributeDefinitions).mockResolvedValue([
      target({ id: "d-1", key: "department", displayName: "Department" }),
      target({ id: "d-2", key: "nickname", displayName: "Nickname", source: "LOCAL" }),
      target({ id: "d-3", key: "email", displayName: "Email", base: true }),
    ]);
    renderMappings();

    await waitFor(() => expect(screen.getByRole("option", { name: "Department" })).toBeInTheDocument());
    expect(screen.queryByRole("option", { name: "Nickname" })).not.toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Email" })).not.toBeInTheDocument();
  });

  /**
   * Swallowed into an empty list this rendered a target picker with nothing in it and an Add button that
   * could never enable — indistinguishable from a tenant that declares nothing a directory owns, and with
   * no way for the administrator to tell which of the two they are looking at.
   */
  it("renders the refusal when the target attributes cannot be loaded", async () => {
    vi.mocked(listAttributeDefinitions).mockRejectedValue(new ApiError(403, "You may not read this schema"));
    renderMappings();

    await waitFor(() => expect(screen.getByText("You may not read this schema")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: "add" })).toBeDisabled();
  });

  it("says nothing about targets when they loaded", async () => {
    renderMappings();

    await waitFor(() => expect(screen.getByRole("option", { name: "Department" })).toBeInTheDocument());
    expect(screen.queryByText("You may not read this schema")).not.toBeInTheDocument();
  });
});

import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MetadataEditor } from "./MetadataEditor";
import { getAttributes } from "@/metadata";
import { listAttributeDefinitions } from "@/attributeDefinitions";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) =>
      (opts?.key ? `${key}:${String(opts.key)}` : key),
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

vi.mock("@/metadata", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/metadata")>()),
  getAttributes: vi.fn(),
  addAttribute: vi.fn(),
  removeAttribute: vi.fn(),
  removeAttributeValue: vi.fn(),
}));

vi.mock("@/attributeDefinitions", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/attributeDefinitions")>()),
  listAttributeDefinitions: vi.fn(),
}));

const USER = "11111111-1111-1111-1111-111111111111";

const definition = (key: string, source: "LOCAL" | "DIRECTORY", extra = {}) => ({
  id: `def-${key}`, entityKind: "USER" as const, key, displayName: `The ${key}`, description: null,
  dataType: "STRING" as const, enumValues: [], multiValued: false, required: false, source, sortOrder: 0,
  ...extra,
});

describe("MetadataEditor — schema awareness", () => {
  beforeEach(() => {
    vi.mocked(getAttributes).mockResolvedValue([{ key: "department", value: "Sales" }]);
    vi.mocked(listAttributeDefinitions).mockResolvedValue([]);
  });

  /** Everything that predates the schema must keep working, so an undeclared key stays free-form. */
  it("falls back to a free-form key input when nothing is declared", async () => {
    render(<MetadataEditor kind="users" entityId={USER} />);

    await waitFor(() => expect(screen.getByPlaceholderText("metadataKey")).toBeInTheDocument());
  });

  it("offers declared attributes by their display name instead of a blank box", async () => {
    vi.mocked(listAttributeDefinitions).mockResolvedValue([definition("costCentre", "LOCAL")]);
    render(<MetadataEditor kind="users" entityId={USER} />);

    await waitFor(() => expect(screen.getByRole("combobox", { name: "metadataKey" })).toBeInTheDocument());
    expect(screen.getByRole("option", { name: "The costCentre" })).toBeInTheDocument();
  });

  /**
   * The load-bearing case. A directory owns the value, so offering an edit here would either be refused by the
   * backend or — worse — accepted and silently overwritten by the next sync.
   */
  it("shows a directory-owned attribute read-only, with no way to remove it", async () => {
    vi.mocked(listAttributeDefinitions).mockResolvedValue([definition("department", "DIRECTORY")]);
    render(<MetadataEditor kind="users" entityId={USER} />);

    await waitFor(() => expect(screen.getByText("metadataDirectoryOwned")).toBeInTheDocument());
    expect(screen.getByText("Sales")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "metadataRemove:department" })).not.toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "The department" })).not.toBeInTheDocument();
  });

  it("keeps a locally-owned attribute removable", async () => {
    vi.mocked(listAttributeDefinitions).mockResolvedValue([definition("department", "LOCAL")]);
    render(<MetadataEditor kind="users" entityId={USER} />);

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "metadataRemove:department" })).toBeInTheDocument());
    expect(screen.queryByText("metadataDirectoryOwned")).not.toBeInTheDocument();
  });

  /** A schema the caller may not read must not break the editor — it degrades to free-form. */
  it("degrades to free-form when the schema cannot be loaded", async () => {
    vi.mocked(listAttributeDefinitions).mockRejectedValue(new Error("forbidden"));
    render(<MetadataEditor kind="users" entityId={USER} />);

    await waitFor(() => expect(screen.getByPlaceholderText("metadataKey")).toBeInTheDocument());
  });
});

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

/**
 * The member table's expiry column and the duration attached to a new grant.
 *
 * <p>The arithmetic lives in `grantDuration` and is tested there. What can only fail HERE is the wiring: that
 * the chosen duration is the one sent, and that a permanent grant is drawn as permanent rather than as a blank
 * cell. A blank would read as missing data on the one screen where an administrator decides whether somebody's
 * access is about to end.
 */

vi.mock("react-router-dom", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-router-dom")>()),
  useParams: () => ({ id: "role-1" }),
}));

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

vi.mock("@/roles", () => ({
  getRoleDetail: vi.fn(),
  listRoleMembers: vi.fn(),
  listPermissions: vi.fn(),
  listRoles: vi.fn(),
  addRoleMember: vi.fn(),
  removeRoleMember: vi.fn(),
}));

vi.mock("@/groups", () => ({ searchUsers: vi.fn(() => Promise.resolve([])) }));

const roles = await import("@/roles");
const groups = await import("@/groups");
const RoleDetail = (await import("./RoleDetail")).default;

function renderPage() {
  return render(<MemoryRouter><RoleDetail /></MemoryRouter>);
}

describe("role member grants", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(roles.getRoleDetail).mockResolvedValue({
      id: "role-1", name: "ROLE_ONCALL", system: false, permissions: [],
      inheritsFrom: [], inheritedBy: [], effectivePermissions: [], denies: [],
    });
    vi.mocked(roles.listPermissions).mockResolvedValue([]);
    vi.mocked(roles.listRoles).mockResolvedValue([]);
    vi.mocked(roles.addRoleMember).mockResolvedValue(undefined as never);
  });

  it("draws a standing grant as permanent, not as an empty cell", async () => {
    vi.mocked(roles.listRoleMembers).mockResolvedValue([
      { id: "u-1", username: "ada", displayName: "Ada", enabled: true, expiresAt: null },
    ]);

    renderPage();

    expect(await screen.findByText("ada")).toBeInTheDocument();
    // Scoped to the table: the same word is also the default option in the duration picker.
    expect(within(screen.getByRole("table")).getByText("roleDetailDuration_PERMANENT"))
      .toBeInTheDocument();
  });

  it("shows when a time-bounded grant runs out", async () => {
    const expiresAt = "2026-09-01T09:00:00.000Z";
    vi.mocked(roles.listRoleMembers).mockResolvedValue([
      { id: "u-2", username: "grace", displayName: "Grace", enabled: true, expiresAt },
    ]);

    renderPage();

    expect(await screen.findByText("grace")).toBeInTheDocument();
    expect(screen.getByText(new Date(expiresAt).toLocaleString())).toBeInTheDocument();
  });

  /** The default has to stay permanent: a duration nobody chose must not quietly time-box an admin's access. */
  it("grants permanently unless a duration was chosen", async () => {
    vi.mocked(roles.listRoleMembers).mockResolvedValue([]);

    renderPage();
    await screen.findByLabelText("roleDetailGrantDuration");

    expect(screen.getByLabelText("roleDetailGrantDuration")).toHaveValue("PERMANENT");
  });

  /**
   * Driven all the way to the call, because the value of the picker is not the thing that matters — what
   * reaches the server is. An earlier version of this test asserted only that the select changed, and removing
   * the expiry from the grant left it passing.
   */
  it("sends the chosen duration as an expiry on the grant", async () => {
    vi.mocked(roles.listRoleMembers).mockResolvedValue([]);
    vi.mocked(groups.searchUsers).mockResolvedValue([{ id: "u-9", label: "ada" }]);

    renderPage();
    fireEvent.change(await screen.findByLabelText("roleDetailGrantDuration"), { target: { value: "P7D" } });
    fireEvent.change(screen.getByPlaceholderText("roleDetailSearchPlaceholder"), { target: { value: "ad" } });
    fireEvent.click(await screen.findByRole("button", { name: "ada" }));

    await vi.waitFor(() => expect(roles.addRoleMember).toHaveBeenCalled());
    const [, , expiresAt] = vi.mocked(roles.addRoleMember).mock.calls[0];
    expect(expiresAt).not.toBeNull();
    expect(new Date(expiresAt as string).getTime()).toBeGreaterThan(Date.now());
  });
});

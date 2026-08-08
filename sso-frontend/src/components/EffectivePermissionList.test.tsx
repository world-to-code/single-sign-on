import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { EffectivePermissionList } from "./EffectivePermissionList";

/** The console bundle really does carry these; anything else falls back. */
const held = (permission: string, ...conferredBy: string[]) =>
  ({ permission, conferredBy, viaGroups: [] as string[] });

const heldViaGroup = (permission: string, role: string, group: string) =>
  ({ permission, conferredBy: [role], viaGroups: [group] });

const KNOWN_KEYS = new Set([
  "none",
  "userDetailDeniedPerms",
  "userDetailDeniedPermsHint",
  "decisionReason_DENIED_AT_USER_LEVEL",
  "decisionReason_DENIED_AT_ROLE_LEVEL",
  "decisionReason_DENIED_AT_GROUP_LEVEL",
  "decisionReason_DENIED_AT_ORG_LEVEL",
  "decisionReason_PLATFORM_VETO",
]);

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    // Models i18next's real contract rather than echoing every key: a key the bundle does not carry resolves
    // to the supplied defaultValue. Without that, a fallback path looks identical to a translated one here
    // and the test could not tell them apart.
    t: (key: string, options?: { defaultValue?: string }) =>
      KNOWN_KEYS.has(key) || options?.defaultValue === undefined ? key : options.defaultValue,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

describe("EffectivePermissionList", () => {
  it("shows the effective permissions", () => {
    render(<EffectivePermissionList effective={[held("user:read"), held("user:update")]} denied={[]} />);

    expect(screen.getByText("user:read")).toBeInTheDocument();
    expect(screen.getByText("user:update")).toBeInTheDocument();
    expect(screen.queryByText("userDetailDeniedPerms")).not.toBeInTheDocument();
  });

  it("surfaces a withheld grant separately from the effective set", () => {
    render(<EffectivePermissionList effective={[held("user:update")]}
                                    denied={[{ permission: "user:read", withheldBy: "DENIED_AT_USER_LEVEL" }]} />);

    expect(screen.getByText("userDetailDeniedPerms")).toBeInTheDocument();
    expect(screen.getByText("user:read")).toBeInTheDocument();
    expect(screen.getByText("user:update")).toBeInTheDocument();
  });

  /**
   * The level is the actionable half. "You do not have user:read" is not something an administrator can act
   * on; which tier withheld it tells them whether this console can lift it at all.
   */
  it("says which level withheld it, not merely that it is withheld", () => {
    render(<EffectivePermissionList effective={[]}
                                    denied={[{ permission: "user:read", withheldBy: "DENIED_AT_ROLE_LEVEL" }]} />);

    expect(screen.getByText("user:read")).toHaveAttribute("title", "decisionReason_DENIED_AT_ROLE_LEVEL");
  });

  /**
   * The conferring role is what an administrator acts on, and it may be a role the user does not hold — one
   * they do hold inherits it. A badge that showed only the permission would send them to revoke the wrong
   * thing.
   */
  it("names the role that confers a held permission", () => {
    render(<EffectivePermissionList effective={[held("user:read", "ROLE_SUPPORT")]} denied={[]} />);

    expect(screen.getByText("user:read")).toHaveAttribute("title", "userDetailConferredBy");
  });

  /**
   * The group is where the role actually has to be removed. Naming only the role sends an administrator to
   * the user's own assignments, where there is nothing to take away.
   */
  it("says the group when a delegated role is what confers it", () => {
    render(<EffectivePermissionList effective={[heldViaGroup("user:read", "ROLE_SUPPORT", "platform")]}
                                    denied={[]} />);

    expect(screen.getByText("user:read"))
      .toHaveAttribute("title", "userDetailConferredBy · userDetailDelegatedByGroup");
  });

  /** A direct grant is conferred by no role, so there is nothing to point at. */
  it("says nothing about roles for a permission no role confers", () => {
    render(<EffectivePermissionList effective={[held("user:read")]} denied={[]} />);

    expect(screen.getByText("user:read")).not.toHaveAttribute("title");
  });

  /** A level the console has no wording for must still say something rather than render a raw enum name. */
  it("falls back to the generic hint for an unknown level", () => {
    render(<EffectivePermissionList effective={[]}
                                    denied={[{ permission: "user:read", withheldBy: "SOMETHING_NEW" }]} />);

    expect(screen.getByText("user:read")).toHaveAttribute("title", "userDetailDeniedPermsHint");
  });

  it("renders an empty state when nothing is in effect", () => {
    render(<EffectivePermissionList effective={[]} denied={[]} />);

    expect(screen.getByText("none")).toBeInTheDocument();
  });
});

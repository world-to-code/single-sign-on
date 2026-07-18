import {
  LayoutDashboard, KeyRound, Users, ShieldCheck, AppWindow, Network, Coins, ScrollText,
  Clock, Globe, LayoutGrid, Boxes, UsersRound, UserCog, KeySquare, Building2, Link2, Wand2, Mail, Palette,
  type LucideIcon,
} from "lucide-react";
import type { nav as navResources } from "@/i18n/en/nav";

/** A key in the `nav` i18n namespace; the render site resolves it via t() bound to that namespace. */
export type NavKey = keyof typeof navResources;

export interface NavItem {
  to: string;
  label: NavKey;
  icon: LucideIcon;
  permission?: string; // fine-grained permission (resource:read) required to see this item
  superAdmin?: boolean; // platform-only area: additionally requires a super-admin (unscoped ROLE_ADMIN)
}

export interface NavSection {
  heading: NavKey;
  items: NavItem[];
  requiresOrg?: boolean; // org-scoped resources: hidden from a super-admin until they drill into a tenant
}

export interface NavGroup {
  heading: NavKey;
  scope: "user" | "admin";  // which shell this group belongs to
  items?: NavItem[];        // a flat group (e.g. Account)
  sections?: NavSection[];  // a grouped/accordion group (e.g. Administration)
}

/**
 * Sidebar navigation, split by shell. The USER portal ("/") shows the flat "Account" group; the
 * ADMIN console ("/admin/*") shows the "Administration" accordion sections by domain (Directory /
 * Applications / Access & Security / System). Admin links live under /admin/*.
 */
export const NAV: NavGroup[] = [
  {
    heading: "groupAccount",
    scope: "user",
    items: [
      { to: "/", label: "dashboard", icon: LayoutDashboard },
      { to: "/profile", label: "profile", icon: UserCog },
      { to: "/apps", label: "myApplications", icon: LayoutGrid },
      { to: "/passkeys", label: "myPasskeys", icon: KeyRound },
    ],
  },
  {
    heading: "groupAdministration",
    scope: "admin",
    sections: [
      {
        heading: "sectionPlatform",
        items: [
          { to: "/admin/dashboard", label: "dashboard", icon: LayoutDashboard, permission: "organization:read", superAdmin: true },
          { to: "/admin/organizations", label: "organizations", icon: Building2, permission: "organization:read", superAdmin: true },
        ],
      },
      {
        heading: "sectionDirectory",
        requiresOrg: true,
        items: [
          { to: "/admin/users", label: "users", icon: Users, permission: "user:read" },
          { to: "/admin/groups", label: "groups", icon: UsersRound, permission: "group:read" },
          { to: "/admin/resources", label: "resources", icon: Boxes, permission: "resource:read" },
        ],
      },
      {
        heading: "sectionApplications",
        requiresOrg: true,
        items: [
          { to: "/admin/applications", label: "applications", icon: Boxes, permission: "app-assignment:read" },
          { to: "/admin/clients", label: "oauthClients", icon: AppWindow, permission: "oidc-client:read" },
          { to: "/admin/relying-parties", label: "samlProviders", icon: Network, permission: "saml-rp:read" },
        ],
      },
      {
        heading: "sectionAccessSecurity",
        requiresOrg: true,
        items: [
          { to: "/admin/roles", label: "roles", icon: KeySquare, permission: "role:read" },
          { to: "/admin/auth-policies", label: "authPolicies", icon: ShieldCheck, permission: "auth-policy:read" },
          { to: "/admin/session-policy", label: "sessionPolicy", icon: Clock, permission: "session-policy:read" },
          { to: "/admin/bindings", label: "bindings", icon: Link2, permission: "auth-policy:read" },
          { to: "/admin/network-zones", label: "networkZones", icon: Globe, permission: "network-zone:read" },
          { to: "/admin/mapping-rules", label: "mappingRules", icon: Wand2, permission: "mapping-rule:read" },
        ],
      },
      {
        heading: "sectionSystem",
        requiresOrg: true,
        items: [
          { to: "/admin/scim-tokens", label: "scimTokens", icon: Coins, permission: "scim:manage" },
          { to: "/admin/smtp-settings", label: "smtpSettings", icon: Mail, permission: "smtp-settings:read" },
          { to: "/admin/customize", label: "customize", icon: Palette, permission: "email-template:read" },
          { to: "/admin/audit", label: "auditLog", icon: ScrollText, permission: "audit:read" },
        ],
      },
    ],
  },
];

/**
 * Whether a nav item's route is active for the current path — exact, or a nested detail route beneath it
 * (e.g. /admin/session-policy/new keeps "Session Policy" highlighted). The `to + "/"` guard stops a
 * sibling like /admin/session-policy-x from matching, and keeps "/" (Dashboard) from matching everything.
 */
export function isNavActive(pathname: string, to: string): boolean {
  return pathname === to || pathname.startsWith(to + "/");
}

/**
 * Breadcrumb trail for a path, as i18n keys: the section heading and the active item's label. A
 * detail route (e.g. /admin/users/:id) still resolves to its list item, so the crumb names where you
 * are without repeating the page's own <h1>, which appears once below it.
 */
export function crumbsFor(pathname: string): NavKey[] {
  for (const group of NAV) {
    for (const section of group.sections ?? []) {
      const item = section.items.find((i) => isNavActive(pathname, i.to));
      if (item) return [section.heading, item.label];
    }
    const flat = (group.items ?? []).find((i) => isNavActive(pathname, i.to));
    if (flat) return [flat.label];
  }
  return [];
}

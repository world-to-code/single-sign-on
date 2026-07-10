import {
  LayoutDashboard, KeyRound, Users, ShieldCheck, AppWindow, Network, Coins, ScrollText,
  Clock, Globe, LayoutGrid, Boxes, UsersRound, UserCog, KeySquare, Building2,
  type LucideIcon,
} from "lucide-react";

export interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  permission?: string; // fine-grained permission (resource:read) required to see this item
  superAdmin?: boolean; // platform-only area: additionally requires a super-admin (unscoped ROLE_ADMIN)
}

export interface NavSection {
  heading: string;
  items: NavItem[];
  requiresOrg?: boolean; // org-scoped resources: hidden from a super-admin until they drill into a tenant
}

export interface NavGroup {
  heading: string;
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
    heading: "Account",
    scope: "user",
    items: [
      { to: "/", label: "Dashboard", icon: LayoutDashboard },
      { to: "/profile", label: "My Profile", icon: UserCog },
      { to: "/apps", label: "My Applications", icon: LayoutGrid },
      { to: "/passkeys", label: "My Passkeys", icon: KeyRound },
    ],
  },
  {
    heading: "Administration",
    scope: "admin",
    sections: [
      {
        heading: "Platform",
        items: [
          { to: "/admin/dashboard", label: "Dashboard", icon: LayoutDashboard, permission: "organization:read", superAdmin: true },
          { to: "/admin/organizations", label: "Organizations", icon: Building2, permission: "organization:read", superAdmin: true },
        ],
      },
      {
        heading: "Directory",
        requiresOrg: true,
        items: [
          { to: "/admin/users", label: "Users", icon: Users, permission: "user:read" },
          { to: "/admin/groups", label: "Groups", icon: UsersRound, permission: "group:read" },
          { to: "/admin/resources", label: "Resources", icon: Boxes, permission: "resource:read" },
        ],
      },
      {
        heading: "Applications",
        requiresOrg: true,
        items: [
          { to: "/admin/applications", label: "Applications", icon: Boxes, permission: "app-assignment:read" },
          { to: "/admin/clients", label: "OAuth2 Clients", icon: AppWindow, permission: "oidc-client:read" },
          { to: "/admin/relying-parties", label: "SAML Providers", icon: Network, permission: "saml-rp:read" },
        ],
      },
      {
        heading: "Access & Security",
        requiresOrg: true,
        items: [
          { to: "/admin/roles", label: "Roles", icon: KeySquare, permission: "role:read" },
          { to: "/admin/auth-policies", label: "Auth Policies", icon: ShieldCheck, permission: "auth-policy:read" },
          { to: "/admin/session-policy", label: "Session Policy", icon: Clock, permission: "session-policy:read" },
          { to: "/admin/network-zones", label: "Network Zones", icon: Globe, permission: "network-zone:read" },
        ],
      },
      {
        heading: "System",
        requiresOrg: true,
        items: [
          { to: "/admin/scim-tokens", label: "SCIM Tokens", icon: Coins, permission: "scim:manage" },
          { to: "/admin/audit", label: "Audit Log", icon: ScrollText, permission: "audit:read" },
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

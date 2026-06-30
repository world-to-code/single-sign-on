import {
  LayoutDashboard, KeyRound, Users, ShieldCheck, AppWindow, Network, Coins, ScrollText,
  Clock, Globe, LayoutGrid, Boxes, UsersRound, UserCog,
  type LucideIcon,
} from "lucide-react";

export interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  admin?: boolean; // requires ROLE_ADMIN
}

export interface NavSection {
  heading: string;
  items: NavItem[];
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
        heading: "Directory",
        items: [
          { to: "/admin/users", label: "Users", icon: Users, admin: true },
          { to: "/admin/groups", label: "Groups", icon: UsersRound, admin: true },
        ],
      },
      {
        heading: "Applications",
        items: [
          { to: "/admin/applications", label: "Applications", icon: Boxes, admin: true },
          { to: "/admin/clients", label: "OAuth2 Clients", icon: AppWindow, admin: true },
          { to: "/admin/relying-parties", label: "SAML Providers", icon: Network, admin: true },
        ],
      },
      {
        heading: "Access & Security",
        items: [
          { to: "/admin/auth-policies", label: "Auth Policies", icon: ShieldCheck, admin: true },
          { to: "/admin/session-policy", label: "Session Policy", icon: Clock, admin: true },
          { to: "/admin/ip-ranges", label: "IP Ranges", icon: Globe, admin: true },
        ],
      },
      {
        heading: "System",
        items: [
          { to: "/admin/scim-tokens", label: "SCIM Tokens", icon: Coins, admin: true },
          { to: "/admin/audit", label: "Audit Log", icon: ScrollText, admin: true },
        ],
      },
    ],
  },
];

/** All nav items, flattened across flat groups and accordion sections. */
function allItems(): NavItem[] {
  return NAV.flatMap((g) => [...(g.items ?? []), ...(g.sections ?? []).flatMap((s) => s.items)]);
}

export function titleFor(pathname: string): string {
  return allItems().find((i) => i.to === pathname)?.label ?? "Dashboard";
}

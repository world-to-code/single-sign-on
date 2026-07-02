import { useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { ArrowLeft, ChevronDown, LogOut, Menu, ShieldCheck, X } from "lucide-react";
import type { SessionView } from "@/auth";
import { logout } from "@/auth";
import { clearAdminUnlock, startAdminOidc } from "@/adminPortal";
import { triggerStepUp } from "@/api";
import { Brand } from "@/components/Brand";
import { NAV, titleFor } from "@/components/layout/nav";
import type { NavItem } from "@/components/layout/nav";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";

export default function AppShell(
  { session, variant = "user", children }:
  { session: SessionView; variant?: "user" | "admin"; children: React.ReactNode },
) {
  const isAdmin = session.roles.includes("ROLE_ADMIN");
  // A nav item is visible when the shell/role allows it AND (if it declares one) the user holds the
  // required fine-grained permission. Backend expands create/update/delete into the implied :read,
  // so an admin with only e.g. user:create still sees the Users tab.
  const canSee = (i: NavItem) =>
    (isAdmin || !i.admin) && (!i.permission || session.permissions.includes(i.permission));
  const location = useLocation();
  const [open, setOpen] = useState(false);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const groups = NAV.filter((g) => g.scope === variant);

  async function doLogout() {
    clearAdminUnlock(); // drop the admin-console OIDC marker on sign-out
    await logout();
    window.location.href = "/";
  }

  function exitAdmin() {
    clearAdminUnlock();
    window.location.href = "/";
  }

  // Enter the admin console: force a FRESH step-up re-auth FIRST (re-stamps the session auth_time), so
  // the elevation token minted by the subsequent OIDC flow carries a fresh auth_time (RFC 9470).
  async function enterAdmin() {
    if (await triggerStepUp("action")) {
      await startAdminOidc(); // navigates away to /oauth2/authorize
    }
  }

  const navLink = ({ to, label, icon: Icon }: NavItem) => {
    const active = location.pathname === to;
    return (
      <Link
        key={to}
        to={to}
        onClick={() => setOpen(false)}
        className={cn(
          "flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors",
          active
            ? "bg-sidebar-accent text-white shadow-sm"
            : "text-sidebar-foreground hover:bg-white/5 hover:text-white",
        )}
      >
        <Icon className="size-4 shrink-0" />
        {label}
      </Link>
    );
  };

  const sidebar = (
    <div className="flex h-full flex-col bg-sidebar text-sidebar-foreground">
      <div className="flex h-16 items-center justify-between border-b border-sidebar-border px-5">
        <Brand className="[&_.text-muted-foreground]:text-sidebar-foreground/60 [&_.text-sm]:text-white" />
        <button className="lg:hidden text-sidebar-foreground" onClick={() => setOpen(false)}><X className="size-5" /></button>
      </div>
      <nav className="flex-1 space-y-6 overflow-y-auto px-3 py-5">
        {variant === "admin" && (
          <button
            type="button"
            onClick={exitAdmin}
            className="flex w-full items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-sidebar-foreground transition-colors hover:bg-white/5 hover:text-white"
          >
            <ArrowLeft className="size-4 shrink-0" /> Back to portal
          </button>
        )}
        {groups.map((group) => {
          // Flat group (e.g. Account).
          if (group.items) {
            const items = group.items.filter(canSee);
            if (!items.length) return null;
            return (
              <div key={group.heading}>
                <p className="px-3 pb-2 text-[11px] font-semibold uppercase tracking-wider text-sidebar-foreground/40">
                  {group.heading}
                </p>
                <div className="space-y-1">{items.map(navLink)}</div>
              </div>
            );
          }
          // Accordion group (e.g. Administration): collapsible sections by domain.
          const sections = (group.sections ?? [])
            .map((s) => ({ heading: s.heading, items: s.items.filter(canSee) }))
            .filter((s) => s.items.length);
          if (!sections.length) return null;
          return (
            <div key={group.heading}>
              <p className="px-3 pb-2 text-[11px] font-semibold uppercase tracking-wider text-sidebar-foreground/40">
                {group.heading}
              </p>
              <div className="space-y-0.5">
                {sections.map((s) => {
                  const hasActive = s.items.some((i) => i.to === location.pathname);
                  const isOpen = hasActive || !collapsed[s.heading]; // active section stays open
                  return (
                    <div key={s.heading}>
                      <button
                        type="button"
                        onClick={() => setCollapsed((c) => ({ ...c, [s.heading]: !c[s.heading] }))}
                        aria-expanded={isOpen}
                        className="flex w-full items-center justify-between rounded-md px-3 py-2 text-xs font-semibold uppercase tracking-wide text-sidebar-foreground/55 transition-colors hover:text-white"
                      >
                        {s.heading}
                        <ChevronDown className={cn("size-3.5 transition-transform", isOpen ? "" : "-rotate-90")} />
                      </button>
                      {isOpen && <div className="mb-1 mt-0.5 space-y-1">{s.items.map(navLink)}</div>}
                    </div>
                  );
                })}
              </div>
            </div>
          );
        })}
      </nav>
      <div className="border-t border-sidebar-border px-5 py-3 text-[11px] text-sidebar-foreground/40">
        Mini SSO · single-node IdP
      </div>
    </div>
  );

  const initials = (session.username ?? "?").slice(0, 2).toUpperCase();

  return (
    <div className="min-h-screen bg-background">
      {/* Desktop sidebar */}
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 lg:block">{sidebar}</aside>

      {/* Mobile drawer */}
      {open && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <div className="absolute inset-0 bg-black/50" onClick={() => setOpen(false)} />
          <div className="absolute inset-y-0 left-0 w-64">{sidebar}</div>
        </div>
      )}

      <div className="lg:pl-64">
        <header className="sticky top-0 z-20 flex h-16 items-center gap-4 border-b bg-background/80 px-4 backdrop-blur sm:px-6">
          <button className="lg:hidden" onClick={() => setOpen(true)}><Menu className="size-5" /></button>
          <h1 className="text-lg font-semibold tracking-tight">{titleFor(location.pathname)}</h1>
          <div className="ml-auto flex items-center gap-3">
            {variant === "user" && isAdmin && (
              <Button variant="outline" size="sm" onClick={() => { void enterAdmin(); }}>
                <ShieldCheck /> Admin console
              </Button>
            )}
            {variant === "admin" && (
              <Button variant="ghost" size="sm" onClick={exitAdmin}>
                <ArrowLeft /> Back to portal
              </Button>
            )}
            <DropdownMenu>
              <DropdownMenuTrigger className="flex items-center gap-2 rounded-full outline-none focus-visible:ring-2 focus-visible:ring-ring">
                <Avatar><AvatarFallback>{initials}</AvatarFallback></Avatar>
                <div className="hidden text-left sm:block">
                  <div className="text-sm font-medium leading-tight">{session.username}</div>
                  <div className="text-[11px] text-muted-foreground">{isAdmin ? "Administrator" : "Member"}</div>
                </div>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuLabel>{session.username}</DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={doLogout} className="text-destructive focus:text-destructive">
                  <LogOut /> Sign out
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </header>
        <main className="mx-auto max-w-7xl p-4 sm:p-6 lg:p-8">{children}</main>
      </div>
    </div>
  );
}

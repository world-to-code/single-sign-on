import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useLocation, useNavigate } from "react-router-dom";
import {
  ArrowLeft, ChevronDown, LogOut, Menu, PanelLeftClose, PanelLeftOpen, ShieldCheck, X,
} from "lucide-react";
import type { SessionView } from "@/auth";
import { isPlatformAdmin, logout } from "@/auth";
import { setDrillIn, useDrillIn } from "@/drillIn";
import { clearAdminUnlock, startAdminOidc } from "@/adminPortal";
import { triggerStepUp } from "@/api";
import { Brand } from "@/components/Brand";
import { NAV, isNavActive } from "@/components/layout/nav";
import type { NavItem } from "@/components/layout/nav";
import { LanguageToggle } from "@/components/layout/LanguageToggle";
import { ThemeToggle } from "@/components/layout/ThemeToggle";
import { useAdminConsoleAccess } from "@/hooks/useAdminConsoleAccess";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export default function AppShell(
  { session, variant = "user", children }:
  { session: SessionView; variant?: "user" | "admin"; children: React.ReactNode },
) {
  const { t } = useTranslation("nav");
  // Console ENTRY is an app assignment (Model B), not a role; undefined while the check loads.
  const canEnterAdmin = useAdminConsoleAccess();
  // Within a shell, a nav item shows when the user holds its fine-grained permission (if it declares
  // one). Backend expands create/update/delete into the implied :read, so an admin with only e.g.
  // user:create still sees the Users tab. Whoever is in the admin shell already passed the assignment
  // gate (AdminGuard), so no role is checked here.
  const canSee = (i: NavItem) =>
    (!i.permission || session.permissions.includes(i.permission))
    && (!i.superAdmin || isPlatformAdmin(session)); // platform-only areas hidden from tenant admins
  const location = useLocation();
  const navigate = useNavigate();
  const drill = useDrillIn(); // the tenant a super-admin is managing, or null (platform view)
  const [open, setOpen] = useState(false); // mobile drawer
  const [userCollapsed, setUserCollapsed] = useState(false); // explicit rail toggle (not persisted yet)
  const [sectionCollapsed, setSectionCollapsed] = useState<Record<string, boolean>>({});
  // Responsive mode. DESIGN.md §3: full ≥1280, auto icon-rail ≤1280, overlay drawer ≤900.
  const [wide, setWide] = useState(true);
  const [mobile, setMobile] = useState(false);
  useEffect(() => {
    const wideMq = window.matchMedia("(min-width: 1280px)");
    const mobileMq = window.matchMedia("(max-width: 900px)");
    const update = () => { setWide(wideMq.matches); setMobile(mobileMq.matches); };
    update();
    wideMq.addEventListener("change", update);
    mobileMq.addEventListener("change", update);
    return () => { wideMq.removeEventListener("change", update); mobileMq.removeEventListener("change", update); };
  }, []);
  const rail = mobile ? false : (userCollapsed || !wide);
  const groups = NAV.filter((g) => g.scope === variant);

  async function doLogout() {
    clearAdminUnlock(); // drop the admin-console OIDC marker on sign-out
    // If the session did SAML SSO to front-channel SPs, follow the redirect chain that logs them out too;
    // it ends by returning here. Otherwise go straight to the landing page.
    const result = await logout().catch(() => null);
    window.location.href = result?.samlLogoutRedirect ?? "/";
  }

  function exitAdmin() {
    clearAdminUnlock();
    window.location.href = "/";
  }

  function exitDrillIn() {
    setDrillIn(null); // subsequent admin requests drop X-Org-Context → back to platform (all tenants)
    navigate("/admin/organizations");
  }

  // Enter the admin console: force a FRESH step-up re-auth FIRST (re-stamps the session auth_time), so
  // the elevation token minted by the subsequent OIDC flow carries a fresh auth_time (RFC 9470).
  async function enterAdmin() {
    if (await triggerStepUp("elevation")) {
      await startAdminOidc(); // navigates away to /oauth2/authorize
    }
  }

  const initials = (session.username ?? "?").slice(0, 2).toUpperCase();

  const navLink = ({ to, label, icon: Icon }: NavItem) => {
    const active = isNavActive(location.pathname, to);
    const text = t(label);
    return (
      <Link
        key={to}
        to={to}
        onClick={() => setOpen(false)}
        aria-current={active ? "page" : undefined}
        title={rail ? text : undefined}
        aria-label={rail ? text : undefined}
        className={cn(
          "flex items-center gap-3 rounded-md py-2 text-sm font-medium transition-colors",
          rail ? "justify-center px-0" : "px-3",
          active
            ? "bg-accent font-semibold text-accent-foreground"
            : "text-ink-2 hover:bg-sunken hover:text-ink",
        )}
      >
        <Icon className="size-4 shrink-0" />
        {!rail && <span>{text}</span>}
      </Link>
    );
  };

  const nav = (
    <nav className="flex-1 space-y-6 overflow-y-auto px-3 py-5">
      {variant === "admin" && (
        <button
          type="button"
          onClick={exitAdmin}
          title={rail ? t("backToPortal") : undefined}
          aria-label={rail ? t("backToPortal") : undefined}
          className={cn(
            "flex w-full items-center gap-3 rounded-md py-2 text-sm font-medium text-ink-2 transition-colors hover:bg-sunken hover:text-ink",
            rail ? "justify-center px-0" : "px-3",
          )}
        >
          <ArrowLeft className="size-4 shrink-0" />
          {!rail && t("backToPortal")}
        </button>
      )}
      {groups.map((group) => {
        // Flat group (e.g. Account).
        if (group.items) {
          const items = group.items.filter(canSee);
          if (!items.length) return null;
          return (
            <div key={group.heading}>
              {!rail && (
                <p className="px-3 pb-2 text-[11px] font-bold uppercase tracking-[0.04em] text-faint">
                  {t(group.heading)}
                </p>
              )}
              <div className="space-y-1">{items.map(navLink)}</div>
            </div>
          );
        }
        // Accordion group (e.g. Administration): collapsible sections by domain. An org-scoped section
        // is hidden from a super-admin until they drill into a tenant (a tenant admin is always in-org,
        // so it shows for them directly) — platform vs. org management stay visually separate.
        const sections = (group.sections ?? [])
          .filter((s) => !s.requiresOrg || !isPlatformAdmin(session) || drill)
          .map((s) => ({ heading: s.heading, items: s.items.filter(canSee) }))
          .filter((s) => s.items.length);
        if (!sections.length) return null;
        // The 68px rail has no room for headings/accordions — show a flat icon list.
        if (rail) {
          return <div key={group.heading} className="space-y-1">{sections.flatMap((s) => s.items).map(navLink)}</div>;
        }
        return (
          <div key={group.heading}>
            <p className="px-3 pb-2 text-[11px] font-bold uppercase tracking-[0.04em] text-faint">
              {t(group.heading)}
            </p>
            <div className="space-y-0.5">
              {sections.map((s) => {
                const hasActive = s.items.some((i) => isNavActive(location.pathname, i.to));
                const isOpen = hasActive || !sectionCollapsed[s.heading]; // active section stays open
                return (
                  <div key={s.heading}>
                    <button
                      type="button"
                      onClick={() => setSectionCollapsed((c) => ({ ...c, [s.heading]: !c[s.heading] }))}
                      aria-expanded={isOpen}
                      className="flex w-full items-center justify-between rounded-md px-3 py-2 text-xs font-semibold uppercase tracking-wide text-muted transition-colors hover:text-ink"
                    >
                      {t(s.heading)}
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
  );

  const sidebar = (
    <div className="flex h-full flex-col bg-card">
      <div className="flex h-16 items-center gap-2 border-b border-line px-4">
        {rail ? (
          <div className="flex size-9 items-center justify-center rounded-lg bg-ink text-bg">
            <ShieldCheck className="size-5" />
          </div>
        ) : (
          <Brand />
        )}
        {mobile && (
          <button className="ml-auto text-muted hover:text-ink" aria-label={t("closeNavigation")} onClick={() => setOpen(false)}>
            <X className="size-5" />
          </button>
        )}
      </div>
      {nav}
      {/* Foot: account, language, theme, sign out — DESIGN.md §3 keeps these here, never in the topbar. */}
      <div className="border-t border-line p-3">
        <div className={cn("flex items-center gap-2.5 rounded-lg", rail ? "justify-center py-1" : "px-1 py-1")}>
          <span className="flex size-8 shrink-0 items-center justify-center rounded-full bg-accent text-xs font-bold text-accent-foreground">
            {initials}
          </span>
          {!rail && (
            <span className="min-w-0 leading-tight">
              <span className="block truncate text-[13px] font-semibold text-ink">{session.username}</span>
              <span className="block text-[11px] text-faint">{canEnterAdmin ? t("administrator") : t("member")}</span>
            </span>
          )}
        </div>
        <div className={cn("mt-1.5 flex gap-1", rail && "flex-col items-center")}>
          <LanguageToggle iconOnly={rail} />
          <ThemeToggle iconOnly={rail} />
          <button
            type="button"
            onClick={doLogout}
            title={rail ? t("signOut") : undefined}
            aria-label={t("signOut")}
            className={cn(
              "flex h-9 items-center justify-center rounded-lg text-muted transition-colors hover:bg-sunken hover:text-destructive",
              rail ? "w-9" : "w-9",
            )}
          >
            <LogOut className="size-4" />
          </button>
        </div>
      </div>
    </div>
  );

  return (
    <div
      className={cn(
        "grid min-h-screen bg-background",
        mobile ? "grid-cols-[minmax(0,1fr)]" : rail ? "grid-cols-[68px_minmax(0,1fr)]" : "grid-cols-[248px_minmax(0,1fr)]",
      )}
    >
      {/* Desktop sidebar / icon rail (grid column, sticky). minmax(0,1fr) on the content column keeps a
          wide table from pushing the whole grid past the viewport (DESIGN.md §3). */}
      {!mobile && (
        <aside className="sticky top-0 z-30 h-screen w-full border-r border-line bg-card">
          {sidebar}
          {wide && (
            <button
              type="button"
              onClick={() => setUserCollapsed((c) => !c)}
              aria-label={rail ? t("expandSidebar") : t("collapseSidebar")}
              className="absolute -right-3 top-16 z-40 flex size-6 items-center justify-center rounded-full border border-line bg-card text-muted shadow-sm transition-colors hover:text-ink"
            >
              {rail ? <PanelLeftOpen className="size-3.5" /> : <PanelLeftClose className="size-3.5" />}
            </button>
          )}
        </aside>
      )}

      {/* Mobile drawer + scrim */}
      {mobile && open && (
        <div className="fixed inset-0 z-50">
          <div className="absolute inset-0 bg-black/50" onClick={() => setOpen(false)} />
          <div className="absolute inset-y-0 left-0 w-[248px] border-r border-line bg-card shadow-lg">{sidebar}</div>
        </div>
      )}

      <div className="flex min-w-0 flex-col">
        <header className="sticky top-0 z-20 flex h-16 items-center gap-3 border-b border-line bg-background/80 px-4 backdrop-blur sm:px-6">
          {mobile && (
            <button data-nav-toggle aria-label={t("openNavigation")} onClick={() => setOpen(true)}>
              <Menu className="size-5" />
            </button>
          )}
          {/* Scope breadcrumb pill: a platform admin drilled into a tenant. No full-width banner. */}
          {variant === "admin" && drill && (
            <div className="flex min-w-0 items-center gap-2">
              <span className="inline-flex items-center gap-2 rounded-full border border-accent-line bg-accent px-3 py-1 text-sm font-medium text-accent-foreground">
                <span className="size-1.5 shrink-0 rounded-full bg-current" />
                <span className="truncate">{t("managing")} · <span className="font-mono">{drill.slug}</span></span>
              </span>
              <Button variant="outline" size="sm" onClick={exitDrillIn}>{t("exitOrganization")}</Button>
            </div>
          )}
          {/* Topbar carries only navigation context and the admin-console entry. Identity, language,
              theme and sign-out live in the sidebar foot (DESIGN.md §3), not here. */}
          {variant === "user" && canEnterAdmin && (
            <div className="ml-auto flex items-center gap-3">
              <Button variant="outline" size="sm" onClick={() => { void enterAdmin(); }}>
                <ShieldCheck /> {t("adminConsole")}
              </Button>
            </div>
          )}
        </header>
        <main className="mx-auto w-full max-w-[1400px] p-4 sm:p-6 lg:p-8">{children}</main>
      </div>
    </div>
  );
}

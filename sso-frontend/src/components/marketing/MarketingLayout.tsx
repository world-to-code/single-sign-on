import { useState } from "react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link, Outlet, useLocation } from "react-router-dom";
import { ArrowRight, Menu, X } from "lucide-react";
import { Brand } from "@/components/Brand";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

type MKey = keyof (typeof import("@/i18n/en/marketing"))["marketing"];

export const signIn = () => window.location.assign("/login");
export const getStarted = () => window.location.assign("/signup");

const NAV: { key: MKey; to: string }[] = [
  { key: "navProduct", to: "/product" },
  { key: "navIntegrations", to: "/integrations" },
  { key: "navSecurity", to: "/security" },
  { key: "navHowItWorks", to: "/how-it-works" },
];

type FootLink = { key?: MKey; raw?: string; to?: string; href?: string };
const FOOTER: { headingKey: MKey; links: FootLink[] }[] = [
  { headingKey: "footProduct", links: [
    { key: "footOverview", to: "/" }, { key: "footCapabilities", to: "/product" },
    { key: "footIntegrations", to: "/integrations" }, { key: "footSecurity", to: "/security" },
  ] },
  { headingKey: "footDevelopers", links: [
    { raw: "OpenID Connect", to: "/integrations" }, { raw: "SAML 2.0", to: "/integrations" },
    { raw: "SCIM 2.0", to: "/integrations" }, { key: "footHowItWorks", to: "/how-it-works" },
  ] },
  { headingKey: "footGetStarted", links: [
    { key: "footCreateWorkspace", href: "/signup" }, { key: "footSignIn", href: "/login" },
  ] },
];

/** Shared chrome for the public marketing site: a global nav that routes to separate pages, and a full
 *  footer. Section links use client-side <Link>; the auth CTAs do a full navigation to leave the marketing
 *  routes and hand off to the app's sign-in / signup flows. */
export default function MarketingLayout() {
  const { t } = useTranslation("marketing");
  const [menuOpen, setMenuOpen] = useState(false);
  const { pathname } = useLocation();

  return (
    <div className="flex min-h-screen flex-col bg-background text-foreground">
      <header className="sticky top-0 z-30 border-b bg-background/85 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-4 sm:px-6">
          <div className="flex items-center gap-8">
            <Link to="/" aria-label={t("ariaHome")}><Brand /></Link>
            <nav className="hidden items-center gap-1 md:flex">
              {NAV.map((n) => (
                <Link key={n.to} to={n.to}
                      className={cn("rounded-md px-3 py-2 text-sm font-medium transition-colors hover:bg-accent hover:text-foreground",
                        pathname === n.to ? "text-foreground" : "text-muted-foreground")}>
                  {t(n.key)}
                </Link>
              ))}
            </nav>
          </div>
          <div className="flex items-center gap-2">
            <Button variant="ghost" size="sm" className="hidden sm:inline-flex" onClick={signIn}>{t("signIn")}</Button>
            <Button size="sm" onClick={getStarted}>{t("getStarted")} <ArrowRight /></Button>
            <button className="ml-1 md:hidden" aria-label={t("ariaMenu")} aria-expanded={menuOpen}
                    onClick={() => setMenuOpen((o) => !o)}>
              {menuOpen ? <X className="size-5" /> : <Menu className="size-5" />}
            </button>
          </div>
        </div>
        {menuOpen && (
          <nav className="border-t md:hidden">
            <div className="mx-auto flex max-w-6xl flex-col px-4 py-2 sm:px-6">
              {NAV.map((n) => (
                <Link key={n.to} to={n.to} onClick={() => setMenuOpen(false)}
                      className="rounded-md px-2 py-2 text-sm font-medium text-muted-foreground hover:text-foreground">
                  {t(n.key)}
                </Link>
              ))}
              <button onClick={signIn} className="px-2 py-2 text-left text-sm font-medium text-muted-foreground hover:text-foreground">
                {t("signIn")}
              </button>
            </div>
          </nav>
        )}
      </header>

      <main className="flex-1"><Outlet /></main>

      <footer className="border-t bg-muted/20">
        <div className="mx-auto max-w-6xl px-4 py-14 sm:px-6">
          <div className="grid gap-10 sm:grid-cols-2 lg:grid-cols-4">
            <div className="space-y-3">
              <Brand />
              <p className="max-w-xs text-sm text-muted-foreground">{t("footerTagline")}</p>
            </div>
            {FOOTER.map((col) => (
              <div key={col.headingKey}>
                <p className="text-sm font-semibold">{t(col.headingKey)}</p>
                <ul className="mt-3 space-y-2">
                  {col.links.map((l) => {
                    const label = l.key ? t(l.key) : l.raw;
                    return (
                      <li key={label}>
                        {l.to
                          ? <Link to={l.to} className="text-sm text-muted-foreground transition-colors hover:text-foreground">{label}</Link>
                          : <a href={l.href} className="text-sm text-muted-foreground transition-colors hover:text-foreground">{label}</a>}
                      </li>
                    );
                  })}
                </ul>
              </div>
            ))}
          </div>
          <div className="mt-12 flex flex-col items-center justify-between gap-3 border-t pt-6 text-sm text-muted-foreground sm:flex-row">
            <span>{t("footerBottomLeft")}</span>
            <span>OIDC · SAML 2.0 · SCIM 2.0 · WebAuthn</span>
          </div>
        </div>
      </footer>
    </div>
  );
}

/* ---------- Reusable marketing primitives (shared chrome across pages) ---------- */

/** Full-bleed section band with a consistent max-width inner container. `tone` sets the surface. */
export function Section(
  { tone = "default", children, id, className }:
  { tone?: "default" | "muted" | "dark"; children: ReactNode; id?: string; className?: string },
) {
  const tones = {
    default: "",
    muted: "border-y bg-muted/30",
    dark: "bg-band text-band-fg",
  } as const;
  return (
    <section id={id} className={cn(tones[tone])}>
      <div className={cn("mx-auto max-w-6xl px-4 py-20 sm:px-6 sm:py-24", className)}>{children}</div>
    </section>
  );
}

export function CtaBand() {
  const { t } = useTranslation("marketing");
  return (
    <section className="border-t">
      <div className="mx-auto max-w-3xl px-4 py-20 text-center sm:px-6">
        <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">{t("ctaTitle")}</h2>
        <p className="mx-auto mt-3 max-w-lg text-muted-foreground">{t("ctaBody")}</p>
        <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
          <Button size="lg" onClick={getStarted}>{t("ctaGetStartedFree")} <ArrowRight /></Button>
          <Button size="lg" variant="outline" onClick={signIn}>{t("signIn")}</Button>
        </div>
      </div>
    </section>
  );
}

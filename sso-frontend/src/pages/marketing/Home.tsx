import { Link } from "react-router-dom";
import {
  ArrowRight, Blocks, Check, CheckCircle2, Eye, KeyRound, Network, RefreshCw, ShieldAlert,
  ShieldCheck, UserCheck, UsersRound, Workflow,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Section, CtaBand, HeroBackdrop, getStarted, signIn } from "@/components/marketing/MarketingLayout";

/** Marketing home ("/"): an asymmetric landing hero with a live product mock, a sticky "why switch"
 *  narrative, and wide entry-point rows that route to the detail pages — a story, not a card wall. */
export default function Home() {
  return (
    <>
      <section className="relative overflow-hidden border-b">
        <HeroBackdrop />
        <div className="relative mx-auto grid max-w-6xl gap-12 px-4 py-20 sm:px-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.05fr)] lg:items-center lg:py-24">
          <div>
            <Badge variant="muted" className="mb-5">Central identity provider</Badge>
            <h1 className="text-balance text-4xl font-bold tracking-tight sm:text-5xl">
              One identity for every app your team touches
            </h1>
            <p className="mt-5 max-w-xl text-pretty text-lg text-muted-foreground">
              Mini SSO is a self-hostable, multi-tenant identity provider — single sign-on over OIDC and SAML,
              passkeys and step-up MFA, SCIM provisioning, and per-tenant isolation, all from one deploy.
            </p>
            <div className="mt-8 flex flex-col gap-3 sm:flex-row">
              <Button size="lg" onClick={getStarted}>Start free <ArrowRight /></Button>
              <Button size="lg" variant="outline" onClick={signIn}>Sign in to your workspace</Button>
            </div>
            <div className="mt-6 flex flex-wrap gap-x-6 gap-y-2 text-sm text-muted-foreground">
              {["No credit card", "Set up in minutes", "Standards-based"].map((t) => (
                <span key={t} className="inline-flex items-center gap-1.5"><Check className="size-4 text-primary" /> {t}</span>
              ))}
            </div>
          </div>
          <ProductPreview />
        </div>
      </section>

      <Section tone="muted" className="!py-12">
        <p className="text-center text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          Built on open standards — connect anything
        </p>
        <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
          {["OpenID Connect", "SAML 2.0", "SCIM 2.0", "OAuth 2.1", "WebAuthn / passkeys"].map((p) => (
            <span key={p} className="rounded-full border bg-card px-4 py-1.5 text-sm font-medium">{p}</span>
          ))}
        </div>
      </Section>

      <Section>
        <div className="grid gap-10 lg:grid-cols-[minmax(0,0.85fr)_minmax(0,1.35fr)]">
          <div className="lg:sticky lg:top-24 lg:self-start">
            <Badge variant="muted" className="mb-4">Why teams switch</Badge>
            <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">
              Stop juggling logins and access
            </h2>
            <p className="mt-3 text-muted-foreground">
              Scattered accounts, manual onboarding, and blind spots are incidents waiting to happen. Mini SSO
              replaces them with one governed front door — and each fix is a link away.
            </p>
          </div>
          <div className="space-y-3">
            {PROBLEMS.map((p) => (
              <div key={p.title} className="flex gap-4 rounded-xl border bg-card p-5">
                <div className="flex size-11 shrink-0 items-center justify-center rounded-lg bg-destructive/10 text-destructive">
                  <p.icon className="size-5" />
                </div>
                <div>
                  <h3 className="font-semibold">{p.title}</h3>
                  <p className="mt-1 text-sm text-muted-foreground">{p.body}</p>
                  <p className="mt-2 inline-flex items-center gap-1.5 text-sm font-medium text-primary">
                    <CheckCircle2 className="size-4" /> {p.fix}
                  </p>
                </div>
              </div>
            ))}
          </div>
        </div>
      </Section>

      <Section tone="muted">
        <div className="mb-8 flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <Badge variant="muted" className="mb-3">Explore</Badge>
            <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">
              Four ways to go deeper
            </h2>
          </div>
          <p className="max-w-sm text-sm text-muted-foreground">
            Each page tells a different part of the story — from capabilities to the isolation model.
          </p>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          {EXPLORE.map((e) => (
            <Link key={e.to} to={e.to}
                  className="group flex items-center gap-4 rounded-xl border bg-card p-5 transition-shadow hover:shadow-md">
              <div className="flex size-12 shrink-0 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                <e.icon className="size-5" />
              </div>
              <div className="min-w-0 flex-1">
                <h3 className="font-semibold">{e.title}</h3>
                <p className="mt-0.5 text-sm text-muted-foreground">{e.body}</p>
              </div>
              <ArrowRight className="size-5 shrink-0 text-muted-foreground transition-all group-hover:translate-x-0.5 group-hover:text-primary" />
            </Link>
          ))}
        </div>
      </Section>

      <CtaBand />
    </>
  );
}

/** A stylized in-browser product preview — a tenant admin dashboard — built entirely from tokens. */
function ProductPreview() {
  const bars = ["h-[38%]", "h-[52%]", "h-[44%]", "h-[61%]", "h-[55%]", "h-[72%]",
    "h-[66%]", "h-[80%]", "h-[74%]", "h-[90%]", "h-[62%]", "h-[84%]"];
  return (
    <div className="overflow-hidden rounded-xl border bg-card shadow-2xl">
      <div className="flex items-center gap-2 border-b bg-muted/50 px-4 py-2.5">
        <span className="size-3 rounded-full bg-destructive/40" />
        <span className="size-3 rounded-full bg-amber-400/50" />
        <span className="size-3 rounded-full bg-success/50" />
        <span className="ml-3 rounded-md border bg-background px-2.5 py-1 font-mono text-xs text-muted-foreground">
          acme.mysso.com/admin
        </span>
      </div>
      <div className="grid gap-4 p-5 sm:grid-cols-3">
        {[
          { label: "Active users", value: "1,284", icon: UsersRound },
          { label: "Sign-ins today", value: "3,907", icon: UserCheck },
          { label: "Connected apps", value: "24", icon: Blocks },
        ].map((s) => (
          <div key={s.label} className="rounded-lg border bg-background p-4 text-left">
            <div className="flex items-center justify-between">
              <span className="text-xs text-muted-foreground">{s.label}</span>
              <s.icon className="size-4 text-primary" />
            </div>
            <div className="mt-1 text-2xl font-semibold tabular-nums">{s.value}</div>
          </div>
        ))}
      </div>
      <div className="px-5 pb-5">
        <div className="rounded-lg border bg-background p-4">
          <div className="mb-3 flex items-center justify-between">
            <span className="text-sm font-medium">Sign-ins this week</span>
            <Badge variant="success">+12%</Badge>
          </div>
          <div className="flex h-24 items-end gap-1.5">
            {bars.map((h, i) => (
              <div key={i} className={`flex-1 rounded-t bg-primary/80 ${h}`} />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

const PROBLEMS: { icon: LucideIcon; title: string; body: string; fix: string }[] = [
  { icon: KeyRound, title: "Password sprawl", body: "Every app becomes another password to reset and another way in for attackers.", fix: "One login, everywhere" },
  { icon: RefreshCw, title: "Manual joiners and leavers", body: "Onboarding drags and offboarding gets missed — orphaned accounts linger for months.", fix: "Automated with SCIM" },
  { icon: Eye, title: "No visibility", body: "Who signed in, from where, with which factor? Without a trail you can't answer an auditor.", fix: "Full audit and analytics" },
  { icon: ShieldAlert, title: "Inconsistent MFA", body: "Some apps enforce it, some don't — the weakest link defines your security posture.", fix: "MFA and step-up, centrally" },
];

const EXPLORE: { to: string; icon: LucideIcon; title: string; body: string }[] = [
  { to: "/product", icon: Workflow, title: "Product", body: "SSO, MFA, provisioning, RBAC, and analytics in one place." },
  { to: "/integrations", icon: Blocks, title: "Integrations", body: "Connect OIDC and SAML apps and sync users with SCIM." },
  { to: "/security", icon: ShieldCheck, title: "Security", body: "Row-level isolation, encryption, and revocation that propagates." },
  { to: "/how-it-works", icon: Network, title: "How it works", body: "From signup to connected apps in four steps." },
];

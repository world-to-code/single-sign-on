import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  ArrowRight, Blocks, Check, CheckCircle2, Eye, KeyRound, Network, RefreshCw, ShieldAlert,
  ShieldCheck, UserCheck, UsersRound, Workflow,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Section, CtaBand, getStarted, signIn } from "@/components/marketing/MarketingLayout";

type MKey = keyof (typeof import("@/i18n/en/marketing"))["marketing"];

/** Marketing home ("/"): an asymmetric landing hero with a live product mock, a sticky "why switch"
 *  narrative, and wide entry-point rows that route to the detail pages — a story, not a card wall. */
export default function Home() {
  const { t } = useTranslation("marketing");
  return (
    <>
      <section className="relative overflow-hidden border-b">
        <div className="relative mx-auto grid max-w-6xl gap-12 px-4 py-20 sm:px-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.05fr)] lg:items-center lg:py-24">
          <div>
            <Badge variant="muted" className="mb-5">{t("homeHeroBadge")}</Badge>
            <h1 className="text-balance text-4xl font-bold tracking-tight sm:text-5xl">{t("homeHeroTitle")}</h1>
            <p className="mt-5 max-w-xl text-pretty text-lg text-muted-foreground">{t("homeHeroBody")}</p>
            <div className="mt-8 flex flex-col gap-3 sm:flex-row">
              <Button size="lg" onClick={getStarted}>{t("homeHeroStartFree")} <ArrowRight /></Button>
              <Button size="lg" variant="outline" onClick={signIn}>{t("homeHeroSignIn")}</Button>
            </div>
            <div className="mt-6 flex flex-wrap gap-x-6 gap-y-2 text-sm text-muted-foreground">
              {(["homeHeroChipNoCard", "homeHeroChipMinutes", "homeHeroChipStandards"] as MKey[]).map((k) => (
                <span key={k} className="inline-flex items-center gap-1.5"><Check className="size-4 text-primary" /> {t(k)}</span>
              ))}
            </div>
          </div>
          <ProductPreview />
        </div>
      </section>

      <Section tone="muted" className="!py-12">
        <p className="text-center text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          {t("homeStandardsHeading")}
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
            <Badge variant="muted" className="mb-4">{t("homeWhyBadge")}</Badge>
            <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">{t("homeWhyTitle")}</h2>
            <p className="mt-3 text-muted-foreground">{t("homeWhyBody")}</p>
          </div>
          <div className="space-y-3">
            {PROBLEMS.map((p) => (
              <div key={p.title} className="flex gap-4 rounded-xl border bg-card p-5">
                <div className="flex size-11 shrink-0 items-center justify-center rounded-lg bg-destructive/10 text-destructive">
                  <p.icon className="size-5" />
                </div>
                <div>
                  <h3 className="font-semibold">{t(p.title)}</h3>
                  <p className="mt-1 text-sm text-muted-foreground">{t(p.body)}</p>
                  <p className="mt-2 inline-flex items-center gap-1.5 text-sm font-medium text-primary">
                    <CheckCircle2 className="size-4" /> {t(p.fix)}
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
            <Badge variant="muted" className="mb-3">{t("homeExploreBadge")}</Badge>
            <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">{t("homeExploreTitle")}</h2>
          </div>
          <p className="max-w-sm text-sm text-muted-foreground">{t("homeExploreBody")}</p>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          {EXPLORE.map((e) => (
            <Link key={e.to} to={e.to}
                  className="group flex items-center gap-4 rounded-xl border bg-card p-5 transition-shadow hover:shadow-md">
              <div className="flex size-12 shrink-0 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                <e.icon className="size-5" />
              </div>
              <div className="min-w-0 flex-1">
                <h3 className="font-semibold">{t(e.title)}</h3>
                <p className="mt-0.5 text-sm text-muted-foreground">{t(e.body)}</p>
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
  const { t } = useTranslation("marketing");
  const bars = ["h-[38%]", "h-[52%]", "h-[44%]", "h-[61%]", "h-[55%]", "h-[72%]",
    "h-[66%]", "h-[80%]", "h-[74%]", "h-[90%]", "h-[62%]", "h-[84%]"];
  const stats: { label: MKey; value: string; icon: LucideIcon }[] = [
    { label: "homePreviewUsers", value: "1,284", icon: UsersRound },
    { label: "homePreviewSignins", value: "3,907", icon: UserCheck },
    { label: "homePreviewApps", value: "24", icon: Blocks },
  ];
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
        {stats.map((s) => (
          <div key={s.label} className="rounded-lg border bg-background p-4 text-left">
            <div className="flex items-center justify-between">
              <span className="text-xs text-muted-foreground">{t(s.label)}</span>
              <s.icon className="size-4 text-primary" />
            </div>
            <div className="mt-1 text-2xl font-semibold tabular-nums">{s.value}</div>
          </div>
        ))}
      </div>
      <div className="px-5 pb-5">
        <div className="rounded-lg border bg-background p-4">
          <div className="mb-3 flex items-center justify-between">
            <span className="text-sm font-medium">{t("homePreviewChartTitle")}</span>
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

const PROBLEMS: { icon: LucideIcon; title: MKey; body: MKey; fix: MKey }[] = [
  { icon: KeyRound, title: "homeProblem1Title", body: "homeProblem1Body", fix: "homeProblem1Fix" },
  { icon: RefreshCw, title: "homeProblem2Title", body: "homeProblem2Body", fix: "homeProblem2Fix" },
  { icon: Eye, title: "homeProblem3Title", body: "homeProblem3Body", fix: "homeProblem3Fix" },
  { icon: ShieldAlert, title: "homeProblem4Title", body: "homeProblem4Body", fix: "homeProblem4Fix" },
];

const EXPLORE: { to: string; icon: LucideIcon; title: MKey; body: MKey }[] = [
  { to: "/product", icon: Workflow, title: "homeExploreProductTitle", body: "homeExploreProductBody" },
  { to: "/integrations", icon: Blocks, title: "homeExploreIntegrationsTitle", body: "homeExploreIntegrationsBody" },
  { to: "/security", icon: ShieldCheck, title: "homeExploreSecurityTitle", body: "homeExploreSecurityBody" },
  { to: "/how-it-works", icon: Network, title: "homeExploreHowTitle", body: "homeExploreHowBody" },
];

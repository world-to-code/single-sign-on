import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Blocks, Building2, CheckCircle2, Mail, Rocket, UsersRound } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Section, CtaBand } from "@/components/marketing/MarketingLayout";

type MKey = keyof (typeof import("@/i18n/en/marketing"))["marketing"];

/** How-it-works page ("/how-it-works"): the path from signup to a live workspace as a connected
 *  vertical timeline, each step carrying its own detail panel, closed by a "day one" ribbon. */
export default function HowItWorks() {
  const { t } = useTranslation("marketing");
  return (
    <>
      <section className="relative overflow-hidden border-b">
        <div className="relative mx-auto max-w-3xl px-4 py-20 text-center sm:px-6 sm:py-24">
          <Badge variant="muted" className="mb-5">{t("howHeroBadge")}</Badge>
          <h1 className="text-balance text-4xl font-bold tracking-tight sm:text-5xl">{t("howHeroTitle")}</h1>
          <p className="mx-auto mt-5 max-w-xl text-pretty text-lg text-muted-foreground">{t("howHeroBody")}</p>
        </div>
      </section>

      <Section>
        <ol className="mx-auto max-w-3xl">
          {STEPS.map((s, i) => (
            <li key={s.title} className="flex gap-5 sm:gap-6">
              <div className="flex flex-col items-center">
                <span className="flex size-11 shrink-0 items-center justify-center rounded-full bg-primary text-lg font-semibold text-primary-foreground">
                  {i + 1}
                </span>
                {i < STEPS.length - 1 && <span className="my-2 w-px flex-1 bg-border" />}
              </div>
              <div className="flex-1 pb-12 last:pb-0">
                <div className="flex items-center gap-2">
                  <s.icon className="size-5 text-primary" />
                  <h3 className="text-lg font-semibold">{t(s.title)}</h3>
                </div>
                <p className="mt-2 text-muted-foreground">{t(s.body)}</p>
                <div className="mt-4 rounded-xl border bg-muted/30 p-4">{s.detail}</div>
              </div>
            </li>
          ))}
        </ol>
      </Section>

      <Section tone="muted">
        <div className="rounded-2xl border bg-card p-8 sm:p-10">
          <div className="mb-8 max-w-2xl">
            <Badge variant="muted" className="mb-3">{t("howDayOneBadge")}</Badge>
            <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">{t("howDayOneTitle")}</h2>
          </div>
          <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
            {OUTCOMES.map((o) => (
              <div key={o.title} className="flex gap-3">
                <CheckCircle2 className="mt-0.5 size-5 shrink-0 text-primary" />
                <div>
                  <h3 className="text-sm font-semibold">{t(o.title)}</h3>
                  <p className="mt-1 text-sm text-muted-foreground">{t(o.body)}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      </Section>

      <CtaBand />
    </>
  );
}

/* ---------- Per-step detail panels ---------- */

function Chips({ itemKeys }: { itemKeys: MKey[] }) {
  const { t } = useTranslation("marketing");
  return (
    <div className="flex flex-wrap gap-2">
      {itemKeys.map((c) => (
        <span key={c} className="rounded-full border bg-card px-3 py-1 font-mono text-xs text-muted-foreground">{t(c)}</span>
      ))}
    </div>
  );
}

function ProvisionDetail() {
  const { t } = useTranslation("marketing");
  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between rounded-lg border bg-card px-3 py-2">
        <span className="font-mono text-xs">acme.mysso.com</span>
        <Badge variant="success">{t("howProvisioned")}</Badge>
      </div>
      <div className="flex items-center gap-2 text-xs text-muted-foreground">
        <Mail className="size-3.5 text-primary" /> {t("howActivationEmail")}
      </div>
    </div>
  );
}

function SyncDetail() {
  const { t } = useTranslation("marketing");
  const rows = [
    { u: "jordan@acme.com", s: t("howProvisioned") },
    { u: "priya@acme.com", s: t("howProvisioned") },
  ];
  return (
    <ul className="space-y-1.5">
      {rows.map((r) => (
        <li key={r.u} className="flex items-center justify-between font-mono text-xs">
          <span className="text-muted-foreground">{r.u}</span>
          <span className="inline-flex items-center gap-1.5 text-success"><span className="size-1.5 rounded-full bg-success" /> {r.s}</span>
        </li>
      ))}
    </ul>
  );
}

const STEPS: { icon: LucideIcon; title: MKey; body: MKey; detail: ReactNode }[] = [
  { icon: Building2, title: "howStep1Title", body: "howStep1Body", detail: <ProvisionDetail /> },
  {
    icon: Blocks, title: "howStep2Title", body: "howStep2Body",
    detail: <Chips itemKeys={["howChipOidcClient", "howChipSamlRp", "howChipPerAppPolicy", "howChipStepUp"]} />,
  },
  { icon: UsersRound, title: "howStep3Title", body: "howStep3Body", detail: <SyncDetail /> },
  {
    icon: Rocket, title: "howStep4Title", body: "howStep4Body",
    detail: <Chips itemKeys={["howChipAuditTrail", "howChipSigninTrends", "howChipBackchannel", "howChipSamlSlo"]} />,
  },
];

const OUTCOMES: { title: MKey; body: MKey }[] = [
  { title: "howOutcome1Title", body: "howOutcome1Body" },
  { title: "howOutcome2Title", body: "howOutcome2Body" },
  { title: "howOutcome3Title", body: "howOutcome3Body" },
  { title: "howOutcome4Title", body: "howOutcome4Body" },
];

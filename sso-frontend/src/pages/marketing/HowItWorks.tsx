import type { ReactNode } from "react";
import { Blocks, Building2, CheckCircle2, Mail, Rocket, UsersRound } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Section, CtaBand } from "@/components/marketing/MarketingLayout";

/** How-it-works page ("/how-it-works"): the path from signup to a live workspace as a connected
 *  vertical timeline, each step carrying its own detail panel, closed by a "day one" ribbon. */
export default function HowItWorks() {
  return (
    <>
      <section className="relative overflow-hidden border-b">
        <div className="relative mx-auto max-w-3xl px-4 py-20 text-center sm:px-6 sm:py-24">
          <Badge variant="muted" className="mb-5">How it works</Badge>
          <h1 className="text-balance text-4xl font-bold tracking-tight sm:text-5xl">
            From signup to single sign-on in an afternoon
          </h1>
          <p className="mx-auto mt-5 max-w-xl text-pretty text-lg text-muted-foreground">
            Self-service from the first click: create your workspace, connect your apps, and invite your team —
            no sales call, no approval queue.
          </p>
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
                  <h3 className="text-lg font-semibold">{s.title}</h3>
                </div>
                <p className="mt-2 text-muted-foreground">{s.body}</p>
                <div className="mt-4 rounded-xl border bg-muted/30 p-4">{s.detail}</div>
              </div>
            </li>
          ))}
        </ol>
      </Section>

      <Section tone="muted">
        <div className="rounded-2xl border bg-card p-8 sm:p-10">
          <div className="mb-8 max-w-2xl">
            <Badge variant="muted" className="mb-3">Day one</Badge>
            <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">
              A governed front door, immediately
            </h2>
          </div>
          <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
            {OUTCOMES.map((o) => (
              <div key={o.title} className="flex gap-3">
                <CheckCircle2 className="mt-0.5 size-5 shrink-0 text-primary" />
                <div>
                  <h3 className="text-sm font-semibold">{o.title}</h3>
                  <p className="mt-1 text-sm text-muted-foreground">{o.body}</p>
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

function Chips({ items }: { items: string[] }) {
  return (
    <div className="flex flex-wrap gap-2">
      {items.map((c) => (
        <span key={c} className="rounded-full border bg-card px-3 py-1 font-mono text-xs text-muted-foreground">{c}</span>
      ))}
    </div>
  );
}

function ProvisionDetail() {
  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between rounded-lg border bg-card px-3 py-2">
        <span className="font-mono text-xs">acme.mysso.com</span>
        <Badge variant="success">Provisioned</Badge>
      </div>
      <div className="flex items-center gap-2 text-xs text-muted-foreground">
        <Mail className="size-3.5 text-primary" /> Activation email sent to your admin
      </div>
    </div>
  );
}

function SyncDetail() {
  const rows = [
    { u: "jordan@acme.com", s: "Provisioned" },
    { u: "priya@acme.com", s: "Provisioned" },
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

const STEPS: { icon: LucideIcon; title: string; body: string; detail: ReactNode }[] = [
  {
    icon: Building2, title: "Create your workspace",
    body: "Sign up with your company details and your tenant is provisioned automatically at its own subdomain. Your admin receives an email to set a password and take control.",
    detail: <ProvisionDetail />,
  },
  {
    icon: Blocks, title: "Connect your applications",
    body: "Register OIDC clients or SAML relying parties and point each app at the discovery URL. Set per-app sign-on policies and step-up where you need higher assurance.",
    detail: <Chips items={["OIDC client", "SAML relying party", "per-app policy", "step-up"]} />,
  },
  {
    icon: UsersRound, title: "Invite and provision your team",
    body: "Add people manually or sync them from your directory over SCIM. Assign roles, require MFA, and everyone lands in the right apps with the right access.",
    detail: <SyncDetail />,
  },
  {
    icon: Rocket, title: "Go live with confidence",
    body: "Turn on the audit trail and analytics, review sign-in trends, and let revocation and session control keep access tight as your team changes.",
    detail: <Chips items={["audit trail", "sign-in trends", "back-channel logout", "SAML SLO"]} />,
  },
];

const OUTCOMES: { title: string; body: string }[] = [
  { title: "Your own subdomain", body: "A tenant workspace isolated from every other." },
  { title: "Admin activation", body: "A secure, one-time link to set your password." },
  { title: "Connected apps", body: "OIDC and SAML integrations, ready to add." },
  { title: "Provisioned users", body: "Manual or SCIM, protected by MFA." },
];

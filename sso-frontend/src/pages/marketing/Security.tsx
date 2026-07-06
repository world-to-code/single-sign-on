import { ArrowDown, Lock, ShieldCheck } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Section, CtaBand, HeroBackdrop } from "@/components/marketing/MarketingLayout";

/** Security page ("/security"): the trust story told seriously — a dark zero-trust isolation flow and a
 *  threat -> mitigation controls table, not another card grid. */
export default function Security() {
  return (
    <>
      <section className="relative overflow-hidden border-b">
        <HeroBackdrop />
        <div className="relative mx-auto max-w-3xl px-4 py-20 sm:px-6 sm:py-24">
          <Badge variant="muted" className="mb-5 inline-flex items-center gap-1.5">
            <ShieldCheck className="size-3.5" /> Secure by design
          </Badge>
          <h1 className="text-balance text-4xl font-bold tracking-tight sm:text-5xl">
            Zero-trust to the row, revocation that actually propagates
          </h1>
          <p className="mt-5 max-w-2xl text-pretty text-lg text-muted-foreground">
            An identity provider is only as good as its weakest guarantee. Mini SSO treats isolation, encryption,
            and revocation as load-bearing — enforced at the database and re-checked on every request.
          </p>
        </div>
      </section>

      <Section tone="dark">
        <div className="grid gap-12 lg:grid-cols-2 lg:items-center">
          <div>
            <Badge variant="default" className="mb-4 bg-white/10 text-white hover:bg-white/10">Isolation model</Badge>
            <h2 className="text-balance text-3xl font-semibold tracking-tight text-white sm:text-4xl">
              Tenants can't see each other — by construction
            </h2>
            <p className="mt-3 text-sidebar-foreground/70">
              Separation isn't a code convention you hope holds. Every query runs against a Postgres row-level-security
              policy that only matches rows for the organization bound to the connection. Bind nothing, and the policy
              matches nothing — the default is deny.
            </p>
            <div className="mt-6 inline-flex items-center gap-2 rounded-lg border border-white/10 bg-white/5 px-4 py-2.5 text-sm text-white">
              <Lock className="size-4 text-primary" /> No org on the connection → zero rows. Fail-closed.
            </div>
          </div>
          <ol className="space-y-3">
            {ISOLATION_FLOW.map((step, i) => (
              <li key={step.label}>
                <div className="rounded-xl border border-white/10 bg-white/5 p-4">
                  <div className="flex items-center gap-3">
                    <span className="flex size-7 shrink-0 items-center justify-center rounded-md bg-primary text-xs font-semibold text-primary-foreground">{i + 1}</span>
                    <span className="font-medium text-white">{step.label}</span>
                  </div>
                  <p className="mt-1.5 pl-10 text-sm text-sidebar-foreground/70">{step.detail}</p>
                </div>
                {i < ISOLATION_FLOW.length - 1 && (
                  <div className="flex justify-center py-1"><ArrowDown className="size-4 text-sidebar-foreground/40" /></div>
                )}
              </li>
            ))}
          </ol>
        </div>
      </Section>

      <Section>
        <div className="mb-8 max-w-2xl">
          <Badge variant="muted" className="mb-3">Controls</Badge>
          <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">
            Every threat mapped to a mitigation
          </h2>
          <p className="mt-3 text-muted-foreground">
            The controls you'd expect from an IdP, and what each one actually stops.
          </p>
        </div>
        <div className="overflow-hidden rounded-xl border">
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/40 hover:bg-muted/40">
                <TableHead className="min-w-[11rem]">Control</TableHead>
                <TableHead className="min-w-[12rem]">Threat it stops</TableHead>
                <TableHead>How it works</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {CONTROLS.map((c) => (
                <TableRow key={c.control}>
                  <TableCell className="align-top font-medium">{c.control}</TableCell>
                  <TableCell className="align-top text-muted-foreground">{c.threat}</TableCell>
                  <TableCell className="align-top text-muted-foreground">{c.how}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </Section>

      <Section tone="muted">
        <div className="grid gap-8 rounded-2xl border bg-card p-8 sm:grid-cols-3 sm:p-10">
          {ASSURANCES.map((a) => (
            <div key={a.title}>
              <a.icon className="size-5 text-primary" />
              <h3 className="mt-3 font-semibold">{a.title}</h3>
              <p className="mt-1.5 text-sm text-muted-foreground">{a.body}</p>
            </div>
          ))}
        </div>
      </Section>

      <CtaBand />
    </>
  );
}

const ISOLATION_FLOW: { label: string; detail: string }[] = [
  { label: "Request arrives", detail: "A tenant-first login or an API call carries the organization it belongs to." },
  { label: "Org bound to the connection", detail: "The resolved org id is set on the database session — nothing runs unscoped." },
  { label: "RLS policy filters", detail: "Postgres applies the policy to every table; rows without a matching org are invisible." },
  { label: "Only that tenant's rows return", detail: "A missing binding matches nothing, so leakage fails closed rather than open." },
];

const CONTROLS: { control: string; threat: string; how: string }[] = [
  { control: "Row-level isolation", threat: "Cross-tenant data access", how: "Postgres RLS binds every query to one org; with no org bound the policy matches zero rows — fail-closed." },
  { control: "Encryption at rest", threat: "Secret and token leakage", how: "Client secrets, tokens, and factor seeds are encrypted; the session store is treated as auth-critical." },
  { control: "Revocation propagation", threat: "Zombie sessions after offboarding", how: "Ending a session fans out to OIDC back-channel logout and SAML single logout downstream." },
  { control: "Per-tenant signing keys", threat: "Token replay across tenants", how: "Each org signs OIDC tokens and SAML assertions under its own issuer and key." },
  { control: "Lockout and rate limiting", threat: "Brute-force and credential stuffing", how: "Account lockout and per-IP throttling on every authentication endpoint." },
  { control: "CSRF and session hardening", threat: "Session hijacking and forgery", how: "CSRF tokens on state-changing routes, hardened cookies, and an absolute session lifetime." },
  { control: "XXE-hardened SAML", threat: "XML external-entity attacks", how: "SAML parsing disables DTDs and external entities entirely." },
  { control: "SSRF-guarded fetches", threat: "Server-side request forgery", how: "Outbound JWKS, metadata, and back-channel calls are validated before they leave the host." },
  { control: "Non-revealing errors", threat: "Account and tenant enumeration", how: "Auth failures return uniform responses — no hint whether an account or org exists." },
  { control: "Step-up re-auth", threat: "Privilege escalation on sensitive actions", how: "Privileged operations demand a fresh factor within a bounded freshness window (RFC 9470)." },
];

const ASSURANCES: { icon: typeof ShieldCheck; title: string; body: string }[] = [
  { icon: ShieldCheck, title: "Re-checked every request", body: "No path assumes an upstream filter already verified authority or freshness — it's re-established each time." },
  { icon: Lock, title: "Bounded delegated admin", body: "Administration is scoped by level, and platform permissions are un-grantable to tenant admins." },
  { icon: ArrowDown, title: "Auditable by default", body: "Security-relevant decisions are logged without leaking secrets, so a breach is detectable after the fact." },
];

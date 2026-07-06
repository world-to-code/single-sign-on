import type { ComponentType } from "react";
import {
  Building2, Check, CheckCircle2, Fingerprint, KeyRound, Lock, Minus, RefreshCw,
  ScrollText, ShieldCheck, UsersRound,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Section, CtaBand, HeroBackdrop } from "@/components/marketing/MarketingLayout";
import { cn } from "@/lib/utils";

/** Product page ("/product"): a capability-by-capability deep dive as alternating feature rows, each
 *  paired with its own purpose-built tokenized mock — no repeated card grid. */
export default function Product() {
  return (
    <>
      <section className="relative overflow-hidden border-b">
        <HeroBackdrop />
        <div className="relative mx-auto max-w-3xl px-4 py-20 text-center sm:px-6 sm:py-24">
          <Badge variant="muted" className="mb-5">Capabilities</Badge>
          <h1 className="text-balance text-4xl font-bold tracking-tight sm:text-5xl">
            Everything an identity provider should do
          </h1>
          <p className="mx-auto mt-5 max-w-xl text-pretty text-lg text-muted-foreground">
            Standards-based, secure by default, and built to serve many organizations from a single origin —
            every capability works the moment your workspace is created.
          </p>
        </div>
      </section>

      <Section>
        <div className="space-y-20 lg:space-y-28">
          {CAPABILITIES.map((c, i) => (
            <div key={c.title} className="grid items-center gap-8 lg:grid-cols-2 lg:gap-14">
              <div className={cn(i % 2 === 1 && "lg:order-2")}>
                <div className="mb-4 flex size-11 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                  <c.icon className="size-5" />
                </div>
                <h2 className="text-2xl font-semibold tracking-tight">{c.title}</h2>
                <p className="mt-3 text-muted-foreground">{c.body}</p>
                <ul className="mt-5 space-y-2.5">
                  {c.points.map((p) => (
                    <li key={p} className="flex gap-2.5 text-sm">
                      <Check className="mt-0.5 size-4 shrink-0 text-primary" /> <span>{p}</span>
                    </li>
                  ))}
                </ul>
              </div>
              <div className={cn("rounded-xl border bg-muted/30 p-6", i % 2 === 1 && "lg:order-1")}>
                <c.Mock />
              </div>
            </div>
          ))}
        </div>
      </Section>

      <Section tone="muted">
        <div className="mb-8 max-w-2xl">
          <Badge variant="muted" className="mb-3">At a glance</Badge>
          <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">
            The spec sheet, in one line each
          </h2>
        </div>
        <dl className="grid overflow-hidden rounded-xl border bg-card sm:grid-cols-2 lg:grid-cols-4 [&>div]:border-b [&>div:last-child]:border-b-0 sm:[&>div]:border-b-0 sm:[&>div]:border-r sm:[&>div:nth-child(2n)]:border-r-0 lg:[&>div]:border-r lg:[&>div:last-child]:border-r-0">
          {SPECS.map((s) => (
            <div key={s.label} className="p-6">
              <dt className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">{s.label}</dt>
              <dd className="mt-1.5 font-medium">{s.value}</dd>
            </div>
          ))}
        </dl>
      </Section>

      <CtaBand />
    </>
  );
}

/* ---------- Per-capability mocks (each visually distinct) ---------- */

function SsoMock() {
  return (
    <div className="space-y-3">
      {[
        { badge: "OIDC", name: "OpenID Connect", ep: "/oauth2/authorize" },
        { badge: "SAML", name: "SAML 2.0", ep: "/saml2/authenticate" },
      ].map((p) => (
        <div key={p.badge} className="flex items-center justify-between rounded-lg border bg-card px-4 py-3">
          <div className="flex items-center gap-3">
            <span className="flex size-9 items-center justify-center rounded-md bg-accent text-xs font-semibold text-accent-foreground">{p.badge}</span>
            <div>
              <div className="text-sm font-medium">{p.name}</div>
              <div className="font-mono text-xs text-muted-foreground">{p.ep}</div>
            </div>
          </div>
          <CheckCircle2 className="size-4 text-success" />
        </div>
      ))}
      <div className="flex items-center gap-2 rounded-lg border border-dashed px-4 py-2.5 text-xs text-muted-foreground">
        <KeyRound className="size-3.5 text-primary" /> One session · signed with the tenant's own key
      </div>
    </div>
  );
}

function MfaMock() {
  return (
    <div className="space-y-3">
      {[
        { icon: Fingerprint, name: "Passkey", meta: "WebAuthn · platform authenticator", state: "Verified" },
        { icon: KeyRound, name: "Authenticator app", meta: "TOTP · replay-protected", state: "Enabled" },
      ].map((f) => (
        <div key={f.name} className="flex items-center justify-between rounded-lg border bg-card px-4 py-3">
          <div className="flex items-center gap-3">
            <f.icon className="size-5 text-primary" />
            <div>
              <div className="text-sm font-medium">{f.name}</div>
              <div className="text-xs text-muted-foreground">{f.meta}</div>
            </div>
          </div>
          <Badge variant="success">{f.state}</Badge>
        </div>
      ))}
      <div className="flex items-center gap-2 rounded-lg bg-primary/10 px-4 py-2.5 text-xs font-medium text-primary">
        <ShieldCheck className="size-3.5" /> Step-up required — fresh factor within 5 min
      </div>
    </div>
  );
}

function ScimMock() {
  const rows = [
    { user: "jordan@acme.com", state: "Provisioned", tone: "success" as const },
    { user: "priya@acme.com", state: "Provisioned", tone: "success" as const },
    { user: "sam@acme.com", state: "Deprovisioned", tone: "muted" as const },
  ];
  return (
    <div className="rounded-lg border bg-card">
      <div className="flex items-center justify-between border-b px-4 py-2.5">
        <span className="flex items-center gap-2 text-sm font-medium"><RefreshCw className="size-4 text-primary" /> Directory sync</span>
        <span className="inline-flex items-center gap-1.5 text-xs text-success"><span className="size-1.5 rounded-full bg-success" /> Live</span>
      </div>
      <ul className="divide-y">
        {rows.map((r) => (
          <li key={r.user} className="flex items-center justify-between px-4 py-2.5">
            <span className="font-mono text-xs text-muted-foreground">{r.user}</span>
            <Badge variant={r.tone}>{r.state}</Badge>
          </li>
        ))}
      </ul>
    </div>
  );
}

function TenantMock() {
  const orgs = ["acme.mysso.com", "globex.mysso.com", "initech.mysso.com"];
  return (
    <div className="space-y-2.5">
      {orgs.map((o) => (
        <div key={o} className="flex items-center justify-between rounded-lg border bg-card px-4 py-3">
          <span className="flex items-center gap-2.5">
            <Building2 className="size-4 text-primary" />
            <span className="font-mono text-xs">{o}</span>
          </span>
          <span className="inline-flex items-center gap-1.5 rounded-full bg-muted px-2 py-0.5 text-[11px] font-medium text-muted-foreground">
            <Lock className="size-3" /> RLS isolated
          </span>
        </div>
      ))}
      <p className="pt-1 text-center text-xs text-muted-foreground">Row-level security · fail-closed by default</p>
    </div>
  );
}

function RbacMock() {
  const caps = ["Read", "Write", "Admin"];
  const roles: { role: string; grants: boolean[] }[] = [
    { role: "Org admin", grants: [true, true, true] },
    { role: "Member", grants: [true, true, false] },
    { role: "Auditor", grants: [true, false, false] },
  ];
  return (
    <div className="overflow-hidden rounded-lg border bg-card">
      <div className="grid grid-cols-[1.4fr_repeat(3,1fr)] border-b bg-muted/40 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
        <span className="px-3 py-2">Role</span>
        {caps.map((c) => <span key={c} className="px-3 py-2 text-center">{c}</span>)}
      </div>
      {roles.map((r) => (
        <div key={r.role} className="grid grid-cols-[1.4fr_repeat(3,1fr)] items-center border-b last:border-b-0 text-sm">
          <span className="px-3 py-2.5 font-medium">{r.role}</span>
          {r.grants.map((g, i) => (
            <span key={i} className="flex justify-center px-3 py-2.5">
              {g ? <Check className="size-4 text-primary" /> : <Minus className="size-4 text-muted-foreground/40" />}
            </span>
          ))}
        </div>
      ))}
    </div>
  );
}

function AuditMock() {
  const bars = ["h-[40%]", "h-[58%]", "h-[48%]", "h-[70%]", "h-[62%]", "h-[85%]", "h-[74%]"];
  const events = [
    { t: "09:14", e: "user.login · passkey" },
    { t: "09:12", e: "client.create · admin" },
    { t: "09:07", e: "session.revoke · SLO" },
  ];
  return (
    <div className="space-y-4 rounded-lg border bg-card p-4">
      <div className="flex h-16 items-end gap-1.5">
        {bars.map((h, i) => <div key={i} className={`flex-1 rounded-t bg-primary/70 ${h}`} />)}
      </div>
      <ul className="space-y-1.5">
        {events.map((ev) => (
          <li key={ev.t} className="flex items-center gap-3 font-mono text-xs">
            <span className="text-muted-foreground">{ev.t}</span>
            <span className="truncate">{ev.e}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

const CAPABILITIES: { icon: LucideIcon; title: string; body: string; points: string[]; Mock: ComponentType }[] = [
  {
    icon: KeyRound, title: "Single sign-on", Mock: SsoMock,
    body: "One login for every connected application. Register an OIDC client or a SAML relying party and your users authenticate once, then move between apps without re-entering credentials.",
    points: [
      "OpenID Connect (authorization code + PKCE) and SAML 2.0",
      "A branded consent screen you control",
      "Per-application sign-on policies and step-up",
      "Per-tenant signing keys and issuer",
    ],
  },
  {
    icon: Fingerprint, title: "Multi-factor and passkeys", Mock: MfaMock,
    body: "Raise assurance without friction. WebAuthn passkeys, TOTP authenticator apps, and step-up re-authentication for sensitive actions — with factor ordering enforced on the server.",
    points: [
      "WebAuthn / FIDO2 passkeys with challenge and origin validation",
      "TOTP with replay protection",
      "Step-up (RFC 9470) on privileged operations",
      "Adaptive rules by network zone",
    ],
  },
  {
    icon: RefreshCw, title: "Automated provisioning", Mock: ScimMock,
    body: "Keep every app in sync with your source of truth. SCIM 2.0 pushes users and groups automatically, so a new hire has access on day one and a departure is deprovisioned the moment it happens.",
    points: [
      "SCIM 2.0 Users and Groups endpoints",
      "Per-tenant, scoped provisioning tokens",
      "Create, update, disable, and delete flows",
      "Group-driven role assignment",
    ],
  },
  {
    icon: Building2, title: "Multi-tenant organizations", Mock: TenantMock,
    body: "Serve many customers from one deploy. Every organization gets its own users, policies, applications, and signing keys, isolated from every other tenant at the database.",
    points: [
      "Tenant-first login at a per-tenant subdomain",
      "Row-level security isolation per organization",
      "Delegated administration by scope",
      "Platform super-admin with drill-in",
    ],
  },
  {
    icon: UsersRound, title: "Roles and access policies", Mock: RbacMock,
    body: "Grant exactly the access each person needs. Fine-grained RBAC, per-app assignment, authentication policies, and network zones let you shape who gets in, from where, and how.",
    points: [
      "Role-based access control with implied permissions",
      "Per-application assignment (who can use what)",
      "Authentication policies and network zones",
      "Self-protection and last-admin safeguards",
    ],
  },
  {
    icon: ScrollText, title: "Audit and analytics", Mock: AuditMock,
    body: "See everything that matters. Every security-relevant action is recorded, with per-organization trends and platform-wide dashboards so you can answer an auditor and spot anomalies early.",
    points: [
      "Immutable audit trail of admin and auth events",
      "Per-organization sign-in trends",
      "Platform-wide analytics for operators",
      "Non-revealing errors — no account enumeration",
    ],
  },
];

const SPECS: { label: string; value: string }[] = [
  { label: "Protocols", value: "OIDC · SAML 2.0" },
  { label: "Factors", value: "Passkeys · TOTP · Step-up" },
  { label: "Provisioning", value: "SCIM 2.0 Users & Groups" },
  { label: "Isolation", value: "Postgres RLS · per-tenant keys" },
];

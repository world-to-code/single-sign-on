import { useState } from "react";
import type { FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Loader2 } from "lucide-react";
import { ApiError } from "../api";
import { goHome, organization } from "../auth";
import { forgetOrg, recentOrgs, rememberOrg } from "../lib/loginMemory";
import { tenantHost } from "../lib/tenantHost";
import { OrgCard } from "../components/auth/OrgCard";
import AuthLayout from "../components/layout/AuthLayout";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";

/**
 * Tenant-first entry screen, shown only on the bare platform host (a tenant subdomain auto-resolves its org
 * and skips straight to sign-in). The user identifies their organization; we then send them to that org's OWN
 * subdomain to sign in, because the session is host-bound — an organization is reached only through its
 * subdomain. Unknown/suspended organizations are rejected uniformly (no enumeration). A returning visitor is
 * offered the organizations this browser has signed in through, for a one-tap continue — a person may hold
 * accounts in several (a secondee is a separate user in the host company's tenant). That list is local to the
 * browser: the server is never asked which organizations an identity belongs to.
 */
export default function OrgSelect() {
  const { t } = useTranslation("auth");
  const [orgs, setOrgs] = useState(recentOrgs);
  // Start on the remembered list when we have one; otherwise ask for the slug.
  const [manual, setManual] = useState(orgs.length === 0);
  const [slug, setSlug] = useState("");
  const [error, setError] = useState<string | null>(null);
  // The organization currently being resolved — drives the spinner on the card that was tapped.
  const [pending, setPending] = useState<string | null>(null);
  const busy = pending !== null;

  async function resolve(value: string) {
    const trimmed = value.trim();
    if (!trimmed) return;
    setError(null);
    setPending(trimmed);
    try {
      // Validate the organization exists and is ACTIVE (404 → inline error, no redirect), then continue the
      // sign-in on the tenant's OWN subdomain, where the host-bound session belongs.
      await organization(trimmed);
      rememberOrg(trimmed);
      // Continue on the tenant's OWN subdomain, where the host-bound session belongs. tenantHost is
      // idempotent, so a stray render on an already-tenant host never nests the label ({slug}.{slug}.host).
      const { protocol, host } = window.location; // host includes the port (e.g. localhost:5173)
      window.location.assign(`${protocol}//${tenantHost(host, trimmed)}/`);
    } catch (e) {
      if (e instanceof ApiError) {
        setError(e.status === 404 ? t("orgNotFound") : t("orgContinueFailed"));
      }
      setManual(true); // let the user correct the slug
      setPending(null);
    }
  }

  /** Drops one organization from this browser's memory. The render guard below falls back to the slug input
   *  once none are left, so this does not also flip `manual` — two sources of truth would drift. */
  function forget(value: string) {
    forgetOrg(value);
    setOrgs(recentOrgs());
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    void resolve(slug);
  }

  return (
    <AuthLayout title={t("loginTitle")} description={t("orgSelectDescription")}
                onBack={goHome} backLabel={t("home")}>
      {error && (
        <Alert variant="destructive" className="mb-4">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      {!manual && orgs.length > 0 ? (
        <div className="space-y-3">
          <p className="text-xs font-medium text-muted-foreground">{t("orgRecentTitle")}</p>
          <div className="space-y-2">
            {orgs.map((remembered) => (
              <OrgCard
                key={remembered}
                slug={remembered}
                disabled={busy}
                pending={pending === remembered}
                onSelect={() => void resolve(remembered)}
                onForget={() => forget(remembered)}
              />
            ))}
          </div>
          <Button type="button" variant="ghost" className="w-full" disabled={busy}
                  onClick={() => { setSlug(""); setManual(true); }}>
            {t("useDifferentOrg")}
          </Button>
        </div>
      ) : (
        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="org">{t("orgLabel")}</Label>
            <Input id="org" type="text" value={slug} autoFocus required
                   autoCapitalize="none" autoCorrect="off" spellCheck={false}
                   placeholder={t("orgPlaceholder")} onChange={(e) => setSlug(e.target.value)} />
          </div>
          <Button type="submit" className="w-full" disabled={busy}>
            {busy && <Loader2 className="animate-spin" />}
            {t("continue")}
          </Button>
        </form>
      )}

      <p className="mt-3 text-xs text-muted-foreground">
        {t("orgSelectHint")}
      </p>
    </AuthLayout>
  );
}

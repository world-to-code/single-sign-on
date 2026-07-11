import { useState } from "react";
import type { FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { ArrowRight, Building2, Loader2 } from "lucide-react";
import { ApiError } from "../api";
import { goHome, organization } from "../auth";
import { lastOrg, rememberOrg } from "../lib/loginMemory";
import AuthLayout from "../components/layout/AuthLayout";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";

/** The origin of the tenant's OWN subdomain ({slug}.{platform-host}), where its sign-in must complete —
 *  the session is host-bound, so an organization is reached ONLY through its subdomain, never the bare host. */
function tenantOrigin(slug: string): string {
  const { protocol, host } = window.location; // host includes the port (e.g. localhost:9000)
  return `${protocol}//${slug}.${host}`;
}

/**
 * Tenant-first entry screen, shown only on the bare platform host (a tenant subdomain auto-resolves its org
 * and skips straight to sign-in). The user identifies their organization; we then send them to that org's OWN
 * subdomain to sign in, because the session is host-bound — an organization is reached only through its
 * subdomain. Unknown/suspended organizations are rejected uniformly (no enumeration). A returning visitor is
 * offered their last-used organization for a one-tap continue.
 */
export default function OrgSelect() {
  const { t } = useTranslation("auth");
  const remembered = lastOrg();
  // Start on the one-tap card when we remember an org; otherwise ask for the slug.
  const [manual, setManual] = useState(!remembered);
  const [slug, setSlug] = useState(remembered ?? "");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function resolve(value: string) {
    const trimmed = value.trim();
    if (!trimmed) return;
    setError(null);
    setBusy(true);
    try {
      // Validate the organization exists and is ACTIVE (404 → inline error, no redirect), then continue the
      // sign-in on the tenant's OWN subdomain, where the host-bound session belongs.
      await organization(trimmed);
      rememberOrg(trimmed);
      window.location.assign(tenantOrigin(trimmed) + "/");
    } catch (e) {
      if (e instanceof ApiError) {
        setError(e.status === 404 ? t("orgNotFound") : t("orgContinueFailed"));
      }
      setManual(true); // let the user correct the slug
      setBusy(false);
    }
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

      {!manual && remembered ? (
        <div className="space-y-3">
          <button
            type="button"
            onClick={() => void resolve(remembered)}
            disabled={busy}
            className="flex w-full items-center gap-3 rounded-lg border bg-card p-3 text-left transition-colors hover:bg-accent disabled:opacity-60"
          >
            <span className="flex size-9 shrink-0 items-center justify-center rounded-md bg-ink text-bg">
              <Building2 className="size-4" />
            </span>
            <span className="min-w-0 flex-1">
              <span className="block text-sm font-medium">{remembered}</span>
              <span className="block text-xs text-muted-foreground">{t("orgContinueToThis")}</span>
            </span>
            {busy
              ? <Loader2 className="size-4 shrink-0 animate-spin" />
              : <ArrowRight className="size-4 shrink-0 text-muted-foreground" />}
          </button>
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

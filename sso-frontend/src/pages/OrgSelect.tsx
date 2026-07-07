import { useState } from "react";
import type { FormEvent } from "react";
import { ArrowRight, Building2, Loader2 } from "lucide-react";
import { ApiError } from "../api";
import { organization } from "../auth";
import type { SessionView } from "../auth";
import { lastOrg, rememberOrg } from "../lib/loginMemory";
import AuthLayout from "../components/layout/AuthLayout";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";

/**
 * Tenant-first entry screen. The user identifies their organization (tenant) before their account, so
 * the sign-in that follows is scoped to that org. Unknown/suspended organizations are rejected uniformly
 * (no enumeration of which tenants exist). A returning visitor is offered their last-used organization for
 * a one-tap continue, with a clear path to a different one.
 */
export default function OrgSelect({ onDone }: { onDone: (s: SessionView) => void }) {
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
      // The organization IS the tenant: a member signs in with their organization's slug.
      const session = await organization(trimmed);
      rememberOrg(trimmed);
      onDone(session);
    } catch (e) {
      if (e instanceof ApiError) {
        setError(e.status === 404
          ? "We couldn't find that organization. Check the identifier with your administrator."
          : "Could not continue. Please try again.");
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
    <AuthLayout title="Sign in" description="Enter your organization to continue.">
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
            <span className="flex size-9 shrink-0 items-center justify-center rounded-md bg-primary text-primary-foreground">
              <Building2 className="size-4" />
            </span>
            <span className="min-w-0 flex-1">
              <span className="block text-sm font-medium">{remembered}</span>
              <span className="block text-xs text-muted-foreground">Continue to this organization</span>
            </span>
            {busy
              ? <Loader2 className="size-4 shrink-0 animate-spin" />
              : <ArrowRight className="size-4 shrink-0 text-muted-foreground" />}
          </button>
          <Button type="button" variant="ghost" className="w-full" disabled={busy}
                  onClick={() => { setSlug(""); setManual(true); }}>
            Use a different organization
          </Button>
        </div>
      ) : (
        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="org">Organization</Label>
            <Input id="org" type="text" value={slug} autoFocus required
                   autoCapitalize="none" autoCorrect="off" spellCheck={false}
                   placeholder="your-org" onChange={(e) => setSlug(e.target.value)} />
          </div>
          <Button type="submit" className="w-full" disabled={busy}>
            {busy && <Loader2 className="animate-spin" />}
            Continue
          </Button>
        </form>
      )}

      <p className="mt-3 text-xs text-muted-foreground">
        Don't know your organization identifier? Contact your administrator.
      </p>
    </AuthLayout>
  );
}

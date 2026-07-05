import { useState } from "react";
import type { FormEvent } from "react";
import { Loader2 } from "lucide-react";
import { ApiError } from "../api";
import { organization } from "../auth";
import type { SessionView } from "../auth";
import AuthLayout from "../components/layout/AuthLayout";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";

/**
 * Tenant-first entry screen. The user identifies their organization (tenant) before their account, so
 * the sign-in that follows is scoped to that org. Unknown/suspended organizations are rejected uniformly
 * (no enumeration of which tenants exist).
 */
export default function OrgSelect({ onDone }: { onDone: (s: SessionView) => void }) {
  const [slug, setSlug] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      onDone(await organization(slug.trim()));
    } catch (e) {
      if (e instanceof ApiError) {
        setError(e.status === 404
          ? "We couldn't find that organization. Check the identifier with your administrator."
          : "Could not continue. Please try again.");
      }
      setBusy(false);
    }
  }

  return (
    <AuthLayout
      title="Sign in"
      description="Enter your organization to continue to its sign-in."
    >
      {error && (
        <Alert variant="destructive" className="mb-4">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <form onSubmit={submit} className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="org">Organization</Label>
          <Input id="org" type="text" value={slug} autoFocus required
                 placeholder="your-organization" onChange={(e) => setSlug(e.target.value)} />
        </div>
        <Button type="submit" className="w-full" disabled={busy}>
          {busy && <Loader2 className="animate-spin" />}
          Continue
        </Button>
      </form>

      <p className="mt-3 text-xs text-muted-foreground">
        Don't know your organization identifier? Contact your administrator.
      </p>
    </AuthLayout>
  );
}

import { useState } from "react";
import type { FormEvent } from "react";
import { CheckCircle2, Loader2, Mail } from "lucide-react";
import { errorMessage } from "@/api";
import { applyForWorkspace } from "@/onboarding";
import AuthLayout from "@/components/layout/AuthLayout";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";

const SIZES = ["1-10", "11-50", "51-200", "201-1000", "1000+"];
const blank = {
  slug: "", companyName: "", companySize: "", companyCountry: "", companyIndustry: "",
  adminName: "", adminEmail: "",
};

/**
 * Public self-service signup at "/signup": a prospective customer requests a workspace. NOTHING is
 * provisioned here — a one-time verification link is emailed, and the workspace + admin are created only when
 * that link is redeemed at /activate (proving the applicant controls the email). So the success screen just
 * tells them to check their email. A taken subdomain returns 409 and is surfaced inline so they can pick another.
 */
export default function Signup() {
  const [form, setForm] = useState({ ...blank });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<{ slug: string; email: string } | null>(null);

  const set = (patch: Partial<typeof form>) => setForm((f) => ({ ...f, ...patch }));

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!form.slug.trim() || !form.companyName.trim() || !form.adminName.trim() || !form.adminEmail.trim()) {
      setError("A subdomain, company name, and the admin's name and email are required.");
      return;
    }
    setError(null);
    setBusy(true);
    try {
      const job = await applyForWorkspace({
        slug: form.slug.trim(),
        companyName: form.companyName.trim(),
        companySize: form.companySize || undefined,
        companyCountry: form.companyCountry.trim() || undefined,
        companyIndustry: form.companyIndustry.trim() || undefined,
        adminName: form.adminName.trim(),
        adminEmail: form.adminEmail.trim(),
      });
      setDone({ slug: job.slug, email: form.adminEmail.trim() });
    } catch (e) {
      setError(errorMessage(e));
      setBusy(false);
    }
  }

  if (done) {
    return (
      <AuthLayout title="Check your email"
                  footer={<a href="/login" className="font-medium text-primary hover:underline">Go to sign in</a>}>
        <div className="space-y-4 text-center">
          <Mail className="mx-auto size-10 text-primary" />
          <p className="text-sm text-muted-foreground">
            We've emailed <span className="font-medium text-foreground">{done.email}</span> a link to verify
            your address and finish creating{" "}
            <span className="font-medium text-foreground">{done.slug}</span>. Open it to set your admin
            password — your workspace is created once you do.
          </p>
          <div className="flex items-center justify-center gap-1.5 text-xs text-muted-foreground">
            <CheckCircle2 className="size-3.5 text-primary" /> No approval needed — just confirm your email.
          </div>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout
      title="Get started"
      description="Create your organization's workspace. We'll email your admin a link to verify and finish setup."
      onBack={() => window.location.assign("/")}
      backLabel="Home"
      footer={<>Already have a workspace? <a href="/login" className="font-medium text-primary hover:underline">Sign in</a></>}
    >
      {error && <Alert variant="destructive" className="mb-4"><AlertDescription>{error}</AlertDescription></Alert>}

      <form onSubmit={submit} className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="slug">Workspace subdomain</Label>
          <Input id="slug" value={form.slug} required autoFocus
                 autoCapitalize="none" autoCorrect="off" spellCheck={false} placeholder="acme"
                 onChange={(e) => set({ slug: e.target.value.toLowerCase() })} />
          <p className="text-xs text-muted-foreground">Lowercase letters, digits, and hyphens — your stable workspace identifier.</p>
        </div>
        <div className="space-y-2">
          <Label htmlFor="company">Company name</Label>
          <Input id="company" value={form.companyName} required placeholder="Acme, Inc."
                 onChange={(e) => set({ companyName: e.target.value })} />
        </div>
        <div className="space-y-2">
          <Label htmlFor="size">Company size <span className="text-muted-foreground">(optional)</span></Label>
          <Select id="size" value={form.companySize} onChange={(e) => set({ companySize: e.target.value })}>
            <option value="">Prefer not to say</option>
            {SIZES.map((s) => <option key={s} value={s}>{s} employees</option>)}
          </Select>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div className="space-y-2">
            <Label htmlFor="country">Country <span className="text-muted-foreground">(optional)</span></Label>
            <Input id="country" value={form.companyCountry} onChange={(e) => set({ companyCountry: e.target.value })} />
          </div>
          <div className="space-y-2">
            <Label htmlFor="industry">Industry <span className="text-muted-foreground">(optional)</span></Label>
            <Input id="industry" value={form.companyIndustry} onChange={(e) => set({ companyIndustry: e.target.value })} />
          </div>
        </div>

        <div className="space-y-2 border-t pt-4">
          <Label htmlFor="adminName">Administrator name</Label>
          <Input id="adminName" value={form.adminName} required placeholder="Ada Lovelace"
                 onChange={(e) => set({ adminName: e.target.value })} />
        </div>
        <div className="space-y-2">
          <Label htmlFor="adminEmail">Work email</Label>
          <Input id="adminEmail" type="email" value={form.adminEmail} required placeholder="ada@acme.com"
                 onChange={(e) => set({ adminEmail: e.target.value })} />
          <p className="text-xs text-muted-foreground">The admin gets an email to set their password and manage the workspace.</p>
        </div>

        <Button type="submit" className="w-full" disabled={busy}>
          {busy && <Loader2 className="animate-spin" />}
          Create workspace
        </Button>
      </form>
    </AuthLayout>
  );
}

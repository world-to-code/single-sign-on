import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { AlertTriangle, CheckCircle2, Loader2, XCircle } from "lucide-react";
import { errorMessage } from "@/api";
import { onboardingStatus, startOnboarding } from "@/onboarding";
import type { OnboardingView } from "@/onboarding";
import { EditorPage } from "@/components/EditorPage";
import { SettingsSection } from "@/components/SettingsSection";
import { Field } from "@/components/form/fields";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";

const SIZES = ["1-10", "11-50", "51-200", "201-1000", "1000+"];
const blank = {
  slug: "", companyName: "", companySize: "", companyCountry: "", companyIndustry: "", companyPhone: "",
  adminName: "", adminEmail: "",
};

const settled = (s: OnboardingView["status"]) => s === "INVITED" || s === "INVITE_FAILED" || s === "FAILED";

/**
 * Okta/Ping-style tenant onboarding (route `admin/onboarding`): collect the company profile + first admin,
 * submit, then poll the async job while the workspace is provisioned and the admin is invited by email.
 */
export default function Onboarding() {
  const navigate = useNavigate();
  const [form, setForm] = useState({ ...blank });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [job, setJob] = useState<OnboardingView | null>(null);

  const set = (patch: Partial<typeof form>) => setForm((f) => ({ ...f, ...patch }));

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!form.slug.trim() || !form.companyName.trim() || !form.adminName.trim() || !form.adminEmail.trim()) {
      setError("Subdomain, company name, and the admin's name and email are required.");
      return;
    }
    setError(null);
    setBusy(true);
    try {
      setJob(await startOnboarding({
        slug: form.slug.trim(),
        companyName: form.companyName.trim(),
        companySize: form.companySize || undefined,
        companyCountry: form.companyCountry.trim() || undefined,
        companyIndustry: form.companyIndustry.trim() || undefined,
        companyPhone: form.companyPhone.trim() || undefined,
        adminName: form.adminName.trim(),
        adminEmail: form.adminEmail.trim(),
      }));
    } catch (e) {
      setError(errorMessage(e));
      setBusy(false);
    }
  }

  // Poll the async job until it settles.
  useEffect(() => {
    if (!job || settled(job.status)) return;
    const timer = setInterval(() => {
      onboardingStatus(job.id).then(setJob).catch(() => undefined);
    }, 2000);
    return () => clearInterval(timer);
  }, [job]);

  if (job) {
    const restart = () => { setJob(null); setForm({ ...blank }); setBusy(false); };
    return <StatusView job={job} onDone={() => navigate("/admin/organizations")} onRestart={restart} />;
  }

  return (
    <EditorPage
      backTo="/admin/organizations" backLabel="Organizations" crumb="Onboard tenant"
      title="Onboard a tenant" description="Create a customer workspace and invite its first administrator by email."
      error={error} formId="onboarding-form" onSubmit={submit} busy={busy} submitLabel="Create workspace"
      onCancel={() => navigate("/admin/organizations")}
    >
      <SettingsSection title="Company" description="How the tenant is identified and shown across the platform.">
        <Field label="Subdomain" hint="Lowercase letters, digits and hyphens — the tenant's stable identifier.">
          <Input value={form.slug} autoCapitalize="none" autoCorrect="off" spellCheck={false}
                 placeholder="acme" onChange={(e) => set({ slug: e.target.value })} required />
        </Field>
        <Field label="Company name">
          <Input value={form.companyName} onChange={(e) => set({ companyName: e.target.value })} required />
        </Field>
        <Field label="Company size" hint="Optional.">
          <Select value={form.companySize} onChange={(e) => set({ companySize: e.target.value })}>
            <option value="">Prefer not to say</option>
            {SIZES.map((s) => <option key={s} value={s}>{s} employees</option>)}
          </Select>
        </Field>
        <Field label="Country" hint="Optional.">
          <Input value={form.companyCountry} onChange={(e) => set({ companyCountry: e.target.value })} />
        </Field>
        <Field label="Industry" hint="Optional.">
          <Input value={form.companyIndustry} onChange={(e) => set({ companyIndustry: e.target.value })} />
        </Field>
        <Field label="Phone" hint="Optional.">
          <Input value={form.companyPhone} onChange={(e) => set({ companyPhone: e.target.value })} />
        </Field>
      </SettingsSection>

      <SettingsSection title="Administrator"
                       description="Gets an email invitation to set their password and manage the tenant.">
        <Field label="Full name">
          <Input value={form.adminName} onChange={(e) => set({ adminName: e.target.value })} required />
        </Field>
        <Field label="Work email">
          <Input type="email" value={form.adminEmail} onChange={(e) => set({ adminEmail: e.target.value })} required />
        </Field>
      </SettingsSection>
    </EditorPage>
  );
}

function StatusView({ job, onDone, onRestart }: {
  job: OnboardingView; onDone: () => void; onRestart: () => void;
}) {
  const provisioning = job.status === "PENDING" || job.status === "PROVISIONING";
  return (
    <div className="mx-auto max-w-xl space-y-4 py-16 text-center">
      {provisioning && (
        <>
          <Loader2 className="mx-auto size-10 animate-spin text-primary" />
          <h1 className="text-lg font-semibold">Setting up {job.slug}…</h1>
          <p className="text-sm text-muted-foreground">
            Your workspace is being provisioned. The administrator will receive an invitation email shortly
            — this can take a few minutes.
          </p>
        </>
      )}
      {job.status === "INVITED" && (
        <>
          <CheckCircle2 className="mx-auto size-10 text-primary" />
          <h1 className="text-lg font-semibold">{job.slug} is ready</h1>
          <p className="text-sm text-muted-foreground">
            The administrator has been emailed an invitation to set their password.
          </p>
          <div className="flex justify-center gap-2">
            <Button onClick={onDone}>Back to organizations</Button>
            <Button variant="outline" onClick={onRestart}>Onboard another</Button>
          </div>
        </>
      )}
      {job.status === "INVITE_FAILED" && (
        <>
          <AlertTriangle className="mx-auto size-10 text-amber-500" />
          <h1 className="text-lg font-semibold">{job.slug} was created</h1>
          <p className="text-sm text-muted-foreground">
            The tenant and its administrator were provisioned, but the invitation email could not be sent.
            Re-invite the administrator to send a fresh link.
          </p>
          <Button onClick={onDone}>Back to organizations</Button>
        </>
      )}
      {job.status === "FAILED" && (
        <>
          <XCircle className="mx-auto size-10 text-destructive" />
          <h1 className="text-lg font-semibold">Onboarding failed</h1>
          <p className="text-sm text-muted-foreground">{job.error ?? "Provisioning failed."} You can try again.</p>
          <Button variant="outline" onClick={onRestart}>Try again</Button>
        </>
      )}
    </div>
  );
}

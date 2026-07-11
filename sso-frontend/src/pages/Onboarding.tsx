import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { AlertTriangle, CheckCircle2, Loader2, XCircle } from "lucide-react";
import { errorMessage } from "@/api";
import { onboardingStatus, reinviteOnboarding, startOnboarding } from "@/onboarding";
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
  const { t } = useTranslation("console");
  const navigate = useNavigate();
  const [form, setForm] = useState({ ...blank });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [job, setJob] = useState<OnboardingView | null>(null);

  const set = (patch: Partial<typeof form>) => setForm((f) => ({ ...f, ...patch }));

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!form.slug.trim() || !form.companyName.trim() || !form.adminName.trim() || !form.adminEmail.trim()) {
      setError(t("onboardingRequired"));
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
    const reinvite = async () => {
      try {
        setJob(await reinviteOnboarding(job.id)); // → PROVISIONING; the poll picks up INVITED/INVITE_FAILED
      } catch (e) {
        window.alert(errorMessage(e)); // rare (e.g. the admin already activated); step-up handled by the client
      }
    };
    return <StatusView job={job} onDone={() => navigate("/admin/organizations")}
                       onRestart={restart} onReinvite={reinvite} />;
  }

  return (
    <EditorPage
      backTo="/admin/organizations" backLabel={t("onboardingBack")} crumb={t("onboardingCrumb")}
      title={t("onboardingTitle")} description={t("onboardingDescription")}
      error={error} formId="onboarding-form" onSubmit={submit} busy={busy} submitLabel={t("onboardingSubmit")}
      onCancel={() => navigate("/admin/organizations")}
    >
      <SettingsSection title={t("onboardingCompany")} description={t("onboardingCompanyDesc")}>
        <Field label={t("onboardingSubdomain")} hint={t("onboardingSubdomainHint")}>
          <Input value={form.slug} autoCapitalize="none" autoCorrect="off" spellCheck={false}
                 placeholder="acme" onChange={(e) => set({ slug: e.target.value })} required />
        </Field>
        <Field label={t("onboardingCompanyName")}>
          <Input value={form.companyName} onChange={(e) => set({ companyName: e.target.value })} required />
        </Field>
        <Field label={t("onboardingCompanySize")} hint={t("onboardingOptional")}>
          <Select value={form.companySize} onChange={(e) => set({ companySize: e.target.value })}>
            <option value="">{t("onboardingPreferNotToSay")}</option>
            {SIZES.map((s) => <option key={s} value={s}>{t("onboardingEmployees", { range: s })}</option>)}
          </Select>
        </Field>
        <Field label={t("onboardingCountry")} hint={t("onboardingOptional")}>
          <Input value={form.companyCountry} onChange={(e) => set({ companyCountry: e.target.value })} />
        </Field>
        <Field label={t("onboardingIndustry")} hint={t("onboardingOptional")}>
          <Input value={form.companyIndustry} onChange={(e) => set({ companyIndustry: e.target.value })} />
        </Field>
        <Field label={t("onboardingPhone")} hint={t("onboardingOptional")}>
          <Input value={form.companyPhone} onChange={(e) => set({ companyPhone: e.target.value })} />
        </Field>
      </SettingsSection>

      <SettingsSection title={t("onboardingAdministrator")}
                       description={t("onboardingAdministratorDesc")}>
        <Field label={t("onboardingFullName")}>
          <Input value={form.adminName} onChange={(e) => set({ adminName: e.target.value })} required />
        </Field>
        <Field label={t("onboardingWorkEmail")}>
          <Input type="email" value={form.adminEmail} onChange={(e) => set({ adminEmail: e.target.value })} required />
        </Field>
      </SettingsSection>
    </EditorPage>
  );
}

function StatusView({ job, onDone, onRestart, onReinvite }: {
  job: OnboardingView; onDone: () => void; onRestart: () => void; onReinvite: () => void;
}) {
  const { t } = useTranslation("console");
  const provisioning = job.status === "PENDING" || job.status === "PROVISIONING";
  return (
    <div className="mx-auto max-w-xl space-y-4 py-16 text-center">
      {provisioning && (
        <>
          <Loader2 className="mx-auto size-10 animate-spin text-primary" />
          <h1 className="text-lg font-semibold">{t("onboardingSettingUp", { slug: job.slug })}</h1>
          <p className="text-sm text-muted-foreground">
            {t("onboardingProvisioningBody")}
          </p>
        </>
      )}
      {job.status === "INVITED" && (
        <>
          <CheckCircle2 className="mx-auto size-10 text-primary" />
          <h1 className="text-lg font-semibold">{t("onboardingReady", { slug: job.slug })}</h1>
          <p className="text-sm text-muted-foreground">
            {t("onboardingReadyBody")}
          </p>
          <div className="flex justify-center gap-2">
            <Button onClick={onDone}>{t("onboardingBackToOrgs")}</Button>
            <Button variant="outline" onClick={onRestart}>{t("onboardingAnother")}</Button>
          </div>
        </>
      )}
      {job.status === "INVITE_FAILED" && (
        <>
          <AlertTriangle className="mx-auto size-10 text-amber-500" />
          <h1 className="text-lg font-semibold">{t("onboardingCreatedTitle", { slug: job.slug })}</h1>
          <p className="text-sm text-muted-foreground">
            {t("onboardingInviteFailedBody")}
          </p>
          <div className="flex justify-center gap-2">
            <Button onClick={onReinvite}>{t("onboardingResend")}</Button>
            <Button variant="outline" onClick={onDone}>{t("onboardingBackToOrgs")}</Button>
          </div>
        </>
      )}
      {job.status === "FAILED" && (
        <>
          <XCircle className="mx-auto size-10 text-destructive" />
          <h1 className="text-lg font-semibold">{t("onboardingFailedTitle")}</h1>
          <p className="text-sm text-muted-foreground">{t("onboardingFailedBody", { message: job.error ?? t("onboardingProvisioningFailed") })}</p>
          <Button variant="outline" onClick={onRestart}>{t("onboardingTryAgain")}</Button>
        </>
      )}
    </div>
  );
}

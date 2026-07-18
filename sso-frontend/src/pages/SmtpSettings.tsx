import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { RotateCcw, Save } from "lucide-react";
import { errorMessage } from "../api";
import { getSmtpSettings, updateSmtpSettings, deleteSmtpSettings, type SmtpSettings } from "../smtp";
import { PageHeader } from "@/components/PageHeader";
import { LoadingCard, ErrorCard } from "@/components/states";
import { useToast } from "@/components/ToastProvider";
import { useConfirm } from "@/components/ConfirmProvider";
import { Field } from "@/components/form/fields";
import { Card, CardContent, CardFooter } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { Button } from "@/components/ui/button";

interface FormState {
  host: string;
  port: string;
  username: string;
  password: string;
  fromAddress: string;
  starttls: boolean;
}

/** Empty form for a tier that has no own relay (it inherits the platform default). */
const BLANK: FormState = { host: "", port: "587", username: "", password: "", fromAddress: "", starttls: true };

function toForm(settings: SmtpSettings): FormState {
  if (!settings.configured) {
    return BLANK;
  }
  return {
    host: settings.host ?? "",
    port: String(settings.port ?? 587),
    username: settings.username ?? "",
    password: "", // write-only — never returned; blank keeps the stored secret
    fromAddress: settings.fromAddress ?? "",
    starttls: settings.starttls,
  };
}

/**
 * Per-tenant SMTP relay settings: the acting tenant configures its own mail server so its onboarding/OTP
 * email leaves from its own domain, or reverts to the platform default. The password is write-only (never
 * shown); leaving it blank on save keeps the stored one. Credential-bearing writes are step-up-gated by the
 * API client transparently.
 */
export default function SmtpSettings() {
  const { t } = useTranslation("console");
  const toast = useToast();
  const confirm = useConfirm();

  const [configured, setConfigured] = useState(false);
  const [form, setForm] = useState<FormState | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    getSmtpSettings()
      .then((s) => {
        setConfigured(s.configured);
        setForm(toForm(s));
      })
      .catch((e) => setLoadError(errorMessage(e)));
  }, []);

  function set(patch: Partial<FormState>): void {
    setForm((prev) => (prev ? { ...prev, ...patch } : prev));
  }

  async function reload(): Promise<void> {
    const s = await getSmtpSettings();
    setConfigured(s.configured);
    setForm(toForm(s));
  }

  async function save(): Promise<void> {
    if (!form) return;
    const port = Number(form.port);
    if (!form.host.trim()) {
      setFormError(t("smtpHostRequired"));
      return;
    }
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      setFormError(t("smtpPortInvalid"));
      return;
    }
    setFormError(null);
    setBusy(true);
    try {
      await updateSmtpSettings({
        host: form.host.trim(),
        port,
        username: form.username.trim() || null,
        password: form.password || null, // blank → keep the stored secret
        fromAddress: form.fromAddress.trim() || null,
        starttls: form.starttls,
      });
      await reload();
      toast({ tone: "success", title: t("smtpSaved") });
    } catch (e) {
      setFormError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function resetToDefault(): Promise<void> {
    const ok = await confirm({
      title: t("smtpResetTitle"),
      description: t("smtpResetConfirm"),
      confirmText: t("smtpResetConfirmAction"),
      variant: "destructive",
    });
    if (!ok) return;
    setBusy(true);
    try {
      await deleteSmtpSettings();
      await reload();
      toast({ tone: "success", title: t("smtpResetDone") });
    } catch (e) {
      setFormError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader title={t("smtpTitle")} description={t("smtpDescription")} />

      {loadError ? (
        <ErrorCard message={loadError} />
      ) : !form ? (
        <LoadingCard rows={6} />
      ) : (
        <Card>
          <CardContent className="grid gap-5 pt-6 sm:max-w-xl">
            <p className="text-sm text-muted-foreground">
              {configured ? t("smtpConfiguredHint") : t("smtpInheritedHint")}
            </p>

            <Field label={t("smtpHost")} hint={t("smtpHostHint")} error={formError ?? undefined}>
              <Input value={form.host} onChange={(e) => set({ host: e.target.value })}
                     placeholder="smtp.example.com" autoComplete="off" />
            </Field>

            <Field label={t("smtpPort")} hint={t("smtpPortHint")}>
              <Input type="number" min={1} max={65535} value={form.port}
                     onChange={(e) => set({ port: e.target.value })} className="max-w-32" />
            </Field>

            <Field label={t("smtpUsername")} hint={t("smtpUsernameHint")}>
              <Input value={form.username} onChange={(e) => set({ username: e.target.value })}
                     autoComplete="off" />
            </Field>

            <Field label={t("smtpPassword")} hint={configured ? t("smtpPasswordKeepHint") : t("smtpPasswordHint")}>
              <Input type="password" value={form.password} onChange={(e) => set({ password: e.target.value })}
                     placeholder={configured ? t("smtpPasswordUnchanged") : ""} autoComplete="new-password" />
            </Field>

            <Field label={t("smtpFrom")} hint={t("smtpFromHint")}>
              <Input type="email" value={form.fromAddress} onChange={(e) => set({ fromAddress: e.target.value })}
                     placeholder="no-reply@example.com" autoComplete="off" />
            </Field>

            <div className="flex items-center justify-between gap-4">
              <div>
                <p className="text-sm font-medium">{t("smtpStarttls")}</p>
                <p className="text-xs text-muted-foreground">{t("smtpStarttlsHint")}</p>
              </div>
              <Switch checked={form.starttls} onCheckedChange={(v) => set({ starttls: v })} />
            </div>
          </CardContent>

          <CardFooter className="justify-between gap-2">
            <Button variant="outline" onClick={resetToDefault} disabled={busy || !configured}>
              <RotateCcw /> {t("smtpReset")}
            </Button>
            <Button onClick={save} disabled={busy}>
              <Save /> {t("save")}
            </Button>
          </CardFooter>
        </Card>
      )}
    </div>
  );
}

import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { RotateCcw, Save } from "lucide-react";
import { errorMessage } from "../api";
import { getSmsSettings, updateSmsSettings, deleteSmsSettings, type SmsProvider, type SmsSettings } from "../sms";
import { PageHeader } from "@/components/PageHeader";
import { LoadingCard, ErrorCard } from "@/components/states";
import { useToast } from "@/components/ToastProvider";
import { useConfirm } from "@/components/ConfirmProvider";
import { Field } from "@/components/form/fields";
import { Card, CardContent, CardFooter } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Button } from "@/components/ui/button";

interface FormState {
  provider: SmsProvider;
  apiKey: string;
  apiSecret: string;
  senderNumber: string;
}

/** Empty form for a tier that has no own gateway (it inherits the platform account). */
const BLANK: FormState = { provider: "SOLAPI", apiKey: "", apiSecret: "", senderNumber: "" };

/**
 * The gateways the backend has a client for. Adding one here without one there produces a setting that saves
 * happily and then fails every send at sign-in time, so the list is deliberately short and hand-kept.
 */
const PROVIDERS: readonly SmsProvider[] = ["SOLAPI", "TWILIO"];

function toForm(settings: SmsSettings): FormState {
  if (!settings.configured) {
    return BLANK;
  }
  return {
    provider: settings.provider ?? "SOLAPI",
    apiKey: settings.apiKey ?? "",
    apiSecret: "", // write-only — never returned; blank keeps the stored secret
    senderNumber: settings.senderNumber ?? "",
  };
}

/**
 * Per-tenant SMS gateway settings: the acting tenant registers its own provider account so its one-time codes
 * arrive from its own sending number and are billed to it, or reverts to the platform account. The API secret
 * is write-only (never shown); leaving it blank on save keeps the stored one. Credential-bearing writes are
 * step-up-gated by the API client transparently.
 */
export default function SmsSettings() {
  const { t } = useTranslation("console");
  const toast = useToast();
  const confirm = useConfirm();

  const [configured, setConfigured] = useState(false);
  const [form, setForm] = useState<FormState | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    getSmsSettings()
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
    const s = await getSmsSettings();
    setConfigured(s.configured);
    setForm(toForm(s));
  }

  async function save(): Promise<void> {
    if (!form) return;
    // Same charset the server enforces: the key is interpolated into Twilio's request path, and both
    // providers issue alphanumeric keys, so anything else is a paste error worth naming here.
    if (!/^[A-Za-z0-9_-]+$/.test(form.apiKey.trim())) {
      setFormError(t(form.apiKey.trim() ? "smsApiKeyInvalid" : "smsApiKeyRequired"));
      return;
    }
    // Same shape the server enforces. Checking it here only saves a round trip — the server's copy is the one
    // that decides, and a provider would reject a malformed number later and far less clearly.
    if (!/^\+?[0-9-]{4,32}$/.test(form.senderNumber.trim())) {
      setFormError(t("smsSenderInvalid"));
      return;
    }
    // A first save has nothing stored to fall back on, so an empty secret would be refused by the server.
    if (!configured && !form.apiSecret) {
      setFormError(t("smsSecretRequired"));
      return;
    }
    setFormError(null);
    setBusy(true);
    try {
      await updateSmsSettings({
        provider: form.provider,
        apiKey: form.apiKey.trim(),
        apiSecret: form.apiSecret || null, // blank → keep the stored secret
        senderNumber: form.senderNumber.trim(),
      });
      await reload();
      toast({ tone: "success", title: t("smsSaved") });
    } catch (e) {
      setFormError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function resetToDefault(): Promise<void> {
    const ok = await confirm({
      title: t("smsResetTitle"),
      description: t("smsResetConfirm"),
      confirmText: t("smsResetConfirmAction"),
      variant: "destructive",
    });
    if (!ok) return;
    setBusy(true);
    try {
      await deleteSmsSettings();
      await reload();
      toast({ tone: "success", title: t("smsResetDone") });
    } catch (e) {
      setFormError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader title={t("smsTitle")} description={t("smsDescription")} />

      {loadError ? (
        <ErrorCard message={loadError} />
      ) : !form ? (
        <LoadingCard rows={5} />
      ) : (
        <Card>
          <CardContent className="grid gap-5 pt-6 sm:max-w-xl">
            <p className="text-sm text-muted-foreground">
              {configured ? t("smsConfiguredHint") : t("smsInheritedHint")}
            </p>

            <Field label={t("smsProvider")} hint={t("smsProviderHint")}>
              <Select id="sms-provider" value={form.provider}
                      onChange={(e) => set({ provider: e.target.value as SmsProvider })}>
                {PROVIDERS.map((provider) => (
                  <option key={provider} value={provider}>{t(`smsProvider_${provider}`)}</option>
                ))}
              </Select>
            </Field>

            <Field label={t("smsApiKey")} hint={t(`smsApiKeyHint_${form.provider}`)}
                   error={formError ?? undefined}>
              <Input value={form.apiKey} onChange={(e) => set({ apiKey: e.target.value })} autoComplete="off" />
            </Field>

            <Field label={t("smsApiSecret")}
                   hint={configured ? t("smsApiSecretKeepHint") : t(`smsApiSecretHint_${form.provider}`)}>
              <Input type="password" value={form.apiSecret}
                     onChange={(e) => set({ apiSecret: e.target.value })}
                     placeholder={configured ? t("smsApiSecretUnchanged") : ""} autoComplete="new-password" />
            </Field>

            <Field label={t("smsSenderNumber")} hint={t("smsSenderNumberHint")}>
              <Input value={form.senderNumber} onChange={(e) => set({ senderNumber: e.target.value })}
                     placeholder={form.provider === "SOLAPI" ? "010-1234-5678" : "+15550000000"}
                     autoComplete="off" />
            </Field>
          </CardContent>

          <CardFooter className="justify-between gap-2">
            <Button variant="outline" onClick={resetToDefault} disabled={busy || !configured}>
              <RotateCcw /> {t("smsReset")}
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

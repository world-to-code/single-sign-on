import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Save, Trash2 } from "lucide-react";
import { errorMessage } from "@/api";
import { deleteAuditExport, getAuditExport, updateAuditExport, type AuditExportSettings } from "@/auditExport";
import type { SessionView } from "@/auth";
import { PageHeader } from "@/components/PageHeader";
import { LoadingCard, ErrorCard } from "@/components/states";
import { useToast } from "@/components/ToastProvider";
import { useConfirm } from "@/components/ConfirmProvider";
import { Field } from "@/components/form/fields";
import { Card, CardContent, CardFooter } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { Button } from "@/components/ui/button";
import { formatDateTime } from "@/lib/format";

interface FormState {
  endpointUrl: string;
  credential: string;
  enabled: boolean;
  includePii: boolean;
}

const BLANK: FormState = { endpointUrl: "", credential: "", enabled: false, includePii: false };

function toForm(settings: AuditExportSettings | undefined): FormState {
  if (!settings) {
    return BLANK;
  }
  return {
    endpointUrl: settings.endpointUrl,
    credential: "", // write-only — never returned; blank keeps the stored one
    enabled: settings.enabled,
    includePii: settings.includePii,
  };
}

/** Beyond this the collector is describing a different shift, not a slightly delayed present. */
const STALE_AFTER_SECONDS = 600;

/**
 * Where this deployment's audit trail is shipped, and whether it is arriving.
 *
 * Platform-only: the exporter reads across every tenant, so pointing it somewhere decides where EVERY
 * tenant's security history goes. The credential is write-only and never read back, and leaving it blank on
 * save keeps the stored one — including when switching the export off, so stopping it never depends on still
 * holding the secret.
 *
 * The health block is the part that is easy to under-value. A collector accepting every batch while falling
 * further behind raises no failures at all, so "no errors" is not the same as "arriving".
 */
export default function AuditExport({ session }: { session: SessionView }) {
  const { t } = useTranslation("console");
  const toast = useToast();
  const confirm = useConfirm();

  const [settings, setSettings] = useState<AuditExportSettings | undefined>();
  const [form, setForm] = useState<FormState | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Shipping identifiers needs audit:read:pii ON TOP of audit:export, because those are exactly the fields
  // the audit screen redacts without it. Hiding the control is a courtesy; the server decides.
  const canIncludePii = session.permissions.includes("audit:read:pii");

  useEffect(() => {
    getAuditExport()
      .then((s) => {
        setSettings(s);
        setForm(toForm(s));
      })
      .catch((e) => setLoadError(errorMessage(e)));
  }, []);

  function set(patch: Partial<FormState>): void {
    setForm((prev) => (prev ? { ...prev, ...patch } : prev));
  }

  async function reload(): Promise<void> {
    const s = await getAuditExport();
    setSettings(s);
    setForm(toForm(s));
  }

  async function save(): Promise<void> {
    if (!form) return;
    // The server re-checks all of this and its answer is the one that decides; naming the obvious cases here
    // only saves a round trip on a step-up-gated write.
    if (!/^https:\/\//i.test(form.endpointUrl.trim())) {
      setFormError(t("auditExportEndpointNotHttps"));
      return;
    }
    // A first save has nothing stored to fall back on.
    if (!settings && !form.credential) {
      setFormError(t("auditExportCredentialRequired"));
      return;
    }
    setFormError(null);
    setBusy(true);
    try {
      await updateAuditExport({
        endpointUrl: form.endpointUrl.trim(),
        credential: form.credential || null, // blank → keep the stored credential
        enabled: form.enabled,
        includePii: form.includePii,
      });
      await reload();
      toast({ tone: "success", title: t("auditExportSaved") });
    } catch (e) {
      setFormError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function removeCollector(): Promise<void> {
    const ok = await confirm({
      title: t("auditExportRemoveTitle"),
      description: t("auditExportRemoveConfirm"),
      confirmText: t("auditExportRemoveConfirmAction"),
      variant: "destructive",
    });
    if (!ok) return;
    setBusy(true);
    try {
      await deleteAuditExport();
      await reload();
      toast({ tone: "success", title: t("auditExportRemoveDone") });
    } catch (e) {
      setFormError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader title={t("auditExportTitle")} description={t("auditExportDescription")} />

      {loadError ? (
        <ErrorCard message={loadError} />
      ) : !form ? (
        <LoadingCard rows={6} />
      ) : (
        <>
          {settings && <Health settings={settings} />}

          <Card>
            <CardContent className="grid gap-5 pt-6 sm:max-w-xl">
              <p className="text-sm text-muted-foreground">
                {settings ? t("auditExportConfiguredHint") : t("auditExportUnconfiguredHint")}
              </p>

              <Field label={t("auditExportEndpoint")} hint={t("auditExportEndpointHint")}
                     error={formError ?? undefined}>
                <Input value={form.endpointUrl} onChange={(e) => set({ endpointUrl: e.target.value })}
                       placeholder="https://collector.example.com/ingest" autoComplete="off" />
              </Field>

              <Field label={t("auditExportCredential")}
                     hint={settings ? t("auditExportCredentialKeepHint") : t("auditExportCredentialHint")}>
                <Input type="password" value={form.credential}
                       onChange={(e) => set({ credential: e.target.value })}
                       placeholder={settings ? t("auditExportCredentialUnchanged") : ""}
                       autoComplete="new-password" />
              </Field>

              <div className="flex items-center justify-between gap-4">
                <div>
                  <p className="text-sm font-medium">{t("auditExportEnabled")}</p>
                  <p className="text-xs text-muted-foreground">{t("auditExportEnabledHint")}</p>
                </div>
                <Switch checked={form.enabled} onCheckedChange={(v) => set({ enabled: v })} />
              </div>

              <div className="flex items-center justify-between gap-4">
                <div>
                  <p className="text-sm font-medium">{t("auditExportIncludePii")}</p>
                  <p className="text-xs text-muted-foreground">
                    {canIncludePii ? t("auditExportIncludePiiHint") : t("auditExportIncludePiiDenied")}
                  </p>
                </div>
                <Switch checked={form.includePii} disabled={!canIncludePii && !form.includePii}
                        onCheckedChange={(v) => set({ includePii: v })} />
              </div>
            </CardContent>

            <CardFooter className="justify-between gap-2">
              <Button onClick={save} disabled={busy}><Save /> {t("save")}</Button>
              {settings && (
                <Button variant="destructive" onClick={removeCollector} disabled={busy}>
                  <Trash2 /> {t("auditExportRemove")}
                </Button>
              )}
            </CardFooter>
          </Card>
        </>
      )}
    </div>
  );
}

/** Whether the trail is arriving — the question the failure count alone cannot answer. */
function Health({ settings }: { settings: AuditExportSettings }) {
  const { t, i18n } = useTranslation("console");
  const { health } = settings;
  const stale = health.behindBySeconds !== null && health.behindBySeconds > STALE_AFTER_SECONDS;
  const failing = health.consecutiveFailures > 0;

  return (
    <Card>
      <CardContent className="grid gap-3 pt-6 sm:grid-cols-3">
        <Stat label={t("auditExportBehind")}
              value={health.behindBySeconds === null
                ? t("auditExportNeverShipped")
                : t("auditExportBehindValue", { seconds: health.behindBySeconds })}
              tone={stale ? "warn" : "ok"} />
        <Stat label={t("auditExportLastSuccess")}
              value={health.lastSuccessAt ? formatDateTime(health.lastSuccessAt, i18n.language) : "—"} />
        <Stat label={t("auditExportFailures")} value={String(health.consecutiveFailures)}
              tone={failing ? "warn" : "ok"} />
      </CardContent>
    </Card>
  );
}

function Stat({ label, value, tone }: { label: string; value: string; tone?: "ok" | "warn" }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className={`text-sm font-medium ${tone === "warn" ? "text-destructive" : ""}`}>{value}</p>
    </div>
  );
}

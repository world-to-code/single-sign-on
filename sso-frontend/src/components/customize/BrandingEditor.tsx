import { useEffect, useState, type CSSProperties } from "react";
import { useTranslation } from "react-i18next";
import { RotateCcw, Save } from "lucide-react";
import { errorMessage } from "../../api";
import {
  deleteBranding,
  getBrandingSettings,
  updateBranding,
  type BrandingInput,
  type BrandingView,
} from "../../branding";
import { hexToHslTriple } from "@/lib/prefs";
import { Brand } from "../Brand";
import { Field } from "../form/fields";
import { LoadingCard, ErrorCard } from "../states";
import { useToast } from "../ToastProvider";
import { useConfirm } from "../ConfirmProvider";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Input } from "../ui/input";

interface FormState {
  logoUrl: string;
  accentColor: string;
  productName: string;
}

function toForm(view: BrandingView): FormState {
  return { logoUrl: view.logoUrl ?? "", accentColor: view.accentColor ?? "", productName: view.productName ?? "" };
}

function toInput(form: FormState): BrandingInput {
  return {
    logoUrl: form.logoUrl.trim() || null,
    accentColor: form.accentColor.trim() || null,
    productName: form.productName.trim() || null,
  };
}

/**
 * Edits the tenant's auth-UI branding (logo URL, accent color, product name) with a LIVE preview of how the
 * sign-in header will look. The preview scopes the accent to itself (a local `--primary`) so it never repaints
 * the console. Validation (https logo, #RRGGBB accent) is the backend's; a rejected save surfaces as an error.
 */
export function BrandingEditor() {
  const { t } = useTranslation("console");
  const toast = useToast();
  const confirm = useConfirm();

  const [configured, setConfigured] = useState(false);
  const [form, setForm] = useState<FormState | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    getBrandingSettings()
      .then((view) => { setConfigured(view.configured); setForm(toForm(view)); })
      .catch((e) => setLoadError(errorMessage(e)));
  }, []);

  function set(patch: Partial<FormState>): void {
    setForm((prev) => (prev ? { ...prev, ...patch } : prev));
  }

  async function reload(): Promise<void> {
    const view = await getBrandingSettings();
    setConfigured(view.configured);
    setForm(toForm(view));
  }

  async function save(): Promise<void> {
    if (!form) return;
    setBusy(true);
    try {
      await updateBranding(toInput(form));
      await reload();
      toast({ tone: "success", title: t("brandingSaved") });
    } catch (e) {
      setFormError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function resetToDefault(): Promise<void> {
    const ok = await confirm({
      title: t("brandingResetTitle"),
      description: t("brandingResetConfirm"),
      confirmText: t("brandingResetConfirmAction"),
      variant: "destructive",
    });
    if (!ok) return;
    setBusy(true);
    try {
      await deleteBranding();
      await reload();
      toast({ tone: "success", title: t("brandingResetDone") });
    } catch (e) {
      setFormError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  if (loadError) return <ErrorCard message={loadError} />;
  if (!form) return <LoadingCard rows={5} />;

  const triple = form.accentColor.trim() ? hexToHslTriple(form.accentColor.trim()) : null;
  const previewStyle = triple ? ({ "--primary": triple, "--ring": triple } as CSSProperties) : undefined;

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <div className="grid gap-5">
        <Badge variant={configured ? "success" : "muted"} className="w-fit">
          {configured ? t("customizeCustomized") : t("customizeInherited")}
        </Badge>

        <Field label={t("brandingLogoUrl")} hint={t("brandingLogoHint")} error={formError ?? undefined}>
          <Input type="url" value={form.logoUrl} onChange={(e) => set({ logoUrl: e.target.value })}
                 placeholder="https://cdn.example.com/logo.png" />
        </Field>

        <Field label={t("brandingAccent")} hint={t("brandingAccentHint")}>
          <div className="flex items-center gap-2">
            <input type="color" aria-label={t("brandingAccent")}
                   value={/^#[0-9a-fA-F]{6}$/.test(form.accentColor) ? form.accentColor : "#0a7a6a"}
                   onChange={(e) => set({ accentColor: e.target.value })}
                   className="h-9 w-12 shrink-0 cursor-pointer rounded-md border border-input bg-transparent" />
            <Input value={form.accentColor} onChange={(e) => set({ accentColor: e.target.value })}
                   placeholder="#0a7a6a" className="font-mono" />
          </div>
        </Field>

        <Field label={t("brandingProductName")} hint={t("brandingNameHint")}>
          <Input value={form.productName} onChange={(e) => set({ productName: e.target.value })}
                 placeholder="Mini SSO" maxLength={64} />
        </Field>

        <div className="flex items-center justify-between gap-2">
          <Button variant="outline" onClick={resetToDefault} disabled={busy || !configured}>
            <RotateCcw /> {t("brandingReset")}
          </Button>
          <Button onClick={save} disabled={busy}>
            <Save /> {t("save")}
          </Button>
        </div>
      </div>

      <div className="grid content-start gap-2">
        <p className="text-sm font-medium">{t("customizePreview")}</p>
        <div style={previewStyle}
             className="flex flex-col items-center gap-5 rounded-md border border-input bg-background p-8">
          <Brand logoUrl={form.logoUrl.trim() || null} name={form.productName.trim() || null} />
          <div className="w-full max-w-xs space-y-3">
            <div className="h-9 rounded-md border border-input bg-sunken" />
            <Button className="w-full">{t("brandingPreviewSignIn")}</Button>
            <p className="text-center text-xs text-primary">{t("brandingPreviewLink")}</p>
          </div>
        </div>
      </div>
    </div>
  );
}

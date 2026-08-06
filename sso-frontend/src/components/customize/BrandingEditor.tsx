import { useEffect, useState, type CSSProperties } from "react";
import { useTranslation } from "react-i18next";
import { useSubmit } from "@/hooks/useSubmit";
import { RotateCcw, Save } from "lucide-react";
import { errorMessage } from "../../api";
import {
  AUTH_LAYOUTS,
  BRANDING_CORNERS,
  BRANDING_FONTS,
  deleteBranding,
  getBrandingSettings,
  updateBranding,
  type AuthScreenLayout,
  type BrandingCorner,
  type BrandingFont,
  type BrandingInput,
  type BrandingView,
} from "../../branding";
import { hexToHslTriple } from "@/lib/brandingTheme";
import { Brand } from "../Brand";
import { useBrandingRefresh } from "../BrandingProvider";
import { LoadingCard, ErrorCard } from "../states";
import { useToast } from "../ToastProvider";
import { useConfirm } from "../ConfirmProvider";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Field } from "../form/fields";
import { InheritableChoiceField, ColorField, UrlField } from "./BrandingFields";

/** Every field is a string here — "" is how the form says "inherit", which maps to null on the wire. */
interface FormState {
  logoUrl: string;
  logoUrlDark: string;
  faviconUrl: string;
  productName: string;
  accentColor: string;
  backgroundColor: string;
  backgroundImageUrl: string;
  font: BrandingFont | "";
  corner: BrandingCorner | "";
  layout: AuthScreenLayout | "";
}

function toForm(view: BrandingView): FormState {
  const { identity, theme } = view;
  return {
    logoUrl: identity.logoUrl ?? "",
    logoUrlDark: identity.logoUrlDark ?? "",
    faviconUrl: identity.faviconUrl ?? "",
    productName: identity.productName ?? "",
    accentColor: theme.accentColor ?? "",
    backgroundColor: theme.backgroundColor ?? "",
    backgroundImageUrl: theme.backgroundImageUrl ?? "",
    font: theme.font ?? "",
    corner: theme.corner ?? "",
    layout: theme.layout ?? "",
  };
}

function toInput(form: FormState): BrandingInput {
  return {
    logoUrl: blankToNull(form.logoUrl),
    logoUrlDark: blankToNull(form.logoUrlDark),
    faviconUrl: blankToNull(form.faviconUrl),
    productName: blankToNull(form.productName),
    accentColor: blankToNull(form.accentColor),
    backgroundColor: blankToNull(form.backgroundColor),
    backgroundImageUrl: blankToNull(form.backgroundImageUrl),
    font: form.font || null,
    corner: form.corner || null,
    layout: form.layout || null,
  };
}

function blankToNull(value: string): string | null {
  return value.trim() || null;
}

/**
 * Edits the tenant's auth-UI branding — marks, name, colours and the three style choices — with a LIVE preview
 * of the sign-in screen. The preview scopes the accent to ITSELF (a local `--primary`) so editing never
 * repaints the console around it. Validation (https URLs, #RRGGBB colours) is the backend's; a rejected save
 * surfaces as an error rather than being second-guessed here, so the two can never disagree.
 */
export function BrandingEditor() {
  const { t } = useTranslation("console");
  const toast = useToast();
  const refreshBranding = useBrandingRefresh();
  const confirm = useConfirm();
  const { busy, error: formError, run } = useSubmit();

  const [configured, setConfigured] = useState(false);
  const [form, setForm] = useState<FormState | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

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
    await run(async () => {
        await updateBranding(toInput(form));
        await reload();
        await refreshBranding(); // the shell reads the resolved branding, not this form
        toast({ tone: "success", title: t("brandingSaved") });
    });
  }

  async function resetToDefault(): Promise<void> {
    const ok = await confirm({
      title: t("brandingResetTitle"),
      description: t("brandingResetConfirm"),
      confirmText: t("brandingResetConfirmAction"),
      variant: "destructive",
    });
    if (!ok) return;
    await run(async () => {
        await deleteBranding();
        await reload();
        await refreshBranding();
        toast({ tone: "success", title: t("brandingResetDone") });
    });
  }

  if (loadError) return <ErrorCard message={loadError} />;
  if (!form) return <LoadingCard rows={5} />;

  const accent = hexToHslTriple(form.accentColor.trim());
  const background = hexToHslTriple(form.backgroundColor.trim());
  const previewStyle: CSSProperties = {
    ...(accent ? { "--primary": accent, "--ring": accent } : {}),
    ...(background ? { "--background": background } : {}),
  } as CSSProperties;

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <div className="grid gap-5">
        <Badge variant={configured ? "success" : "muted"} className="w-fit">
          {configured ? t("customizeCustomized") : t("customizeInherited")}
        </Badge>

        {formError && <ErrorCard message={formError} />}

        <UrlField label={t("brandingLogoUrl")} hint={t("brandingLogoHint")} value={form.logoUrl}
                  placeholder="https://cdn.example.com/logo.png"
                  onChange={(logoUrl) => set({ logoUrl })} />

        <UrlField label={t("brandingLogoDark")} hint={t("brandingLogoDarkHint")} value={form.logoUrlDark}
                  placeholder="https://cdn.example.com/logo-dark.png"
                  onChange={(logoUrlDark) => set({ logoUrlDark })} />

        <UrlField label={t("brandingFavicon")} hint={t("brandingFaviconHint")} value={form.faviconUrl}
                  placeholder="https://cdn.example.com/favicon.png"
                  onChange={(faviconUrl) => set({ faviconUrl })} />

        <Field label={t("brandingProductName")} hint={t("brandingNameHint")}>
          <Input value={form.productName} onChange={(e) => set({ productName: e.target.value })}
                 placeholder="Svalinn" maxLength={64} />
        </Field>

        <ColorField label={t("brandingAccent")} hint={t("brandingAccentHint")} value={form.accentColor}
                    fallback="#0a7a6a" onChange={(accentColor) => set({ accentColor })} />

        <ColorField label={t("brandingBackground")} hint={t("brandingBackgroundHint")}
                    value={form.backgroundColor} fallback="#ffffff"
                    onChange={(backgroundColor) => set({ backgroundColor })} />

        <UrlField label={t("brandingBackgroundImage")} hint={t("brandingBackgroundImageHint")}
                  value={form.backgroundImageUrl} placeholder="https://cdn.example.com/cover.jpg"
                  onChange={(backgroundImageUrl) => set({ backgroundImageUrl })} />

        <InheritableChoiceField label={t("brandingFont")} hint={t("brandingFontHint")} value={form.font}
                     options={BRANDING_FONTS} inheritLabel={t("brandingInherit")}
                     labelFor={(font) => t(`brandingFont_${font}`)}
                     onChange={(font) => set({ font })} />

        <InheritableChoiceField label={t("brandingCorner")} hint={t("brandingCornerHint")} value={form.corner}
                     options={BRANDING_CORNERS} inheritLabel={t("brandingInherit")}
                     labelFor={(corner) => t(`brandingCorner_${corner}`)}
                     onChange={(corner) => set({ corner })} />

        <InheritableChoiceField label={t("brandingLayout")} hint={t("brandingLayoutHint")} value={form.layout}
                     options={AUTH_LAYOUTS} inheritLabel={t("brandingInherit")}
                     labelFor={(layout) => t(`brandingLayout_${layout}`)}
                     onChange={(layout) => set({ layout })} />

        <div className="flex items-center justify-between gap-2">
          <Button variant="outline" onClick={resetToDefault} disabled={busy || !configured}>
            <RotateCcw /> {t("brandingReset")}
          </Button>
          <Button onClick={save} disabled={busy}>
            <Save /> {t("save")}
          </Button>
        </div>
      </div>

      <div className="grid content-start gap-2 lg:sticky lg:top-4">
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

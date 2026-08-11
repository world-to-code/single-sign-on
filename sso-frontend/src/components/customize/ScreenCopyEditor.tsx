import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useSubmit } from "@/hooks/useSubmit";
import { RotateCcw, Save } from "lucide-react";
import { errorMessage } from "@/api";
import {
  AUTH_SCREENS,
  deleteScreenCopy,
  getScreenCopy,
  updateScreenCopy,
  type AuthScreen,
  type ScreenCopy,
} from "@/branding";
import { Field } from "@/components/form/fields";
import { LoadingCard, ErrorCard } from "@/components/states";
import { useToast } from "@/components/ToastProvider";
import { useConfirm } from "@/components/ConfirmProvider";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { ChoiceField } from "./BrandingFields";

/**
 * The console label for each screen. `satisfies Record<AuthScreen, string>` is what makes adding a sixth
 * screen a COMPILE error here rather than a button rendering the raw key `brandingScreen_DEVICE` — the enum,
 * the migration CHECK and the union all fail loudly, and this was the one place that did not.
 */
const SCREEN_LABEL = {
  LOGIN: "brandingScreen_LOGIN",
  MFA: "brandingScreen_MFA",
  STEPUP: "brandingScreen_STEPUP",
  CONSENT: "brandingScreen_CONSENT",
  RESET: "brandingScreen_RESET",
} as const satisfies Record<AuthScreen, string>;

/** Every field is a string in the form; "" is how it says "inherit", which maps to null on the wire. */
interface FormState {
  headline: string;
  subtext: string;
  footer: string;
  helpUrl: string;
}

const EMPTY: FormState = { headline: "", subtext: "", footer: "", helpUrl: "" };

function toForm(copy: ScreenCopy | undefined): FormState {
  return {
    headline: copy?.headline ?? "",
    subtext: copy?.subtext ?? "",
    footer: copy?.footer ?? "",
    helpUrl: copy?.helpUrl ?? "",
  };
}

function toCopy(form: FormState): ScreenCopy {
  return {
    headline: blankToNull(form.headline),
    subtext: blankToNull(form.subtext),
    footer: blankToNull(form.footer),
    helpUrl: blankToNull(form.helpUrl),
  };
}

function blankToNull(value: string): string | null {
  return value.trim() || null;
}

/**
 * Edits what the sign-in screens SAY, one screen at a time.
 *
 * <p>One screen at a time is a correctness decision, not a layout one: the API writes a single screen's row,
 * so a form holding all five would have to guess which ones the tenant meant to touch. Switching screens
 * reloads the form from the fetched wording, so text is never carried from the screen last looked at into the
 * one being saved.
 */
export function ScreenCopyEditor() {
  const { t } = useTranslation("console");
  const toast = useToast();
  const confirm = useConfirm();
  const { busy, error: formError, setError: setFormError, run } = useSubmit();
  // No useBrandingRefresh() here, unlike BrandingEditor — a decision, not an omission. That hook exists so the
  // console repaints its own logo, name and accent after a save; the console renders no screen WORDING, so
  // there is nothing on this page for a refresh to change. Add it the day the console previews these screens.

  const [screen, setScreen] = useState<AuthScreen>("LOGIN");
  const [saved, setSaved] = useState<Partial<Record<AuthScreen, ScreenCopy>> | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY);
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    getScreenCopy()
      .then((all) => { setSaved(all); setForm(toForm(all.LOGIN)); })
      .catch((e) => setLoadError(errorMessage(e)));
  }, []);

  function select(next: AuthScreen): void {
    setScreen(next);
    setForm(toForm(saved?.[next]));
    setFormError(null);
  }

  function set(patch: Partial<FormState>): void {
    setForm((prev) => ({ ...prev, ...patch }));
  }

  async function save(): Promise<void> {
    await run(async () => {
        setSaved(await updateScreenCopy(screen, toCopy(form)));
        toast({ tone: "success", title: t("brandingSaved") });
    });
  }

  async function revert(): Promise<void> {
    const ok = await confirm({
      title: t("brandingScreenResetTitle"),
      description: t("brandingScreenResetConfirm"),
      confirmText: t("brandingResetConfirmAction"),
      variant: "destructive",
    });
    if (!ok) return;
    await run(async () => {
        await deleteScreenCopy(screen);
        const all = await getScreenCopy();
        setSaved(all);
        setForm(toForm(all[screen]));
        toast({ tone: "success", title: t("brandingResetDone") });
    });
  }

  if (loadError) return <ErrorCard message={loadError} />;
  if (!saved) return <LoadingCard rows={4} />;

  return (
    <div className="grid gap-5">
      <ChoiceField label={t("brandingScreen")} hint={t("brandingScreenHint")} value={screen}
                   options={AUTH_SCREENS}
                   labelFor={(option) => t(SCREEN_LABEL[option])}
                   onChange={select} />

      {formError && <ErrorCard message={formError} />}

      <Field label={t("brandingScreenHeadline")} hint={t("brandingScreenHeadlineHint")}>
        <Input id="screen-headline" aria-label={t("brandingScreenHeadline")} value={form.headline}
               maxLength={120} onChange={(e) => set({ headline: e.target.value })} />
      </Field>

      <Field label={t("brandingScreenSubtext")} hint={t("brandingScreenSubtextHint")}>
        <Textarea aria-label={t("brandingScreenSubtext")} value={form.subtext} rows={2} maxLength={300}
                  onChange={(e) => set({ subtext: e.target.value })} />
      </Field>

      <Field label={t("brandingScreenFooter")} hint={t("brandingScreenFooterHint")}>
        <Input aria-label={t("brandingScreenFooter")} value={form.footer} maxLength={200}
               onChange={(e) => set({ footer: e.target.value })} />
      </Field>

      <Field label={t("brandingScreenHelpUrl")} hint={t("brandingScreenHelpUrlHint")}>
        <Input type="url" aria-label={t("brandingScreenHelpUrl")} value={form.helpUrl}
               placeholder="https://help.example.com"
               onChange={(e) => set({ helpUrl: e.target.value })} />
      </Field>

      <div className="flex items-center justify-between gap-2">
        <Button variant="outline" onClick={revert} disabled={busy || !saved[screen]}>
          <RotateCcw /> {t("brandingReset")}
        </Button>
        <Button onClick={save} disabled={busy}>
          <Save /> {t("save")}
        </Button>
      </div>
    </div>
  );
}

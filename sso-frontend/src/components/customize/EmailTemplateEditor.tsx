import { Suspense, lazy, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useSubmit } from "@/hooks/useSubmit";
import { RotateCcw, Save } from "lucide-react";
import { errorMessage } from "../../api";
import {
  deleteEmailTemplate,
  previewEmailTemplate,
  updateEmailTemplate,
  type EmailTemplate,
  type EmailTemplateInput,
  type EmailTemplatePreview,
} from "../../emailTemplates";
import { HtmlPreview } from "../HtmlPreview";
// Lazy: CodeMirror is large and this is one admin screen, so it must not sit in the bundle every sign-in
// downloads first. The fallback is the old read-only textarea, so the markup is never invisible.
const TemplateSourceEditor = lazy(() =>
  import("./TemplateSourceEditor").then((m) => ({ default: m.TemplateSourceEditor })));
import { Field } from "../form/fields";
import { useToast } from "../ToastProvider";
import { useConfirm } from "../ConfirmProvider";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Textarea } from "../ui/textarea";

interface FormState {
  subject: string;
  htmlBody: string;
  textBody: string;
  logoUrl: string;
}

function toForm(template: EmailTemplate): FormState {
  return {
    subject: template.subject,
    htmlBody: template.htmlBody,
    textBody: template.textBody ?? "",
    logoUrl: template.logoUrl ?? "",
  };
}

function toInput(form: FormState): EmailTemplateInput {
  return {
    subject: form.subject,
    htmlBody: form.htmlBody,
    textBody: form.textBody.trim() || null,
    logoUrl: form.logoUrl.trim() || null,
  };
}

/**
 * The editor for one email event: subject/HTML/text/logo fields, the event's variable palette, and a LIVE
 * server-rendered preview (debounced) shown inside a sandboxed frame. Validation (logo https, template syntax,
 * size) is the backend's — a rejected save surfaces as a field error. On save/reset the parent is handed the
 * refreshed list.
 */
export function EmailTemplateEditor({ template, onSaved }: {
  template: EmailTemplate;
  onSaved: (templates: EmailTemplate[]) => void;
}) {
  const { t } = useTranslation("console");
  const toast = useToast();
  const confirm = useConfirm();
  const { busy, error: formError, run } = useSubmit();

  const [form, setForm] = useState<FormState>(() => toForm(template));
  const [preview, setPreview] = useState<EmailTemplatePreview | null>(null);
  const [showText, setShowText] = useState(false);
  // Two different failures, deliberately kept apart: a SAVE that was refused belongs beside the button that
  // asked for it, a RENDER that was refused belongs beside the preview it invalidates. They shared one field
  // before, under the Subject input, which is where neither of them is about.
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [rendering, setRendering] = useState(false);

  // Re-seed the form when the selected event changes or the server state is refreshed (save/reset).
  useEffect(() => setForm(toForm(template)), [template]);

  /**
   * Debounced live preview, re-rendered on the server (and shown sandboxed) as the admin edits.
   *
   * <p>Two things this has to get right, and neither is the request itself.
   *
   * <p>A superseded request must not land last. The renders are not equally fast — a template that grew a
   * table takes longer than the one before it — so without the guard an older response can arrive after a
   * newer one and leave the preview showing markup the editor no longer contains. That is indistinguishable
   * from "the preview ignored my change", which is exactly what it was reported as.
   *
   * <p>And a FAILED render must not leave the last good one standing silently. Half-typed markup is refused by
   * the server constantly and normally — every {@code &#123;&#123;} is invalid until its closing braces are
   * typed — so the preview would sit there looking authoritative while showing something else entirely.
   */
  useEffect(() => {
    let cancelled = false;
    const handle = setTimeout(() => {
      setRendering(true);
      previewEmailTemplate(template.event, toInput(form))
        .then((rendered) => {
          if (cancelled) return;
          setPreview(rendered);
          setPreviewError(null);
        })
        .catch((e) => {
          if (cancelled) return;
          setPreviewError(errorMessage(e));
        })
        .finally(() => { if (!cancelled) setRendering(false); });
    }, 400);
    return () => { cancelled = true; clearTimeout(handle); };
  }, [form, template.event]);

  function set(patch: Partial<FormState>): void {
    setForm((prev) => ({ ...prev, ...patch }));
  }

  function insertVariable(variable: string): void {
    set({ htmlBody: `${form.htmlBody}{{${variable}}}` });
  }

  async function save(): Promise<void> {
    await run(async () => {
        onSaved(await updateEmailTemplate(template.event, toInput(form)));
        toast({ tone: "success", title: t("customizeSaved") });
    });
  }

  async function resetToDefault(): Promise<void> {
    const ok = await confirm({
      title: t("customizeResetTitle"),
      description: t("customizeResetConfirm"),
      confirmText: t("customizeResetConfirmAction"),
      variant: "destructive",
    });
    if (!ok) return;
    await run(async () => {
        onSaved(await deleteEmailTemplate(template.event));
        toast({ tone: "success", title: t("customizeResetDone") });
    });
  }

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <div className="grid gap-5">
        <div className="flex items-center justify-between">
          <Badge variant={template.configured ? "success" : "muted"}>
            {template.configured ? t("customizeCustomized") : t("customizeInherited")}
          </Badge>
        </div>

        <Field label={t("customizeSubject")}>
          <Input value={form.subject} onChange={(e) => set({ subject: e.target.value })} />
        </Field>

        <Field label={t("customizeHtmlBody")} hint={t("customizeHtmlHint")}>
          <Suspense fallback={<Textarea value={form.htmlBody} readOnly rows={12} />}>
            <TemplateSourceEditor value={form.htmlBody} onChange={(next) => set({ htmlBody: next })}
                                  ariaLabel={t("customizeHtmlBody")} variables={template.variables} rows={12} />
          </Suspense>
        </Field>

        <div className="grid gap-1.5">
          <p className="text-xs text-muted-foreground">{t("customizeVariables")}</p>
          <div className="flex flex-wrap gap-1.5">
            {template.variables.map((variable) => (
              <button key={variable} type="button" onClick={() => insertVariable(variable)}
                      className="rounded-md border border-input px-2 py-0.5 font-mono text-xs
                                 text-muted-foreground hover:bg-muted hover:text-foreground">
                {`{{${variable}}}`}
              </button>
            ))}
          </div>
        </div>

        <Field label={t("customizeTextBody")} hint={t("customizeTextHint")}>
          <Suspense fallback={<Textarea value={form.textBody} readOnly rows={5} />}>
            {/* language="text": the plain-text body is not markup, so it gets the placeholder highlighting
                the two bodies share and none of the HTML-specific machinery. */}
            <TemplateSourceEditor value={form.textBody} onChange={(next) => set({ textBody: next })}
                                  ariaLabel={t("customizeTextBody")} variables={template.variables}
                                  language="text" rows={5} />
          </Suspense>
        </Field>

        <Field label={t("customizeLogoUrl")} hint={t("customizeLogoHint")}>
          <Input type="url" value={form.logoUrl} onChange={(e) => set({ logoUrl: e.target.value })}
                 placeholder="https://cdn.example.com/logo.png" />
        </Field>

        {formError && <p role="alert" className="text-xs text-destructive">{formError}</p>}

        <div className="flex items-center justify-between gap-2">
          <Button variant="outline" onClick={resetToDefault} disabled={busy || !template.configured}>
            <RotateCcw /> {t("customizeReset")}
          </Button>
          <Button onClick={save} disabled={busy}>
            <Save /> {t("save")}
          </Button>
        </div>
      </div>

      <div className="grid content-start gap-2">
        <div className="flex items-center gap-2">
          <p className="text-sm font-medium">{t("customizePreview")}</p>
          <div className="ml-auto flex gap-1">
            <Button variant={showText ? "ghost" : "secondary"} size="sm" onClick={() => setShowText(false)}>
              {t("customizePreviewHtml")}
            </Button>
            <Button variant={showText ? "secondary" : "ghost"} size="sm" onClick={() => setShowText(true)}>
              {t("customizePreviewText")}
            </Button>
          </div>
        </div>
        {previewError && (
          <p role="alert" className="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2
                                     text-xs text-destructive">
            {t("customizePreviewStale")} {previewError}
          </p>
        )}
        {/* Dimmed while a render is in flight or the last one failed: the frame below is the PREVIOUS answer,
            and showing it at full strength is how it gets mistaken for the current one. */}
        <div className={previewError || rendering ? "opacity-50 transition-opacity" : "transition-opacity"}>
          {preview && !showText && <HtmlPreview html={preview.html} title={t("customizePreview")} />}
          {preview && showText && (
            <pre className="h-96 w-full overflow-auto rounded-md border border-input bg-sunken p-3 text-xs
                            whitespace-pre-wrap">{preview.text}</pre>
          )}
        </div>
        {preview && <p className="text-xs text-muted-foreground">{t("customizePreviewSubject")}: {preview.subject}</p>}
      </div>
    </div>
  );
}

import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
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

  const [form, setForm] = useState<FormState>(() => toForm(template));
  const [preview, setPreview] = useState<EmailTemplatePreview | null>(null);
  const [showText, setShowText] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Re-seed the form when the selected event changes or the server state is refreshed (save/reset).
  useEffect(() => setForm(toForm(template)), [template]);

  // Debounced live preview: re-render on the server (sandboxed) as the admin edits.
  useEffect(() => {
    const handle = setTimeout(() => {
      previewEmailTemplate(template.event, toInput(form))
        .then((rendered) => { setPreview(rendered); setFormError(null); })
        .catch((e) => setFormError(errorMessage(e)));
    }, 400);
    return () => clearTimeout(handle);
  }, [form, template.event]);

  function set(patch: Partial<FormState>): void {
    setForm((prev) => ({ ...prev, ...patch }));
  }

  function insertVariable(variable: string): void {
    set({ htmlBody: `${form.htmlBody}{{${variable}}}` });
  }

  async function save(): Promise<void> {
    setBusy(true);
    try {
      onSaved(await updateEmailTemplate(template.event, toInput(form)));
      toast({ tone: "success", title: t("customizeSaved") });
    } catch (e) {
      setFormError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function resetToDefault(): Promise<void> {
    const ok = await confirm({
      title: t("customizeResetTitle"),
      description: t("customizeResetConfirm"),
      confirmText: t("customizeResetConfirmAction"),
      variant: "destructive",
    });
    if (!ok) return;
    setBusy(true);
    try {
      onSaved(await deleteEmailTemplate(template.event));
      toast({ tone: "success", title: t("customizeResetDone") });
    } catch (e) {
      setFormError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <div className="grid gap-5">
        <div className="flex items-center justify-between">
          <Badge variant={template.configured ? "success" : "muted"}>
            {template.configured ? t("customizeCustomized") : t("customizeInherited")}
          </Badge>
        </div>

        <Field label={t("customizeSubject")} error={formError ?? undefined}>
          <Input value={form.subject} onChange={(e) => set({ subject: e.target.value })} />
        </Field>

        <Field label={t("customizeHtmlBody")} hint={t("customizeHtmlHint")}>
          <Textarea value={form.htmlBody} onChange={(e) => set({ htmlBody: e.target.value })} rows={12} />
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
          <Textarea value={form.textBody} onChange={(e) => set({ textBody: e.target.value })} rows={5} />
        </Field>

        <Field label={t("customizeLogoUrl")} hint={t("customizeLogoHint")}>
          <Input type="url" value={form.logoUrl} onChange={(e) => set({ logoUrl: e.target.value })}
                 placeholder="https://cdn.example.com/logo.png" />
        </Field>

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
        {preview && !showText && <HtmlPreview html={preview.html} title={t("customizePreview")} />}
        {preview && showText && (
          <pre className="h-96 w-full overflow-auto rounded-md border border-input bg-sunken p-3 text-xs
                          whitespace-pre-wrap">{preview.text}</pre>
        )}
        {preview && <p className="text-xs text-muted-foreground">{t("customizePreviewSubject")}: {preview.subject}</p>}
      </div>
    </div>
  );
}

import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { errorMessage } from "../api";
import { listEmailTemplates, type EmailEvent, type EmailTemplate } from "../emailTemplates";
import { EmailTemplateEditor } from "../components/customize/EmailTemplateEditor";
import { PageHeader } from "@/components/PageHeader";
import { LoadingCard, ErrorCard } from "@/components/states";
import { Card, CardContent } from "@/components/ui/card";
import { cn } from "@/lib/utils";

/** Friendly i18n label key per event, for the tab bar (literal keys so t() type-checks). */
const EVENT_LABEL = {
  EMAIL_VERIFICATION_CODE: "customizeEventVerificationCode",
  ONBOARDING_INVITATION: "customizeEventInvitation",
  SIGNUP_VERIFICATION: "customizeEventSignup",
} as const satisfies Record<EmailEvent, string>;

/**
 * The Customize admin tab. Its first section is the per-tenant email-template editor (one tab per event); the
 * auth-UI branding editor will join it here as a sibling section. Branding is per-tenant, so the tab lives
 * under org scope in the console.
 */
export default function Customize() {
  const { t } = useTranslation("console");

  const [templates, setTemplates] = useState<EmailTemplate[] | null>(null);
  const [active, setActive] = useState<EmailEvent | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    listEmailTemplates()
      .then((list) => {
        setTemplates(list);
        setActive(list[0]?.event ?? null);
      })
      .catch((e) => setLoadError(errorMessage(e)));
  }, []);

  const selected = templates?.find((template) => template.event === active) ?? null;

  return (
    <div className="space-y-6">
      <PageHeader title={t("customizeTitle")} description={t("customizeDescription")} />

      {loadError ? (
        <ErrorCard message={loadError} />
      ) : !templates || !selected ? (
        <LoadingCard rows={8} />
      ) : (
        <Card>
          <CardContent className="grid gap-6 pt-6">
            <div>
              <p className="mb-1 text-sm font-medium">{t("customizeEmailSection")}</p>
              <p className="mb-3 text-xs text-muted-foreground">{t("customizeEmailSectionHint")}</p>
              <div className="flex flex-wrap gap-1 border-b border-border">
                {templates.map((template) => (
                  <button
                    key={template.event}
                    type="button"
                    onClick={() => setActive(template.event)}
                    className={cn(
                      "-mb-px border-b-2 px-3 py-2 text-sm",
                      template.event === active
                        ? "border-primary font-medium text-foreground"
                        : "border-transparent text-muted-foreground hover:text-foreground",
                    )}
                  >
                    {t(EVENT_LABEL[template.event])}
                  </button>
                ))}
              </div>
            </div>

            <EmailTemplateEditor template={selected} onSaved={setTemplates} />
          </CardContent>
        </Card>
      )}
    </div>
  );
}

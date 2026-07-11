import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ArrowLeft, Building2, Lock } from "lucide-react";
import { Brand } from "@/components/Brand";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

/** Centered authentication shell used by the login / MFA screens. */
export default function AuthLayout({
  title, description, step, org, children, footer, onBack, backLabel,
}: {
  title: string; description?: string; step?: string; org?: string | null; children: ReactNode;
  footer?: ReactNode; onBack?: () => void; backLabel?: string;
}) {
  const { t } = useTranslation("auth");
  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4 py-10">
      <div className="w-full max-w-md">
        <div className="mb-6 flex justify-center"><Brand /></div>
        <Card>
          <CardHeader className="space-y-1">
            {onBack && (
              <button type="button" onClick={onBack}
                      className="mb-1 -ml-1 inline-flex w-fit items-center gap-1 rounded-md px-1 py-0.5 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground">
                <ArrowLeft className="size-4" /> {backLabel ?? t("layoutBack")}
              </button>
            )}
            {org && (
              <div className="mb-1 inline-flex w-fit items-center gap-1.5 rounded-full border bg-muted/60 px-2.5 py-1 text-xs font-medium text-foreground">
                <Building2 className="size-3.5 text-primary" /> {org}
              </div>
            )}
            {step && (
              <div className="mb-1 flex items-center gap-1.5 text-xs font-medium uppercase tracking-wider text-primary">
                <Lock className="size-3" /> {step}
              </div>
            )}
            <CardTitle className="text-xl">{title}</CardTitle>
            {description && <CardDescription>{description}</CardDescription>}
          </CardHeader>
          <CardContent>{children}</CardContent>
        </Card>
        {footer && <div className="mt-4 text-center text-sm text-muted-foreground">{footer}</div>}
        <p className="mt-6 text-center text-xs text-muted-foreground">{t("layoutSecuredBy")}</p>
      </div>
    </div>
  );
}

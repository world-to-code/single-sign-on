import { type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ArrowLeft, Building2, Lock } from "lucide-react";
import { Brand } from "@/components/Brand";
import { LanguageToggle } from "@/components/layout/LanguageToggle";
import { ThemeToggle } from "@/components/layout/ThemeToggle";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { useBranding } from "@/components/BrandingProvider";
import { cn } from "@/lib/utils";

/** Centered authentication shell used by the login / MFA screens. */
export default function AuthLayout({
  title, description, step, org, children, footer, onBack, backLabel, wide = false,
}: {
  title: string; description?: string; step?: string; org?: string | null; children: ReactNode;
  footer?: ReactNode; onBack?: () => void; backLabel?: string;
  /** A form's width suits a form. A screen that lists things to READ needs the extra measure. */
  wide?: boolean;
}) {
  const { t } = useTranslation("auth");
  // Shared with the console, the portal and the splash: one fetch, one answer, everywhere.
  const branding = useBranding();
  return (
    <div className="relative flex min-h-screen items-center justify-center bg-background px-4 py-10">
      {/* Pre-login language/theme switch — a signed-out visitor still needs to pick their language. */}
      <div className="absolute right-4 top-4 flex items-center gap-1">
        <LanguageToggle iconOnly />
        <ThemeToggle iconOnly />
      </div>
      <div className={cn("w-full", wide ? "max-w-lg" : "max-w-md")}>
        <div className="mb-6 flex justify-center">
          <Brand logoUrl={branding.logoUrl} name={branding.productName} />
        </div>
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

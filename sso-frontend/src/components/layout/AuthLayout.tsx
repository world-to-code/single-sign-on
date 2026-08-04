import { type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ArrowLeft, Building2, Lock } from "lucide-react";
import { Brand } from "@/components/Brand";
import { LanguageToggle } from "@/components/layout/LanguageToggle";
import { ThemeToggle } from "@/components/layout/ThemeToggle";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { useBranding, useBrandMark } from "@/components/BrandingProvider";
import { cn } from "@/lib/utils";

/**
 * Authentication shell used by the login / MFA / step-up / consent screens.
 *
 * <p>The tenant chooses the arrangement. `CENTERED` is one card on the page background; `SPLIT` puts the form
 * beside a panel carrying the tenant's background image. SPLIT collapses back to centered below `lg` and when
 * no background image is set — a second panel with nothing in it is worse than not having one.
 */
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
  const brand = useBrandMark();
  const split = branding.theme.layout === "SPLIT" && Boolean(branding.theme.backgroundImageUrl);
  return (
    <div className={cn("brand-surface relative min-h-screen", split ? "lg:grid lg:grid-cols-2" : "")}>
      {/* Pre-login language/theme switch — a signed-out visitor still needs to pick their language. */}
      <div className="absolute right-4 top-4 z-10 flex items-center gap-1">
        <LanguageToggle iconOnly />
        <ThemeToggle iconOnly />
      </div>
      {split && (
        <aside className="brand-panel relative hidden lg:flex lg:flex-col lg:justify-end lg:p-12"
               aria-hidden="true">
          <Brand logoUrl={brand.logoUrl} name={brand.name} className="text-white drop-shadow" />
        </aside>
      )}
      <div className={cn("flex min-h-screen items-center justify-center px-4 py-10",
                         split ? "lg:min-h-0" : "")}>
      <div className={cn("w-full", wide ? "max-w-lg" : "max-w-md")}>
        <div className={cn("mb-6 flex justify-center", split ? "lg:hidden" : "")}>
          <Brand logoUrl={brand.logoUrl} name={brand.name} />
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
    </div>
  );
}

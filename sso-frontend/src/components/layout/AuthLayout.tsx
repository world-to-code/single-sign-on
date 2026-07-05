import type { ReactNode } from "react";
import { ArrowLeft, Building2, Lock } from "lucide-react";
import { Brand } from "@/components/Brand";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

/** Centered authentication shell used by the login / MFA screens. */
export default function AuthLayout({
  title, description, step, org, children, footer, onBack, backLabel = "Back",
}: {
  title: string; description?: string; step?: string; org?: string | null; children: ReactNode;
  footer?: ReactNode; onBack?: () => void; backLabel?: string;
}) {
  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-background px-4 py-10">
      {/* decorative backdrop */}
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(60rem_40rem_at_50%_-10%,hsl(var(--primary)/0.12),transparent)]" />
      <div className="pointer-events-none absolute inset-0 [background-image:linear-gradient(to_right,hsl(var(--border)/0.4)_1px,transparent_1px),linear-gradient(to_bottom,hsl(var(--border)/0.4)_1px,transparent_1px)] [background-size:36px_36px] [mask-image:radial-gradient(40rem_30rem_at_50%_0%,black,transparent)]" />

      <div className="relative w-full max-w-md">
        <div className="mb-6 flex justify-center"><Brand /></div>
        <Card className="shadow-xl">
          <CardHeader className="space-y-1">
            {onBack && (
              <button type="button" onClick={onBack}
                      className="mb-1 -ml-1 inline-flex w-fit items-center gap-1 rounded-md px-1 py-0.5 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground">
                <ArrowLeft className="size-4" /> {backLabel}
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
        <p className="mt-6 text-center text-xs text-muted-foreground">Secured by Mini SSO · single-node Identity Provider</p>
      </div>
    </div>
  );
}

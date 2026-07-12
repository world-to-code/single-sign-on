import { useTranslation } from "react-i18next";
import { ShieldCheck } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * The product mark — the shield glyph in its ink square. Used ALONE as the shell's sidebar mark (the
 * same glyph in the user portal and the admin console; only the label beside it names the context), and
 * with the wordmark on the auth/marketing screens.
 */
export function BrandMark({ className, title }: { className?: string; title?: string }) {
  return (
    <span
      title={title}
      className={cn("flex size-9 shrink-0 items-center justify-center rounded-lg bg-ink text-bg shadow-sm", className)}
    >
      <ShieldCheck className="size-5" />
    </span>
  );
}

/** Product wordmark: the brand mark + "Mini SSO". */
export function Brand({ className }: { className?: string }) {
  const { t } = useTranslation();
  return (
    <div className={cn("flex items-center gap-2.5", className)}>
      <BrandMark />
      <div className="leading-tight">
        <div className="text-sm font-semibold tracking-tight">{t("appName")}</div>
        <div className="text-[11px] text-muted-foreground">{t("brandSubtitle")}</div>
      </div>
    </div>
  );
}

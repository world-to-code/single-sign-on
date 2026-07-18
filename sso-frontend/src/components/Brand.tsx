import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ShieldCheck } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * The product mark — a tenant's logo image when one is set, else the shield glyph in its ink square. Used
 * ALONE as the shell's sidebar mark, and with the wordmark on the auth/marketing screens. A broken logo URL
 * falls back to the glyph.
 */
export function BrandMark({ className, title, logoUrl }: {
  className?: string;
  title?: string;
  logoUrl?: string | null;
}) {
  const [failed, setFailed] = useState(false);
  if (logoUrl && !failed) {
    return (
      <img
        src={logoUrl}
        alt=""
        title={title}
        onError={() => setFailed(true)}
        className={cn("size-9 shrink-0 rounded-lg object-contain", className)}
      />
    );
  }
  return (
    <span
      title={title}
      className={cn("flex size-9 shrink-0 items-center justify-center rounded-lg bg-ink text-bg shadow-sm", className)}
    >
      <ShieldCheck className="size-5" />
    </span>
  );
}

/** Product wordmark: the brand mark + product name ("Mini SSO" by default, a tenant's name when branded). */
export function Brand({ className, logoUrl, name }: {
  className?: string;
  logoUrl?: string | null;
  name?: string | null;
}) {
  const { t } = useTranslation();
  return (
    <div className={cn("flex items-center gap-2.5", className)}>
      <BrandMark logoUrl={logoUrl} />
      <div className="leading-tight">
        <div className="text-sm font-semibold tracking-tight">{name || t("appName")}</div>
        <div className="text-[11px] text-muted-foreground">{t("brandSubtitle")}</div>
      </div>
    </div>
  );
}

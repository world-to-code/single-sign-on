import { useTranslation } from "react-i18next";
import { Loader2 } from "lucide-react";
import { Brand } from "@/components/Brand";
import { useBrandMark } from "@/components/BrandingProvider";

/**
 * Full-screen branded splash shown while the session is being probed — a flat, on-brand first paint
 * rather than a bare "Loading…" line.
 */
export default function LoadingScreen() {
  const { t } = useTranslation();
  const brand = useBrandMark();
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-7 bg-background">
      <div className="flex flex-col items-center gap-4">
        <Brand logoUrl={brand.logoUrl} name={brand.name} />
        <Loader2 className="size-5 animate-spin text-primary" aria-label={t("loading")} />
      </div>
    </div>
  );
}

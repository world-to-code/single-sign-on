import { useTranslation } from "react-i18next";
import { Languages } from "lucide-react";
import { setLocale, type Locale } from "@/lib/prefs";
import { cn } from "@/lib/utils";

/**
 * Switches the UI between Korean and English, persisting to localStorage. i18next's languageChanged
 * handler (src/i18n) updates <html lang> so the CSS [lang] rules follow. The visible label always
 * names the OTHER language — the one you switch to — via the `nav.language` key, which is "English"
 * in the Korean bundle and "한국어" in the English one.
 */
export function LanguageToggle({ iconOnly = false }: { iconOnly?: boolean }) {
  const { t, i18n } = useTranslation("nav");

  const toggle = () => {
    const next: Locale = i18n.language.startsWith("ko") ? "en" : "ko";
    setLocale(next);
    void i18n.changeLanguage(next);
  };

  const label = t("language");

  return (
    <button
      type="button"
      onClick={toggle}
      title={iconOnly ? label : undefined}
      aria-label={label}
      className={cn(
        "flex h-9 items-center justify-center gap-2 rounded-lg text-xs font-semibold text-muted transition-colors hover:bg-sunken hover:text-ink",
        iconOnly ? "w-9" : "flex-1",
      )}
    >
      <Languages className="size-4 shrink-0" />
      {!iconOnly && <span>{label}</span>}
    </button>
  );
}

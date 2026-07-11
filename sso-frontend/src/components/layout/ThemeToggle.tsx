import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Moon, Sun } from "lucide-react";
import { applyTheme, resolvedTheme, setTheme, type Theme } from "@/lib/prefs";
import { cn } from "@/lib/utils";

/**
 * Flips between light and dark, persisting to localStorage and stamping <html data-theme>.
 * `iconOnly` is the collapsed-rail variant (icon centred, label as a tooltip).
 */
export function ThemeToggle({ iconOnly = false }: { iconOnly?: boolean }) {
  const { t } = useTranslation("nav");
  const [theme, setLocal] = useState<Theme>(resolvedTheme());

  const toggle = () => {
    const next: Theme = theme === "dark" ? "light" : "dark";
    setLocal(next);
    setTheme(next);
    applyTheme(next);
  };

  const nextLabel = theme === "dark" ? t("themeLight") : t("themeDark");

  return (
    <button
      type="button"
      onClick={toggle}
      title={iconOnly ? nextLabel : undefined}
      aria-label={`${t("theme")}: ${nextLabel}`}
      className={cn(
        "flex h-9 items-center justify-center gap-2 rounded-lg text-xs font-semibold text-muted transition-colors hover:bg-sunken hover:text-ink",
        iconOnly ? "w-9" : "flex-1",
      )}
    >
      {theme === "dark" ? <Sun className="size-4 shrink-0" /> : <Moon className="size-4 shrink-0" />}
      {!iconOnly && <span>{t("theme")}</span>}
    </button>
  );
}

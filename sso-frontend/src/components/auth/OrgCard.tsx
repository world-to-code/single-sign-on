import { useTranslation } from "react-i18next";
import { ArrowRight, Building2, Loader2, X } from "lucide-react";
import { Button } from "@/components/ui/button";

/**
 * One remembered organization on the sign-in entry screen: tap the card to continue into it, or drop it from
 * the list. The remove control is a SIBLING of the card button, not nested inside it — a button within a
 * button is invalid markup and swallows the inner click.
 */
export function OrgCard({
  slug, disabled, pending, onSelect, onForget,
}: {
  slug: string;
  /** Another card is mid-flight; this one is not clickable but is not the one loading. */
  disabled: boolean;
  /** This card is the one resolving — show the spinner here. */
  pending: boolean;
  onSelect: () => void;
  onForget: () => void;
}) {
  const { t } = useTranslation("auth");
  return (
    <div className="flex items-center gap-1">
      <button
        type="button"
        onClick={onSelect}
        disabled={disabled}
        className="flex min-w-0 flex-1 items-center gap-3 rounded-lg border bg-card p-3 text-left transition-colors hover:bg-accent disabled:opacity-60"
      >
        <span className="flex size-9 shrink-0 items-center justify-center rounded-md bg-ink text-bg">
          <Building2 className="size-4" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-medium">{slug}</span>
          <span className="block text-xs text-muted-foreground">{t("orgContinueToThis")}</span>
        </span>
        {pending
          ? <Loader2 className="size-4 shrink-0 animate-spin" />
          : <ArrowRight className="size-4 shrink-0 text-muted-foreground" />}
      </button>
      <Button
        type="button"
        variant="ghost"
        size="icon"
        disabled={disabled}
        onClick={onForget}
        aria-label={t("orgForget", { slug })}
      >
        <X className="size-4" />
      </Button>
    </div>
  );
}

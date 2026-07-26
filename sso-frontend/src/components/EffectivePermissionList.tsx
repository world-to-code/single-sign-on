import { useTranslation } from "react-i18next";
import { Badge } from "@/components/ui/badge";

type Props = {
  effective: string[];
  denied: string[];
};

/**
 * The permissions a user actually holds (deny-applied) plus, when any exist, the ones a grant hands out but a
 * deny removed — the "why is this permission absent" answer. Presentational: the caller wraps it in a card.
 */
export function EffectivePermissionList({ effective, denied }: Props) {
  const { t } = useTranslation("console");
  return (
    <>
      {effective.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("none")}</p>
      ) : (
        <div className="flex flex-wrap gap-1">
          {effective.map((p) => (
            <Badge key={p} variant="muted" className="font-mono text-xs">{p}</Badge>
          ))}
        </div>
      )}
      {denied.length > 0 && (
        <div className="mt-4 border-t border-border pt-3">
          <p className="mb-1.5 text-xs font-medium text-muted-foreground">{t("userDetailDeniedPerms")}</p>
          <div className="flex flex-wrap gap-1">
            {denied.map((p) => (
              <Badge key={p} variant="destructive" className="font-mono text-xs line-through"
                     title={t("userDetailDeniedPermsHint")}>{p}</Badge>
            ))}
          </div>
        </div>
      )}
    </>
  );
}

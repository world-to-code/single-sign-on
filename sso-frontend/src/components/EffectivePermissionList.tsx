import { useTranslation } from "react-i18next";
import { Badge } from "@/components/ui/badge";
import type { HeldPermission, WithheldPermission } from "@/users";

type Props = {
  effective: HeldPermission[];
  denied: WithheldPermission[];
};

/**
 * The permissions a user actually holds (deny-applied) plus, when any exist, the ones that were withheld and
 * the LEVEL that withheld each. The level is the actionable half: "you do not have user:delete" is not
 * something an administrator can act on, "a deny on one of their roles removed it" is. Presentational: the
 * caller wraps it in a card.
 */
export function EffectivePermissionList({ effective, denied }: Props) {
  const { t } = useTranslation("console");

  /** Where the permission came from, and — when a group delegates it — where it has to be removed. */
  const provenanceOf = (held: HeldPermission) => {
    if (held.conferredBy.length === 0) return undefined;
    const roles = t("userDetailConferredBy", { roles: held.conferredBy.join(", ") });
    return held.viaGroups.length === 0
      ? roles
      : `${roles} · ${t("userDetailDelegatedByGroup", { groups: held.viaGroups.join(", ") })}`;
  };
  return (
    <>
      {effective.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("none")}</p>
      ) : (
        <div className="flex flex-wrap gap-1">
          {effective.map((held) => (
            <Badge key={held.permission} variant="muted" className="font-mono text-xs"
                   title={provenanceOf(held)}>
              {held.permission}
            </Badge>
          ))}
        </div>
      )}
      {denied.length > 0 && (
        <div className="mt-4 border-t border-border pt-3">
          <p className="mb-1.5 text-xs font-medium text-muted-foreground">{t("userDetailDeniedPerms")}</p>
          <div className="flex flex-wrap gap-1">
            {denied.map((withheld) => (
              <Badge key={withheld.permission} variant="destructive"
                     className="font-mono text-xs line-through"
                     title={t(`decisionReason_${withheld.withheldBy}`, {
                       defaultValue: t("userDetailDeniedPermsHint"),
                     })}>
                {withheld.permission}
              </Badge>
            ))}
          </div>
        </div>
      )}
    </>
  );
}

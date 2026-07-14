import { useTranslation } from "react-i18next";
import { Badge } from "@/components/ui/badge";

/**
 * A compact, non-overflowing summary of a permission set for dense list rows: the first few permissions as
 * badges, then a muted "+N more" count. A role can carry many permissions, and rendering all of them wraps a
 * table row into a tall block — this caps the width while keeping the row scannable. The full set lives on
 * the role's detail page.
 */
export function PermissionSummary({ permissions, max = 4 }: { permissions: string[]; max?: number }) {
  const { t } = useTranslation("console");
  if (permissions.length === 0) {
    return <span className="text-muted-foreground">—</span>;
  }
  const shown = permissions.slice(0, max);
  const extra = permissions.length - shown.length;
  return (
    <div className="flex flex-wrap items-center gap-1">
      {shown.map((p) => <Badge key={p} variant="outline" className="font-mono text-xs">{p}</Badge>)}
      {extra > 0 && <Badge variant="muted" className="text-xs">{t("permsMore", { count: extra })}</Badge>}
    </div>
  );
}

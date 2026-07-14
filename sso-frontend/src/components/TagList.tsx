import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Badge } from "@/components/ui/badge";

type TagVariant = "outline" | "secondary" | "muted";

/**
 * A compact, non-overflowing display of a string set for dense table rows: the first few as badges, then a
 * muted "+N more" count. Rendering an unbounded list (permissions, roles, scopes, assignees) wraps a table
 * row into a tall block and breaks the layout; this caps the width while keeping the row scannable. The full
 * set belongs on the entity's detail page.
 */
export function TagList({ items, max = 4, variant = "outline", mono = false, empty }: {
  items: string[];
  max?: number;
  variant?: TagVariant;
  mono?: boolean;
  empty?: ReactNode;
}) {
  const { t } = useTranslation("console");
  if (items.length === 0) {
    return <>{empty ?? <span className="text-muted-foreground">—</span>}</>;
  }
  const shown = items.slice(0, max);
  const extra = items.length - shown.length;
  return (
    <div className="flex flex-wrap items-center gap-1">
      {shown.map((item) => (
        <Badge key={item} variant={variant} className={mono ? "font-mono text-xs" : "text-xs"}>{item}</Badge>
      ))}
      {extra > 0 && <Badge variant="muted" className="text-xs">{t("permsMore", { count: extra })}</Badge>}
    </div>
  );
}

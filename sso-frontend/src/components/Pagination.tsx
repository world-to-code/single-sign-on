import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";

/**
 * Previous/Next pager for admin list views. Renders nothing when everything fits on a single page.
 * {@code page} is 0-based; the label shows 1-based numbers plus the total row count.
 */
export function Pagination({ page, size, total, onPage }: {
  page: number;
  size: number;
  total: number;
  onPage: (page: number) => void;
}) {
  const { t } = useTranslation();
  if (total <= size) return null;
  const lastPage = Math.max(0, Math.ceil(total / size) - 1);
  return (
    <div className="mt-3 flex items-center justify-between text-sm">
      <span className="text-muted-foreground">
        {t("paginationSummary", { page: page + 1, pages: lastPage + 1, total })}
      </span>
      <div className="flex gap-2">
        <Button variant="outline" size="sm" disabled={page <= 0} onClick={() => onPage(page - 1)}>
          {t("paginationPrevious")}
        </Button>
        <Button variant="outline" size="sm" disabled={page >= lastPage} onClick={() => onPage(page + 1)}>
          {t("paginationNext")}
        </Button>
      </div>
    </div>
  );
}

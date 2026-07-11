import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ScrollText } from "lucide-react";
import { usePaginated } from "@/usePaginated";
import { Pagination } from "@/components/Pagination";
import { PageHeader } from "@/components/PageHeader";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";
import { formatDateTime } from "@/lib/format";

interface AuditEvent {
  id: number;
  occurredAt: string;
  principal: string | null;
  type: string;
  category: string;
  success: boolean;
  detail: string | null;
}

const CATEGORIES = [
  "AUTHENTICATION", "AUTHORIZATION", "SESSION", "ACCESS", "APP_ACCESS", "USER_ACTION", "ADMIN", "SYSTEM",
] as const;

export default function Audit() {
  const { t, i18n } = useTranslation(["console", "states"]);
  const [category, setCategory] = useState<string>("ALL");
  const path = category === "ALL" ? "/api/admin/audit" : `/api/admin/audit?category=${category}`;
  const { items, total, page, setPage, size, error } = usePaginated<AuditEvent>(path);

  // Translate a category enum to a label; unknown values fall back to the raw enum.
  const catLabel = (c: string) => (c === "ALL" ? t("auditCategoryAll") : t(`auditCat${c}` as "auditCatSYSTEM", { defaultValue: c }));

  const description = total
    ? category === "ALL"
      ? t("auditCount", { count: total })
      : t("auditCountScoped", { count: total, category: catLabel(category) })
    : t("auditDescription");

  return (
    <>
      <PageHeader title={t("auditTitle")} description={description} />

      <div className="mb-4 flex flex-wrap gap-1 border-b">
        {(["ALL", ...CATEGORIES] as string[]).map((c) => (
          <button
            key={c}
            onClick={() => setCategory(c)}
            className={`-mb-px whitespace-nowrap border-b-2 px-4 py-2 text-sm font-medium ${
              category === c
                ? "border-primary text-foreground"
                : "border-transparent text-muted-foreground hover:text-foreground"
            }`}
          >
            {catLabel(c)}
          </button>
        ))}
      </div>

      <DataList
        data={items}
        error={error}
        isEmpty={(events) => events.length === 0}
        empty={<EmptyState icon={<ScrollText className="size-8" />} title={t("states:auditEmptyTitle")} hint={t("states:auditEmptyHint")} />}
      >
        {(events) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("auditColTime")}</TableHead>
                <TableHead>{t("auditColCategory")}</TableHead>
                <TableHead>{t("auditColType")}</TableHead>
                <TableHead>{t("auditColPrincipal")}</TableHead>
                <TableHead>{t("auditColResult")}</TableHead>
                <TableHead>{t("auditColDetail")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {events.map((e) => (
                <TableRow key={e.id}>
                  <TableCell className="whitespace-nowrap text-muted-foreground">{formatDateTime(e.occurredAt, i18n.language)}</TableCell>
                  <TableCell><Badge variant="muted" className="text-xs">{catLabel(e.category ?? "SYSTEM")}</Badge></TableCell>
                  <TableCell className="font-medium">{e.type}</TableCell>
                  <TableCell>{e.principal ?? <span className="text-muted-foreground">—</span>}</TableCell>
                  <TableCell><Badge variant={e.success ? "success" : "destructive"}>{e.success ? t("auditResultOk") : t("auditResultFail")}</Badge></TableCell>
                  <TableCell className="max-w-md truncate font-mono text-xs text-muted-foreground" title={e.detail ?? undefined}>{e.detail ?? "—"}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>
      <Pagination page={page} size={size} total={total} onPage={setPage} />
    </>
  );
}

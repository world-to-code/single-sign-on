import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ScrollText } from "lucide-react";
import { usePaginated } from "@/usePaginated";
import { Pagination } from "@/components/Pagination";
import { PageHeader } from "@/components/PageHeader";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";

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

const label = (c: string) => c.replace(/_/g, " ").toLowerCase();

export default function Audit() {
  const { t } = useTranslation("states");
  const [category, setCategory] = useState<string>("ALL");
  const path = category === "ALL" ? "/api/admin/audit" : `/api/admin/audit?category=${category}`;
  const { items, total, page, setPage, size, error } = usePaginated<AuditEvent>(path);

  return (
    <>
      <PageHeader
        title="Audit Log"
        description={total ? `${total} recent event${total === 1 ? "" : "s"}${category === "ALL" ? "" : ` in ${label(category)}`}.` : "Recent security events."}
      />

      <div className="mb-4 flex flex-wrap gap-1 border-b">
        {(["ALL", ...CATEGORIES] as string[]).map((c) => (
          <button
            key={c}
            onClick={() => setCategory(c)}
            className={`-mb-px whitespace-nowrap border-b-2 px-4 py-2 text-sm font-medium capitalize ${
              category === c
                ? "border-primary text-foreground"
                : "border-transparent text-muted-foreground hover:text-foreground"
            }`}
          >
            {c === "ALL" ? "All" : label(c)}
          </button>
        ))}
      </div>

      <DataList
        data={items}
        error={error}
        isEmpty={(events) => events.length === 0}
        empty={<EmptyState icon={<ScrollText className="size-8" />} title={t("auditEmptyTitle")} hint={t("auditEmptyHint")} />}
      >
        {(events) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Time</TableHead>
                <TableHead>Category</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Principal</TableHead>
                <TableHead>Result</TableHead>
                <TableHead>Detail</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {events.map((e) => (
                <TableRow key={e.id}>
                  <TableCell className="whitespace-nowrap text-muted-foreground">{new Date(e.occurredAt).toLocaleString()}</TableCell>
                  <TableCell><Badge variant="muted" className="text-xs">{label(e.category ?? "system")}</Badge></TableCell>
                  <TableCell className="font-medium">{e.type}</TableCell>
                  <TableCell>{e.principal ?? <span className="text-muted-foreground">—</span>}</TableCell>
                  <TableCell><Badge variant={e.success ? "success" : "destructive"}>{e.success ? "ok" : "fail"}</Badge></TableCell>
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

import { Fragment, useState } from "react";
import { useTranslation } from "react-i18next";
import { ScrollText, ChevronRight, ChevronDown } from "lucide-react";
import { usePaginated } from "@/usePaginated";
import { Pagination } from "@/components/Pagination";
import { PageHeader } from "@/components/PageHeader";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";
import { formatDateTime } from "@/lib/format";

type Severity = "INFO" | "WARNING" | "CRITICAL";

interface AuditEvent {
  id: number;
  occurredAt: string;
  principal: string | null;
  type: string;
  category: string;
  success: boolean;
  detail: string | null;
  subjectType: string | null;
  subjectId: string | null;
  actorType: string | null;
  actorId: string | null;
  actorEmail: string | null;
  actorDisplay: string | null;
  remoteIp: string | null;
  userAgent: string | null;
  device: string | null;
  requestId: string | null;
  reason: string | null;
  severity: Severity | null;
}

const CATEGORIES = [
  "AUTHENTICATION", "AUTHORIZATION", "SESSION", "ACCESS", "APP_ACCESS", "USER_ACTION", "ADMIN", "SYSTEM",
] as const;

const SEVERITY_VARIANT: Record<Severity, "muted" | "warn" | "destructive"> = {
  INFO: "muted",
  WARNING: "warn",
  CRITICAL: "destructive",
};

const COLUMN_COUNT = 8;

export default function Audit() {
  const { t, i18n } = useTranslation(["console", "states"]);
  const [category, setCategory] = useState<string>("ALL");
  const [expanded, setExpanded] = useState<number | null>(null);
  const path = category === "ALL" ? "/api/admin/audit" : `/api/admin/audit?category=${category}`;
  const { items, total, page, setPage, size, error } = usePaginated<AuditEvent>(path);

  // Translate a category enum to a label; unknown values fall back to the raw enum.
  const catLabel = (c: string) => (c === "ALL" ? t("auditCategoryAll") : t(`auditCat${c}` as "auditCatSYSTEM", { defaultValue: c }));
  const sevLabel = (s: Severity) => t(`auditSev${s}` as "auditSevINFO", { defaultValue: s });

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
                <TableHead className="w-8" />
                <TableHead>{t("auditColTime")}</TableHead>
                <TableHead>{t("auditColSeverity")}</TableHead>
                <TableHead>{t("auditColType")}</TableHead>
                <TableHead>{t("auditColActor")}</TableHead>
                <TableHead>{t("auditColIp")}</TableHead>
                <TableHead>{t("auditColResult")}</TableHead>
                <TableHead>{t("auditColCategory")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {events.map((e) => (
                <Fragment key={e.id}>
                  <TableRow
                    className="cursor-pointer"
                    onClick={() => setExpanded((cur) => (cur === e.id ? null : e.id))}
                  >
                    <TableCell className="text-muted-foreground">
                      {expanded === e.id ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">{formatDateTime(e.occurredAt, i18n.language)}</TableCell>
                    <TableCell>
                      <Badge variant={e.severity ? SEVERITY_VARIANT[e.severity] : "muted"} className="text-xs">
                        {e.severity ? sevLabel(e.severity) : "—"}
                      </Badge>
                    </TableCell>
                    <TableCell className="font-medium">{e.type}</TableCell>
                    <TableCell><ActorCell event={e} /></TableCell>
                    <TableCell className="whitespace-nowrap font-mono text-xs text-muted-foreground">{e.remoteIp ?? "—"}</TableCell>
                    <TableCell><Badge variant={e.success ? "success" : "destructive"}>{e.success ? t("auditResultOk") : t("auditResultFail")}</Badge></TableCell>
                    <TableCell><Badge variant="muted" className="text-xs">{catLabel(e.category ?? "SYSTEM")}</Badge></TableCell>
                  </TableRow>
                  {expanded === e.id && (
                    <TableRow className="hover:bg-transparent">
                      <TableCell colSpan={COLUMN_COUNT} className="bg-muted/30">
                        <AuditDetail event={e} />
                      </TableCell>
                    </TableRow>
                  )}
                </Fragment>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>
      <Pagination page={page} size={size} total={total} onPage={setPage} />
    </>
  );
}

/** Actor identity: the resolved display name over the account email, falling back to the raw principal. */
function ActorCell({ event }: { event: AuditEvent }) {
  const name = event.actorDisplay ?? event.principal;
  if (!name) return <span className="text-muted-foreground">—</span>;
  return (
    <div className="leading-tight">
      <div>{name}</div>
      {event.actorEmail && <div className="text-xs text-muted-foreground">{event.actorEmail}</div>}
    </div>
  );
}

/** The full structured event for one row: the fields that don't fit the table, shown on expand. */
function AuditDetail({ event }: { event: AuditEvent }) {
  const { t } = useTranslation("console");
  const subject =
    event.subjectType && event.subjectType !== "NONE"
      ? `${event.subjectType}${event.subjectId ? ` · ${event.subjectId}` : ""}`
      : null;
  const rows: Array<[string, string | null]> = [
    [t("auditColDevice"), event.device],
    [t("auditFieldUserAgent"), event.userAgent],
    [t("auditFieldSubject"), subject],
    [t("auditColReason"), event.reason],
    [t("auditFieldRequestId"), event.requestId],
    [t("auditColDetail"), event.detail],
  ];
  const present = rows.filter(([, value]) => value);
  if (present.length === 0) return <p className="text-sm text-muted-foreground">{t("auditDetailNone")}</p>;
  return (
    <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-sm">
      {present.map(([label, value]) => (
        <Fragment key={label}>
          <dt className="whitespace-nowrap text-muted-foreground">{label}</dt>
          <dd className="break-all font-mono text-xs">{value}</dd>
        </Fragment>
      ))}
    </dl>
  );
}

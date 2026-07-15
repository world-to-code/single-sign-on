import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { errorMessage } from "@/api";
import { loadPolicyBindings, type BindingAxis, type BindingSubjectKind, type PolicyBindingRow } from "@/bindings";
import { PageHeader } from "@/components/PageHeader";
import { DataList, EmptyState } from "@/components/states";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

/**
 * A single read-only overview of every policy binding — app × subject → auth and/or session policy —
 * aggregated from the surfaces that each own a slice (applications, auth policies, session policies, portal
 * settings) and MERGED so each app × subject appears once with both policies. Each policy name links to the
 * surface that edits it, so this stays a truthful mirror without duplicating those (security-critical) writes.
 */
export default function Bindings() {
  const { t } = useTranslation(["console", "states"]);
  const [rows, setRows] = useState<PolicyBindingRow[] | null>(null);
  const [partial, setPartial] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [cause, setCause] = useState<unknown>(null);
  const [query, setQuery] = useState("");

  const reload = useCallback(() => {
    setRows(null);
    setError(null);
    setCause(null);
    loadPolicyBindings()
      .then((result) => {
        setRows(result.rows);
        setPartial(result.partial);
      })
      .catch((e) => {
        setError(errorMessage(e));
        setCause(e);
      });
  }, []);

  useEffect(reload, [reload]);

  const subjectKindLabel = (kind: BindingSubjectKind) =>
    kind === "USER" ? t("bindingsSubjectUser")
      : kind === "GROUP" ? t("bindingsSubjectGroup")
        : kind === "ROLE" ? t("bindingsSubjectRole") : "";
  const appLabel = (row: PolicyBindingRow) =>
    row.appType !== "PORTAL" ? row.appName
      : row.appName === "admin" ? t("bindingsPortalAdmin") : t("bindingsPortalUser");

  const needle = query.trim().toLowerCase();
  const filtered = (rows ?? []).filter((r) =>
    !needle
    || appLabel(r).toLowerCase().includes(needle)
    || r.subjectLabel.toLowerCase().includes(needle)
    || (r.auth?.policyName ?? "").toLowerCase().includes(needle)
    || (r.session?.policyName ?? "").toLowerCase().includes(needle),
  );

  const policyCell = (axis: BindingAxis | null) =>
    axis
      ? <Link to={axis.editTo} className="text-primary hover:underline">{axis.policyName}</Link>
      : <span className="text-muted-foreground">—</span>;

  return (
    <div className="space-y-5">
      <PageHeader title={t("bindingsTitle")} description={t("bindingsDescription")} />

      {partial && rows !== null && (
        <Alert>
          <AlertDescription>{t("bindingsPartial")}</AlertDescription>
        </Alert>
      )}

      <Input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder={t("bindingsSearchPlaceholder")}
        aria-label={t("bindingsSearchPlaceholder")}
        className="sm:max-w-sm"
      />

      <DataList
        data={rows}
        error={error}
        cause={cause}
        onRetry={reload}
        isEmpty={() => filtered.length === 0}
        empty={
          needle
            ? <EmptyState title={t("states:bindingsNoMatchTitle")} hint={t("states:bindingsNoMatchHint")} />
            : <EmptyState title={t("states:bindingsEmptyTitle")} hint={t("states:bindingsEmptyHint")} />
        }
      >
        {() => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("bindingsColApp")}</TableHead>
                <TableHead>{t("bindingsColSubject")}</TableHead>
                <TableHead>{t("bindingsColAuthPolicy")}</TableHead>
                <TableHead>{t("bindingsColSessionPolicy")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filtered.map((r) => (
                <TableRow key={r.key}>
                  <TableCell className="font-medium">
                    <div className="flex flex-wrap items-center gap-1.5">
                      {appLabel(r)}
                      <Badge variant="outline">{r.appType === "PORTAL" ? t("bindingsAppPortal") : r.appType}</Badge>
                    </div>
                  </TableCell>
                  <TableCell>
                    {r.subjectKind === "ALL" ? (
                      <span className="text-muted-foreground">{t("bindingsSubjectAll")}</span>
                    ) : (
                      <div className="flex flex-wrap items-center gap-1.5">
                        {r.subjectKind !== "OTHER" && <Badge variant="muted">{subjectKindLabel(r.subjectKind)}</Badge>}
                        {r.subjectLabel}
                      </div>
                    )}
                  </TableCell>
                  <TableCell>{policyCell(r.auth)}</TableCell>
                  <TableCell>{policyCell(r.session)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>
    </div>
  );
}

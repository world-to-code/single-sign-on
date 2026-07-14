import { useEffect, useState } from "react";
import { Trans, useTranslation } from "react-i18next";
import type { FormEvent } from "react";
import { Link } from "react-router-dom";
import { Pencil, Plus, Save, Trash2 } from "lucide-react";
import { apiGet, apiPut, errorMessage, type Page } from "../api";
import { PageHeader } from "@/components/PageHeader";
import { TagList } from "@/components/TagList";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Select } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";
import { Field } from "@/components/form/fields";
import { usersByIds } from "@/groups";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";

interface IpRuleWire { zoneId: string; action: string; priority: number }
interface SessionPolicy {
  id: string;
  name: string;
  priority: number;
  enabled: boolean;
  absoluteTimeoutMinutes: number;
  idleTimeoutMinutes: number;
  reauthIntervalMinutes: number;
  reauthFactors: string;
  sensitiveReauthWindowMinutes: number;
  stepUpFactors: string;
  bindClient: boolean;
  maxConcurrentSessions: number;
  rotateOnReauth: boolean;
  cookieSameSite: string;
  assignedUserIds: string[];
  assignedRoleIds: string[];
  ipRules: IpRuleWire[];
}
interface Role { id: string; name: string }

export default function SessionPolicyPage() {
  const { t } = useTranslation(["console", "states"]);
  const confirmDelete = useDeleteConfirm();
  const [policies, setPolicies] = useState<SessionPolicy[] | null>(null);
  const [roles, setRoles] = useState<Role[]>([]);
  const [userNames, setUserNames] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);

  function reload() {
    apiGet<Page<SessionPolicy>>("/api/admin/session-policies?size=100")
      .then((p) => setPolicies(p.items)).catch((e) => setError(errorMessage(e)));
  }
  useEffect(() => {
    reload();
    apiGet<Role[]>("/api/admin/roles").then(setRoles).catch(() => undefined);
  }, []);

  // Resolve the user ids assigned across the loaded policies to names for the table (no all-users load).
  useEffect(() => {
    const ids = [...new Set((policies ?? []).flatMap((p) => p.assignedUserIds))];
    usersByIds(ids)
      .then((sugs) => setUserNames(Object.fromEntries(sugs.map((s) => [s.id, s.label]))))
      .catch(() => undefined);
  }, [policies]);

  async function remove(p: SessionPolicy) {
    await confirmDelete({
      title: t("sessionPolicyDeleteTitle"),
      description: t("sessionPolicyDeleteDescription", { name: p.name }),
      path: `/api/admin/session-policies/${p.id}`,
      onDeleted: reload,
    });
  }

  const roleName = (id: string) => roles.find((r) => r.id === id)?.name ?? id;
  const userName = (id: string) => userNames[id] ?? id;
  const defaultPolicy = policies?.find((p) => p.name === "Default") ?? null;

  return (
    <>
      <PageHeader
        title={t("sessionPolicyTitle")}
        description={t("sessionPolicyDescription")}
        actions={<Button asChild><Link to="new"><Plus /> {t("sessionPolicyNew")}</Link></Button>}
      />

      <Alert variant="info" className="mb-4">
        <AlertDescription>
          <Trans t={t} i18nKey="sessionPolicyInfo" components={[<strong key="0" />, <strong key="1" />]} />
        </AlertDescription>
      </Alert>

      <DataList
        data={policies}
        error={error}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState title={t("states:sessionPoliciesEmptyTitle")} hint={t("states:sessionPoliciesEmptyHint")} />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("sessionPolicyColName")}</TableHead>
                <TableHead>{t("sessionPolicyColPriority")}</TableHead>
                <TableHead>{t("sessionPolicyColEnabled")}</TableHead>
                <TableHead>{t("sessionPolicyColTimeouts")}</TableHead>
                <TableHead>{t("sessionPolicyColMaxSessions")}</TableHead>
                <TableHead>{t("sessionPolicyColRoles")}</TableHead>
                <TableHead>{t("sessionPolicyColUsers")}</TableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((p) => (
                <TableRow key={p.id}>
                  <TableCell className="font-medium">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <Link to={p.id} className="hover:underline">{p.name}</Link>
                      {(p.assignedRoleIds.length === 0 && p.assignedUserIds.length === 0) && p.name !== "Default" && (
                        <Badge variant="default">{t("badgeGlobal")}</Badge>
                      )}
                      {p.rotateOnReauth && <Badge variant="muted">{t("sessionPolicyRotateBadge")}</Badge>}
                    </div>
                  </TableCell>
                  <TableCell><Badge variant="muted">{p.priority}</Badge></TableCell>
                  <TableCell>
                    <Badge variant={p.enabled ? "success" : "muted"}>{p.enabled ? t("badgeEnabled") : t("badgeDisabled")}</Badge>
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {p.absoluteTimeoutMinutes}m / {p.idleTimeoutMinutes}m
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {p.maxConcurrentSessions === 0 ? t("sessionPolicyUnlimited") : p.maxConcurrentSessions}
                  </TableCell>
                  <TableCell><TagList items={p.assignedRoleIds.map(roleName)} variant="secondary" /></TableCell>
                  <TableCell><TagList items={p.assignedUserIds.map(userName)} variant="secondary" /></TableCell>
                  <TableCell className="text-right">
                    {p.name !== "Default" ? (
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="icon" asChild><Link to={p.id}><Pencil /></Link></Button>
                        <Button variant="ghost" size="icon" className="text-muted-foreground hover:text-destructive" onClick={() => remove(p)}><Trash2 /></Button>
                      </div>
                    ) : (
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="icon" asChild><Link to={p.id}><Pencil /></Link></Button>
                        <Badge variant="outline">{t("badgeBuiltIn")}</Badge>
                      </div>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>

      {defaultPolicy && <CookieCard policy={defaultPolicy} onSaved={reload} />}
    </>
  );
}

/** Global session-cookie attributes — applied for every session, so they live on the Default policy. */
function CookieCard({ policy, onSaved }: { policy: SessionPolicy; onSaved: () => void }) {
  const { t } = useTranslation("console");
  const [sameSite, setSameSite] = useState(policy.cookieSameSite);
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setSameSite(policy.cookieSameSite);
  }, [policy.cookieSameSite]);

  async function save(event: FormEvent) {
    event.preventDefault();
    setStatus(null); setError(null); setBusy(true);
    try {
      await apiPut(`/api/admin/session-policies/${policy.id}`, {
        name: policy.name,
        priority: policy.priority,
        enabled: policy.enabled,
        absoluteTimeoutMinutes: policy.absoluteTimeoutMinutes,
        idleTimeoutMinutes: policy.idleTimeoutMinutes,
        reauthIntervalMinutes: policy.reauthIntervalMinutes,
        reauthFactors: policy.reauthFactors,
        sensitiveReauthWindowMinutes: policy.sensitiveReauthWindowMinutes,
        stepUpFactors: policy.stepUpFactors,
        bindClient: policy.bindClient,
        maxConcurrentSessions: policy.maxConcurrentSessions,
        rotateOnReauth: policy.rotateOnReauth,
        cookieSameSite: sameSite,
        assignedRoleIds: policy.assignedRoleIds,
        assignedUserIds: policy.assignedUserIds,
        // Echo the zone rules: the update endpoint REPLACES them, so omitting this would silently wipe
        // the Default policy's network rules on every cookie save.
        ipRules: policy.ipRules,
      });
      setStatus(t("sessionPolicyCookieSaved"));
      onSaved();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="mt-6 max-w-xl">
      <CardHeader>
        <CardTitle>{t("sessionPolicyCookieTitle")}</CardTitle>
        <CardDescription>{t("sessionPolicyCookieDescription")}</CardDescription>
      </CardHeader>
      <CardContent>
        {status && <Alert variant="success" className="mb-4"><AlertDescription>{status}</AlertDescription></Alert>}
        {error && <Alert variant="destructive" className="mb-4"><AlertDescription>{error}</AlertDescription></Alert>}
        <form onSubmit={save} className="space-y-4">
          <Field label={t("sessionPolicySameSiteLabel")} hint={t("sessionPolicySameSiteHint")}>
            <Select value={sameSite} onChange={(e) => setSameSite(e.target.value)}>
              <option>Lax</option><option>Strict</option><option>None</option>
            </Select>
          </Field>
          <p className="text-sm text-muted-foreground">
            <Trans t={t} i18nKey="sessionPolicySecureHint" components={[<strong key="0" />, <code key="1" />]} />
          </p>
          <Button type="submit" disabled={busy}><Save /> {t("sessionPolicySaveCookie")}</Button>
        </form>
      </CardContent>
    </Card>
  );
}

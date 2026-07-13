import { useEffect, useState } from "react";
import { Trans, useTranslation } from "react-i18next";
import { AppWindow, Lock, Network, Settings, Trash2, UserPlus, Users as UsersIcon } from "lucide-react";
import { apiGet, apiPost, apiPut, errorMessage, type Page } from "../api";
import { PageHeader } from "@/components/PageHeader";
import { usePaginated } from "@/usePaginated";
import { Pagination } from "@/components/Pagination";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";
import { SearchSelect } from "@/components/SearchSelect";
import { searchGroups, searchUsers } from "@/groups";

interface Application { id: string; type: "OIDC" | "SAML" | "PORTAL"; name: string; launchUrl: string | null; system: boolean; requiredPolicyId: string | null; requiredPolicyName: string | null; }
// The admin console carries two extra, console-only knobs (elevation TTL + entry IP allowlist); the user
// portal settings are just the session policy. Both come back from their respective portal-settings endpoint.
interface PortalSettings { sessionPolicyId: string | null; elevationTokenTtlMinutes?: number; adminAllowedCidrs?: string | null; }
// Which portal a "Portal settings" dialog governs: the admin console vs the end-user portal (distinct bindings).
type PortalKind = "admin" | "user";
const PORTAL_SETTINGS_PATH: Record<PortalKind, string> = {
  admin: "/api/admin/portal-settings",
  user: "/api/admin/portal-settings/user",
};
interface SessionPolicy { id: string; name: string; }

interface Assignment { id: string; subjectType: string; subjectName: string; requiredPolicyId: string | null; }
interface Policy { id: string; name: string; appliesToLogin: boolean; }

export default function Applications() {
  const { t } = useTranslation(["console", "states"]);
  const confirmDelete = useDeleteConfirm();
  const { items: apps, total, page, setPage, size, error, reload } = usePaginated<Application>("/api/admin/applications");
  const [policies, setPolicies] = useState<Policy[]>([]);

  const [active, setActive] = useState<Application | null>(null);
  const [assignments, setAssignments] = useState<Assignment[]>([]);
  const [subjectType, setSubjectType] = useState<"USER" | "GROUP">("GROUP");
  const [subjectId, setSubjectId] = useState("");
  const [pickerKey, setPickerKey] = useState(0); // bump to reset the SearchSelect after add/type change
  const [requiredPolicyId, setRequiredPolicyId] = useState("");
  const [appPolicyId, setAppPolicyId] = useState("");
  const [formError, setFormError] = useState<string | null>(null);

  // Portal session-policy settings (system apps: the admin console and the end-user portal).
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settingsPortal, setSettingsPortal] = useState<PortalKind>("admin");
  const [settings, setSettings] = useState<PortalSettings | null>(null);
  const [settingsError, setSettingsError] = useState<string | null>(null);
  const [settingsSaved, setSettingsSaved] = useState(false);
  const [sessionPolicies, setSessionPolicies] = useState<SessionPolicy[]>([]);

  useEffect(() => {
    apiGet<Page<Policy>>("/api/admin/auth-policies?size=100").then((p) => setPolicies(p.items)).catch(() => undefined);
  }, []);

  // Only app-only policies (Applies-to-login = off) are valid as per-app step-up policies.
  const appOnlyPolicies = policies.filter((p) => !p.appliesToLogin);
  const policyName = (id: string | null) => (id ? policies.find((p) => p.id === id)?.name ?? "policy" : null);

  function loadAssignments(app: Application) {
    apiGet<Assignment[]>(`/api/admin/applications/${app.type.toLowerCase()}/${app.id}/assignments`).then(setAssignments).catch(() => setAssignments([]));
  }
  function manage(app: Application) {
    setFormError(null); setActive(app); setSubjectType("GROUP"); setSubjectId(""); setRequiredPolicyId("");
    setAppPolicyId(app.requiredPolicyId ?? ""); setAssignments([]); setPickerKey((k) => k + 1);
    loadAssignments(app);
  }

  function openSettings(portal: PortalKind) {
    setSettingsPortal(portal);
    setSettingsError(null); setSettingsSaved(false); setSettings(null); setSettingsOpen(true);
    apiGet<PortalSettings>(PORTAL_SETTINGS_PATH[portal]).then(setSettings).catch((e) => setSettingsError(errorMessage(e)));
    apiGet<Page<SessionPolicy>>("/api/admin/session-policies?size=100")
      .then((p) => setSessionPolicies(p.items)).catch(() => undefined);
  }

  async function saveSettings() {
    if (!settings) return;
    setSettingsError(null); setSettingsSaved(false);
    try {
      const saved = await apiPut<PortalSettings>(PORTAL_SETTINGS_PATH[settingsPortal], settings);
      setSettings(saved); setSettingsSaved(true);
    } catch (e) {
      setSettingsError(errorMessage(e));
    }
  }

  async function saveAppPolicy() {
    if (!active) return;
    setFormError(null);
    try {
      await apiPut(`/api/admin/applications/${active.type.toLowerCase()}/${active.id}/policy`, { requiredPolicyId: appPolicyId || null });
      reload();
    } catch (e) {
      setFormError(errorMessage(e));
    }
  }

  async function add() {
    if (!active || !subjectId) return;
    setFormError(null);
    try {
      await apiPost("/api/admin/applications/assignments", {
        appType: active.type, appId: active.id, subjectType, subjectId,
        requiredPolicyId: requiredPolicyId || null,
      });
      setSubjectId(""); setRequiredPolicyId(""); setPickerKey((k) => k + 1);
      loadAssignments(active);
    } catch (e) {
      setFormError(errorMessage(e));
    }
  }

  async function removeAssignment(a: Assignment) {
    await confirmDelete({
      title: t("applicationsRemoveAccessTitle"),
      description: t("applicationsRemoveAccessDescription", { name: a.subjectName, app: active?.name ?? "" }),
      confirmText: t("applicationsRemoveAccessConfirm"),
      path: `/api/admin/applications/assignments/${a.id}`,
      onDeleted: () => { if (active) loadAssignments(active); },
    });
  }

  return (
    <>
      <PageHeader title={t("applicationsTitle")} description={t("applicationsDescription")} />

      <DataList
        data={apps}
        error={error}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState icon={<AppWindow className="size-8" />} title={t("states:applicationsEmptyTitle")} hint={t("states:applicationsEmptyHint")} />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow><TableHead>{t("applicationsColApplication")}</TableHead><TableHead>{t("applicationsColType")}</TableHead><TableHead>{t("applicationsColLaunchUrl")}</TableHead><TableHead className="w-0" /></TableRow>
            </TableHeader>
            <TableBody>
              {items.map((app) => (
                <TableRow key={`${app.type}:${app.id}`}>
                  <TableCell className="font-medium">
                    <span className="inline-flex flex-wrap items-center gap-2">
                      {app.type === "SAML" ? <Network className="size-4 text-muted-foreground" /> : <AppWindow className="size-4 text-muted-foreground" />}
                      {app.name}
                      {app.system && <Badge variant="secondary" title={t("applicationsSystemTitle")}><Lock className="size-3" /> {t("badgeSystem")}</Badge>}
                      {app.requiredPolicyName && <Badge variant="default" title={t("applicationsPolicyRequiredTitle")}><Lock className="size-3" /> {app.requiredPolicyName}</Badge>}
                    </span>
                  </TableCell>
                  <TableCell><Badge variant="muted">{app.type}</Badge></TableCell>
                  <TableCell className="max-w-xs truncate font-mono text-xs text-muted-foreground">{app.launchUrl ?? "—"}</TableCell>
                  <TableCell className="text-right">
                    {app.system
                      ? <Button variant="outline" size="sm" onClick={() => openSettings(app.type === "PORTAL" ? "user" : "admin")}><Settings /> {t("applicationsPortalSettings")}</Button>
                      : <Button variant="outline" size="sm" onClick={() => manage(app)}><UsersIcon /> {t("applicationsManageAccess")}</Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>
      <Pagination page={page} size={size} total={total} onPage={setPage} />

      <Dialog open={!!active} onOpenChange={(o) => { if (!o) setActive(null); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("applicationsManageTitle", { name: active?.name ?? "" })}</DialogTitle>
            <DialogDescription>{t("applicationsManageDescription")}</DialogDescription>
          </DialogHeader>

          {formError && <Alert variant="destructive"><AlertDescription>{formError}</AlertDescription></Alert>}

          <div className="space-y-2 rounded-md border bg-muted/30 p-3">
            <Label>{t("applicationsSignOnPolicy")} <span className="text-muted-foreground">{t("applicationsSignOnPolicyHint")}</span></Label>
            <div className="flex gap-2">
              <Select value={appPolicyId} onChange={(e) => setAppPolicyId(e.target.value)} className="flex-1">
                <option value="">{t("applicationsNoAppPolicy")}</option>
                {appOnlyPolicies.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </Select>
              <Button variant="outline" onClick={saveAppPolicy}>{t("save")}</Button>
            </div>
            <p className="text-xs text-muted-foreground">
              <Trans t={t} i18nKey="applicationsSignOnPolicyDetail" components={[<strong key="0" />, <strong key="1" />]} />
            </p>
          </div>

          <Label className="pt-1">{t("applicationsPerUserGroupAccess")}</Label>
          <div className="rounded-md border">
            {assignments.length === 0 ? (
              <p className="p-4 text-sm text-muted-foreground">{t("applicationsNoAssignments")}</p>
            ) : assignments.map((a) => (
              <div key={a.id} className="flex items-center justify-between border-b p-3 last:border-0">
                <span className="flex flex-wrap items-center gap-2 text-sm">
                  <Badge variant={a.subjectType === "USER" ? "outline" : "secondary"}>{a.subjectType === "USER" ? t("applicationsSubjectUser") : t("applicationsSubjectGroup")}</Badge>
                  {a.subjectName}
                  {a.requiredPolicyId && <Badge variant="default">+ {policyName(a.requiredPolicyId)}</Badge>}
                </span>
                <Button variant="ghost" size="icon" className="text-muted-foreground hover:text-destructive" onClick={() => removeAssignment(a)}><Trash2 /></Button>
              </div>
            ))}
          </div>

          <div className="space-y-2">
            <Label>{t("applicationsAssignTo")}</Label>
            <div className="flex gap-2">
              <Select value={subjectType} className="w-32"
                      onChange={(e) => { setSubjectType(e.target.value as "USER" | "GROUP"); setSubjectId(""); setPickerKey((k) => k + 1); }}>
                <option value="GROUP">{t("applicationsSubjectGroup")}</option><option value="USER">{t("applicationsSubjectUser")}</option>
              </Select>
              <SearchSelect
                resetKey={`${subjectType}:${pickerKey}`}
                placeholder={subjectType === "GROUP" ? t("applicationsSearchGroups") : t("applicationsSearchUsers")}
                fetcher={(q) => (subjectType === "GROUP" ? searchGroups(q) : searchUsers(q))}
                onSelect={(s) => setSubjectId(s?.id ?? "")}
              />
            </div>
            <Label>{t("applicationsExtraAuth")} <span className="text-muted-foreground">{t("applicationsOptional")}</span></Label>
            <div className="flex gap-2">
              <Select value={requiredPolicyId} onChange={(e) => setRequiredPolicyId(e.target.value)} className="flex-1">
                <option value="">{t("applicationsNoExtraAuth")}</option>
                {appOnlyPolicies.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </Select>
              <Button onClick={add} disabled={!subjectId}><UserPlus /> {t("add")}</Button>
            </div>
            <p className="text-xs text-muted-foreground">
              {t("applicationsExtraAuthHint")}
            </p>
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={settingsOpen} onOpenChange={setSettingsOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2"><Lock className="size-4" /> {t(settingsPortal === "user" ? "applicationsUserPortalSettingsTitle" : "applicationsPortalSecurityTitle")}</DialogTitle>
            <DialogDescription>
              <Trans t={t} i18nKey={settingsPortal === "user" ? "applicationsUserPortalSettingsDescription" : "applicationsPortalSecurityDescription"} components={[<strong key="0" />]} />
            </DialogDescription>
          </DialogHeader>

          {settingsError && <Alert variant="destructive"><AlertDescription>{settingsError}</AlertDescription></Alert>}
          {settingsSaved && <Alert variant="success"><AlertDescription>{t("applicationsSaved")}</AlertDescription></Alert>}

          {settings ? (
            <div className="space-y-4">
              <div className="space-y-1.5">
                <Label htmlFor="console-policy">{t("applicationsSessionPolicyLabel")}</Label>
                <Select id="console-policy" value={settings.sessionPolicyId ?? ""}
                        onChange={(e) => setSettings({ ...settings, sessionPolicyId: e.target.value || null })}>
                  <option value="">{t(settingsPortal === "user" ? "applicationsEachUserPolicy" : "applicationsEachAdminPolicy")}</option>
                  {sessionPolicies.map((p) => (
                    <option key={p.id} value={p.id}>{p.name}</option>
                  ))}
                </Select>
                <p className="text-xs text-muted-foreground">
                  {t(settingsPortal === "user" ? "applicationsUserPortalPolicyHint" : "applicationsPortalPolicyHint")}
                </p>
              </div>
              {settingsPortal === "admin" && (
                <>
                  <div className="space-y-1.5">
                    <Label htmlFor="console-elevation">{t("applicationsElevationTtl")}</Label>
                    <Input id="console-elevation" type="number" min={1}
                           value={settings.elevationTokenTtlMinutes ?? 5}
                           onChange={(e) => setSettings({ ...settings, elevationTokenTtlMinutes: Number(e.target.value) })} />
                    <p className="text-xs text-muted-foreground">{t("applicationsElevationTtlHint")}</p>
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="console-cidrs">{t("applicationsAdminCidrs")}</Label>
                    <Input id="console-cidrs" placeholder="e.g. 203.0.113.0/24, 10.0.0.0/8"
                           value={settings.adminAllowedCidrs ?? ""}
                           onChange={(e) => setSettings({ ...settings, adminAllowedCidrs: e.target.value || null })} />
                    <p className="text-xs text-muted-foreground">{t("applicationsAdminCidrsHint")}</p>
                  </div>
                </>
              )}
            </div>
          ) : !settingsError ? <p className="text-sm text-muted-foreground">{t("loading")}</p> : null}

          <DialogFooter>
            <Button variant="outline" onClick={() => setSettingsOpen(false)}>{t("close")}</Button>
            <Button onClick={saveSettings} disabled={!settings}>{t("saveChanges")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { Trans, useTranslation } from "react-i18next";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ChevronDown, ChevronUp, Trash2 } from "lucide-react";
import { apiGet, apiPost, apiPut, errorMessage, type Page } from "../api";
import { listZones, searchZones } from "@/zones";
import { EditorPage } from "@/components/EditorPage";
import { InfoHint } from "@/components/InfoHint";
import { SearchSelect } from "@/components/SearchSelect";
import { SettingsSection } from "@/components/SettingsSection";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { tokens } from "@/lib/utils";
import { Field, Toggle } from "@/components/form/fields";
import { CheckboxGroup } from "@/components/form/CheckboxGroup";
import { UserMultiSelect } from "@/components/UserMultiSelect";

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
interface ZoneRule { zoneId: string; action: string } // order = priority

interface Editor {
  id: string | null;
  name: string;
  priority: string;
  enabled: boolean;
  absoluteTimeoutMinutes: string;
  idleTimeoutMinutes: string;
  reauthIntervalMinutes: string;
  reauthFactors: string;
  sensitiveReauthWindowMinutes: string;
  stepUpFactors: string;
  bindClient: boolean;
  maxConcurrentSessions: string;
  rotateOnReauth: boolean;
  cookieSameSite: string;
  roleIds: string[];
  userIds: string[];
  ipRules: ZoneRule[];
}

const REAUTH_FACTORS = ["TOTP", "FIDO2", "PASSWORD", "EMAIL"];
type Tab = "general" | "reauth" | "network" | "assign";

const blankEditor: Editor = {
  id: null, name: "", priority: "10", enabled: true,
  absoluteTimeoutMinutes: "480", idleTimeoutMinutes: "30", reauthIntervalMinutes: "5",
  reauthFactors: "TOTP,FIDO2", sensitiveReauthWindowMinutes: "2", stepUpFactors: "TOTP,FIDO2",
  bindClient: true, maxConcurrentSessions: "0", rotateOnReauth: true,
  cookieSameSite: "Lax", roleIds: [], userIds: [], ipRules: [],
};

function toEditor(p: SessionPolicy): Editor {
  return {
    id: p.id, name: p.name, priority: String(p.priority), enabled: p.enabled,
    absoluteTimeoutMinutes: String(p.absoluteTimeoutMinutes),
    idleTimeoutMinutes: String(p.idleTimeoutMinutes),
    reauthIntervalMinutes: String(p.reauthIntervalMinutes),
    reauthFactors: p.reauthFactors,
    sensitiveReauthWindowMinutes: String(p.sensitiveReauthWindowMinutes), stepUpFactors: p.stepUpFactors,
    bindClient: p.bindClient,
    maxConcurrentSessions: String(p.maxConcurrentSessions), rotateOnReauth: p.rotateOnReauth,
    cookieSameSite: p.cookieSameSite,
    roleIds: [...p.assignedRoleIds], userIds: [...p.assignedUserIds],
    ipRules: [...p.ipRules].sort((a, b) => a.priority - b.priority).map((r) => ({ zoneId: r.zoneId, action: r.action })),
  };
}

/**
 * Okta-style, full-width session-policy editor (create at `session-policy/new`, edit at
 * `session-policy/:id`): a breadcrumb, tabs, two-column settings sections (title/description on the left,
 * controls on the right) and a sticky save bar. Network rules reference reusable Network Zones.
 */
export default function SessionPolicyDetail() {
  const { t } = useTranslation("console");
  const TABS: { key: Tab; label: string }[] = [
    { key: "general", label: t("spDetailTabGeneral") },
    { key: "reauth", label: t("spDetailTabReauth") },
    { key: "network", label: t("spDetailTabNetwork") },
    { key: "assign", label: t("spDetailTabAssign") },
  ];
  const { id } = useParams();
  const navigate = useNavigate();
  const isNew = !id;
  const [editor, setEditor] = useState<Editor | null>(isNew ? blankEditor : null);
  const [roles, setRoles] = useState<Role[]>([]);
  const [zoneNames, setZoneNames] = useState<Record<string, string>>({});
  const [tab, setTab] = useState<Tab>("general");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [addKey, setAddKey] = useState(0);

  useEffect(() => {
    apiGet<Role[]>("/api/admin/roles").then(setRoles).catch(() => undefined);
    listZones().then((zs) => setZoneNames(Object.fromEntries(zs.map((z) => [z.id, z.name])))).catch(() => undefined);
  }, []);
  useEffect(() => {
    if (isNew) return;
    apiGet<Page<SessionPolicy>>("/api/admin/session-policies?size=100")
      .then((p) => {
        const found = p.items.find((x) => x.id === id);
        if (found) setEditor(toEditor(found));
        else setError(t("spDetailNotFound"));
      })
      .catch((e) => setError(errorMessage(e)));
  }, [id, isNew]);

  const set = (patch: Partial<Editor>) => setEditor((e) => (e ? { ...e, ...patch } : e));

  /** Client-side check with a jump to the offending tab — the tabbed layout hides other tabs' fields. */
  function validate(e: Editor): { tab: Tab; message: string } | null {
    const intOf = (v: string) => (/^\d+$/.test(v.trim()) ? Number(v) : NaN);
    if (!e.name.trim()) return { tab: "general", message: t("spDetailNameRequired") };
    if (Number.isNaN(intOf(e.priority))) return { tab: "general", message: t("spDetailPriorityWhole") };
    if (!(intOf(e.absoluteTimeoutMinutes) >= 1)) return { tab: "general", message: t("spDetailAbsoluteMin") };
    if (!(intOf(e.idleTimeoutMinutes) >= 1)) return { tab: "general", message: t("spDetailIdleMin") };
    if (Number.isNaN(intOf(e.maxConcurrentSessions))) return { tab: "general", message: t("spDetailMaxWhole") };
    if (!(intOf(e.reauthIntervalMinutes) >= 1)) return { tab: "reauth", message: t("spDetailReauthMinErr") };
    if (!(intOf(e.sensitiveReauthWindowMinutes) >= 1)) return { tab: "reauth", message: t("spDetailStepupWindowMin") };
    if (tokens(e.reauthFactors, ",").length === 0) return { tab: "reauth", message: t("spDetailPickReauth") };
    if (tokens(e.stepUpFactors, ",").length === 0) return { tab: "reauth", message: t("spDetailPickStepup") };
    return null;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!editor) return;
    const invalid = validate(editor);
    if (invalid) { setError(invalid.message); setTab(invalid.tab); return; }
    setError(null); setBusy(true);
    const body = {
      name: editor.name,
      priority: Number(editor.priority),
      enabled: editor.enabled,
      absoluteTimeoutMinutes: Number(editor.absoluteTimeoutMinutes),
      idleTimeoutMinutes: Number(editor.idleTimeoutMinutes),
      reauthIntervalMinutes: Number(editor.reauthIntervalMinutes),
      reauthFactors: editor.reauthFactors,
      sensitiveReauthWindowMinutes: Number(editor.sensitiveReauthWindowMinutes),
      stepUpFactors: editor.stepUpFactors,
      bindClient: editor.bindClient,
      maxConcurrentSessions: Number(editor.maxConcurrentSessions),
      rotateOnReauth: editor.rotateOnReauth,
      cookieSameSite: editor.cookieSameSite,
      assignedRoleIds: editor.roleIds,
      assignedUserIds: editor.userIds,
      ipRules: editor.ipRules.map((r, i) => ({ zoneId: r.zoneId, action: r.action, priority: i })),
    };
    try {
      if (editor.id) await apiPut(`/api/admin/session-policies/${editor.id}`, body);
      else await apiPost("/api/admin/session-policies", body);
      navigate("/admin/session-policy");
    } catch (e) {
      setError(errorMessage(e)); // a cancelled step-up maps to "" — form stays as-is
      setBusy(false);
    }
  }

  const crumbName = isNew ? t("spDetailNewCrumb") : (editor?.name || t("spDetailEditCrumb"));
  // The "Default" is the tier's unconditional fallback: its priority, enabled state and assignments are fixed
  // (the server rejects changes) so it always covers every user not matched by a higher-priority policy.
  const isDefault = !!editor?.id && editor.name === "Default";

  return (
    <EditorPage<Tab>
      backTo="/admin/session-policy" backLabel={t("spDetailBack")} crumb={crumbName}
      title={isNew ? t("spDetailNewTitle") : (editor?.name ?? "…")}
      description={t("spDetailDescription")}
      tabs={TABS} activeTab={tab} onTab={setTab}
      error={error} formId="policy-form" onSubmit={submit} busy={busy}
      submitLabel={isNew ? t("spDetailCreate") : t("saveChanges")}
      onCancel={() => navigate("/admin/session-policy")} loading={!editor}
    >
      {editor && (
        <>
          {tab === "general" && <GeneralTab editor={editor} set={set} isDefault={isDefault} />}
          {tab === "reauth" && <ReauthTab editor={editor} set={set} />}
          {tab === "network" && (
            <NetworkTab editor={editor} set={set} zoneNames={zoneNames} addKey={addKey}
                        onAdd={(zoneId, label) => {
                          setZoneNames((m) => ({ ...m, [zoneId]: label }));
                          // One rule per zone: a duplicate row could never fire (first-match) yet would
                          // display as a meaningful rule — skip instead.
                          if (!editor.ipRules.some((r) => r.zoneId === zoneId)) {
                            set({ ipRules: [...editor.ipRules, { zoneId, action: "BLOCK" }] });
                          }
                          setAddKey((k) => k + 1);
                        }} />
          )}
          {tab === "assign" && <AssignTab editor={editor} set={set} roles={roles} isDefault={isDefault} />}
        </>
      )}
    </EditorPage>
  );
}

/** A checkbox chip group for the factor pickers. */
function FactorChips({ selected, onToggle }: { selected: string[]; onToggle: (f: string) => void }) {
  return (
    <div className="flex flex-wrap gap-2">
      {REAUTH_FACTORS.map((f) => (
        <label key={f} className="flex cursor-pointer items-center gap-2 rounded-md border px-3 py-2 text-sm has-[:checked]:border-primary has-[:checked]:bg-accent">
          <Checkbox checked={selected.includes(f)} onCheckedChange={() => onToggle(f)} /> {f}
        </label>
      ))}
    </div>
  );
}

function GeneralTab({ editor, set, isDefault }:
  { editor: Editor; set: (p: Partial<Editor>) => void; isDefault: boolean }) {
  const { t } = useTranslation("console");
  return (
    <>
      <SettingsSection title={t("spDetailBasics")} description={t("spDetailBasicsDesc")}>
        <Field label={t("spDetailName")} hint={editor.id ? t("spDetailNameHint") : undefined}>
          <Input value={editor.name} disabled={!!editor.id} onChange={(e) => set({ name: e.target.value })} />
        </Field>
        <div className="grid gap-5 sm:grid-cols-2">
          <Field label={t("spDetailPriority")}
                 hint={isDefault ? t("spDetailPriorityDefaultHint") : t("spDetailPriorityHint")}>
            <Input value={editor.priority} inputMode="numeric" disabled={isDefault}
                   onChange={(e) => set({ priority: e.target.value })} />
          </Field>
          <Toggle label={t("spDetailEnabled")}
                  hint={isDefault ? t("spDetailEnabledDefaultHint") : t("spDetailEnabledHint")}
                  checked={editor.enabled} disabled={isDefault} onChange={(v) => set({ enabled: v })} />
        </div>
      </SettingsSection>

      <SettingsSection title={t("spDetailLifetime")} description={t("spDetailLifetimeDesc")}>
        <div className="grid gap-5 sm:grid-cols-2">
          <Field label={t("spDetailAbsolute")} hint={t("spDetailAbsoluteHint")}>
            <Input type="number" min={1} value={editor.absoluteTimeoutMinutes} onChange={(e) => set({ absoluteTimeoutMinutes: e.target.value })} />
          </Field>
          <Field label={t("spDetailIdle")} hint={t("spDetailIdleHint")}>
            <Input type="number" min={1} value={editor.idleTimeoutMinutes} onChange={(e) => set({ idleTimeoutMinutes: e.target.value })} />
          </Field>
        </div>
        <Field label={t("spDetailMaxSessions")} hint={t("spDetailMaxSessionsHint")}>
          <Input type="number" min={0} value={editor.maxConcurrentSessions} onChange={(e) => set({ maxConcurrentSessions: e.target.value })} />
        </Field>
      </SettingsSection>

      <SettingsSection title={t("spDetailHardening")} description={t("spDetailHardeningDesc")}>
        <Toggle label={t("spDetailBindClient")} hint={t("spDetailBindClientHint")}
                checked={editor.bindClient} onChange={(v) => set({ bindClient: v })} />
        <Toggle label={t("spDetailRotate")} hint={t("spDetailRotateHint")}
                checked={editor.rotateOnReauth} onChange={(v) => set({ rotateOnReauth: v })} />
      </SettingsSection>
    </>
  );
}

function ReauthTab({ editor, set }: { editor: Editor; set: (p: Partial<Editor>) => void }) {
  const { t } = useTranslation("console");
  const factors = tokens(editor.reauthFactors, ",");
  const stepUp = tokens(editor.stepUpFactors, ",");
  const toggle = (list: string[], f: string) => (list.includes(f) ? list.filter((x) => x !== f) : [...list, f]);
  return (
    <>
      <SettingsSection title={t("spDetailReauthTitle")}
                       description={t("spDetailReauthDesc")}>
        <Field label={t("spDetailReauthInterval")} hint={t("spDetailReauthIntervalHint")}>
          <Input type="number" min={1} value={editor.reauthIntervalMinutes} onChange={(e) => set({ reauthIntervalMinutes: e.target.value })} />
        </Field>
        <div className="space-y-2">
          <Label>{t("spDetailAllowedFactors")}</Label>
          <FactorChips selected={factors} onToggle={(f) => set({ reauthFactors: toggle(factors, f).join(",") })} />
        </div>
      </SettingsSection>

      <SettingsSection title={t("spDetailSensitive")}
                       description={t("spDetailSensitiveDesc")}>
        <Field label={t("spDetailStepupWindow")} hint={t("spDetailStepupWindowHint")}>
          <Input type="number" min={1} value={editor.sensitiveReauthWindowMinutes} onChange={(e) => set({ sensitiveReauthWindowMinutes: e.target.value })} />
        </Field>
        <div className="space-y-2">
          <Label>{t("spDetailStepupFactors")}</Label>
          <FactorChips selected={stepUp} onToggle={(f) => set({ stepUpFactors: toggle(stepUp, f).join(",") })} />
          <p className="text-xs text-muted-foreground">{t("spDetailStepupNote")}</p>
        </div>
      </SettingsSection>
    </>
  );
}

function NetworkTab({ editor, set, zoneNames, addKey, onAdd }:
    { editor: Editor; set: (p: Partial<Editor>) => void; zoneNames: Record<string, string>; addKey: number;
      onAdd: (zoneId: string, label: string) => void }) {
  const { t } = useTranslation("console");
  const rules = editor.ipRules;
  const setRule = (i: number, action: string) => set({ ipRules: rules.map((r, j) => (j === i ? { ...r, action } : r)) });
  const removeRule = (i: number) => set({ ipRules: rules.filter((_, j) => j !== i) });
  const moveRule = (i: number, dir: -1 | 1) => {
    const j = i + dir;
    if (j < 0 || j >= rules.length) return;
    const next = [...rules];
    [next[i], next[j]] = [next[j], next[i]];
    set({ ipRules: next });
  };
  return (
    <SettingsSection
      title={t("spDetailNetworkZones")}
      description={t("spDetailNetworkZonesDesc")}>
      {rules.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("spDetailNoRestriction")}</p>
      ) : (
        <div className="space-y-2">
          {rules.map((r, i) => (
            <div key={i} className="flex items-center gap-2 rounded-md border p-2">
              <span className="w-5 text-center text-xs tabular-nums text-muted-foreground">{i + 1}</span>
              <Badge variant="muted" className="flex-1 justify-start truncate">{zoneNames[r.zoneId] ?? r.zoneId}</Badge>
              <Select className="w-28" value={r.action} onChange={(e) => setRule(i, e.target.value)}>
                <option value="ALLOW">{t("spDetailActionAllow")}</option>
                <option value="BLOCK">{t("spDetailActionBlock")}</option>
              </Select>
              <Button type="button" variant="ghost" size="icon" disabled={i === 0} onClick={() => moveRule(i, -1)} aria-label={t("spDetailMoveUp")}><ChevronUp /></Button>
              <Button type="button" variant="ghost" size="icon" disabled={i === rules.length - 1} onClick={() => moveRule(i, 1)} aria-label={t("spDetailMoveDown")}><ChevronDown /></Button>
              <Button type="button" variant="ghost" size="icon" className="text-muted-foreground hover:text-destructive" onClick={() => removeRule(i)} aria-label={t("spDetailRemove")}><Trash2 /></Button>
            </div>
          ))}
        </div>
      )}
      <div className="max-w-sm">
        <SearchSelect placeholder={t("spDetailSearchZones")} fetcher={searchZones} resetKey={addKey}
                      onSelect={(s) => { if (s) onAdd(s.id, s.label); }} />
      </div>
      <div className="flex items-start gap-1.5 text-xs text-muted-foreground">
        <span>
          <Trans t={t} i18nKey="spDetailZonesDefinedIn"
                 components={[
                   <Link key="0" to="/admin/network-zones" className="font-medium text-foreground hover:underline" />,
                   <code key="1" />, <code key="2" />,
                 ]} />
        </span>
        <InfoHint label={t("spDetailInfoLabel")}>
          <Trans t={t} i18nKey="spDetailInfoBody" components={[<strong key="0" />, <strong key="1" />]} />
        </InfoHint>
      </div>
    </SettingsSection>
  );
}

function AssignTab({ editor, set, roles, isDefault }:
  { editor: Editor; set: (p: Partial<Editor>) => void; roles: Role[]; isDefault: boolean }) {
  const { t } = useTranslation("console");
  const toggle = (v: string) => (editor.roleIds.includes(v) ? editor.roleIds.filter((x) => x !== v) : [...editor.roleIds, v]);
  if (isDefault) {
    return (
      <SettingsSection title={t("spDetailAppliesTo")} description={t("spDetailDefaultAppliesDesc")}>
        <p className="text-sm text-muted-foreground">
          <Trans t={t} i18nKey="spDetailDefaultAppliesBody" components={[<strong key="0" />, <strong key="1" />]} />
        </p>
      </SettingsSection>
    );
  }
  return (
    <SettingsSection
      title={t("spDetailAppliesTo")}
      description={t("spDetailAssignDesc")}>
      <div className="space-y-2">
        <Label>{t("spDetailRoles")}</Label>
        <CheckboxGroup
          options={roles.map((r) => ({ value: r.id, label: r.name }))}
          selected={editor.roleIds} onToggle={(v) => set({ roleIds: toggle(v) })} emptyText={t("spDetailNoRoles")}
        />
      </div>
      <div className="space-y-2">
        <Label>{t("spDetailUsers")}</Label>
        <UserMultiSelect selected={editor.userIds} onChange={(ids) => set({ userIds: ids })} placeholder={t("spDetailUsersPlaceholder")} />
      </div>
    </SettingsSection>
  );
}

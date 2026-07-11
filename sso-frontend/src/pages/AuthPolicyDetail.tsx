import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { useNavigate, useParams } from "react-router-dom";
import { apiGet, apiPost, apiPut, errorMessage, type Page } from "../api";
import { EditorPage } from "@/components/EditorPage";
import type { DiffEntry } from "@/components/EditorPage";
import { SettingsSection } from "@/components/SettingsSection";
import { Field, Toggle } from "@/components/form/fields";
import { CheckboxGroup } from "@/components/form/CheckboxGroup";
import { StepsBuilder } from "@/components/form/StepsBuilder";
import { SignInPreview } from "@/components/form/SignInPreview";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { UserMultiSelect } from "@/components/UserMultiSelect";

interface Policy {
  id: string;
  name: string;
  priority: number;
  enabled: boolean;
  appliesToLogin: boolean;
  allowEnrollmentAtLogin: boolean;
  steps: string[][];
  assignedUserIds: string[];
  assignedRoleIds: string[];
  stepUpFreshnessMinutes: number;
}
interface Role { id: string; name: string }

interface Editor {
  id: string | null;
  name: string;
  priority: string;
  enabled: boolean;
  appliesToLogin: boolean;
  allowEnrollmentAtLogin: boolean;
  steps: string[][];
  roleIds: string[];
  userIds: string[];
  stepUpFreshnessMinutes: string;
}

const blankEditor: Editor = {
  id: null, name: "", priority: "10", enabled: true, appliesToLogin: true, allowEnrollmentAtLogin: true,
  steps: [["PASSWORD"], ["TOTP", "EMAIL"]], roleIds: [], userIds: [], stepUpFreshnessMinutes: "15",
};

function toEditor(p: Policy): Editor {
  return {
    id: p.id, name: p.name, priority: String(p.priority), enabled: p.enabled, appliesToLogin: p.appliesToLogin,
    allowEnrollmentAtLogin: p.allowEnrollmentAtLogin, steps: p.steps.map((s) => [...s]),
    roleIds: [...p.assignedRoleIds], userIds: [...p.assignedUserIds],
    stepUpFreshnessMinutes: String(p.stepUpFreshnessMinutes ?? 15),
  };
}

/** The save bar names the diff against the loaded policy — old value struck through (DESIGN.md §4). */
function diffEntries(base: Editor, cur: Editor, t: TFunction<"console">): DiffEntry[] {
  const onOff = (b: boolean) => (b ? t("authPolicyDetailOn") : t("authPolicyDetailOff"));
  const factorCount = (steps: string[][]) => t("authPolicyDetailFactors", { count: steps.reduce((n, s) => n + s.length, 0) });
  const min = (v: string) => t("authPolicyDetailReauthMin", { minutes: v });
  const out: DiffEntry[] = [];
  if (base.priority !== cur.priority) out.push({ label: t("authPolicyDetailDiffPriority"), from: base.priority, to: cur.priority });
  if (base.enabled !== cur.enabled) out.push({ label: t("authPolicyDetailDiffStatus"), from: base.enabled ? t("badgeEnabled") : t("badgeDisabled"), to: cur.enabled ? t("badgeEnabled") : t("badgeDisabled") });
  if (base.appliesToLogin !== cur.appliesToLogin) out.push({ label: t("authPolicyDetailDiffUseForLogin"), from: onOff(base.appliesToLogin), to: onOff(cur.appliesToLogin) });
  if (base.allowEnrollmentAtLogin !== cur.allowEnrollmentAtLogin) out.push({ label: t("authPolicyDetailDiffEnrollAtLogin"), from: onOff(base.allowEnrollmentAtLogin), to: onOff(cur.allowEnrollmentAtLogin) });
  if (base.stepUpFreshnessMinutes !== cur.stepUpFreshnessMinutes) out.push({ label: t("authPolicyDetailDiffReauthWindow"), from: min(base.stepUpFreshnessMinutes), to: min(cur.stepUpFreshnessMinutes) });
  if (JSON.stringify(base.steps) !== JSON.stringify(cur.steps)) out.push({ label: t("authPolicyDetailDiffChain"), from: factorCount(base.steps), to: factorCount(cur.steps) });
  if (JSON.stringify(base.roleIds) !== JSON.stringify(cur.roleIds)) out.push({ label: t("authPolicyDetailDiffRoles"), from: base.roleIds.length, to: cur.roleIds.length });
  if (JSON.stringify(base.userIds) !== JSON.stringify(cur.userIds)) out.push({ label: t("authPolicyDetailDiffUsers"), from: base.userIds.length, to: cur.userIds.length });
  return out;
}

/** Okta-style full-page create/edit for an authentication policy (routes `auth-policies/new` + `:id`). */
export default function AuthPolicyDetail() {
  const { t } = useTranslation("console");
  const { id } = useParams();
  const navigate = useNavigate();
  const isNew = !id;
  const [editor, setEditor] = useState<Editor | null>(isNew ? blankEditor : null);
  const [baseline, setBaseline] = useState<Editor | null>(null); // snapshot of the loaded policy, for the diff bar
  const [roles, setRoles] = useState<Role[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    apiGet<Role[]>("/api/admin/roles").then(setRoles).catch(() => undefined);
  }, []);
  useEffect(() => {
    if (isNew) return;
    apiGet<Page<Policy>>("/api/admin/auth-policies?size=100")
      .then((p) => {
        const found = p.items.find((x) => x.id === id);
        if (found) { const ed = toEditor(found); setEditor(ed); setBaseline(ed); }
        else setError(t("authPolicyDetailNotFound"));
      })
      .catch((e) => setError(errorMessage(e)));
  }, [id, isNew]);

  const set = (patch: Partial<Editor>) => setEditor((e) => (e ? { ...e, ...patch } : e));
  const toggleRole = (rid: string) =>
    set({ roleIds: editor!.roleIds.includes(rid) ? editor!.roleIds.filter((x) => x !== rid) : [...editor!.roleIds, rid] });

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!editor) return;
    if (!editor.name.trim()) { setError(t("authPolicyDetailNameRequired")); return; }
    if (editor.steps.every((s) => s.length === 0)) { setError(t("authPolicyDetailAddStep")); return; }
    setError(null); setBusy(true);
    const body = {
      name: editor.name,
      priority: Number(editor.priority),
      enabled: editor.enabled,
      appliesToLogin: editor.appliesToLogin,
      allowEnrollmentAtLogin: editor.allowEnrollmentAtLogin,
      steps: editor.steps.map((s) => [...s]).filter((s) => s.length > 0),
      assignedRoleIds: editor.roleIds,
      assignedUserIds: editor.userIds,
      stepUpFreshnessMinutes: Number(editor.stepUpFreshnessMinutes) || 15,
    };
    try {
      if (editor.id) await apiPut(`/api/admin/auth-policies/${editor.id}`, body);
      else await apiPost("/api/admin/auth-policies", body);
      navigate("/admin/auth-policies");
    } catch (e) {
      setError(errorMessage(e)); // a cancelled step-up maps to "" — form stays as-is
      setBusy(false);
    }
  }

  const crumbName = isNew ? t("authPolicyDetailNewCrumb") : (editor?.name || t("authPolicyDetailEditCrumb"));
  // Edits raise a diff bar; creation keeps the always-enabled create bar (opt-in — pass diff only when editing).
  const diff = !isNew && editor && baseline ? diffEntries(baseline, editor, t) : undefined;

  return (
    <EditorPage
      backTo="/admin/auth-policies" backLabel={t("authPolicyDetailBack")} crumb={crumbName}
      title={isNew ? t("authPolicyDetailNewTitle") : (editor?.name ?? "…")}
      description={t("authPolicyDetailDescription")}
      error={error} formId="auth-policy-form" onSubmit={submit} busy={busy}
      submitLabel={isNew ? t("authPolicyDetailCreate") : t("saveChanges")}
      onCancel={() => navigate("/admin/auth-policies")} loading={!editor} diff={diff}
    >
      {editor && (
        <>
          <SettingsSection title={t("authPolicyDetailBasics")} description={t("authPolicyDetailBasicsDesc")}>
            <Field label={t("authPolicyDetailNameLabel")} hint={editor.id ? t("authPolicyDetailNameHint") : undefined}>
              <Input value={editor.name} disabled={!!editor.id} onChange={(e) => set({ name: e.target.value })} />
            </Field>
            <div className="grid gap-5 sm:grid-cols-2">
              <Field label={t("authPolicyDetailPriority")} hint={t("authPolicyDetailPriorityHint")}>
                <Input value={editor.priority} inputMode="numeric" onChange={(e) => set({ priority: e.target.value })} />
              </Field>
              <Toggle label={t("authPolicyDetailEnabled")} hint={t("authPolicyDetailEnabledHint")}
                      checked={editor.enabled} onChange={(v) => set({ enabled: v })} />
            </div>
          </SettingsSection>

          <SettingsSection title={t("authPolicyDetailChain")} description={t("authPolicyDetailChainDesc")}>
            {/* editorgrid: preview sits beside the builder, dropping below it at ≤1180px (DESIGN.md §3). */}
            <div className="grid grid-cols-1 gap-5 min-[1180px]:grid-cols-[minmax(0,1fr)_minmax(0,20rem)]">
              <StepsBuilder steps={editor.steps} onChange={(steps) => set({ steps })} />
              <SignInPreview steps={editor.steps} />
            </div>
          </SettingsSection>

          <SettingsSection title={t("authPolicyDetailBehavior")} description={t("authPolicyDetailBehaviorDesc")}>
            <Toggle label={t("authPolicyDetailUseForLogin")}
                    hint={t("authPolicyDetailUseForLoginHint")}
                    checked={editor.appliesToLogin} onChange={(v) => set({ appliesToLogin: v })} />
            {!editor.appliesToLogin && (
              <Field label={t("authPolicyDetailReauthWindow")}
                     hint={t("authPolicyDetailReauthWindowHint")}>
                <Input type="number" min={1} className="max-w-40" value={editor.stepUpFreshnessMinutes}
                       onChange={(e) => set({ stepUpFreshnessMinutes: e.target.value })} />
              </Field>
            )}
            <Toggle label={t("authPolicyDetailAllowEnroll")}
                    hint={t("authPolicyDetailAllowEnrollHint")}
                    checked={editor.allowEnrollmentAtLogin} onChange={(v) => set({ allowEnrollmentAtLogin: v })} />
          </SettingsSection>

          <SettingsSection title={t("authPolicyDetailAppliesTo")}
                           description={t("authPolicyDetailAppliesToDesc")}>
            <div className="space-y-2">
              <Label>{t("authPolicyDetailRoles")}</Label>
              <CheckboxGroup options={roles.map((r) => ({ value: r.id, label: r.name }))}
                             selected={editor.roleIds} onToggle={toggleRole} emptyText={t("authPolicyDetailNoRoles")} />
            </div>
            <div className="space-y-2">
              <Label>{t("authPolicyDetailUsers")}</Label>
              <UserMultiSelect selected={editor.userIds} onChange={(ids) => set({ userIds: ids })}
                               placeholder={t("authPolicyDetailUsersPlaceholder")} />
            </div>
          </SettingsSection>
        </>
      )}
    </EditorPage>
  );
}

import { useEffect, useState } from "react";
import type { FormEvent } from "react";
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

const onOff = (b: boolean) => (b ? "On" : "Off");
const factorCount = (steps: string[][]) => `${steps.reduce((n, s) => n + s.length, 0)} factors`;

/** The save bar names the diff against the loaded policy — old value struck through (DESIGN.md §4). */
function diffEntries(base: Editor, cur: Editor): DiffEntry[] {
  const out: DiffEntry[] = [];
  if (base.priority !== cur.priority) out.push({ label: "Priority", from: base.priority, to: cur.priority });
  if (base.enabled !== cur.enabled) out.push({ label: "Status", from: base.enabled ? "Enabled" : "Disabled", to: cur.enabled ? "Enabled" : "Disabled" });
  if (base.appliesToLogin !== cur.appliesToLogin) out.push({ label: "Use for login", from: onOff(base.appliesToLogin), to: onOff(cur.appliesToLogin) });
  if (base.allowEnrollmentAtLogin !== cur.allowEnrollmentAtLogin) out.push({ label: "Enroll at login", from: onOff(base.allowEnrollmentAtLogin), to: onOff(cur.allowEnrollmentAtLogin) });
  if (base.stepUpFreshnessMinutes !== cur.stepUpFreshnessMinutes) out.push({ label: "Re-auth window", from: `${base.stepUpFreshnessMinutes} min`, to: `${cur.stepUpFreshnessMinutes} min` });
  if (JSON.stringify(base.steps) !== JSON.stringify(cur.steps)) out.push({ label: "Sign-on chain", from: factorCount(base.steps), to: factorCount(cur.steps) });
  if (JSON.stringify(base.roleIds) !== JSON.stringify(cur.roleIds)) out.push({ label: "Roles", from: base.roleIds.length, to: cur.roleIds.length });
  if (JSON.stringify(base.userIds) !== JSON.stringify(cur.userIds)) out.push({ label: "Users", from: base.userIds.length, to: cur.userIds.length });
  return out;
}

/** Okta-style full-page create/edit for an authentication policy (routes `auth-policies/new` + `:id`). */
export default function AuthPolicyDetail() {
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
        else setError("Policy not found.");
      })
      .catch((e) => setError(errorMessage(e)));
  }, [id, isNew]);

  const set = (patch: Partial<Editor>) => setEditor((e) => (e ? { ...e, ...patch } : e));
  const toggleRole = (rid: string) =>
    set({ roleIds: editor!.roleIds.includes(rid) ? editor!.roleIds.filter((x) => x !== rid) : [...editor!.roleIds, rid] });

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!editor) return;
    if (!editor.name.trim()) { setError("Name is required."); return; }
    if (editor.steps.every((s) => s.length === 0)) { setError("Add at least one sign-on step."); return; }
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

  const crumbName = isNew ? "New policy" : (editor?.name || "Edit");
  // Edits raise a diff bar; creation keeps the always-enabled create bar (opt-in — pass diff only when editing).
  const diff = !isNew && editor && baseline ? diffEntries(baseline, editor) : undefined;

  return (
    <EditorPage
      backTo="/admin/auth-policies" backLabel="Authentication Policies" crumb={crumbName}
      title={isNew ? "New authentication policy" : (editor?.name ?? "…")}
      description="Define the ordered factor chain, whether it governs login, and where it applies."
      error={error} formId="auth-policy-form" onSubmit={submit} busy={busy}
      submitLabel={isNew ? "Create policy" : "Save changes"}
      onCancel={() => navigate("/admin/auth-policies")} loading={!editor} diff={diff}
    >
      {editor && (
        <>
          <SettingsSection title="Basics" description="Identify the policy and set how it ranks against others.">
            <Field label="Name" hint={editor.id ? "The name can't be changed after creation." : undefined}>
              <Input value={editor.name} disabled={!!editor.id} onChange={(e) => set({ name: e.target.value })} />
            </Field>
            <div className="grid gap-5 sm:grid-cols-2">
              <Field label="Priority" hint="Higher wins when several policies match a user.">
                <Input value={editor.priority} inputMode="numeric" onChange={(e) => set({ priority: e.target.value })} />
              </Field>
              <Toggle label="Enabled" hint="Disabled policies are ignored during resolution."
                      checked={editor.enabled} onChange={(v) => set({ enabled: v })} />
            </div>
          </SettingsSection>

          <SettingsSection title="Sign-on chain" description="The factors a user must complete, verified step by step.">
            {/* editorgrid: preview sits beside the builder, dropping below it at ≤1180px (DESIGN.md §3). */}
            <div className="grid grid-cols-1 gap-5 min-[1180px]:grid-cols-[minmax(0,1fr)_minmax(0,20rem)]">
              <StepsBuilder steps={editor.steps} onChange={(steps) => set({ steps })} />
              <SignInPreview steps={editor.steps} />
            </div>
          </SettingsSection>

          <SettingsSection title="Behavior" description="How this policy is used and whether users can enroll factors while signing in.">
            <Toggle label="Use for login (sign-on policy)"
                    hint="Off = app-only policy, used only for per-app extra authentication (never for login)."
                    checked={editor.appliesToLogin} onChange={(v) => set({ appliesToLogin: v })} />
            {!editor.appliesToLogin && (
              <Field label="Re-authentication window (minutes)"
                     hint="Attached to an app, the user steps up on entry; it stays valid this long before challenging again. Login alone never satisfies it.">
                <Input type="number" min={1} className="max-w-40" value={editor.stepUpFreshnessMinutes}
                       onChange={(e) => set({ stepUpFreshnessMinutes: e.target.value })} />
              </Field>
            )}
            <Toggle label="Allow enrollment at login"
                    hint="On: a user missing a required factor sets it up (TOTP QR / passkey) during login. Off: login only verifies existing factors (admin must pre-provision)."
                    checked={editor.allowEnrollmentAtLogin} onChange={(v) => set({ allowEnrollmentAtLogin: v })} />
          </SettingsSection>

          <SettingsSection title="Applies to"
                           description="Target roles and/or users. Leave both empty to apply this policy to everyone (global).">
            <div className="space-y-2">
              <Label>Roles</Label>
              <CheckboxGroup options={roles.map((r) => ({ value: r.id, label: r.name }))}
                             selected={editor.roleIds} onToggle={toggleRole} emptyText="No roles" />
            </div>
            <div className="space-y-2">
              <Label>Users</Label>
              <UserMultiSelect selected={editor.userIds} onChange={(ids) => set({ userIds: ids })}
                               placeholder="Search users to target…" />
            </div>
          </SettingsSection>
        </>
      )}
    </EditorPage>
  );
}

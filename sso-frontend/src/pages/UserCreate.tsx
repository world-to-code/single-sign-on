import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { apiGet } from "@/api";
import { createUser } from "@/users";
import { errorMessage } from "@/api";
import { EditorPage } from "@/components/EditorPage";
import { SettingsSection } from "@/components/SettingsSection";
import { Field } from "@/components/form/fields";
import { CheckboxGroup } from "@/components/form/CheckboxGroup";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { listAttributeDefinitions, type AttributeDefinition } from "@/attributeDefinitions";
import { useTenantProfile } from "@/hooks/useTenantProfile";

interface Role { id: string; name: string }
const blank = { username: "", email: "", displayName: "", password: "", roles: ["ROLE_USER"] };

/** Full-page create form for a directory user (route `users/new`), matching the Okta-style editor shell. */
export default function UserCreate() {
  const { t } = useTranslation("console");
  const navigate = useNavigate();
  const [form, setForm] = useState({ ...blank });
  const [roles, setRoles] = useState<Role[]>([]);
  const [definitions, setDefinitions] = useState<AttributeDefinition[]>([]);
  const [attrs, setAttrs] = useState<Record<string, string>>({});
  const profile = useTenantProfile();

  // A tenant with no declared attributes simply gets no extra section — the schema is a catalog, not a demand.
  useEffect(() => {
    if (!profile) return;
    listAttributeDefinitions("USER", profile.id).then(setDefinitions).catch(() => setDefinitions([]));
  }, [profile]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    apiGet<Role[]>("/api/admin/roles").then(setRoles).catch(() => undefined);
  }, []);

  const set = (patch: Partial<typeof form>) => setForm((f) => ({ ...f, ...patch }));
  const toggleRole = (name: string) =>
    set({ roles: form.roles.includes(name) ? form.roles.filter((r) => r !== name) : [...form.roles, name] });

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!form.username.trim() || !form.email.trim() || !form.password) {
      setError(t("userCreateRequired"));
      return;
    }
    setError(null); setBusy(true);
    try {
      await createUser({
        username: form.username,
        email: form.email,
        displayName: form.displayName || null,
        password: form.password,
        roles: form.roles,
        attributes: Object.fromEntries(
          Object.entries(attrs).filter(([, v]) => v.trim() !== "").map(([k, v]) => [k, [v]])),
      });
      navigate("/admin/users");
    } catch (e) {
      setError(errorMessage(e)); // a cancelled step-up maps to "" — form stays as-is
      setBusy(false);
    }
  }

  // The profile declares what a person of this tenant HAS, so the form asks for exactly that — no more
  // hardcoded field list. Built-ins already have their own inputs above.
  const declared = definitions.filter((d) => !d.base);

  return (
    <EditorPage
      backTo="/admin/users" backLabel={t("userCreateBack")} crumb={t("userCreateCrumb")}
      title={t("userCreateTitle")} description={t("userCreateDescription")}
      error={error} formId="user-form" onSubmit={submit} busy={busy} submitLabel={t("userCreateSubmit")}
      onCancel={() => navigate("/admin/users")}
    >
      <SettingsSection title={t("userCreateIdentityTitle")} description={t("userCreateIdentityDesc")}>
        <Field label={t("userCreateUsername")}>
          <Input value={form.username} onChange={(e) => set({ username: e.target.value })} required />
        </Field>
        <Field label={t("userCreateEmail")} hint={t("userCreateEmailHint")}>
          <Input type="email" value={form.email} onChange={(e) => set({ email: e.target.value })} required />
        </Field>
        <Field label={t("userCreateDisplayName")} hint={t("userCreateDisplayNameHint")}>
          <Input value={form.displayName} onChange={(e) => set({ displayName: e.target.value })} />
        </Field>
      </SettingsSection>

      <SettingsSection title={t("userCreateCredentialsTitle")} description={t("userCreateCredentialsDesc")}>
        <Field label={t("userCreateTempPassword")}>
          <Input type="password" value={form.password} onChange={(e) => set({ password: e.target.value })} required />
        </Field>
      </SettingsSection>

      {declared.length > 0 && (
        <SettingsSection title={t("userCreateProfileTitle")} description={t("userCreateProfileDesc")}>
          {declared.map((d) => (
            <Field key={d.key} label={d.displayName} hint={d.description ?? undefined}>
              {d.dataType === "ENUM" ? (
                <Select value={attrs[d.key] ?? ""}
                        onChange={(e) => setAttrs({ ...attrs, [d.key]: e.target.value })}
                        required={d.required}>
                  <option value="">{t("userCreateAttrUnset")}</option>
                  {d.enumValues.map((v) => <option key={v} value={v}>{v}</option>)}
                </Select>
              ) : (
                <Input type={d.dataType === "DATE" ? "date" : d.dataType === "INTEGER" ? "number" : "text"}
                       value={attrs[d.key] ?? ""}
                       onChange={(e) => setAttrs({ ...attrs, [d.key]: e.target.value })}
                       required={d.required} />
              )}
            </Field>
          ))}
        </SettingsSection>
      )}

      <SettingsSection title={t("userCreateRolesTitle")} description={t("userCreateRolesDesc")}>
        <CheckboxGroup
          options={roles.map((r) => ({ value: r.name, label: r.name }))}
          selected={form.roles} onToggle={toggleRole} emptyText={t("userCreateNoRoles")}
        />
      </SettingsSection>
    </EditorPage>
  );
}

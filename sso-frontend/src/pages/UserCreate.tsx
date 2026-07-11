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

interface Role { id: string; name: string }
const blank = { username: "", email: "", displayName: "", password: "", roles: ["ROLE_USER"] };

/** Full-page create form for a directory user (route `users/new`), matching the Okta-style editor shell. */
export default function UserCreate() {
  const { t } = useTranslation("console");
  const navigate = useNavigate();
  const [form, setForm] = useState({ ...blank });
  const [roles, setRoles] = useState<Role[]>([]);
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
      });
      navigate("/admin/users");
    } catch (e) {
      setError(errorMessage(e)); // a cancelled step-up maps to "" — form stays as-is
      setBusy(false);
    }
  }

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

      <SettingsSection title={t("userCreateRolesTitle")} description={t("userCreateRolesDesc")}>
        <CheckboxGroup
          options={roles.map((r) => ({ value: r.name, label: r.name }))}
          selected={form.roles} onToggle={toggleRole} emptyText={t("userCreateNoRoles")}
        />
      </SettingsSection>
    </EditorPage>
  );
}

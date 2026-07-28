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
import {
  listAttributeDefinitions, listProfiles, type AttributeDefinition, type Profile,
} from "@/attributeDefinitions";
import { useCreationProfile } from "@/hooks/useCreationProfile";

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
  const defaultProfile = useCreationProfile();
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [profileId, setProfileId] = useState<string>("");

  // Only TENANT profiles: a source profile describes a remote directory's schema, and creating a person on
  // one would promise a shape no local form can fill.
  useEffect(() => {
    listProfiles().then((all) => setProfiles(all.filter((p) => p.kind === "TENANT")))
      .catch((e) => setError(errorMessage(e)));
  }, []);
  useEffect(() => {
    if (defaultProfile && !profileId) setProfileId(defaultProfile.id);
  }, [defaultProfile, profileId]);

  // A tenant with no declared attributes simply gets no extra section — the schema is a catalog, not a demand.
  //
  // Re-read on every profile change: a stale list would ask for one schema while the server validated another.
  useEffect(() => {
    if (!profileId) return;
    setAttrs({});
    // Swallowing this one cost the most: the form then asked for no attributes at all, and the server
    // refused the create for a required column the administrator was never shown.
    listAttributeDefinitions("USER", profileId).then(setDefinitions)
      .catch((e) => setError(errorMessage(e)));
  }, [profileId]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    apiGet<Role[]>("/api/admin/roles").then(setRoles).catch((e) => setError(errorMessage(e)));
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
        profileId: profileId || null,
        attributes: Object.fromEntries(
          Object.entries(attrs).filter(([, v]) => v.trim() !== "").map(([k, v]) => [k, [v]])),
      });
      navigate("/admin/users");
    } catch (e) {
      setError(errorMessage(e)); // a cancelled step-up maps to "" — form stays as-is
      setBusy(false);
    }
  }

  // Only what the profile REQUIRES. Creation is the one moment an administrator cannot skip, so asking for
  // every optional column here turns a two-field task into a form nobody finishes — and the server refuses a
  // create that omits a required one, which is the only part that has to happen now. The optional columns are
  // editable on the user's own page, where there is context for them.
  // A DIRECTORY-owned column is never asked for: its connector supplies it, and the server refuses it here.
  const declared = definitions.filter((d) => !d.base && d.required && d.source === "LOCAL");

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

      {profiles.length > 1 && (
        <SettingsSection title={t("userCreateProfilePickTitle")} description={t("userCreateProfilePickDesc")}>
          <Field label={t("userCreateProfileLabel")}>
            <Select value={profileId} onChange={(e) => setProfileId(e.target.value)}>
              {profiles.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.defaultForCreation ? t("userCreateProfileDefaultOption", { name: p.name }) : p.name}
                </option>
              ))}
            </Select>
          </Field>
        </SettingsSection>
      )}

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

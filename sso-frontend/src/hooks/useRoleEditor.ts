import { useState } from "react";
import { setRoleInheritance, togglePermission, updateRole, type Permission, type RoleDetail } from "@/roles";
import { errorMessage } from "@/api";

type InheritRef = { id: string; name: string };

/**
 * Owns the role-detail edit mode: staging permission and inheritance drafts and committing them together, so
 * the page stays presentational. Save is two idempotent writes (permissions via `updateRole`, inheritance via
 * its own step-up-gated endpoint); a retry after a mid-way failure reconciles, and the returned fresh detail
 * reflects whatever committed.
 */
export function useRoleEditor(
  id: string,
  role: RoleDetail | null,
  catalog: Permission[],
  onSaved: (fresh: RoleDetail) => void,
  onError: (message: string | null) => void,
) {
  const [editing, setEditing] = useState(false);
  const [draftPerms, setDraftPerms] = useState<string[]>([]);
  const [draftInherits, setDraftInherits] = useState<InheritRef[]>([]);
  const [saving, setSaving] = useState(false);

  function start() {
    if (!role) return;
    setDraftPerms(role.permissions);
    setDraftInherits(role.inheritsFrom);
    onError(null);
    setEditing(true);
  }

  const cancel = () => setEditing(false);
  const togglePerm = (perm: Permission) => setDraftPerms((prev) => togglePermission(prev, perm, catalog));
  const removeInherit = (roleId: string) => setDraftInherits((prev) => prev.filter((r) => r.id !== roleId));
  const addInherit = (ref: InheritRef) => setDraftInherits((prev) => [...prev, ref]);

  async function save() {
    if (!role) return;
    setSaving(true);
    try {
      await updateRole(id, { name: role.name, permissions: draftPerms });
      const fresh = await setRoleInheritance(id, draftInherits.map((r) => r.id));
      onSaved(fresh);
      setEditing(false);
      onError(null);
    } catch (e) {
      onError(errorMessage(e));
    } finally {
      setSaving(false);
    }
  }

  return { editing, draftPerms, draftInherits, saving, start, cancel, togglePerm, removeInherit, addInherit, save };
}

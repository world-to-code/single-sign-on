import { apiGet, apiPut } from "@/api";

/**
 * One column a profile declares, paired with what this person holds for it.
 *
 * Declarations and values arrive together on purpose: fetched separately the console would have to join them
 * itself, and would either render a column the profile dropped or miss one nobody has filled — and an empty
 * required column is exactly what an administrator opened the page to notice.
 */
export interface ProfileColumn {
  key: string;
  displayName: string;
  description: string | null;
  dataType: "STRING" | "INTEGER" | "BOOLEAN" | "DATE" | "ENUM";
  enumValues: string[];
  multiValued: boolean;
  required: boolean;
  /** False for a base column (its own edit dialog owns it) and for a directory-owned one (its connector does). */
  editable: boolean;
  /** A synthesised app_user field — username, email, display name, phone, external id. */
  base: boolean;
  values: string[];
}

export interface ProfileAttributes {
  columns: ProfileColumn[];
}

/** What moving a user onto another profile would cost. */
export interface ProfileSwitchPreview {
  removedKeys: string[];
  blockedKeys: string[];
  externallyManaged: boolean;
  blocked: boolean;
}

export const getProfileAttributes = (userId: string) =>
  apiGet<ProfileAttributes>(`/api/admin/users/${userId}/profile-attributes`);

/**
 * Saves the WHOLE set. A column omitted here is cleared — which is how the form says "unset this optional
 * attribute" — and sending everything is what lets the server check that a REQUIRED column is still filled.
 */
export const saveProfileAttributes = (userId: string, values: Record<string, string[]>) =>
  apiPut<ProfileAttributes>(`/api/admin/users/${userId}/profile-attributes`, { values });

export const previewProfileSwitch = (userId: string, profileId: string) =>
  apiGet<ProfileSwitchPreview>(`/api/admin/users/${userId}/profile/preview?profileId=${profileId}`);

/**
 * Destructive: attributes the target profile does not declare are deleted. Preview first, and send back the
 * keys that preview named — the server refuses the move if the cost has changed since, because the extra key
 * that would go can be the condition on a mapping rule.
 */
export const switchProfile = (userId: string, profileId: string, confirmedKeys: string[]) =>
  apiPut<ProfileAttributes>(`/api/admin/users/${userId}/profile`, { profileId, confirmedKeys });

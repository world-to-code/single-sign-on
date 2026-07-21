import { apiDelete, apiGet, apiPost, apiPut } from "./api";

export type AttributeDataType = "STRING" | "INTEGER" | "BOOLEAN" | "DATE" | "ENUM";

/**
 * Who owns an attribute's VALUES. A `DIRECTORY` attribute is filled by a directory connector and is read-only
 * in the console — otherwise an admin's edit would be silently overwritten by the next sync.
 */
export type AttributeSource = "LOCAL" | "DIRECTORY";

export type AttributeEntityKind = "USER" | "GROUP" | "APPLICATION" | "RESOURCE";

/** One declared attribute of this organization's profile schema. */
export interface AttributeDefinition {
  id: string;
  entityKind: AttributeEntityKind;
  key: string;
  displayName: string;
  description: string | null;
  dataType: AttributeDataType;
  /** Permitted values; populated only for `ENUM`. */
  enumValues: string[];
  multiValued: boolean;
  required: boolean;
  source: AttributeSource;
  sortOrder: number;
  /** An app_user column surfaced for context — synthesised server-side, so it has no id and cannot be edited. */
  base: boolean;
}

/** What the definition form submits. The key is the identity — re-declaring one redefines it in place. */
export interface AttributeDefinitionInput {
  entityKind: AttributeEntityKind;
  key: string;
  displayName: string;
  description: string;
  dataType: AttributeDataType;
  enumValues: string[];
  multiValued: boolean;
  required: boolean;
  source: AttributeSource;
  sortOrder: number;
}

/** A profile as the admin API returns it. USER attributes are declared inside one. */
export interface Profile {
  id: string;
  name: string;
  kind: "TENANT" | "LDAP" | "SCIM" | "CSV" | "GOOGLE_WORKSPACE" | "ENTRA_ID";
  connectorId: string | null;
  system: boolean;
  defaultForCreation: boolean;
}

export const profilesPath = "/api/admin/profiles";

export const listProfiles = (): Promise<Profile[]> => apiGet<Profile[]>(profilesPath);

/** One attribute carried from a source profile into a target profile. */
export interface ProfileMapping {
  id: string;
  sourceProfileId: string;
  sourceKey: string;
  targetProfileId: string;
  targetKey: string;
}

const at = (profileId: string) => `${profilesPath}/${encodeURIComponent(profileId)}`;

export const profileMappingsPath = (profileId: string) => `${at(profileId)}/mappings`;

export const mapProfileAttribute = (
  profileId: string, sourceKey: string, targetProfileId: string, targetKey: string,
): Promise<ProfileMapping> =>
  apiPut<ProfileMapping>(profileMappingsPath(profileId), { sourceKey, targetProfileId, targetKey });

export const unmapProfileAttribute = (profileId: string, mappingId: string): Promise<void> =>
  apiDelete(`${profileMappingsPath(profileId)}/${encodeURIComponent(mappingId)}`);

/** A person's attributes live in a profile; every other entity kind is a tag outside one. */
export const attributeDefinitionsPath = (kind: AttributeEntityKind, profileId?: string) =>
  kind === "USER" && profileId
    ? `${profilesPath}/${encodeURIComponent(profileId)}/attributes`
    : `/api/admin/attribute-definitions?entityKind=${kind}`;

export const listAttributeDefinitions = (
  kind: AttributeEntityKind, profileId?: string,
): Promise<AttributeDefinition[]> =>
  apiGet<AttributeDefinition[]>(attributeDefinitionsPath(kind, profileId));

export const saveAttributeDefinition = (
  body: AttributeDefinitionInput, profileId?: string,
): Promise<AttributeDefinition> =>
  body.entityKind === "USER" && profileId
    ? apiPost<AttributeDefinition>(`${profilesPath}/${encodeURIComponent(profileId)}/attributes`, body)
    : apiPost<AttributeDefinition>("/api/admin/attribute-definitions", body);

export const deleteAttributeDefinition = (id: string): Promise<void> =>
  apiDelete(`/api/admin/attribute-definitions/${encodeURIComponent(id)}`);

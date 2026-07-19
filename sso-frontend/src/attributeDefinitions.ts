import { apiDelete, apiGet, apiPost } from "./api";

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

export const attributeDefinitionsPath = (kind: AttributeEntityKind) =>
  `/api/admin/attribute-definitions?entityKind=${kind}`;

export const listAttributeDefinitions = (kind: AttributeEntityKind): Promise<AttributeDefinition[]> =>
  apiGet<AttributeDefinition[]>(attributeDefinitionsPath(kind));

export const saveAttributeDefinition = (body: AttributeDefinitionInput): Promise<AttributeDefinition> =>
  apiPost<AttributeDefinition>("/api/admin/attribute-definitions", body);

export const deleteAttributeDefinition = (id: string): Promise<void> =>
  apiDelete(`/api/admin/attribute-definitions/${encodeURIComponent(id)}`);

import { apiGet, apiPut, apiDelete } from "@/api";

/** A single metadata attribute (key/value tag) on an entity. */
export interface Attribute {
  key: string;
  value: string;
}

/** The entity kinds that carry metadata. */
export type MetadataKind = "users" | "groups" | "resources";

// Users/groups are edited through the metadata admin API; a resource through its own controller (whose
// tier-aware scope check is the right guard for it). Both return the entity's fresh attribute list.
const base = (kind: MetadataKind, id: string) =>
  kind === "resources" ? `/api/admin/resources/${id}/metadata` : `/api/admin/metadata/${kind}/${id}`;

export const getAttributes = (kind: MetadataKind, id: string) => apiGet<Attribute[]>(base(kind, id));

export const setAttribute = (kind: MetadataKind, id: string, key: string, value: string) =>
  apiPut<Attribute[]>(base(kind, id), { key, value });

export const removeAttribute = (kind: MetadataKind, id: string, key: string) =>
  apiDelete(`${base(kind, id)}/${encodeURIComponent(key)}`);

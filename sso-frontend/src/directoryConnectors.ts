import { apiDelete, apiGet, apiPost, apiPut } from "./api";

export type DirectoryConnectorKind = "LDAP" | "GOOGLE_WORKSPACE" | "ENTRA_ID";

/** A connector as the admin API returns it — it structurally cannot carry the bind password. */
export interface DirectoryConnector {
  id: string;
  name: string;
  displayName: string;
  kind: DirectoryConnectorKind;
  enabled: boolean;
  host: string;
  port: number;
  useSsl: boolean;
  startTls: boolean;
  bindDn: string | null;
  baseDn: string;
  userFilter: string;
  externalIdAttribute: string;
}

/** What the connector form submits. `bindPassword` is write-only: blank on an edit keeps the stored one. */
export interface DirectoryConnectorInput {
  displayName: string;
  kind: DirectoryConnectorKind;
  enabled: boolean;
  host: string;
  port: number;
  useSsl: boolean;
  startTls: boolean;
  bindDn: string;
  bindPassword: string;
  baseDn: string;
  userFilter: string;
  externalIdAttribute: string;
}

export interface DirectoryAttributeMapping {
  id: string;
  sourceAttribute: string;
  targetKey: string;
}

/** What one run did. The counts are how "nothing matched" is told apart from "nothing changed". */
export interface DirectorySyncRun {
  id: string;
  startedAt: string;
  finishedAt: string | null;
  status: "RUNNING" | "SUCCEEDED" | "FAILED";
  entriesRead: number;
  matched: number;
  updated: number;
  skipped: number;
  error: string | null;
}

const base = "/api/admin/directory-connectors";
const at = (name: string) => `${base}/${encodeURIComponent(name)}`;

export const directoryConnectorsPath = base;
export const mappingsPath = (name: string) => `${at(name)}/mappings`;
export const runsPath = (name: string) => `${at(name)}/runs`;

export const listDirectoryConnectors = (): Promise<DirectoryConnector[]> =>
  apiGet<DirectoryConnector[]>(base);

/** Upsert by name (the backend keys on the {name} path; a blank password keeps the stored one). */
export const saveDirectoryConnector = (
  name: string, body: DirectoryConnectorInput,
): Promise<DirectoryConnector> => apiPut<DirectoryConnector>(at(name), body);

export const deleteDirectoryConnector = (name: string): Promise<void> => apiDelete(at(name));

export const mapDirectoryAttribute = (
  name: string, sourceAttribute: string, targetKey: string,
): Promise<DirectoryAttributeMapping[]> =>
  apiPut<DirectoryAttributeMapping[]>(mappingsPath(name), { sourceAttribute, targetKey });

export const unmapDirectoryAttribute = (name: string, mappingId: string): Promise<void> =>
  apiDelete(`${mappingsPath(name)}/${encodeURIComponent(mappingId)}`);

/** Runs it now; resolves with what the run DID, so the UI can report rather than just say "started". */
export const syncDirectoryNow = (name: string): Promise<DirectorySyncRun> =>
  apiPost<DirectorySyncRun>(`${at(name)}/sync`);

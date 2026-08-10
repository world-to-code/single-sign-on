import { apiDelete, apiGet, apiPut } from "@/api";

/**
 * How the export is doing, as opposed to how it is configured.
 *
 * A collector that accepts every batch while falling further behind produces no failures at all, so the
 * failure count alone would report a healthy export describing hours ago. `behindBySeconds` is the honest
 * number and is computed by the server — comparing a browser clock to a server timestamp would measure skew
 * as lag.
 */
export interface AuditExportHealth {
  /** When a batch was last acknowledged; null if none ever has been. */
  lastSuccessAt: string | null;
  /** The timestamp of the newest event the collector has; null before the first batch. */
  position: string | null;
  /** How stale the collector's copy is; null when nothing has shipped yet — unknown, not zero. */
  behindBySeconds: number | null;
  /** Counted across the whole deployment, not per node. */
  consecutiveFailures: number;
}

/** The collector as the console sees it. The credential is write-only and is never part of this. */
export interface AuditExportSettings {
  endpointUrl: string;
  enabled: boolean;
  includePii: boolean;
  updatedAt: string;
  updatedBy: string | null;
  health: AuditExportHealth;
}

export interface AuditExportInput {
  endpointUrl: string;
  /** Blank keeps the stored credential; required only when there is none yet. */
  credential: string | null;
  enabled: boolean;
  includePii: boolean;
}

/**
 * The collector, or `undefined` when none is configured — the endpoint answers 204 for that.
 *
 * Deliberately NOT caught into `undefined`: a refusal is not "nothing configured", and collapsing the two
 * would show an empty form to an admin who was actually denied.
 */
export const getAuditExport = (): Promise<AuditExportSettings | undefined> =>
  apiGet<AuditExportSettings | undefined>("/api/admin/audit/export");

export const updateAuditExport = (body: AuditExportInput): Promise<void> =>
  apiPut<void>("/api/admin/audit/export", body);

export const deleteAuditExport = (): Promise<void> => apiDelete("/api/admin/audit/export");

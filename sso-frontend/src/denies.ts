import { apiDelete, apiPost } from "@/api";

export type DenySubjectKind = "USER" | "ROLE" | "GROUP" | "ORG";

/** A USER-level deny row shown against a user — its id (to lift) and the withheld permission pattern. */
export interface UserDenyRow {
  id: string;
  pattern: string;
}

/** Authors a deny (dedicated verb; the server enforces the per-subject authorization + last-admin guard). */
export const createDeny = (kind: DenySubjectKind, subjectId: string, pattern: string) =>
  apiPost<{ id: string }>("/api/admin/denies", { kind, subjectId, pattern });

/** Lifts a deny by id; the server re-checks that the caller may lift it. */
export const liftDeny = (id: string, kind: DenySubjectKind): Promise<void> =>
  apiDelete(`/api/admin/denies/${encodeURIComponent(id)}?kind=${kind}`);

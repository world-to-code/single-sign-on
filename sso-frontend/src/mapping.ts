import { apiGet, apiPost, apiPut } from "./api";

export type MappingTargetKind = "GROUP" | "ROLE";

/** An auto-mapping rule: users carrying attrKey=attrValue are assigned to the target (group or role). */
export interface MappingRule {
  id: string;
  attrKey: string;
  attrValue: string;
  thenKind: MappingTargetKind;
  targetId: string;
  targetName: string | null;
  assignedCount: number;
}

/** Dry-run result: how many users the predicate matches now, plus a capped sample. */
export interface MappingPreview {
  matchedCount: number;
  sample: { id: string; username: string }[];
}

export interface MappingRuleRequest {
  attrKey: string;
  attrValue: string;
  thenKind: MappingTargetKind;
  targetId: string;
}

export const listMappingRules = (): Promise<MappingRule[]> => apiGet<MappingRule[]>("/api/admin/mapping-rules");

export const createMappingRule = (body: MappingRuleRequest): Promise<MappingRule> =>
  apiPost<MappingRule>("/api/admin/mapping-rules", body);

export const updateMappingRule = (id: string, body: MappingRuleRequest): Promise<MappingRule> =>
  apiPut<MappingRule>(`/api/admin/mapping-rules/${id}`, body);

export const previewMappingRule = (body: MappingRuleRequest): Promise<MappingPreview> =>
  apiPost<MappingPreview>("/api/admin/mapping-rules/preview", body);

import { apiGet, apiPost, apiPut } from "./api";

/** An auto-mapping rule: users carrying attrKey=attrValue are added to the target group. */
export interface MappingRule {
  id: string;
  attrKey: string;
  attrValue: string;
  thenKind: string;
  groupId: string;
  groupName: string | null;
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
  groupId: string;
}

export const listMappingRules = (): Promise<MappingRule[]> => apiGet<MappingRule[]>("/api/admin/mapping-rules");

export const createMappingRule = (body: MappingRuleRequest): Promise<MappingRule> =>
  apiPost<MappingRule>("/api/admin/mapping-rules", body);

export const updateMappingRule = (id: string, body: MappingRuleRequest): Promise<MappingRule> =>
  apiPut<MappingRule>(`/api/admin/mapping-rules/${id}`, body);

export const previewMappingRule = (body: MappingRuleRequest): Promise<MappingPreview> =>
  apiPost<MappingPreview>("/api/admin/mapping-rules/preview", body);

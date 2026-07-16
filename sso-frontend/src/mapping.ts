import { apiGet, apiPost, apiPut } from "./api";

export type MappingTargetKind = "GROUP" | "ROLE" | "RESOURCE_MEMBER";

/** Predicate operator for a mapping rule. Mapping forbids the NOT_* operators the policy targeting allows. */
export type MappingAttrOp = "EQUALS" | "EXISTS";

/** An auto-mapping rule: users whose attribute satisfies the predicate are assigned to the target. */
export interface MappingRule {
  id: string;
  attrKey: string;
  attrOp: MappingAttrOp;
  attrValue: string | null;
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
  attrOp: MappingAttrOp;
  attrValue?: string; // omitted for the EXISTS operator
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

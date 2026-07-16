import { apiGet, apiPost, apiPut } from "./api";

export type MappingTargetKind = "GROUP" | "ROLE" | "RESOURCE_MEMBER";

/** Predicate operator for a mapping rule. Mapping forbids the NOT_* operators the policy targeting allows. */
export type MappingAttrOp = "EQUALS" | "EXISTS";

/** A single attribute predicate; a rule's conditions are AND-combined. `attrValue` is null for EXISTS. */
export interface MappingCondition {
  attrKey: string;
  attrOp: MappingAttrOp;
  attrValue: string | null;
}

/** An auto-mapping rule: users whose attributes satisfy every condition (AND) are assigned to the target. */
export interface MappingRule {
  id: string;
  conditions: MappingCondition[];
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

export interface MappingConditionRequest {
  attrKey: string;
  attrOp: MappingAttrOp;
  attrValue?: string; // omitted for the EXISTS operator
}

export interface MappingRuleRequest {
  conditions: MappingConditionRequest[]; // at least one; all must match (AND)
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

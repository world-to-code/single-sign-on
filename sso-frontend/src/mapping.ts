import { apiGet, apiPost, apiPut } from "./api";

export type MappingTargetKind = "GROUP" | "ROLE" | "RESOURCE_MEMBER";

/** Predicate operator for a mapping rule. Mapping forbids the NOT_* operators the policy targeting allows. */
export type MappingAttrOp = "EQUALS" | "EXISTS" | "IN";

/**
 * A single attribute predicate; a rule's conditions are AND-combined. For EQUALS `attrValue` is set and
 * `attrValues` is empty; for IN `attrValue` is null and `attrValues` is non-empty; for EXISTS both are empty/null.
 */
export interface MappingCondition {
  attrKey: string;
  attrOp: MappingAttrOp;
  attrValue: string | null;
  attrValues: string[];
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
  attrValue?: string; // set for EQUALS; omitted for EXISTS and IN
  attrValues?: string[]; // non-empty for IN; omitted otherwise
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

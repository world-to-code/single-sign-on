import { useState } from "react";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { Plus, Trash2, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { ValueListInput } from "@/components/ValueListInput";

/** Predicate operator. Key operators (EXISTS / NOT_EXISTS) test presence and carry no value; IN matches a value list. */
export type AttrOperator = "EQUALS" | "NOT_EQUALS" | "CONTAINS" | "EXISTS" | "NOT_EXISTS" | "IN";

const OPERATORS: AttrOperator[] = ["EQUALS", "NOT_EQUALS", "CONTAINS", "IN", "EXISTS", "NOT_EXISTS"];

/** EQUALS / NOT_EQUALS / CONTAINS compare against a scalar value; IN matches a list; EXISTS / NOT_EXISTS test presence. */
function attrOperatorNeedsValue(op: AttrOperator): boolean {
  return op === "EQUALS" || op === "NOT_EQUALS" || op === "CONTAINS";
}

/** IN matches a non-empty value list; the other operators carry a scalar value or nothing. */
function attrOperatorNeedsValues(op: AttrOperator): boolean {
  return op === "IN";
}

/** One predicate: (key, operator, value | values). The key operators drop both; IN uses `values`, the rest `value`. */
export interface AttrCondition {
  key: string;
  operator: AttrOperator;
  value: string; // used by EQUALS / NOT_EQUALS / CONTAINS; empty otherwise
  values: string[]; // used by IN; empty otherwise
}

/** A target is an AND-group of conditions (all must hold). It must carry at least one condition. */
export interface AttributeTarget {
  conditions: AttrCondition[];
}

/** Wire shape read from the API: a group of conditions (each may carry an ignored empty `values`). Legacy rows
 *  stored before groups existed arrive as a bare single predicate, tolerated by {@link parseAttributeTargets}. */
export interface AttributeConditionWire {
  key?: string;
  operator?: AttrOperator;
  value?: string;
  values?: string[]; // populated for IN, empty/absent otherwise.
}
export interface AttributeTargetWire extends AttributeConditionWire {
  conditions?: AttributeConditionWire[];
}

const blankCondition = (): AttrCondition => ({ key: "", operator: "EQUALS", value: "", values: [] });

/** An IN condition's non-empty values, trimmed. */
const trimmedValues = (c: AttrCondition): string[] => c.values.map((v) => v.trim()).filter(Boolean);

/**
 * Complete when it has a key, plus (EQUALS / NOT_EQUALS / CONTAINS) a value or (IN) at least one list value;
 * the key operators (EXISTS / NOT_EXISTS) need only the key.
 */
function conditionComplete(c: AttrCondition): boolean {
  if (!c.key.trim()) return false;
  if (attrOperatorNeedsValues(c.operator)) return trimmedValues(c).length > 0;
  return !attrOperatorNeedsValue(c.operator) || c.value.trim().length > 0;
}

/** Trim; keep only the field the operator uses (scalar `value` for the value ops, `values` for IN, neither for keys). */
function normalizeCondition(c: AttrCondition): AttrCondition {
  return {
    key: c.key.trim(),
    operator: c.operator,
    value: attrOperatorNeedsValue(c.operator) ? c.value.trim() : "",
    values: attrOperatorNeedsValues(c.operator) ? trimmedValues(c) : [],
  };
}

/** Parse API groups into editor targets, tolerating the pre-group single-predicate shape (defaults to EQUALS). */
export function parseAttributeTargets(raw: readonly AttributeTargetWire[] | null | undefined): AttributeTarget[] {
  return (raw ?? [])
    .map((g) => {
      const rows = Array.isArray(g.conditions) ? g.conditions : [g];
      return {
        conditions: rows.map((c) => ({
          key: c.key ?? "", operator: c.operator ?? "EQUALS", value: c.value ?? "", values: c.values ?? [],
        })),
      };
    })
    .filter((g) => g.conditions.length > 0);
}

/** Build the request body: each target sends its AND conditions; IN sends `values`, the key operators send neither. */
export function attributeTargetsToRequest(targets: AttributeTarget[]): { conditions: AttributeConditionWire[] }[] {
  return targets.map((g) => ({
    conditions: g.conditions.map((c) => {
      if (attrOperatorNeedsValues(c.operator)) return { key: c.key, operator: c.operator, values: c.values };
      if (attrOperatorNeedsValue(c.operator)) return { key: c.key, operator: c.operator, value: c.value };
      return { key: c.key, operator: c.operator };
    }),
  }));
}

/** Option label for the operator picker. */
function operatorLabel(op: AttrOperator, t: TFunction<"console">): string {
  switch (op) {
    case "EQUALS": return t("attrTargetOpEquals");
    case "NOT_EQUALS": return t("attrTargetOpNotEquals");
    case "CONTAINS": return t("attrTargetOpContains");
    case "EXISTS": return t("attrTargetOpExists");
    case "NOT_EXISTS": return t("attrTargetOpNotExists");
    case "IN": return t("attrTargetOpIn");
  }
}

/** Infix glyph for the value operators: `=` / `≠` / `contains`. */
function valueOperatorSymbol(op: AttrOperator, t: TFunction<"console">): string {
  if (op === "NOT_EQUALS") return "≠";
  if (op === "CONTAINS") return t("attrTargetOpContains");
  return "=";
}

/** Human-readable single predicate, e.g. `dept = eng`, `dept ≠ eng`, `dept contains eng`, `dept exists`. */
function describeCondition(c: AttrCondition, t: TFunction<"console">): string {
  if (c.operator === "EXISTS") return `${c.key} ${t("attrTargetOpExists")}`;
  if (c.operator === "NOT_EXISTS") return `${c.key} ${t("attrTargetOpNotExists")}`;
  if (c.operator === "IN") return `${c.key} ${t("mappingRulesIn")} (${c.values.join(", ")})`;
  return `${c.key} ${valueOperatorSymbol(c.operator, t)} ${c.value}`;
}

/** AND-joined target, e.g. `dept = eng AND level = senior AND clearance exists`. */
function describeGroup(g: AttributeTarget, t: TFunction<"console">): string {
  return g.conditions.map((c) => describeCondition(c, t)).join(` ${t("mappingRulesAnd")} `);
}

const groupIdentity = (g: AttributeTarget) =>
  g.conditions.map((c) => `${c.key} ${c.operator} ${c.value} ${c.values.join(",")}`).join(" AND ");

/**
 * Controlled editor for a policy's metadata-predicate targets. Each target is an AND-group of conditions
 * (key, operator, value); the key operators (EXISTS / NOT_EXISTS) drop the value. Admins build a target from
 * one or more condition rows, then add it to the list; whole targets can be removed. The targets live in the
 * parent policy form and are saved with it, so this component mutates only the passed-in list via {@link onChange}
 * — it does not persist. Reused by the auth- and session-policy assignment tabs.
 */
export function AttributeTargetEditor({ value, onChange }: {
  value: AttributeTarget[];
  onChange: (next: AttributeTarget[]) => void;
}) {
  const { t } = useTranslation("console");
  const [draft, setDraft] = useState<AttrCondition[]>([blankCondition()]);

  const setCondition = (i: number, patch: Partial<AttrCondition>) =>
    setDraft((d) => d.map((c, idx) => (idx === i ? { ...c, ...patch } : c)));
  const addCondition = () => setDraft((d) => [...d, blankCondition()]);
  const removeCondition = (i: number) => setDraft((d) => (d.length === 1 ? d : d.filter((_, idx) => idx !== i)));

  const nextGroup = (): AttributeTarget => ({ conditions: draft.map(normalizeCondition) });
  const canAdd =
    draft.every(conditionComplete) &&
    !value.some((g) => groupIdentity(g) === groupIdentity(nextGroup()));

  function add() {
    if (!canAdd) return;
    onChange([...value, nextGroup()]);
    setDraft([blankCondition()]);
  }

  function remove(target: AttributeTarget) {
    onChange(value.filter((g) => groupIdentity(g) !== groupIdentity(target)));
  }

  return (
    <div className="space-y-2">
      {value.length === 0 && <p className="text-sm text-muted-foreground">{t("attrTargetNone")}</p>}
      {value.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {value.map((g) => (
            <span
              key={groupIdentity(g)}
              className="inline-flex flex-wrap items-center gap-1 rounded-md bg-muted py-0.5 pl-2.5 pr-1 text-xs"
            >
              {g.conditions.map((c, i) => (
                <span key={i} className="inline-flex items-center gap-1">
                  {i > 0 && <span className="text-muted-foreground">{t("mappingRulesAnd")}</span>}
                  <span className="font-mono">{c.key}</span>
                  {c.operator === "EXISTS" || c.operator === "NOT_EXISTS" ? (
                    <span className="text-muted-foreground">{operatorLabel(c.operator, t)}</span>
                  ) : c.operator === "IN" ? (
                    <>
                      <span className="text-muted-foreground">{t("mappingRulesIn")} (</span>
                      <span>{c.values.join(", ")}</span>
                      <span className="text-muted-foreground">)</span>
                    </>
                  ) : (
                    <>
                      <span className="text-muted-foreground">{valueOperatorSymbol(c.operator, t)}</span>
                      <span>{c.value}</span>
                    </>
                  )}
                </span>
              ))}
              <button
                type="button"
                aria-label={t("attrTargetRemove", { predicate: describeGroup(g, t) })}
                className="rounded-full p-0.5 text-muted-foreground hover:text-destructive"
                onClick={() => remove(g)}
              >
                <X className="size-3" />
              </button>
            </span>
          ))}
        </div>
      )}

      <div className="space-y-2 rounded-md border p-3">
        {draft.map((c, i) => {
          const needsValue = attrOperatorNeedsValue(c.operator);
          const needsValues = attrOperatorNeedsValues(c.operator);
          return (
            <div key={i} className="flex flex-wrap items-center gap-2">
              {i > 0 && <span className="text-xs text-muted-foreground">{t("mappingRulesAnd")}</span>}
              <Input
                className="max-w-44"
                value={c.key}
                onChange={(e) => setCondition(i, { key: e.target.value })}
                placeholder={t("attrTargetKey")}
              />
              <Select
                className="max-w-40"
                aria-label={t("attrTargetOperator")}
                value={c.operator}
                onChange={(e) => setCondition(i, { operator: e.target.value as AttrOperator })}
              >
                {OPERATORS.map((o) => (
                  <option key={o} value={o}>{operatorLabel(o, t)}</option>
                ))}
              </Select>
              {needsValue && (
                <Input
                  className="max-w-52"
                  value={c.value}
                  onChange={(e) => setCondition(i, { value: e.target.value })}
                  placeholder={t("attrTargetValue")}
                />
              )}
              {needsValues && (
                <div className="max-w-52 flex-1">
                  <ValueListInput
                    value={c.values}
                    onChange={(vs) => setCondition(i, { values: vs })}
                    placeholder={t("mappingRulesValuesPlaceholder")}
                  />
                </div>
              )}
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="text-muted-foreground hover:text-destructive"
                disabled={draft.length === 1}
                aria-label={t("attrTargetRemoveCondition")}
                onClick={() => removeCondition(i)}
              >
                <Trash2 className="size-4" />
              </Button>
            </div>
          );
        })}
        <div className="flex flex-wrap items-center gap-2">
          <Button type="button" variant="outline" size="sm" onClick={addCondition}>
            <Plus /> {t("attrTargetAddCondition")}
          </Button>
          <Button type="button" size="sm" disabled={!canAdd} onClick={add}>
            <Plus /> {t("attrTargetAddTarget")}
          </Button>
        </div>
      </div>
    </div>
  );
}

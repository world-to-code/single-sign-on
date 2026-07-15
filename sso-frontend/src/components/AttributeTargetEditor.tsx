import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Plus, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

export interface AttributeTarget {
  key: string;
  value: string;
}

/**
 * Controlled editor for a policy's metadata-predicate targets (key = value). The predicates live in the parent
 * policy form and are saved with it, so this component mutates only the passed-in list via {@link onChange} — it
 * does not persist. Reused by the auth- and session-policy assignment tabs.
 */
export function AttributeTargetEditor({ value, onChange }: {
  value: AttributeTarget[];
  onChange: (next: AttributeTarget[]) => void;
}) {
  const { t } = useTranslation("console");
  const [key, setKey] = useState("");
  const [val, setVal] = useState("");

  const canAdd =
    key.trim().length > 0 &&
    val.trim().length > 0 &&
    !value.some((a) => a.key === key.trim() && a.value === val.trim());

  function add() {
    if (!canAdd) return;
    onChange([...value, { key: key.trim(), value: val.trim() }]);
    setKey("");
    setVal("");
  }

  function remove(target: AttributeTarget) {
    onChange(value.filter((a) => !(a.key === target.key && a.value === target.value)));
  }

  return (
    <div className="space-y-2">
      {value.length === 0 && <p className="text-sm text-muted-foreground">{t("attrTargetNone")}</p>}
      {value.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {value.map((a) => (
            <span
              key={`${a.key}=${a.value}`}
              className="inline-flex items-center gap-1 rounded-full bg-muted py-0.5 pl-2.5 pr-1 text-xs"
            >
              <span className="font-mono">{a.key}</span>
              <span className="text-muted-foreground">=</span>
              {a.value}
              <button
                type="button"
                aria-label={t("attrTargetRemove", { key: a.key, value: a.value })}
                className="rounded-full p-0.5 text-muted-foreground hover:text-destructive"
                onClick={() => remove(a)}
              >
                <X className="size-3" />
              </button>
            </span>
          ))}
        </div>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <Input
          className="max-w-44"
          value={key}
          onChange={(e) => setKey(e.target.value)}
          placeholder={t("attrTargetKey")}
          onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); add(); } }}
        />
        <Input
          className="max-w-52"
          value={val}
          onChange={(e) => setVal(e.target.value)}
          placeholder={t("attrTargetValue")}
          onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); add(); } }}
        />
        <Button type="button" size="sm" variant="outline" disabled={!canAdd} onClick={add}>
          <Plus /> {t("attrTargetAdd")}
        </Button>
      </div>
    </div>
  );
}

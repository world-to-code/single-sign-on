import { useState } from "react";
import { useTranslation } from "react-i18next";
import { X } from "lucide-react";
import { Input } from "@/components/ui/input";

/**
 * Controlled chip input for an unordered set of string values (e.g. a mapping condition's IN list). Committed
 * values render as removable chips in the shared chip style; the draft input commits on Enter or comma and
 * dedupes/trims. The list lives in the parent form and is saved with it — this component only mutates the
 * passed-in list via {@link onChange}, it does not persist.
 */
export function ValueListInput({ value, onChange, placeholder, inputId }: {
  value: string[];
  onChange: (next: string[]) => void;
  placeholder?: string;
  inputId?: string;
}) {
  const { t } = useTranslation("console");
  const [draft, setDraft] = useState("");

  const commit = () => {
    const v = draft.trim();
    if (v && !value.includes(v)) onChange([...value, v]);
    setDraft("");
  };
  const remove = (v: string) => onChange(value.filter((item) => item !== v));

  return (
    <div className="space-y-2">
      {value.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {value.map((v) => (
            <span key={v} className="inline-flex items-center gap-1 rounded-full bg-muted py-0.5 pl-2.5 pr-1 text-xs">
              <span className="font-mono">{v}</span>
              <button
                type="button"
                aria-label={t("valueListRemove", { value: v })}
                className="rounded-full p-0.5 text-muted-foreground hover:text-destructive"
                onClick={() => remove(v)}
              >
                <X className="size-3" />
              </button>
            </span>
          ))}
        </div>
      )}
      <Input
        id={inputId}
        className="font-mono"
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        placeholder={placeholder}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === ",") { e.preventDefault(); commit(); }
          else if (e.key === "Backspace" && draft === "" && value.length > 0) { remove(value[value.length - 1]); }
        }}
        onBlur={commit}
      />
    </div>
  );
}

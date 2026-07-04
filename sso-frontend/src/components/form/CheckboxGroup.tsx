import { Checkbox } from "@/components/ui/checkbox";

export interface CheckboxOption {
  value: string;
  label: string;
}

/**
 * A bordered, wrapping set of checkboxes over a fixed option list — the shared "pick several from a
 * small set" control used by role pickers, member-kind pickers, etc. Selection is a controlled string[].
 */
export function CheckboxGroup({ options, selected, onToggle, emptyText = "No options" }: {
  options: CheckboxOption[];
  selected: string[];
  onToggle: (value: string) => void;
  emptyText?: string;
}) {
  return (
    <div className="flex flex-wrap gap-3 rounded-md border p-3">
      {options.length === 0 ? (
        <span className="text-sm text-muted-foreground">{emptyText}</span>
      ) : (
        options.map((o) => (
          <label key={o.value} className="flex cursor-pointer items-center gap-2 text-sm">
            <Checkbox checked={selected.includes(o.value)} onCheckedChange={() => onToggle(o.value)} /> {o.label}
          </label>
        ))
      )}
    </div>
  );
}

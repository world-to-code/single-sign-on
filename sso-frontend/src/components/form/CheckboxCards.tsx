import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";

export interface CheckboxOption {
  value: string;
  label: string;
  hint?: string;
}

/**
 * A labelled grid of selectable option cards (multi-select) for a fixed protocol value set — so users
 * pick from known options instead of typing delimited strings. Mirrors the role-picker card styling.
 */
export function CheckboxCards({ label, hint, options, selected, onToggle, columns = 2 }: {
  label: string;
  hint?: string;
  options: CheckboxOption[];
  selected: string[];
  onToggle: (value: string) => void;
  columns?: 1 | 2;
}) {
  return (
    <div className="grid gap-1.5">
      <Label>{label}</Label>
      <div className={`grid gap-1.5 ${columns === 2 ? "sm:grid-cols-2" : ""}`}>
        {options.map((opt) => (
          <label key={opt.value}
            className="flex cursor-pointer items-start gap-2.5 rounded-md border p-2.5 text-sm transition-colors hover:bg-muted/60 has-[:checked]:border-primary has-[:checked]:bg-accent">
            <Checkbox className="mt-0.5 size-4" checked={selected.includes(opt.value)}
                      onCheckedChange={() => onToggle(opt.value)} />
            <span>
              <span className="font-medium">{opt.label}</span>
              {opt.hint && <span className="block text-xs text-muted-foreground">{opt.hint}</span>}
            </span>
          </label>
        ))}
      </div>
      {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}

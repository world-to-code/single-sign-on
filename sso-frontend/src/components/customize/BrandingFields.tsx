import { Field } from "../form/fields";
import { Input } from "../ui/input";
import { cn } from "@/lib/utils";

/**
 * The three field shapes the branding editor repeats: an https URL, a `#RRGGBB` colour, and a pick from a
 * closed set. Extracted because the editor now has four URLs, two colours and three choices — nine fields
 * that would otherwise be nine copies of the same markup drifting apart one placeholder at a time.
 */

/** An https asset URL. Validation is the server's; this only shapes the input and says what it expects. */
export function UrlField({ label, hint, value, placeholder, error, onChange }: {
  label: string;
  hint?: string;
  value: string;
  placeholder: string;
  error?: string;
  onChange: (value: string) => void;
}) {
  return (
    <Field label={label} hint={hint} error={error}>
      <Input type="url" value={value} placeholder={placeholder}
             onChange={(e) => onChange(e.target.value)} />
    </Field>
  );
}

/**
 * A colour, editable as a swatch or as text. The swatch needs a valid hex to show anything, so it falls back
 * to `fallback` while the text box holds something half-typed — without that the picker resets to black on
 * every keystroke, which silently overwrites what the user was in the middle of writing.
 */
export function ColorField({ label, hint, value, fallback, onChange }: {
  label: string;
  hint?: string;
  value: string;
  fallback: string;
  onChange: (value: string) => void;
}) {
  const valid = /^#[0-9a-fA-F]{6}$/.test(value);
  return (
    <Field label={label} hint={hint}>
      <div className="flex items-center gap-2">
        <input type="color" aria-label={label} value={valid ? value : fallback}
               onChange={(e) => onChange(e.target.value)}
               className="h-9 w-12 shrink-0 cursor-pointer rounded-md border border-input bg-transparent" />
        <Input value={value} placeholder={fallback} className="font-mono"
               onChange={(e) => onChange(e.target.value)} />
      </div>
    </Field>
  );
}

/**
 * A pick from a closed set, rendered as a segmented control. `null` is a real option — "inherit" — and it is
 * listed first so a tenant can always get back to it without knowing which value they started from.
 */
export function ChoiceField<T extends string>({ label, hint, value, options, inheritLabel, labelFor, onChange }: {
  label: string;
  hint?: string;
  value: T | "";
  options: readonly T[];
  inheritLabel: string;
  labelFor: (option: T) => string;
  onChange: (value: T | "") => void;
}) {
  return (
    <Field label={label} hint={hint}>
      <div role="radiogroup" aria-label={label} className="flex flex-wrap gap-1.5">
        <ChoiceOption selected={value === ""} label={inheritLabel} onSelect={() => onChange("")} />
        {options.map((option) => (
          <ChoiceOption key={option} selected={value === option} label={labelFor(option)}
                        onSelect={() => onChange(option)} />
        ))}
      </div>
    </Field>
  );
}

function ChoiceOption({ selected, label, onSelect }: {
  selected: boolean;
  label: string;
  onSelect: () => void;
}) {
  return (
    <button type="button" role="radio" aria-checked={selected} onClick={onSelect}
            className={cn("rounded-md border px-3 py-1.5 text-sm transition-colors",
                          selected
                            ? "border-primary bg-primary/10 font-medium text-primary"
                            : "border-input text-muted-foreground hover:text-foreground")}>
      {label}
    </button>
  );
}

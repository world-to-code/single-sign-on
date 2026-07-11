import { cloneElement, isValidElement, useEffect, useId } from "react";
import type { ReactElement, ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { cn } from "@/lib/utils";

/** Props DataList/Field clone onto the control to wire ARIA + the invalid skin. */
type ControlProps = { id?: string; className?: string; "aria-invalid"?: boolean; "aria-describedby"?: string };

/**
 * Labelled field wrapper used by create/edit dialogs and settings forms. When `error` is set the
 * control gets `aria-invalid` + `aria-describedby`, a --deny border with a ~4% tint, and a
 * `role="alert"` message with an icon beneath — and the error REPLACES the hint (never stacked).
 * Validate on blur and submit, not on every keystroke.
 */
export function Field({ id, label, hint, error, children }: {
  id?: string;
  label: string;
  hint?: string;
  error?: string;
  children: ReactNode;
}) {
  const generatedId = useId();
  const controlId = id ?? generatedId;
  const msgId = `${controlId}-msg`;

  const control = isValidElement(children)
    ? cloneElement(children as ReactElement<ControlProps>, {
        id: controlId,
        "aria-invalid": error ? true : undefined,
        "aria-describedby": error ? msgId : undefined,
        className: cn(
          error && "border-deny bg-deny/[0.04] focus-visible:ring-deny",
          (children as ReactElement<ControlProps>).props.className,
        ),
      })
    : children;

  return (
    <div className="grid gap-1.5">
      <Label htmlFor={controlId}>{label}</Label>
      {control}
      {error ? (
        <p id={msgId} role="alert" className="flex items-start gap-1.5 text-xs text-deny">
          <AlertCircle className="mt-px size-3.5 shrink-0" />
          <span>{error}</span>
        </p>
      ) : hint ? (
        <p className="text-xs text-muted-foreground">{hint}</p>
      ) : null}
    </div>
  );
}

export interface FieldError {
  /** The id of the invalid control (matches the Field's `id`), used as the anchor target. */
  id: string;
  label: string;
  message: string;
}

/**
 * Failed-submit summary (DESIGN.md §6): an alert at the top of the form listing the count and anchor
 * links to each bad field. On appearance, focus moves to the first invalid control.
 */
export function FormErrorSummary({ errors }: { errors: FieldError[] }) {
  const { t } = useTranslation("validation");
  const firstId = errors[0]?.id;
  useEffect(() => {
    if (firstId) document.getElementById(firstId)?.focus();
  }, [firstId]);

  if (errors.length === 0) return null;

  return (
    <Alert variant="destructive" className="mb-5">
      <AlertCircle className="size-4" />
      <AlertTitle>
        {errors.length === 1
          ? t("oneFieldNeedsAttention")
          : t("fieldsNeedAttention", { count: errors.length })}
      </AlertTitle>
      <AlertDescription>
        <ul className="mt-1 space-y-0.5">
          {errors.map((e) => (
            <li key={e.id}>
              <a
                href={`#${e.id}`}
                onClick={(ev) => {
                  ev.preventDefault();
                  document.getElementById(e.id)?.focus();
                }}
                className="underline underline-offset-2 hover:no-underline"
              >
                {e.label}
              </a>
              <span className="text-muted-foreground"> — {e.message}</span>
            </li>
          ))}
        </ul>
      </AlertDescription>
    </Alert>
  );
}

/** Bordered label + description row with a trailing Switch. */
export function Toggle({ label, hint, checked, onChange, disabled }: {
  label: string;
  hint?: string;
  checked: boolean;
  onChange: (value: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <div className="flex items-center justify-between gap-4 rounded-lg border p-3">
      <div>
        <p className="text-sm font-medium">{label}</p>
        {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
      </div>
      <Switch checked={checked} onCheckedChange={onChange} disabled={disabled} />
    </div>
  );
}

import type { InputHTMLAttributes } from "react";
import { cn } from "@/lib/utils";

/** Native checkbox with a Switch-style controlled API (checked + onCheckedChange). */
export interface CheckboxProps
  extends Omit<InputHTMLAttributes<HTMLInputElement>, "checked" | "onChange" | "type"> {
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
}

export function Checkbox({ checked, onCheckedChange, className, ...props }: CheckboxProps) {
  return (
    <input
      type="checkbox"
      checked={checked}
      onChange={(e) => onCheckedChange(e.target.checked)}
      className={cn("accent-primary", className)}
      {...props}
    />
  );
}

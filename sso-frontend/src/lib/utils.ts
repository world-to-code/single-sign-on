import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/** Merge conditional class names, de-duplicating conflicting Tailwind utilities. */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** Split a delimited string into trimmed, non-empty tokens (default: split on whitespace/commas). */
export function tokens(value: string, separator: RegExp | string = /[\s,]+/): string[] {
  return value.split(separator).map((s) => s.trim()).filter(Boolean);
}

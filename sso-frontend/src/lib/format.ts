// Locale-aware date formatting. Pass `i18n.language` so dates render in the reader's language
// (DESIGN.md §10). Keeps `toLocaleString()` calls from defaulting to the browser locale.

type DateInput = string | number | Date;

/** Date + time, medium date / short time (e.g. audit rows, "last seen"). */
export function formatDateTime(value: DateInput, locale: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}

/** Date only, medium (e.g. "created" columns). */
export function formatDate(value: DateInput, locale: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(new Date(value));
}

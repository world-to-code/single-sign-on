/**
 * How long a role grant lasts, as offered in the console.
 *
 * <p>Kept out of the page so the arithmetic is testable on its own: the difference between "permanent" and
 * "expires in zero days" is the difference between a standing privilege and one the sweeper removes, and that is
 * not something to discover by clicking.
 */
export type GrantDuration = "PERMANENT" | "P1D" | "P7D" | "P30D" | "P90D";

export const GRANT_DURATIONS: GrantDuration[] = ["PERMANENT", "P1D", "P7D", "P30D", "P90D"];

const DAYS: Record<Exclude<GrantDuration, "PERMANENT">, number> = {
  P1D: 1,
  P7D: 7,
  P30D: 30,
  P90D: 90,
};

const MS_PER_DAY = 24 * 60 * 60 * 1000;

/**
 * The instant a grant of this duration ends, or null for a standing one.
 *
 * `from` is passed in rather than read here so a caller — and a test — decides what "now" is.
 */
export function expiryOf(duration: GrantDuration, from: Date): string | null {
  if (duration === "PERMANENT") return null;
  return new Date(from.getTime() + DAYS[duration] * MS_PER_DAY).toISOString();
}

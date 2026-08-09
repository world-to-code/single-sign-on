/**
 * How long a console-placed account hold lasts, as offered in the picker.
 *
 * <p>Minutes rather than days, unlike a role grant, because a hold buys time to look at something and a
 * multi-day one is a disable that nobody signed off. The server keeps the real ceiling
 * (`sso.account-hold.max-duration`) and refuses anything past it; this list only decides what the console
 * offers, so the two can differ without anything breaking — a shortened ceiling turns the last option into a
 * refusal with a message, not a hold that quietly outlives it.
 */
export const HOLD_DURATION_MINUTES = [60, 240, 720, 1440] as const;

export type HoldDurationMinutes = (typeof HOLD_DURATION_MINUTES)[number];

export const DEFAULT_HOLD_DURATION_MINUTES: HoldDurationMinutes = 240;

import { describe, expect, it } from "vitest";
import { expiryOf, GRANT_DURATIONS } from "./grantDuration";

/**
 * The console offers a few fixed durations, and the value it sends is an instant. Getting this wrong is not a
 * cosmetic bug: too short revokes an administrator's access early, and "permanent" silently becoming a date is
 * how a standing privilege disappears at 3am.
 */
describe("grant duration", () => {
  const now = new Date("2026-08-08T09:00:00.000Z");

  it("sends nothing at all for a standing grant", () => {
    expect(expiryOf("PERMANENT", now)).toBeNull();
  });

  it("counts forward from the moment given, not from an ambient clock", () => {
    expect(expiryOf("P1D", now)).toBe("2026-08-09T09:00:00.000Z");
    expect(expiryOf("P7D", now)).toBe("2026-08-15T09:00:00.000Z");
    expect(expiryOf("P30D", now)).toBe("2026-09-07T09:00:00.000Z");
    expect(expiryOf("P90D", now)).toBe("2026-11-06T09:00:00.000Z");
  });

  /** The server refuses an expiry in the past, so every offered duration has to land in the future. */
  it("every offered duration is in the future", () => {
    for (const duration of GRANT_DURATIONS) {
      const expiry = expiryOf(duration, now);
      if (expiry !== null) {
        expect(new Date(expiry).getTime()).toBeGreaterThan(now.getTime());
      }
    }
  });
});

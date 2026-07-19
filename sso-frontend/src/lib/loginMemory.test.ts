import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { forgetOrg, recentOrgs, rememberOrg } from "./loginMemory";

const KEY = "sso.recentOrgs";
const LEGACY_KEY = "sso.lastOrg";

/** The list is capped so a long-lived browser cannot grow it without bound. */
const MAX = 5;

describe("loginMemory — recent organizations", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe("basic ordering", () => {
    it("returns an empty list when nothing was ever remembered", () => {
      expect(recentOrgs()).toEqual([]);
    });

    it("remembers a single organization", () => {
      rememberOrg("acme");
      expect(recentOrgs()).toEqual(["acme"]);
    });

    it("orders most-recently-selected first", () => {
      rememberOrg("acme");
      rememberOrg("beta");
      expect(recentOrgs()).toEqual(["beta", "acme"]);
    });

    it("moves an already-known organization to the front instead of duplicating it", () => {
      rememberOrg("acme");
      rememberOrg("beta");
      rememberOrg("acme");
      expect(recentOrgs()).toEqual(["acme", "beta"]);
    });
  });

  describe("normalization", () => {
    it("trims surrounding whitespace and lowercases, matching the server's slug normalization", () => {
      rememberOrg("  Acme  ");
      expect(recentOrgs()).toEqual(["acme"]);
    });

    it("treats slugs case-insensitively when de-duplicating", () => {
      rememberOrg("Acme");
      rememberOrg("acme");
      expect(recentOrgs()).toEqual(["acme"]);
    });

    it("ignores a blank slug rather than storing an unusable entry", () => {
      rememberOrg("acme");
      rememberOrg("   ");
      rememberOrg("");
      expect(recentOrgs()).toEqual(["acme"]);
    });
  });

  describe("cap", () => {
    it("keeps only the most recent entries, dropping the oldest", () => {
      const slugs = ["a", "b", "c", "d", "e", "f"];
      slugs.forEach(rememberOrg);

      const remembered = recentOrgs();
      expect(remembered).toHaveLength(MAX);
      expect(remembered).toEqual(["f", "e", "d", "c", "b"]);
      expect(remembered).not.toContain("a"); // the oldest fell off
    });

    // The point of the cap is what LANDS IN STORAGE. Asserting only the return value would pass even if the
    // write side never trimmed, because the read side re-applies its own cap and hides the overflow.
    it("caps what is actually persisted, not just what is read back", () => {
      ["a", "b", "c", "d", "e", "f"].forEach(rememberOrg);
      expect(JSON.parse(localStorage.getItem(KEY) ?? "null")).toEqual(["f", "e", "d", "c", "b"]);
    });

    it("caps a list that was already over-long in storage", () => {
      localStorage.setItem(KEY, JSON.stringify(["a", "b", "c", "d", "e", "f", "g", "h"]));
      expect(recentOrgs()).toEqual(["a", "b", "c", "d", "e"]);
    });

    it("drops the legacy slug rather than exceeding the cap", () => {
      localStorage.setItem(KEY, JSON.stringify(["a", "b", "c", "d", "e"]));
      localStorage.setItem(LEGACY_KEY, "old");

      const remembered = recentOrgs();
      expect(remembered).toHaveLength(MAX);
      expect(remembered).not.toContain("old"); // appended behind a full list, so it falls off
    });
  });

  describe("legacy single-slug migration", () => {
    it("surfaces a pre-existing sso.lastOrg value as the only entry", () => {
      localStorage.setItem(LEGACY_KEY, "acme");
      expect(recentOrgs()).toEqual(["acme"]);
    });

    it("does not duplicate the legacy value when the new list already holds it", () => {
      localStorage.setItem(KEY, JSON.stringify(["acme", "beta"]));
      localStorage.setItem(LEGACY_KEY, "acme");
      expect(recentOrgs()).toEqual(["acme", "beta"]);
    });

    it("appends the legacy value behind the new list, as the older selection", () => {
      localStorage.setItem(KEY, JSON.stringify(["beta"]));
      localStorage.setItem(LEGACY_KEY, "acme");
      expect(recentOrgs()).toEqual(["beta", "acme"]);
    });

    it("drops the legacy key once the new list has been written", () => {
      localStorage.setItem(LEGACY_KEY, "acme");
      rememberOrg("beta");
      expect(localStorage.getItem(LEGACY_KEY)).toBeNull();
      expect(recentOrgs()).toEqual(["beta", "acme"]);
    });

    it("ignores a blank legacy value", () => {
      localStorage.setItem(LEGACY_KEY, "  ");
      expect(recentOrgs()).toEqual([]);
    });
  });

  // The sign-in entry screen renders from this list, so malformed storage must degrade to "no memory"
  // rather than throw — a thrown parse error would blank the whole organization picker.
  describe("corrupt stored data", () => {
    it("recovers from a value that is not valid JSON", () => {
      localStorage.setItem(KEY, "{{{");
      expect(() => recentOrgs()).not.toThrow();
      expect(recentOrgs()).toEqual([]);
    });

    it("recovers from valid JSON that is not an array", () => {
      localStorage.setItem(KEY, JSON.stringify({ a: 1 }));
      expect(recentOrgs()).toEqual([]);
    });

    it("filters out non-string entries inside the array", () => {
      localStorage.setItem(KEY, JSON.stringify(["ok", 42, null, { x: 1 }]));
      expect(recentOrgs()).toEqual(["ok"]);
    });

    // A blank entry would render an unlabelled card that navigates to a malformed ".{host}" origin.
    it("drops blank and whitespace-only entries", () => {
      localStorage.setItem(KEY, JSON.stringify(["", "   ", "acme"]));
      expect(recentOrgs()).toEqual(["acme"]);
    });

    it("still accepts a well-formed list after rejecting a corrupt one", () => {
      localStorage.setItem(KEY, "not json");
      rememberOrg("acme");
      expect(recentOrgs()).toEqual(["acme"]);
    });
  });

  // Private mode / disabled storage: every access is best-effort and must never break sign-in.
  describe("storage unavailable", () => {
    it("reads as an empty list when localStorage refuses to be read", () => {
      vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
        throw new Error("storage disabled");
      });
      expect(() => recentOrgs()).not.toThrow();
      expect(recentOrgs()).toEqual([]);
    });

    it("swallows a write failure instead of breaking the caller", () => {
      vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
        throw new Error("storage disabled");
      });
      expect(() => rememberOrg("acme")).not.toThrow();
    });

    it("swallows a removal failure instead of breaking the caller", () => {
      vi.spyOn(Storage.prototype, "removeItem").mockImplementation(() => {
        throw new Error("storage disabled");
      });
      expect(() => forgetOrg("acme")).not.toThrow();
    });
  });

  describe("forgetting one organization", () => {
    it("removes only the named entry and preserves the rest of the order", () => {
      rememberOrg("acme");
      rememberOrg("beta");
      rememberOrg("gamma");

      forgetOrg("beta");

      expect(recentOrgs()).toEqual(["gamma", "acme"]);
    });

    it("matches case-insensitively, as selection does", () => {
      rememberOrg("acme");
      forgetOrg("ACME");
      expect(recentOrgs()).toEqual([]);
    });

    it("leaves the list untouched for an unknown slug", () => {
      rememberOrg("acme");
      forgetOrg("nope");
      expect(recentOrgs()).toEqual(["acme"]);
    });

    it("also forgets a value that only exists as the legacy single slug", () => {
      localStorage.setItem(LEGACY_KEY, "acme");
      forgetOrg("acme");
      expect(recentOrgs()).toEqual([]);
    });
  });
});

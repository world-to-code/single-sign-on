import { afterEach, describe, expect, it } from "vitest";
import type { BrandingCorner, BrandingFont } from "@/branding";
import { applyAccent, applyBrandingTheme, hexToHslTriple } from "./brandingTheme";

/**
 * This module is where every tenant-supplied value becomes a CSS custom property, and it had no tests at all
 * — so the https guard on the background image, the closed-set lookups and the anchored hex regex were each
 * one deletion away from shipping, with the whole suite green. That is the layer the feature's safety rests
 * on, and it is the layer the security review found resting on itself.
 *
 * <p>Every case is asserted in BOTH directions. A value being applied says nothing about a bad one being
 * refused, and "refused" here has to mean the property is REMOVED — writing `undefined` or an empty string
 * would leave the browser something to interpret.
 */
describe("prefs — the CSS sink layer", () => {
  const root = document.documentElement;

  afterEach(() => {
    root.removeAttribute("style");
  });

  function theme(overrides: Partial<Parameters<typeof applyBrandingTheme>[0]> = {}) {
    return {
      accentColor: null, backgroundColor: null, backgroundImageUrl: null,
      font: null, corner: null, ...overrides,
    };
  }

  describe("hexToHslTriple", () => {
    /** Achromatic inputs exercise the d === 0 guard that would otherwise divide by zero. */
    it("converts greys without dividing by zero", () => {
      expect(hexToHslTriple("#000000")).toBe("0 0% 0%");
      expect(hexToHslTriple("#ffffff")).toBe("0 0% 100%");
      expect(hexToHslTriple("#808080")).toBe("0 0% 50%");
    });

    /** One case per max-channel branch: a single colour cannot catch the branches being swapped. */
    it("takes the hue from the right channel", () => {
      expect(hexToHslTriple("#ff0000")).toBe("0 100% 50%");
      expect(hexToHslTriple("#00ff00")).toBe("120 100% 50%");
      expect(hexToHslTriple("#0000ff")).toBe("240 100% 50%");
    });

    /** Magenta drives ((g-b)/d) % 6 negative, so it pins the `if (h < 0) h += 360` wrap. */
    it("wraps a negative hue into the circle", () => {
      expect(hexToHslTriple("#ff00ff")).toBe("300 100% 50%");
    });

    it("accepts either case and surrounding whitespace", () => {
      expect(hexToHslTriple("#AABBCC")).toBe(hexToHslTriple("#aabbcc"));
      expect(hexToHslTriple("  #aabbcc  ")).toBe(hexToHslTriple("#aabbcc"));
    });

    /**
     * The anchors are the load-bearing part. Unanchor the regex and the last case here — a hex followed by a
     * second CSS declaration — starts parsing, which is the shape of an injection into a custom property.
     */
    it("refuses anything that is not exactly a #RRGGBB value", () => {
      for (const bad of ["", "aabbcc", "#abc", "#aabbccdd", "#gggggg", "red",
                         "#aabbcc; color:red", "#aabbcc)", "var(--x)"]) {
        expect(hexToHslTriple(bad)).toBeNull();
      }
    });
  });

  describe("applyAccent", () => {
    it("sets both the primary and the ring it derives", () => {
      applyAccent("#0a7a6a");

      expect(root.style.getPropertyValue("--primary")).not.toBe("");
      expect(root.style.getPropertyValue("--ring"))
        .toBe(root.style.getPropertyValue("--primary"));
    });

    /** The absence direction: an invalid value must REMOVE the override, not leave the previous one. */
    it("removes both when the value is missing or malformed", () => {
      applyAccent("#0a7a6a");
      applyAccent("javascript:alert(1)");

      expect(root.style.getPropertyValue("--primary")).toBe("");
      expect(root.style.getPropertyValue("--ring")).toBe("");
    });
  });

  describe("applyBrandingTheme — background image", () => {
    it("wraps an https URL in url() and percent-encodes it", () => {
      applyBrandingTheme(theme({ backgroundImageUrl: 'https://cdn.example/a"b c.jpg' }));

      const value = root.style.getPropertyValue("--brand-background-image");
      expect(value).toBe('url("https://cdn.example/a%22b%20c.jpg")');
      // Nothing that could end the CSS string or the declaration survives.
      expect(value).not.toContain('a"b');
      expect(value).not.toContain(" ");
    });

    /**
     * Each rejected scheme on its own line. A single "rejects a bad URL" case would pass while three of the
     * four went through — which is exactly how the sibling https checks were found to be uneven.
     */
    it("refuses every scheme that is not https, leaving no property behind", () => {
      for (const bad of ["http://cdn.example/x.jpg", "//cdn.example/x.jpg",
                         "javascript:alert(1)", "data:image/svg+xml,<svg onload=alert(1)>", ""]) {
        applyBrandingTheme(theme({ backgroundImageUrl: "https://cdn.example/ok.jpg" }));
        applyBrandingTheme(theme({ backgroundImageUrl: bad }));

        expect(root.style.getPropertyValue("--brand-background-image")).toBe("");
      }
    });

    it("accepts an uppercase scheme, matching the service's case-insensitive check", () => {
      applyBrandingTheme(theme({ backgroundImageUrl: "HTTPS://cdn.example/x.jpg" }));

      expect(root.style.getPropertyValue("--brand-background-image")).toContain("cdn.example");
    });
  });

  describe("applyBrandingTheme — closed sets", () => {
    /** The whole point of the enum: the NAME must never reach the CSS value. */
    it("maps a typeface to its stack, never to its name", () => {
      applyBrandingTheme(theme({ font: "SERIF" }));

      const value = root.style.getPropertyValue("--brand-font");
      expect(value).toContain("serif");
      expect(value).not.toBe("SERIF");
    });

    it("gives each choice of each set a distinct value", () => {
      const seen = new Set<string>();
      for (const font of ["SANS", "SERIF", "SYSTEM"] as BrandingFont[]) {
        applyBrandingTheme(theme({ font }));
        seen.add(root.style.getPropertyValue("--brand-font"));
      }
      expect(seen.size).toBe(3);

      const radii = new Set<string>();
      for (const corner of ["SHARP", "SOFT", "ROUND"] as BrandingCorner[]) {
        applyBrandingTheme(theme({ corner }));
        radii.add(root.style.getPropertyValue("--brand-radius"));
      }
      expect(radii.size).toBe(3);
    });

    /**
     * A value from outside the set can only arrive at runtime — from a cache entry or a hand-edited row — and
     * it must clear the property rather than write `undefined`, which the browser would try to parse.
     */
    it("clears the property for a value outside the set instead of writing undefined", () => {
      applyBrandingTheme(theme({ font: "SANS" }));
      applyBrandingTheme(theme({ font: "COMIC_SANS" as BrandingFont }));

      expect(root.style.getPropertyValue("--brand-font")).toBe("");
    });
  });

  describe("applyBrandingTheme — the axes are independent", () => {
    it("sets each property from its own input without disturbing the others", () => {
      applyBrandingTheme(theme({
        accentColor: "#0a7a6a", backgroundColor: "#101010",
        backgroundImageUrl: "https://cdn.example/x.jpg", font: "SERIF", corner: "ROUND",
      }));

      expect(root.style.getPropertyValue("--primary")).not.toBe("");
      expect(root.style.getPropertyValue("--brand-background")).not.toBe("");
      expect(root.style.getPropertyValue("--brand-background-image")).not.toBe("");
      expect(root.style.getPropertyValue("--brand-font")).not.toBe("");
      expect(root.style.getPropertyValue("--brand-radius")).not.toBe("");
    });

    /** An unbranded tenant after a branded one must leave nothing of the previous tenant behind. */
    it("clears every property when the theme carries nothing", () => {
      applyBrandingTheme(theme({
        accentColor: "#0a7a6a", backgroundColor: "#101010",
        backgroundImageUrl: "https://cdn.example/x.jpg", font: "SERIF", corner: "ROUND",
      }));
      applyBrandingTheme(theme());

      for (const property of ["--primary", "--ring", "--brand-background",
                              "--brand-background-image", "--brand-font", "--brand-radius"]) {
        expect(root.style.getPropertyValue(property)).toBe("");
      }
    });

    /** A malformed background colour must not fall through to the accent's parse or vice versa. */
    it("keeps a malformed background colour out of the property", () => {
      applyBrandingTheme(theme({ accentColor: "#0a7a6a", backgroundColor: "red" }));

      expect(root.style.getPropertyValue("--primary")).not.toBe("");
      expect(root.style.getPropertyValue("--brand-background")).toBe("");
    });
  });
});

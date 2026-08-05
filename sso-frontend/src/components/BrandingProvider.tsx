import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { getBranding, type Branding } from "@/branding";
import { applyBrandingTheme, resolvedTheme } from "@/lib/prefs";

/** Unbranded: the built-in mark, the product name, and the default theme. What every surface starts from. */
const DEFAULT: Branding = {
  identity: { logoUrl: null, logoUrlDark: null, faviconUrl: null, productName: null },
  theme: { accentColor: null, backgroundColor: null, backgroundImageUrl: null, font: null, corner: null, layout: null },
  copy: {},
};

/**
 * Exported so a screen can be rendered against a chosen branding in a test without standing up the fetch.
 * Consumers still go through {@link useBranding}; this is the seam, not a second way to read it.
 */
export const BrandingContextValue = createContext<Branding>(DEFAULT);
const BrandingContext = BrandingContextValue;

/**
 * Separate from the value so that consumers of the branding do not re-render when only this function's
 * identity changes — and so {@link useBranding} keeps its shape, since almost every caller wants only the mark.
 */
const BrandingRefreshContext = createContext<() => Promise<void>>(async () => {});

/**
 * The host tenant's branding, for the WHOLE app.
 *
 * <p>It used to be fetched inside the auth layout, which meant it reached the sign-in screens and nothing
 * else: the console, the user portal and the loading splash all rendered the built-in shield and the word
 * "Svalinn" no matter what the tenant had configured, and the accent colour reverted to the default the
 * moment a session existed. A tenant that has set their logo has said what this deployment is called, and
 * that answer does not stop being true after sign-in.
 *
 * <p>The endpoint is PUBLIC and resolves the tenant from the request host, so fetching it at the root is
 * safe before anyone has signed in — which is also why it can be fetched exactly once for every surface.
 */
export function BrandingProvider({ children }: { children: ReactNode }) {
  const [branding, setBranding] = useState<Branding>(DEFAULT);

  // A tenant that has not configured branding and a request that fails outright mean the same thing: use the
  // defaults. There is no version of this worth showing anyone an error about.
  const load = useCallback(async (): Promise<void> => {
    const resolved = await getBranding().catch(() => DEFAULT);
    setBranding(resolved);
    // Overrides the design tokens for every screen, not just the auth ones — a tenant that has set an accent
    // has said what this deployment looks like, and that does not stop being true after sign-in.
    applyBrandingTheme(resolved.theme);
    applyFavicon(resolved.identity.faviconUrl);
  }, []);

  // Once, for the page. The provider is mounted at the root, so every surface below it shares this one
  // fetch — which is the whole reason it moved here from the auth layout, where it was re-fetched as the
  // sign-in steps mounted and unmounted.
  useEffect(() => {
    let cancelled = false;
    load().then(() => { if (cancelled) return; });
    return () => { cancelled = true; };
  }, [load]);

  return (
    <BrandingRefreshContext.Provider value={load}>
      <BrandingContext.Provider value={branding}>{children}</BrandingContext.Provider>
    </BrandingRefreshContext.Provider>
  );
}

/** The resolved branding. Defaults until the fetch lands, so a consumer never has to handle "not yet". */
export function useBranding(): Branding {
  return useContext(BrandingContext);
}

/**
 * The mark and name to draw, with the dark-mode logo picked when the viewer is in dark mode. Every surface
 * that renders the brand wants exactly this pair, and the light/dark choice is one decision that belongs in
 * one place rather than repeated at each of them.
 *
 * <p>A tenant that supplies only a light logo keeps it in dark mode: a mark that is merely low-contrast is a
 * better outcome than no mark at all, and it is the tenant's own asset either way.
 */
export function useBrandMark(): { logoUrl: string | null; name: string | null } {
  const { identity } = useContext(BrandingContext);
  const dark = resolvedTheme() === "dark";
  return {
    logoUrl: (dark ? identity.logoUrlDark : identity.logoUrl) ?? identity.logoUrl,
    name: identity.productName,
  };
}

/**
 * Point the browser tab at the tenant's favicon. Rewrites the existing <link rel="icon"> rather than adding
 * one, so a re-fetch after an edit replaces the icon instead of stacking links the browser resolves by luck.
 */
function applyFavicon(url: string | null): void {
  const link = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
  // Re-checked here rather than trusted from the server, like the accent hex and the background url() are.
  // Not exploitable through <link rel=icon> today, but the asymmetry is what breaks the moment the element
  // or its rel changes, and this is the one client sink that had no check of its own.
  if (!url || !url.toLowerCase().startsWith("https://")) {
    link?.removeAttribute("href"); // clearing must restore the built-in icon, not strand the previous one
    return;
  }
  (link ?? document.head.appendChild(Object.assign(document.createElement("link"), { rel: "icon" }))).href = url;
}

/**
 * Re-reads the branding for the whole app. The editor calls this after saving, because the provider sits at
 * the ROOT and never unmounts — so without it a tenant saw their new logo and product name only after a full
 * browser reload, and until then the console kept showing what the branding used to be.
 */
export function useBrandingRefresh(): () => Promise<void> {
  return useContext(BrandingRefreshContext);
}

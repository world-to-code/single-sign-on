import { useEffect, useState } from "react";
import { listProfiles, type Profile } from "@/attributeDefinitions";

/**
 * The profile a manually created user will be given.
 *
 * <p>Deliberately resolved the same way the server does — the tenant's designated default first, its own
 * profile as the fallback. Rendering the tenant profile instead would be right only until someone designates
 * another: the form would then ask for one schema while the server validated against a different one, and
 * every create would fail on an attribute the administrator was never shown.
 */
export function useCreationProfile(): Profile | null {
  const [profile, setProfile] = useState<Profile | null>(null);

  useEffect(() => {
    let cancelled = false;
    listProfiles()
      .then((all) => {
        if (cancelled) return;
        setProfile(all.find((p) => p.defaultForCreation) ?? all.find((p) => p.system) ?? null);
      })
      .catch(() => { if (!cancelled) setProfile(null); });
    return () => { cancelled = true; };
  }, []);

  return profile;
}

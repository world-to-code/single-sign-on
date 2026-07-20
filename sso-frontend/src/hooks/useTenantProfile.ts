import { useEffect, useState } from "react";
import { listProfiles, type Profile } from "@/attributeDefinitions";

/**
 * The acting tenant's own profile — where a person's attribute definitions live.
 *
 * <p>Every screen that reads or declares USER attributes needs it, and it is seeded with the organization, so
 * it is fetched once here rather than threaded through props. Null while loading, and on failure: callers
 * render the "no schema" fallback they already have for a tenant that declared nothing.
 */
export function useTenantProfile(): Profile | null {
  const [profile, setProfile] = useState<Profile | null>(null);

  useEffect(() => {
    let cancelled = false;
    listProfiles()
      .then((all) => {
        if (!cancelled) setProfile(all.find((p) => p.kind === "TENANT") ?? null);
      })
      .catch(() => { if (!cancelled) setProfile(null); });
    return () => { cancelled = true; };
  }, []);

  return profile;
}

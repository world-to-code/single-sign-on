import { useEffect, useState } from "react";
import { listAttributeDefinitions, type AttributeDefinition } from "@/attributeDefinitions";
import { errorMessage } from "@/api";

export interface AttributeTargets {
  /** The attributes a source may fill. Empty while loading, and after a failure. */
  targets: AttributeDefinition[];
  /** Why the schema could not be read, or null. */
  error: string | null;
}

/**
 * The attributes of {@code profileId} a source may fill: declared, directory-owned, and not a built-in.
 *
 * <p>The store refuses anything else — a sync may only write what the schema says a directory owns, and a
 * built-in is an app_user column — so offering the rest would only produce errors at save time.
 *
 * <p>A failure is REPORTED rather than swallowed into an empty list. An empty list leaves the mapping form
 * with nothing to pick and an Add button that can never enable, which is exactly what a profile declaring
 * nothing directory-owned looks like — so the administrator could not tell a refusal from a schema.
 */
export function useAttributeTargets(profileId: string): AttributeTargets {
  const [targets, setTargets] = useState<AttributeDefinition[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    listAttributeDefinitions("USER", profileId)
      .then((all) => {
        if (!cancelled) setTargets(all.filter((d) => !d.base && d.source === "DIRECTORY"));
      })
      .catch((e) => { if (!cancelled) setError(errorMessage(e)); });
    return () => { cancelled = true; };
  }, [profileId]);

  return { targets, error };
}

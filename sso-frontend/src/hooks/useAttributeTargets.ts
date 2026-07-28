import { useEffect, useState } from "react";
import { listAttributeDefinitions, type AttributeDefinition } from "@/attributeDefinitions";

/**
 * The attributes of {@code profileId} a source may fill: declared, directory-owned, and not a built-in.
 *
 * <p>The store refuses anything else — a sync may only write what the schema says a directory owns, and a
 * built-in is an app_user column — so offering the rest would only produce errors at save time.
 */
export function useAttributeTargets(profileId: string): AttributeDefinition[] {
  const [definitions, setDefinitions] = useState<AttributeDefinition[]>([]);

  useEffect(() => {
    listAttributeDefinitions("USER", profileId)
      .then((all) => setDefinitions(all.filter((d) => !d.base && d.source === "DIRECTORY")))
      // Deliberate: an empty target list makes the mapping form offer nothing, which is the same outcome as
      // a profile that declares nothing directory-owned — and the save would refuse either way.
      .catch(() => setDefinitions([]));
  }, [profileId]);

  return definitions;
}

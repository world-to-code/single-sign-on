import { useSyncExternalStore } from "react";
import { registerDrillInOrgId } from "@/api";

/** The tenant a platform super-admin is currently "managing" (drilled into). */
export interface DrillInOrg {
  id: string;
  slug: string;
}

// In-memory only: a drill-in resets on reload, so a stale X-Org-Context never lingers across sessions.
let current: DrillInOrg | null = null;
const listeners = new Set<() => void>();

// Feed the api client the active org id so admin requests carry X-Org-Context.
registerDrillInOrgId(() => current?.id ?? null);

/** Enter (or, with null, exit) a tenant's admin context. Notifies subscribers so the banner updates. */
export function setDrillIn(org: DrillInOrg | null): void {
  current = org;
  listeners.forEach((notify) => notify());
}

export function getDrillIn(): DrillInOrg | null {
  return current;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** The org the super-admin is drilled into, or null (platform view) — re-renders on change. */
export function useDrillIn(): DrillInOrg | null {
  return useSyncExternalStore(subscribe, getDrillIn, getDrillIn);
}

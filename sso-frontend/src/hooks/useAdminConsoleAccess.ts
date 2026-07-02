import { useEffect, useState } from "react";
import { getAdminConsoleAccess } from "@/auth";

// Shared in-flight promise: AppShell and AdminGuard both consult this hook while the admin shell
// mounts, so a module-level cache collapses their two identical requests into one.
let cached: Promise<boolean> | null = null;
function fetchAccess(): Promise<boolean> {
  if (!cached) {
    cached = getAdminConsoleAccess().then((r) => r.allowed).catch(() => false);
  }
  return cached;
}

/**
 * Whether the signed-in user may enter the admin console — an APP ASSIGNMENT (Model B), not a role.
 * `undefined` while loading so callers can avoid flashing the entry affordance before the answer.
 */
export function useAdminConsoleAccess(): boolean | undefined {
  const [allowed, setAllowed] = useState<boolean | undefined>(undefined);
  useEffect(() => {
    let active = true;
    fetchAccess().then((a) => {
      if (active) setAllowed(a);
    });
    return () => {
      active = false;
    };
  }, []);
  return allowed;
}

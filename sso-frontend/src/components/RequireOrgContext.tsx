import { Navigate, Outlet } from "react-router-dom";
import type { SessionView } from "@/auth";
import { isPlatformAdmin } from "@/auth";
import { useDrillIn } from "@/drillIn";

/**
 * Route guard for org-scoped admin pages (Directory / Applications / Access & Security / System). A
 * platform super-admin has no tenant context until they drill into one, so — matching the sidebar, which
 * hides these links until drill-in — reaching such a page directly (typed URL, stale link) redirects to the
 * organization picker instead of rendering a tenant page in the platform's out-of-context global view. A
 * tenant admin is always in their own org, so they pass straight through.
 */
export default function RequireOrgContext({ session }: { session: SessionView }) {
  const drill = useDrillIn();
  if (isPlatformAdmin(session) && !drill) {
    return <Navigate to="/admin/organizations" replace />;
  }
  return <Outlet />;
}

import { Building2, LogIn, Users } from "lucide-react";
import type { PlatformMetrics } from "@/metrics";
import { useApiData } from "@/useApiData";
import { PageHeader } from "@/components/PageHeader";
import { MetricTile } from "@/components/MetricTile";
import { ErrorCard, LoadingCard } from "@/components/states";

/** Platform super-admin landing: cross-tenant totals for the whole identity provider. */
export default function PlatformDashboard() {
  const { data, error } = useApiData<PlatformMetrics>("/api/admin/metrics/platform");

  return (
    <>
      <PageHeader
        title="Platform overview"
        description="Tenants, users, and sign-in activity across the whole identity provider."
      />
      {error ? (
        <ErrorCard message={error} />
      ) : !data ? (
        <LoadingCard rows={3} />
      ) : (
        <div className="grid gap-4 sm:grid-cols-3">
          <MetricTile icon={<Building2 className="size-5" />} label="Organizations" value={data.organizations} />
          <MetricTile icon={<Users className="size-5" />} label="Users" value={data.users} />
          <MetricTile icon={<LogIn className="size-5" />} label="Sign-ins"
                      value={data.signInsInWindow} hint={`last ${data.windowDays} days`} />
        </div>
      )}
    </>
  );
}

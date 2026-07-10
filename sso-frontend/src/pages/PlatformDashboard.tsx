import type { PlatformMetrics } from "@/metrics";
import { useApiData } from "@/useApiData";
import { PageHeader } from "@/components/PageHeader";
import { Metric } from "@/components/Metric";
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
          <Metric label="Organizations" value={data.organizations} />
          <Metric label="Users" value={data.users} />
          <Metric label="Sign-ins" value={data.signInsInWindow} hint={`last ${data.windowDays} days`} />
        </div>
      )}
    </>
  );
}

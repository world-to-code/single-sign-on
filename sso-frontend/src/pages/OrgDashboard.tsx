import { Link, useParams } from "react-router-dom";
import { ArrowLeft, LogIn, Users, XCircle } from "lucide-react";
import type { OrgMetrics } from "@/metrics";
import { useApiData } from "@/useApiData";
import { PageHeader } from "@/components/PageHeader";
import { MetricTile } from "@/components/MetricTile";
import { SignInTrendChart } from "@/components/charts/SignInTrendChart";
import { ErrorCard, LoadingCard } from "@/components/states";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

/** One tenant's analytics: member count, sign-in totals, and the daily sign-in trend. */
export default function OrgDashboard() {
  const { id = "" } = useParams();
  const { data, error } = useApiData<OrgMetrics>(`/api/admin/metrics/orgs/${id}`);

  const successes = data?.signIns.reduce((sum, d) => sum + d.successes, 0) ?? 0;
  const failures = data?.signIns.reduce((sum, d) => sum + d.failures, 0) ?? 0;

  return (
    <>
      <Link to="/admin/organizations"
            className="mb-3 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> Organizations
      </Link>
      <PageHeader
        title={data ? `${data.name} analytics` : "Organization analytics"}
        description={data ? `${data.slug} · last ${data.windowDays} days` : undefined}
      />
      {error ? (
        <ErrorCard message={error} />
      ) : !data ? (
        <LoadingCard rows={3} />
      ) : (
        <div className="space-y-6">
          <div className="grid gap-4 sm:grid-cols-3">
            <MetricTile icon={<Users className="size-5" />} label="Members" value={data.users} />
            <MetricTile icon={<LogIn className="size-5" />} label="Successful sign-ins" value={successes} />
            <MetricTile icon={<XCircle className="size-5" />} label="Failed attempts" value={failures} />
          </div>
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Sign-in trend</CardTitle>
            </CardHeader>
            <CardContent>
              <SignInTrendChart days={data.signIns} />
            </CardContent>
          </Card>
        </div>
      )}
    </>
  );
}

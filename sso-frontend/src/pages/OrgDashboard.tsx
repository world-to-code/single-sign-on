import { Link, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ArrowLeft } from "lucide-react";
import type { OrgMetrics } from "@/metrics";
import { useApiData } from "@/useApiData";
import { PageHeader } from "@/components/PageHeader";
import { Metric } from "@/components/Metric";
import { SignInTrendChart } from "@/components/charts/SignInTrendChart";
import { ErrorCard, LoadingCard } from "@/components/states";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

/** One tenant's analytics: member count, sign-in totals, and the daily sign-in trend. */
export default function OrgDashboard() {
  const { t } = useTranslation("console");
  const { id = "" } = useParams();
  const { data, error } = useApiData<OrgMetrics>(`/api/admin/metrics/orgs/${id}`);

  const successes = data?.signIns.reduce((sum, d) => sum + d.successes, 0) ?? 0;
  const failures = data?.signIns.reduce((sum, d) => sum + d.failures, 0) ?? 0;

  return (
    <>
      <Link to="/admin/organizations"
            className="mb-3 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> {t("orgDashBack")}
      </Link>
      <PageHeader
        title={data ? t("orgDashTitle", { name: data.name }) : t("orgDashTitleFallback")}
        description={data ? t("orgDashSubtitle", { slug: data.slug, count: data.windowDays }) : undefined}
      />
      {error ? (
        <ErrorCard message={error} />
      ) : !data ? (
        <LoadingCard rows={3} />
      ) : (
        <div className="space-y-6">
          <div className="grid gap-4 sm:grid-cols-3">
            <Metric label={t("orgDashMembers")} value={data.users} />
            <Metric label={t("orgDashSuccessfulSignIns")} value={successes} />
            <Metric label={t("orgDashFailedAttempts")} value={failures} />
          </div>
          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t("orgDashTrend")}</CardTitle>
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

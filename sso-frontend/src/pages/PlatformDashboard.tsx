import { useTranslation } from "react-i18next";
import type { PlatformMetrics } from "@/metrics";
import { useApiData } from "@/useApiData";
import { PageHeader } from "@/components/PageHeader";
import { Metric } from "@/components/Metric";
import { ErrorCard, LoadingCard } from "@/components/states";

/** Platform super-admin landing: cross-tenant totals for the whole identity provider. */
export default function PlatformDashboard() {
  const { t } = useTranslation("console");
  const { data, error } = useApiData<PlatformMetrics>("/api/admin/metrics/platform");

  return (
    <>
      <PageHeader
        title={t("platformDashTitle")}
        description={t("platformDashDescription")}
      />
      {error ? (
        <ErrorCard message={error} />
      ) : !data ? (
        <LoadingCard rows={3} />
      ) : (
        <div className="grid gap-4 sm:grid-cols-3">
          <Metric label={t("platformDashOrganizations")} value={data.organizations} />
          <Metric label={t("platformDashUsers")} value={data.users} />
          <Metric label={t("platformDashSignIns")} value={data.signInsInWindow} hint={t("platformDashSignInsHint", { count: data.windowDays })} />
        </div>
      )}
    </>
  );
}

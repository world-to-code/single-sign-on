import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { ExternalLink, LayoutGrid, ShieldCheck } from "lucide-react";
import { getMyApps } from "../portal";
import type { Application } from "../portal";
import { PageHeader } from "@/components/PageHeader";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { EmptyState, ErrorCard, LoadingCard } from "@/components/states";

export default function MyApps() {
  const { t } = useTranslation("states");
  const [apps, setApps] = useState<Application[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getMyApps().then(setApps).catch((e) => setError(String(e)));
  }, []);

  function launch(app: Application) {
    if (app.launchUrl) window.open(app.launchUrl, "_blank", "noopener");
  }

  return (
    <>
      <PageHeader title="My Applications" description="Single sign-on to the apps assigned to you." />
      {error ? <ErrorCard message={error} /> : !apps ? <LoadingCard /> : apps.length === 0 ? (
        <Card><CardContent className="p-0">
          <EmptyState icon={<LayoutGrid className="size-8" />} title={t("myAppsEmptyTitle")}
                      hint={t("myAppsEmptyHint")} />
        </CardContent></Card>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {apps.map((app) => (
            <Card key={`${app.type}:${app.id}`} className="transition-shadow hover:shadow-md">
              <CardContent className="flex h-full flex-col gap-4 p-5">
                <div className="flex items-start justify-between">
                  <div className="flex size-12 items-center justify-center rounded-xl bg-accent text-primary text-lg font-semibold">
                    {app.name.slice(0, 1).toUpperCase()}
                  </div>
                  <Badge variant="muted">{app.type}</Badge>
                </div>
                <div className="flex-1">
                  <p className="font-semibold leading-tight">{app.name}</p>
                  <p className="mt-1 flex items-center gap-1 text-xs text-muted-foreground">
                    <ShieldCheck className="size-3" /> SSO enabled
                  </p>
                </div>
                <Button className="w-full" onClick={() => launch(app)} disabled={!app.launchUrl}>
                  <ExternalLink /> Launch
                </Button>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </>
  );
}

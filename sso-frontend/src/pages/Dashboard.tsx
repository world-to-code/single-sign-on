import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { KeyRound, UserCircle } from "lucide-react";
import type { SessionView } from "../auth";
import { Metric } from "../components/Metric";
import { PageHeader } from "../components/PageHeader";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";

/**
 * An enrollment state, not a figure — so it reads as a state chip rather than a Metric. The dot is
 * hollow when the factor is missing, so the state survives without colour (DESIGN.md §4).
 */
function FactorStat({ label, value, enrolled }: { label: string; value: string; enrolled: boolean }) {
  return (
    <Card>
      <CardContent className="p-5">
        <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</p>
        <p className="mt-2 flex items-center gap-2 text-lg font-semibold">
          <span
            aria-hidden
            className={`size-2 rounded-full ${enrolled ? "bg-allow" : "ring-2 ring-inset ring-faint"}`}
          />
          <span className={enrolled ? "" : "text-muted-foreground"}>{value}</span>
        </p>
      </CardContent>
    </Card>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 border-b py-2 last:border-0">
      <span className="text-muted-foreground">{label}</span>
      {children}
    </div>
  );
}

export default function Dashboard({ session }: { session: SessionView }) {
  const { t } = useTranslation("auth");
  return (
    <>
      <PageHeader title={t("dashboardWelcome", { name: session.username ?? "" })} description={t("dashboardDescription")} />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <FactorStat label={t("dashboardTotpLabel")} enrolled={session.totpEnrolled}
                    value={session.totpEnrolled ? t("enrolled") : t("notSetUp")} />
        <FactorStat label={t("dashboardPasskeyLabel")} enrolled={session.fido2Enrolled}
                    value={session.fido2Enrolled ? t("registered") : t("none")} />
        <Metric label={t("dashboardFactorsThisSession")} value={session.factors.length} />
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2"><UserCircle className="size-4 text-primary" /> {t("dashboardIdentity")}</CardTitle>
            <CardDescription>{t("dashboardIdentityDesc")}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <Row label={t("user")}><span className="font-medium">{session.username}</span></Row>
            <Row label={t("roles")}>
              <div className="flex flex-wrap gap-1">
                {session.roles.length ? session.roles.map((r) => <Badge key={r} variant="secondary">{r}</Badge>)
                  : <span className="text-muted-foreground">—</span>}
              </div>
            </Row>
            <Row label={t("dashboardFactorsSatisfied")}>
              <div className="flex flex-wrap justify-end gap-1">
                {session.factors.length ? session.factors.map((f) => <Badge key={f} variant="success">{f.replace("FACTOR_", "")}</Badge>)
                  : <span className="text-muted-foreground">—</span>}
              </div>
            </Row>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2"><KeyRound className="size-4 text-primary" /> {t("dashboardSecurityKeys")}</CardTitle>
            <CardDescription>{t("dashboardSecurityKeysDesc")}</CardDescription>
          </CardHeader>
          <CardContent>
            <Button asChild variant="outline"><Link to="/passkeys"><KeyRound /> {t("manageMyPasskeys")}</Link></Button>
          </CardContent>
        </Card>
      </div>
    </>
  );
}

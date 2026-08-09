import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Loader2, ShieldAlert } from "lucide-react";
import { errorMessage } from "@/api";
import { getUserHold, holdUser, liftUserHold } from "@/users";
import type { AccountHoldStatus } from "@/users";
import { useSubmit } from "@/hooks/useSubmit";
import { DEFAULT_HOLD_DURATION_MINUTES, HOLD_DURATION_MINUTES } from "@/lib/holdDuration";
import { formatDateTime } from "@/lib/format";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";

/**
 * Place, read and lift the reversible hold on one account.
 *
 * <p>The hold is the middle move between leaving a suspicious account alone and disabling it: it ends the
 * sessions the account has and makes every sign-in prove a second factor until it expires. The screen says so
 * rather than leaving an administrator to infer it from a badge, because "held" and "disabled" look alike and
 * are not.
 *
 * <p>The reason is required, and it is the one field here that a person will read later — a hold with an
 * empty reason is one nobody can decide whether to lift.
 */
export function AccountHoldCard({ userId }: { userId: string }) {
  const { t, i18n } = useTranslation("console");
  const [status, setStatus] = useState<AccountHoldStatus | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [minutes, setMinutes] = useState<number>(DEFAULT_HOLD_DURATION_MINUTES);
  const { busy, error, run } = useSubmit();

  useEffect(() => {
    // Kept in state rather than swallowed: a hold nobody can read is indistinguishable from no hold, and the
    // difference decides whether an administrator acts.
    getUserHold(userId).then(setStatus).catch((e) => setLoadError(errorMessage(e)));
  }, [userId]);

  async function place() {
    if (await run(() => holdUser(userId, reason, minutes).then(setStatus))) {
      setReason("");
    }
  }

  async function lift() {
    await run(() => liftUserHold(userId).then(() => getUserHold(userId)).then(setStatus));
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <ShieldAlert className="size-4" /> {t("userDetailHold")}
          {status?.held === true && <Badge variant="warn">{t("userDetailHoldActive")}</Badge>}
        </CardTitle>
        <CardDescription>{t("userDetailHoldDesc")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {loadError && <Alert variant="destructive"><AlertDescription>{loadError}</AlertDescription></Alert>}
        {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}

        {status === null ? null : status.held ? (
          <div className="space-y-3">
            <dl className="grid gap-2 text-sm sm:grid-cols-2">
              <div>
                <dt className="text-muted-foreground">{t("userDetailHoldReason")}</dt>
                <dd>{status.reason}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">{t("userDetailHoldExpires")}</dt>
                <dd>{formatDateTime(status.expiresAt, i18n.language)}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">{t("userDetailHoldPlacedBy")}</dt>
                {/* A machine names the detection that raised it; nobody is invented when no person asked. */}
                <dd>{status.placedBy ?? status.correlationId ?? t("userDetailHoldPlacedByUnknown")}</dd>
              </div>
            </dl>
            <Button type="button" variant="outline" onClick={lift} disabled={busy}>
              {busy && <Loader2 className="animate-spin" />}
              {t("userDetailHoldLift")}
            </Button>
          </div>
        ) : (
          <div className="flex flex-wrap items-end gap-3">
            <div className="min-w-56 flex-1 space-y-2">
              <Label htmlFor="hold-reason">{t("userDetailHoldReason")}</Label>
              <Input id="hold-reason" value={reason} maxLength={200}
                     placeholder={t("userDetailHoldReasonPlaceholder")}
                     onChange={(e) => setReason(e.target.value)} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="hold-duration">{t("userDetailHoldDuration")}</Label>
              <Select id="hold-duration" className="w-44" value={minutes}
                      onChange={(e) => setMinutes(Number(e.target.value))}>
                {HOLD_DURATION_MINUTES.map((m) => (
                  <option key={m} value={m}>{t(`userDetailHoldDuration_${m}`)}</option>
                ))}
              </Select>
            </div>
            <Button type="button" onClick={place} disabled={busy || reason.trim() === ""}>
              {busy && <Loader2 className="animate-spin" />}
              {t("userDetailHoldPlace")}
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

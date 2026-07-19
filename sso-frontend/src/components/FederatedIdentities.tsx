import { useTranslation } from "react-i18next";
import { Unlink } from "lucide-react";
import { useApiData } from "@/useApiData";
import { useConfirm } from "@/components/ConfirmProvider";
import {
  federatedIdentitiesPath,
  unlinkFederatedIdentity,
  type FederatedIdentity,
} from "@/federatedIdentities";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

/**
 * The upstream identities this account signs in through. Revoking one is the recovery path for the login
 * side's fail-closed guards: an account holds at most one identity per upstream, and a first sign-in may not
 * claim a privileged account by address — so when an upstream reissues someone's subject, or the wrong
 * subject claimed an account once, unlinking is what lets them back in.
 */
export function FederatedIdentities({ userId }: { userId: string }) {
  const { t } = useTranslation("console");
  const confirm = useConfirm();
  const { data, error, reload } = useApiData<FederatedIdentity[]>(federatedIdentitiesPath(userId));

  async function unlink(identity: FederatedIdentity) {
    const ok = await confirm({
      title: t("federatedUnlinkTitle"),
      description: t("federatedUnlinkConfirm", { provider: identity.providerAlias }),
      confirmText: t("federatedUnlink"),
      variant: "destructive",
      blastRadius: t("federatedUnlinkBlastRadius"),
    });
    if (!ok) return;
    await unlinkFederatedIdentity(userId, identity.id);
    reload();
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t("federatedIdentitiesTitle")}</CardTitle>
        <CardDescription>{t("federatedIdentitiesDesc")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-2 text-sm">
        {error && <p className="text-xs text-destructive">{error}</p>}
        {data?.length === 0 && <p className="text-xs text-muted-foreground">{t("federatedIdentitiesNone")}</p>}
        {data?.map((identity) => (
          <div key={identity.id} className="flex items-center justify-between gap-2 rounded-lg border p-2">
            <div className="min-w-0">
              <Badge variant="muted" className="font-mono">{identity.providerAlias}</Badge>
              <p className="mt-1 truncate text-xs text-muted-foreground">{identity.issuer}</p>
              <p className="truncate font-mono text-xs text-muted-foreground">{identity.subjectHint}</p>
            </div>
            <Button variant="ghost" size="icon" aria-label={t("federatedUnlinkOne", { provider: identity.providerAlias })}
                    className="text-muted-foreground hover:text-destructive" onClick={() => void unlink(identity)}>
              <Unlink />
            </Button>
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

import { useState } from "react";
import type { FormEvent } from "react";
import { Trans, useTranslation } from "react-i18next";
import { Coins, KeyRound } from "lucide-react";
import { apiPost, errorMessage } from "../api";
import { PageHeader } from "@/components/PageHeader";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

interface ScimTokenIssued {
  token: string;
  description: string;
}

export default function ScimTokens() {
  const { t } = useTranslation("console");
  const [description, setDescription] = useState("");
  const [ttlDays, setTtlDays] = useState("");
  const [issued, setIssued] = useState<ScimTokenIssued | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setIssued(null);
    try {
      const result = await apiPost<ScimTokenIssued>("/api/admin/scim/tokens", {
        description,
        ttlDays: ttlDays ? Number(ttlDays) : null,
      });
      setIssued(result);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <>
      <PageHeader
        title={t("scimTitle")}
        description={t("scimDescription")}
      />

      <div className="grid gap-6 lg:max-w-2xl">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Coins className="size-4 text-primary" /> {t("scimIssueTitle")}
            </CardTitle>
            <CardDescription>{t("scimIssueDescription")}</CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={submit} className="grid gap-4">
              <div className="grid gap-2">
                <Label htmlFor="description">{t("scimDescLabel")}</Label>
                <Input
                  id="description"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder={t("scimDescPlaceholder")}
                  required
                />
              </div>
              <div className="grid gap-2">
                <Label htmlFor="ttlDays">{t("scimTtlLabel")}</Label>
                <Input
                  id="ttlDays"
                  value={ttlDays}
                  onChange={(e) => setTtlDays(e.target.value)}
                  inputMode="numeric"
                  placeholder={t("scimTtlPlaceholder")}
                />
              </div>
              <div>
                <Button type="submit">
                  <KeyRound /> {t("scimIssueButton")}
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>

        {error && (
          <Alert variant="destructive">
            <AlertDescription>{t("scimErrorPrefix", { message: error })}</AlertDescription>
          </Alert>
        )}

        {issued && (
          <Alert variant="success">
            <AlertTitle>{t("scimIssuedTitle")}</AlertTitle>
            <AlertDescription>
              <p className="mb-2 text-muted-foreground">
                <Trans t={t} i18nKey="scimIssuedBody" values={{ description: issued.description }}
                       components={[<strong key="0" />]} />
              </p>
              <code className="block break-all rounded-md bg-muted px-3 py-2 font-mono text-xs text-foreground">
                {issued.token}
              </code>
            </AlertDescription>
          </Alert>
        )}
      </div>
    </>
  );
}

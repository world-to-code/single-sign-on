import { useState } from "react";
import type { FormEvent } from "react";
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
        title="SCIM Tokens"
        description="Issue bearer tokens for external systems to provision users and groups over SCIM 2.0."
      />

      <div className="grid gap-6 lg:max-w-2xl">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Coins className="size-4 text-primary" /> Issue SCIM bearer token
            </CardTitle>
            <CardDescription>The plaintext token is shown only once at creation time.</CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={submit} className="grid gap-4">
              <div className="grid gap-2">
                <Label htmlFor="description">Description</Label>
                <Input
                  id="description"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder="e.g. Workday provisioning"
                  required
                />
              </div>
              <div className="grid gap-2">
                <Label htmlFor="ttlDays">TTL (days)</Label>
                <Input
                  id="ttlDays"
                  value={ttlDays}
                  onChange={(e) => setTtlDays(e.target.value)}
                  inputMode="numeric"
                  placeholder="Blank = no expiry"
                />
              </div>
              <div>
                <Button type="submit">
                  <KeyRound /> Issue token
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>

        {error && (
          <Alert variant="destructive">
            <AlertDescription>Error: {error}</AlertDescription>
          </Alert>
        )}

        {issued && (
          <Alert variant="success">
            <AlertTitle>Token issued — copy it now</AlertTitle>
            <AlertDescription>
              <p className="mb-2 text-muted-foreground">
                This token for <strong>{issued.description}</strong> is shown once and cannot be retrieved later.
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

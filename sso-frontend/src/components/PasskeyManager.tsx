import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Fingerprint, KeyRound, Plus, Trash2 } from "lucide-react";
import { deletePasskey, listPasskeys, registerPasskey, webAuthnSupported } from "../webauthn";
import type { Passkey } from "../webauthn";
import { Alert, AlertDescription } from "./ui/alert";
import { Button } from "./ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "./ui/card";
import { Input } from "./ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "./ui/table";
import { DataList, EmptyState } from "./states";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";

/**
 * Self-service passkey list + register/remove. Shared by the "My Passkeys" page and the unified
 * "My Profile" page so the WebAuthn logic lives in exactly one place. `onChanged` lets a host
 * (e.g. Profile) refresh dependent data (passkey count) after add/remove.
 */
export default function PasskeyManager({ onChanged }: { onChanged?: () => void } = {}) {
  const { t } = useTranslation("states");
  const confirmDelete = useDeleteConfirm();
  const [passkeys, setPasskeys] = useState<Passkey[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [label, setLabel] = useState("");
  const [status, setStatus] = useState<string | null>(null);

  function reload() {
    listPasskeys().then(setPasskeys).catch((e) => setError(String(e)));
  }
  useEffect(reload, []);

  async function register() {
    setStatus(null);
    try {
      await registerPasskey(label.trim() || "passkey");
      setLabel("");
      setStatus("Passkey registered.");
      reload();
      onChanged?.();
    } catch {
      setStatus("Passkey registration was cancelled or failed.");
    }
  }

  async function remove(p: Passkey) {
    await confirmDelete({
      title: "Delete passkey?",
      description: `"${p.label}" will be removed from your account.`,
      run: () => deletePasskey(p.id),
      onDeleted: () => {
        reload();
        onChanged?.();
      },
    });
  }

  return (
    <>
      {!webAuthnSupported() && (
        <Alert variant="destructive" className="mb-4"><AlertDescription>This browser does not support WebAuthn.</AlertDescription></Alert>
      )}
      {status && <Alert variant="info" className="mb-4"><AlertDescription>{status}</AlertDescription></Alert>}

      <DataList
        data={passkeys}
        error={error}
        errorAlways
        isEmpty={(items) => items.length === 0}
        empty={
          <EmptyState icon={<Fingerprint className="size-8" />} title={t("passkeysEmptyTitle")}
                      hint={t("passkeysEmptyHint")} />
        }
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow><TableHead>Label</TableHead><TableHead>Created</TableHead><TableHead>Last used</TableHead><TableHead className="w-0" /></TableRow>
            </TableHeader>
            <TableBody>
              {items.map((p) => (
                <TableRow key={p.id}>
                  <TableCell className="font-medium"><span className="inline-flex items-center gap-2"><KeyRound className="size-4 text-muted-foreground" />{p.label}</span></TableCell>
                  <TableCell className="text-muted-foreground">{p.createdAt ? new Date(p.createdAt).toLocaleString() : "—"}</TableCell>
                  <TableCell className="text-muted-foreground">{p.lastUsedAt ? new Date(p.lastUsedAt).toLocaleString() : "—"}</TableCell>
                  <TableCell className="text-right">
                    <Button variant="ghost" size="icon" onClick={() => remove(p)} className="text-muted-foreground hover:text-destructive"><Trash2 /></Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>

      {webAuthnSupported() && (
        <Card className="mt-6">
          <CardHeader><CardTitle className="text-base">Register a new passkey</CardTitle></CardHeader>
          <CardContent className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <Input value={label} onChange={(e) => setLabel(e.target.value)} placeholder="Label (optional), e.g. MacBook Touch ID" className="sm:max-w-xs" />
            <Button type="button" onClick={register}><Plus /> Register passkey</Button>
          </CardContent>
        </Card>
      )}
    </>
  );
}

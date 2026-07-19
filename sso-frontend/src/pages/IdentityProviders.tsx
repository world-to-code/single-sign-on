import { useState } from "react";
import { Trans, useTranslation } from "react-i18next";
import { Pencil, Plus, Save, Trash2 } from "lucide-react";
import { saveIdentityProvider, type IdentityProvider } from "@/identityProviders";
import { PageHeader } from "@/components/PageHeader";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";
import { useApiData } from "@/useApiData";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { useEditorForm } from "@/hooks/useEditorForm";

interface Editor {
  id: string | null; // the alias when editing an existing provider, null when creating
  alias: string;
  displayName: string;
  issuerUri: string;
  clientId: string;
  clientSecret: string;
  scopes: string;
  allowJitProvisioning: boolean;
  enabled: boolean;
}

const blank: Editor = {
  id: null, alias: "", displayName: "", issuerUri: "", clientId: "", clientSecret: "",
  scopes: "openid email profile", allowJitProvisioning: false, enabled: true,
};

/** Per-tenant upstream OIDC providers users can sign in through ("Sign in with …" on the login screen). */
export default function IdentityProviders() {
  const { t } = useTranslation(["console", "states"]);
  const providers = useApiData<IdentityProvider[]>("/api/admin/identity-providers");
  const confirmDelete = useDeleteConfirm();
  const [actionError, setActionError] = useState<string | null>(null);

  const { editor, set, open, setOpen, error: formError, openCreate, openEdit, save } = useEditorForm<Editor>({
    blank,
    // The alias rides in the body so create() can address the {alias} path; the backend ignores it there.
    toRequest: (e) => ({
      alias: e.alias.trim().toLowerCase(),
      displayName: e.displayName.trim(),
      issuerUri: e.issuerUri.trim(),
      clientId: e.clientId.trim(),
      clientSecret: e.clientSecret, // blank on edit → backend keeps the stored secret
      scopes: e.scopes.trim(),
      allowJitProvisioning: e.allowJitProvisioning,
      enabled: e.enabled,
    }),
    create: (body) => saveIdentityProvider((body as { alias: string }).alias, body as Parameters<typeof saveIdentityProvider>[1]),
    update: (alias, body) => saveIdentityProvider(alias, body as Parameters<typeof saveIdentityProvider>[1]),
    onSaved: providers.reload,
  });

  const edit = (p: IdentityProvider) =>
    openEdit({
      id: p.alias, alias: p.alias, displayName: p.displayName, issuerUri: p.issuerUri, clientId: p.clientId,
      clientSecret: "", scopes: p.scopes, allowJitProvisioning: p.allowJitProvisioning, enabled: p.enabled,
    });

  const remove = (p: IdentityProvider) => {
    setActionError(null);
    return confirmDelete({
      title: t("idpDeleteTitle"),
      description: t("idpDeleteDescription", { name: p.displayName }),
      path: `/api/admin/identity-providers/${encodeURIComponent(p.alias)}`,
      onDeleted: providers.reload,
      onError: setActionError,
    });
  };

  return (
    <>
      <PageHeader
        title={t("idpTitle")}
        description={t("idpDescription")}
        actions={<Button onClick={openCreate}><Plus /> {t("idpNew")}</Button>}
      />

      <Alert variant="info" className="mb-4">
        <AlertDescription>
          <Trans t={t} i18nKey="idpInfo" components={[<strong key="0" />]} />
        </AlertDescription>
      </Alert>

      {actionError && <Alert variant="destructive" className="mb-4"><AlertDescription>{actionError}</AlertDescription></Alert>}

      <DataList
        data={providers.data}
        error={providers.error}
        isEmpty={(rows) => rows.length === 0}
        empty={<EmptyState title={t("states:idpEmptyTitle")} hint={t("states:idpEmptyHint")} />}
      >
        {(rows) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("idpColName")}</TableHead>
                <TableHead>{t("idpColAlias")}</TableHead>
                <TableHead>{t("idpColIssuer")}</TableHead>
                <TableHead>{t("idpColStatus")}</TableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((p) => (
                <TableRow key={p.alias}>
                  <TableCell className="font-medium">{p.displayName}</TableCell>
                  <TableCell><Badge variant="muted" className="font-mono">{p.alias}</Badge></TableCell>
                  <TableCell className="max-w-xs truncate text-muted-foreground">{p.issuerUri}</TableCell>
                  <TableCell>
                    <div className="flex gap-1">
                      <Badge variant={p.enabled ? "success" : "muted"}>{p.enabled ? t("idpEnabled") : t("idpDisabled")}</Badge>
                      {p.allowJitProvisioning && <Badge variant="muted">{t("idpJit")}</Badge>}
                    </div>
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="flex justify-end gap-1">
                      <Button variant="ghost" size="icon" onClick={() => edit(p)}><Pencil /></Button>
                      <Button variant="ghost" size="icon" className="text-muted-foreground hover:text-destructive"
                              onClick={() => remove(p)}><Trash2 /></Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editor.id ? t("idpDialogEdit", { name: editor.displayName }) : t("idpDialogCreate")}</DialogTitle>
            <DialogDescription>{t("idpDialogDescription")}</DialogDescription>
          </DialogHeader>

          {formError && <Alert variant="destructive"><AlertDescription>{formError}</AlertDescription></Alert>}

          <form onSubmit={save} className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-2">
                <Label htmlFor="idp-alias">{t("idpAliasLabel")}</Label>
                <Input id="idp-alias" className="font-mono" value={editor.alias} disabled={!!editor.id}
                       placeholder="google" onChange={(e) => set({ alias: e.target.value })} required />
              </div>
              <div className="space-y-2">
                <Label htmlFor="idp-name">{t("idpNameLabel")}</Label>
                <Input id="idp-name" value={editor.displayName} placeholder="Google"
                       onChange={(e) => set({ displayName: e.target.value })} required />
              </div>
            </div>
            <div className="space-y-2">
              <Label htmlFor="idp-issuer">{t("idpIssuerLabel")}</Label>
              <Input id="idp-issuer" value={editor.issuerUri} placeholder="https://accounts.google.com"
                     onChange={(e) => set({ issuerUri: e.target.value })} required />
            </div>
            <div className="space-y-2">
              <Label htmlFor="idp-client-id">{t("idpClientIdLabel")}</Label>
              <Input id="idp-client-id" value={editor.clientId} onChange={(e) => set({ clientId: e.target.value })} required />
            </div>
            <div className="space-y-2">
              <Label htmlFor="idp-secret">{t("idpClientSecretLabel")}</Label>
              <Input id="idp-secret" type="password" value={editor.clientSecret} autoComplete="new-password"
                     placeholder={editor.id ? t("idpClientSecretKeep") : undefined}
                     onChange={(e) => set({ clientSecret: e.target.value })} required={!editor.id} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="idp-scopes">{t("idpScopesLabel")}</Label>
              <Input id="idp-scopes" className="font-mono" value={editor.scopes}
                     onChange={(e) => set({ scopes: e.target.value })} />
              <p className="text-xs text-muted-foreground">{t("idpScopesHint")}</p>
            </div>
            <div className="flex items-center justify-between rounded-lg border p-3">
              <div>
                <p className="text-sm font-medium">{t("idpJitLabel")}</p>
                <p className="text-xs text-muted-foreground">{t("idpJitHint")}</p>
              </div>
              <Switch checked={editor.allowJitProvisioning} onCheckedChange={(v) => set({ allowJitProvisioning: v })} />
            </div>
            <div className="flex items-center justify-between rounded-lg border p-3">
              <div>
                <p className="text-sm font-medium">{t("idpEnabledLabel")}</p>
                <p className="text-xs text-muted-foreground">{t("idpEnabledHint")}</p>
              </div>
              <Switch checked={editor.enabled} onCheckedChange={(v) => set({ enabled: v })} />
            </div>

            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setOpen(false)}>{t("cancel")}</Button>
              <Button type="submit"><Save /> {editor.id ? t("saveChanges") : t("idpCreate")}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

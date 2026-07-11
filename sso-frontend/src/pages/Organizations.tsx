import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { BarChart3, Building2, LogIn, Pause, Pencil, Play, Plus, Trash2 } from "lucide-react";
import {
  createOrganization, updateOrganization, updatePasswordlessLogin,
  type Organization, type OrganizationStatus,
} from "@/organizations";
import { setDrillIn } from "@/drillIn";
import { usePaginated } from "@/usePaginated";
import { errorMessage } from "@/api";
import { formatDate } from "@/lib/format";
import { Pagination } from "@/components/Pagination";
import { PageHeader } from "@/components/PageHeader";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Toggle } from "@/components/form/fields";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { useEditorForm } from "@/hooks/useEditorForm";

interface Editor {
  id: string | null;
  slug: string;
  name: string;
  status: OrganizationStatus;
  passwordlessLoginEnabled: boolean;
}

const blankEditor: Editor = { id: null, slug: "", name: "", status: "ACTIVE", passwordlessLoginEnabled: false };

/**
 * Platform super-admin registry of tenants: create, rename, suspend/activate, and delete organizations.
 * The nav item is super-admin-gated; the API additionally enforces platform permissions server-side, so a
 * tenant admin can neither reach this page's nav nor its mutations. Per-tenant configuration (users,
 * roles, policies) is done by drilling into an org (SA-3), not here.
 */
export default function Organizations() {
  const { t, i18n } = useTranslation(["console", "states"]);
  const navigate = useNavigate();
  const confirmDelete = useDeleteConfirm();
  const { items: orgs, total, page, setPage, size, error: listError, reload } =
    usePaginated<Organization>("/api/admin/organizations");

  const {
    editor, set, setEditor, open, setOpen, error, openCreate, openEdit, save,
  } = useEditorForm<Editor>({
    blank: blankEditor,
    toRequest: (e) => ({ slug: e.slug, name: e.name, status: e.status }),
    create: (body) => createOrganization({ slug: (body as Editor).slug, name: (body as Editor).name }),
    update: async (id, body) => {
      const e = body as Editor;
      await updateOrganization(id, { name: e.name, status: e.status });
      // Passwordless login is a distinct, step-up-gated setting with its own endpoint.
      return updatePasswordlessLogin(id, e.passwordlessLoginEnabled);
    },
    onSaved: reload,
  });

  async function toggleStatus(org: Organization) {
    const next: OrganizationStatus = org.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE";
    try {
      await updateOrganization(org.id, { name: org.name, status: next });
      reload();
    } catch (e) {
      window.alert(errorMessage(e)); // rare; step-up is handled by the api client
    }
  }

  function manage(org: Organization) {
    // Drill in: scope subsequent admin requests to this tenant, then land on its user directory.
    setDrillIn({ id: org.id, slug: org.slug });
    navigate("/admin/users");
  }

  async function remove(org: Organization) {
    await confirmDelete({
      title: t("organizationsDeleteTitle"),
      description: t("organizationsDeleteDescription", { name: org.name, slug: org.slug }),
      path: `/api/admin/organizations/${org.id}`,
      onDeleted: reload,
    });
  }

  return (
    <>
      <PageHeader
        title={t("organizationsTitle")}
        description={t("organizationsDescription")}
        actions={<Button onClick={openCreate}><Plus /> {t("organizationsNew")}</Button>}
      />

      <DataList
        data={orgs}
        error={listError}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState icon={<Building2 className="size-8" />} title={t("states:organizationsEmptyTitle")}
                           hint={t("states:organizationsEmptyHint")} />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("organizationsColOrganization")}</TableHead>
                <TableHead>{t("organizationsColIdentifier")}</TableHead>
                <TableHead>{t("organizationsColStatus")}</TableHead>
                <TableHead>{t("organizationsColCreated")}</TableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((org) => (
                <TableRow key={org.id}>
                  <TableCell className="font-medium">
                    <span className="inline-flex items-center gap-2">
                      <Building2 className="size-4 text-muted-foreground" /> {org.name}
                    </span>
                  </TableCell>
                  <TableCell className="font-mono text-xs text-muted-foreground">{org.slug}</TableCell>
                  <TableCell>
                    <Badge variant={org.status === "ACTIVE" ? "success" : "destructive"}>
                      {org.status === "ACTIVE" ? t("organizationsActive") : t("organizationsSuspended")}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {formatDate(org.createdAt, i18n.language)}
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="flex justify-end gap-1">
                      <Button variant="ghost" size="icon" title={t("organizationsAnalytics")}
                              onClick={() => navigate(`/admin/organizations/${org.id}`)}>
                        <BarChart3 />
                      </Button>
                      <Button variant="ghost" size="sm" title={t("organizationsManageTitle")}
                              disabled={org.status !== "ACTIVE"} onClick={() => manage(org)}>
                        <LogIn /> {t("organizationsManage")}
                      </Button>
                      <Button variant="ghost" size="icon" title={t("organizationsEdit")} onClick={() =>
                        openEdit({ id: org.id, slug: org.slug, name: org.name, status: org.status,
                          passwordlessLoginEnabled: org.passwordlessLoginEnabled })}>
                        <Pencil />
                      </Button>
                      <Button variant="ghost" size="icon"
                              title={org.status === "ACTIVE" ? t("organizationsSuspend") : t("organizationsActivate")}
                              onClick={() => toggleStatus(org)}>
                        {org.status === "ACTIVE" ? <Pause /> : <Play />}
                      </Button>
                      <Button variant="ghost" size="icon" className="text-muted-foreground hover:text-destructive"
                              title={t("organizationsDelete")} onClick={() => remove(org)}>
                        <Trash2 />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>
      <Pagination page={page} size={size} total={total} onPage={setPage} />

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editor.id ? t("organizationsDialogEdit", { slug: editor.slug }) : t("organizationsDialogCreate")}</DialogTitle>
            <DialogDescription>
              {editor.id ? t("organizationsEditHint") : t("organizationsCreateHint")}
            </DialogDescription>
          </DialogHeader>

          {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}

          <form onSubmit={save} className="space-y-4">
            {!editor.id && (
              <div className="space-y-2">
                <Label htmlFor="org-slug">{t("organizationsIdentifierLabel")}</Label>
                <Input id="org-slug" value={editor.slug} required autoFocus
                       autoCapitalize="none" autoCorrect="off" spellCheck={false}
                       placeholder="acme" onChange={(e) => set({ slug: e.target.value })} />
              </div>
            )}
            <div className="space-y-2">
              <Label htmlFor="org-name">{t("organizationsNameLabel")}</Label>
              <Input id="org-name" value={editor.name} required autoFocus={!!editor.id}
                     placeholder="Acme, Inc." onChange={(e) => set({ name: e.target.value })} />
            </div>
            {editor.id && (
              <Toggle
                label={t("organizationsPasswordlessLabel")}
                hint={t("organizationsPasswordlessHint")}
                checked={editor.passwordlessLoginEnabled}
                onChange={(v) => set({ passwordlessLoginEnabled: v })}
              />
            )}

            <DialogFooter>
              <Button type="button" variant="outline"
                      onClick={() => { setEditor(blankEditor); setOpen(false); }}>{t("cancel")}</Button>
              <Button type="submit">{editor.id ? t("saveChanges") : t("organizationsCreateOrg")}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

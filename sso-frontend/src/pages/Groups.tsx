import { Link } from "react-router-dom";
import { Trans, useTranslation } from "react-i18next";
import { Lock, Pencil, Plus, Trash2 } from "lucide-react";
import { createGroup, updateGroup, type Group, type GroupRequest } from "@/groups";
import { usePaginated } from "@/usePaginated";
import { Pagination } from "@/components/Pagination";
import { UserMultiSelect } from "@/components/UserMultiSelect";
import { PageHeader } from "@/components/PageHeader";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { useEditorForm } from "@/hooks/useEditorForm";

interface Editor {
  id: string | null;
  name: string;
  description: string;
  externalId: string;
  userIds: string[];
}

const blankEditor: Editor = { id: null, name: "", description: "", externalId: "", userIds: [] };

export default function Groups() {
  const { t } = useTranslation(["console", "states"]);
  const confirmDelete = useDeleteConfirm();
  const { items: groups, total, page, setPage, size, error: listError, reload } = usePaginated<Group>("/api/admin/groups");

  const {
    editor, set, setEditor, open, setOpen, error, openCreate, openEdit, save,
  } = useEditorForm<Editor>({
    blank: blankEditor,
    toRequest: (e) => ({
      name: e.name,
      description: e.description || null,
      externalId: e.externalId || null,
      memberUserIds: e.userIds,
    }),
    create: (body) => createGroup(body as GroupRequest),
    update: (id, body) => updateGroup(id, body as GroupRequest),
    onSaved: reload,
  });

  function editGroup(g: Group) {
    openEdit({
      id: g.id, name: g.name, description: g.description ?? "", externalId: g.externalId ?? "",
      userIds: [...g.memberUserIds],
    });
  }

  async function remove(g: Group) {
    await confirmDelete({
      title: t("groupsDeleteTitle"),
      description: t("groupsDeleteDescription", { name: g.name }),
      path: `/api/admin/groups/${g.id}`,
      onDeleted: reload,
    });
  }

  return (
    <>
      <PageHeader
        title={t("groupsTitle")}
        description={t("groupsDescription")}
        actions={<Button onClick={openCreate}><Plus /> {t("groupsNew")}</Button>}
      />

      <Alert variant="info" className="mb-4">
        <AlertDescription>
          <Trans t={t} i18nKey="groupsInfo" components={[<strong key="0" />, <strong key="1" />]} />
        </AlertDescription>
      </Alert>

      <DataList
        data={groups}
        error={listError}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState title={t("states:groupsEmptyTitle")} hint={t("states:groupsEmptyHint")} />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("groupsColName")}</TableHead>
                <TableHead>{t("groupsColDescription")}</TableHead>
                <TableHead>{t("groupsColExternalId")}</TableHead>
                <TableHead>{t("groupsColMembers")}</TableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((g) => (
                <TableRow key={g.id}>
                  <TableCell className="font-medium">
                    <span className="inline-flex items-center gap-2">
                      <Link to={`/admin/groups/${g.id}`} className="text-primary hover:underline">{g.name}</Link>
                      {g.system && <Badge variant="secondary"><Lock className="size-3" /> {t("badgeSystem")}</Badge>}
                    </span>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{g.description || "—"}</TableCell>
                  <TableCell className="text-muted-foreground">
                    {g.externalId ? <Badge variant="outline">{g.externalId}</Badge> : "—"}
                  </TableCell>
                  <TableCell><Badge variant="muted">{g.memberCount}</Badge></TableCell>
                  <TableCell className="text-right">
                    {g.system ? (
                      <span className="text-xs text-muted-foreground">{t("groupsManaged")}</span>
                    ) : (
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="icon" onClick={() => editGroup(g)}><Pencil /></Button>
                        <Button variant="ghost" size="icon" className="text-muted-foreground hover:text-destructive" onClick={() => remove(g)}><Trash2 /></Button>
                      </div>
                    )}
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
            <DialogTitle>{editor.id ? t("groupsDialogEdit", { name: editor.name }) : t("groupsDialogCreate")}</DialogTitle>
            <DialogDescription>{t("groupsDialogDescription")}</DialogDescription>
          </DialogHeader>

          {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}

          <form onSubmit={save} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="grp-name">{t("groupsNameLabel")}</Label>
              <Input id="grp-name" value={editor.name}
                     onChange={(e) => set({ name: e.target.value })} required />
            </div>

            <div className="space-y-2">
              <Label htmlFor="grp-description">{t("groupsDescriptionLabel")}</Label>
              <Input id="grp-description" value={editor.description}
                     onChange={(e) => set({ description: e.target.value })} />
            </div>

            <div className="space-y-2">
              <Label htmlFor="grp-external-id">{t("groupsExternalIdLabel")}</Label>
              <Input id="grp-external-id" value={editor.externalId}
                     onChange={(e) => set({ externalId: e.target.value })} />
            </div>

            <div className="space-y-2">
              <Label>{t("groupsMembersLabel")}</Label>
              <UserMultiSelect selected={editor.userIds} onChange={(ids) => set({ userIds: ids })}
                               placeholder={t("groupsMembersPlaceholder")} />
            </div>

            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => { setEditor(blankEditor); setOpen(false); }}>{t("cancel")}</Button>
              <Button type="submit">{editor.id ? t("saveChanges") : t("groupsCreateGroup")}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

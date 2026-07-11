import { useState } from "react";
import { Trans, useTranslation } from "react-i18next";
import { Pencil, Plus, Save, Trash2, X } from "lucide-react";
import { createZone, updateZone, type NetworkZone } from "@/zones";
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
import { Pagination } from "@/components/Pagination";
import { usePaginated } from "@/usePaginated";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { useEditorForm } from "@/hooks/useEditorForm";

interface Editor {
  id: string | null;
  name: string;
  description: string;
  cidrs: string[];
}
const blank: Editor = { id: null, name: "", description: "", cidrs: [""] };

/** Catalog of reusable named IP zones (CIDR sets) that session policies reference to allow/block networks. */
export default function NetworkZones() {
  const { t } = useTranslation(["console", "states"]);
  const { items, total, page, setPage, size, error, reload } = usePaginated<NetworkZone>("/api/admin/network-zones");
  const confirmDelete = useDeleteConfirm();
  const [actionError, setActionError] = useState<string | null>(null);

  const { editor, set, open, setOpen, error: formError, openCreate, openEdit, save } = useEditorForm<Editor>({
    blank,
    toRequest: (e) => ({
      name: e.name,
      description: e.description.trim() || null,
      cidrs: e.cidrs.map((c) => c.trim()).filter(Boolean),
    }),
    create: (body) => createZone(body),
    update: (id, body) => updateZone(id, body),
    onSaved: reload,
  });

  const editZone = (z: NetworkZone) =>
    openEdit({ id: z.id, name: z.name, description: z.description ?? "", cidrs: z.cidrs.length ? [...z.cidrs] : [""] });
  const setCidr = (i: number, v: string) => set({ cidrs: editor.cidrs.map((c, j) => (j === i ? v : c)) });
  const addCidr = () => set({ cidrs: [...editor.cidrs, ""] });
  const removeCidr = (i: number) => set({ cidrs: editor.cidrs.filter((_, j) => j !== i) });

  const remove = (z: NetworkZone) => {
    setActionError(null);
    return confirmDelete({
      title: t("networkZonesDeleteTitle"),
      description: t("networkZonesDeleteDescription", { name: z.name }),
      path: `/api/admin/network-zones/${z.id}`,
      onDeleted: reload,
      onError: setActionError, // e.g. the designed 409 when a policy still references the zone
    });
  };

  return (
    <>
      <PageHeader
        title={t("networkZonesTitle")}
        description={t("networkZonesDescription")}
        actions={<Button onClick={openCreate}><Plus /> {t("networkZonesNew")}</Button>}
      />

      <Alert variant="info" className="mb-4">
        <AlertDescription>
          <Trans t={t} i18nKey="networkZonesInfo" components={[<strong key="0" />]} />
        </AlertDescription>
      </Alert>

      {actionError && <Alert variant="destructive" className="mb-4"><AlertDescription>{actionError}</AlertDescription></Alert>}

      <DataList
        data={items}
        error={error}
        isEmpty={(rows) => rows.length === 0}
        empty={<EmptyState title={t("states:networkZonesEmptyTitle")} hint={t("states:networkZonesEmptyHint")} />}
      >
        {(rows) => (
          <>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("networkZonesColName")}</TableHead>
                  <TableHead>{t("networkZonesColCidrs")}</TableHead>
                  <TableHead>{t("networkZonesColDescription")}</TableHead>
                  <TableHead className="w-0" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((z) => (
                  <TableRow key={z.id}>
                    <TableCell className="font-medium">{z.name}</TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {z.cidrs.map((c) => <Badge key={c} variant="muted" className="font-mono">{c}</Badge>)}
                      </div>
                    </TableCell>
                    <TableCell className="text-muted-foreground">{z.description || "—"}</TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="icon" onClick={() => editZone(z)}><Pencil /></Button>
                        <Button variant="ghost" size="icon" className="text-muted-foreground hover:text-destructive" onClick={() => remove(z)}><Trash2 /></Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Pagination page={page} size={size} total={total} onPage={setPage} />
          </>
        )}
      </DataList>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editor.id ? t("networkZonesDialogEdit", { name: editor.name }) : t("networkZonesDialogCreate")}</DialogTitle>
            <DialogDescription>{t("networkZonesDialogDescription")}</DialogDescription>
          </DialogHeader>

          {formError && <Alert variant="destructive"><AlertDescription>{formError}</AlertDescription></Alert>}

          <form onSubmit={save} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="nz-name">{t("networkZonesNameLabel")}</Label>
              <Input id="nz-name" value={editor.name} onChange={(e) => set({ name: e.target.value })} required />
            </div>
            <div className="space-y-2">
              <Label htmlFor="nz-desc">{t("networkZonesDescriptionLabel")}</Label>
              <Input id="nz-desc" value={editor.description} onChange={(e) => set({ description: e.target.value })}
                     placeholder={t("networkZonesDescriptionPlaceholder")} />
            </div>
            <div className="space-y-2">
              <Label>{t("networkZonesCidrsLabel")}</Label>
              {editor.cidrs.map((c, i) => (
                <div key={i} className="flex gap-2">
                  <Input className="font-mono" value={c} placeholder={t("networkZonesCidrPlaceholder")}
                         onChange={(e) => setCidr(i, e.target.value)} />
                  <Button type="button" variant="ghost" size="icon" disabled={editor.cidrs.length === 1}
                          onClick={() => removeCidr(i)} aria-label={t("networkZonesRemoveCidr")}><X /></Button>
                </div>
              ))}
              <Button type="button" variant="outline" size="sm" onClick={addCidr}><Plus /> {t("networkZonesAddCidr")}</Button>
            </div>

            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setOpen(false)}>{t("cancel")}</Button>
              <Button type="submit"><Save /> {editor.id ? t("saveChanges") : t("networkZonesCreateZone")}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

import { useState } from "react";
import { useTranslation } from "react-i18next";
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
  const { t } = useTranslation("states");
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
      title: "Delete network zone?",
      description: `"${z.name}" will be removed. A zone in use by a session policy cannot be deleted.`,
      path: `/api/admin/network-zones/${z.id}`,
      onDeleted: reload,
      onError: setActionError, // e.g. the designed 409 when a policy still references the zone
    });
  };

  return (
    <>
      <PageHeader
        title="Network Zones"
        description="Reusable named IP ranges — reference them from a session policy's Network tab to allow or block networks."
        actions={<Button onClick={openCreate}><Plus /> New zone</Button>}
      />

      <Alert variant="info" className="mb-4">
        <AlertDescription>
          Define a zone once (e.g. <strong>Corporate network</strong> = your office subnets), then pick it in a
          session policy. A zone that a policy references cannot be deleted.
        </AlertDescription>
      </Alert>

      {actionError && <Alert variant="destructive" className="mb-4"><AlertDescription>{actionError}</AlertDescription></Alert>}

      <DataList
        data={items}
        error={error}
        isEmpty={(rows) => rows.length === 0}
        empty={<EmptyState title={t("networkZonesEmptyTitle")} hint={t("networkZonesEmptyHint")} />}
      >
        {(rows) => (
          <>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>CIDR ranges</TableHead>
                  <TableHead>Description</TableHead>
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
            <DialogTitle>{editor.id ? `Edit zone: ${editor.name}` : "New network zone"}</DialogTitle>
            <DialogDescription>A name and the CIDR ranges it covers (IPv4 and/or IPv6).</DialogDescription>
          </DialogHeader>

          {formError && <Alert variant="destructive"><AlertDescription>{formError}</AlertDescription></Alert>}

          <form onSubmit={save} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="nz-name">Name</Label>
              <Input id="nz-name" value={editor.name} onChange={(e) => set({ name: e.target.value })} required />
            </div>
            <div className="space-y-2">
              <Label htmlFor="nz-desc">Description</Label>
              <Input id="nz-desc" value={editor.description} onChange={(e) => set({ description: e.target.value })}
                     placeholder="Optional" />
            </div>
            <div className="space-y-2">
              <Label>CIDR ranges</Label>
              {editor.cidrs.map((c, i) => (
                <div key={i} className="flex gap-2">
                  <Input className="font-mono" value={c} placeholder="203.0.113.0/24 or 2001:db8::/32"
                         onChange={(e) => setCidr(i, e.target.value)} />
                  <Button type="button" variant="ghost" size="icon" disabled={editor.cidrs.length === 1}
                          onClick={() => removeCidr(i)} aria-label="Remove CIDR"><X /></Button>
                </div>
              ))}
              <Button type="button" variant="outline" size="sm" onClick={addCidr}><Plus /> Add CIDR</Button>
            </div>

            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setOpen(false)}>Cancel</Button>
              <Button type="submit"><Save /> {editor.id ? "Save changes" : "Create zone"}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

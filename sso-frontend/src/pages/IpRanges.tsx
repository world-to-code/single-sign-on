import { Ban, CheckCircle2, Globe, Pencil, Plus, Trash2 } from "lucide-react";
import { apiPost, apiPut } from "../api";
import { usePaginated } from "@/usePaginated";
import { Pagination } from "@/components/Pagination";
import { PageHeader } from "@/components/PageHeader";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { useEditorForm } from "@/hooks/useEditorForm";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";

interface IpRule {
  id: string;
  cidr: string;
  action: "ALLOW" | "BLOCK";
  description: string | null;
  enabled: boolean;
  priority: number;
}

const blank = { id: null as string | null, cidr: "", action: "BLOCK", description: "", enabled: true, priority: "100" };
type Editor = typeof blank;

export default function IpRanges() {
  const confirmDelete = useDeleteConfirm();
  const { items: rules, total, page, setPage, size, error, reload } = usePaginated<IpRule>("/api/admin/ip-rules");

  const {
    editor, set, setEditor, open, setOpen, error: formError, openCreate, openEdit, save,
  } = useEditorForm<Editor>({
    blank,
    toRequest: (e) => ({ cidr: e.cidr.trim(), action: e.action, description: e.description || null, enabled: e.enabled, priority: Number(e.priority) }),
    create: (body) => apiPost("/api/admin/ip-rules", body),
    update: (id, body) => apiPut(`/api/admin/ip-rules/${id}`, body),
    onSaved: reload,
  });

  function edit(r: IpRule) {
    openEdit({ id: r.id, cidr: r.cidr, action: r.action, description: r.description ?? "", enabled: r.enabled, priority: String(r.priority) });
  }

  async function remove(r: IpRule) {
    await confirmDelete({
      title: "Delete IP rule?",
      description: `${r.action} ${r.cidr} will be removed.`,
      path: `/api/admin/ip-rules/${r.id}`,
      onDeleted: reload,
    });
  }

  return (
    <>
      <PageHeader
        title="IP Ranges"
        description="Network access control. BLOCK denies a range; if any ALLOW rule exists, only listed ranges may connect."
        actions={<Button onClick={openCreate}><Plus /> New rule</Button>}
      />

      <Alert variant="info" className="mb-4">
        <AlertDescription>
          Rules apply to <strong>every</strong> request (evaluated before sign-in), lowest priority first.
          Take care not to block your own network — you could lock yourself out.
        </AlertDescription>
      </Alert>

      <DataList
        data={rules}
        error={error}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState icon={<Globe className="size-8" />} title="No IP rules" hint="All networks are allowed. Add a BLOCK rule to deny a range, or ALLOW rules to restrict access." />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>CIDR</TableHead><TableHead>Action</TableHead><TableHead>Description</TableHead>
                <TableHead>Priority</TableHead><TableHead>Status</TableHead><TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((r) => (
                <TableRow key={r.id}>
                  <TableCell className="font-mono text-sm">{r.cidr}</TableCell>
                  <TableCell>
                    <Badge variant={r.action === "BLOCK" ? "destructive" : "success"}>
                      {r.action === "BLOCK" ? <Ban className="mr-1 size-3" /> : <CheckCircle2 className="mr-1 size-3" />}{r.action}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{r.description || "—"}</TableCell>
                  <TableCell><Badge variant="muted">{r.priority}</Badge></TableCell>
                  <TableCell><Badge variant={r.enabled ? "success" : "muted"}>{r.enabled ? "enabled" : "disabled"}</Badge></TableCell>
                  <TableCell className="text-right">
                    <div className="flex justify-end gap-1">
                      <Button variant="ghost" size="icon" onClick={() => edit(r)}><Pencil /></Button>
                      <Button variant="ghost" size="icon" className="text-muted-foreground hover:text-destructive" onClick={() => remove(r)}><Trash2 /></Button>
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
            <DialogTitle>{editor.id ? "Edit IP rule" : "New IP rule"}</DialogTitle>
            <DialogDescription>Specify a CIDR range and whether to allow or block it.</DialogDescription>
          </DialogHeader>
          {formError && <Alert variant="destructive"><AlertDescription>{formError}</AlertDescription></Alert>}
          <form onSubmit={save} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="cidr">CIDR range</Label>
              <Input id="cidr" value={editor.cidr} onChange={(e) => set({ cidr: e.target.value })}
                     placeholder="203.0.113.0/24 or 2001:db8::/32" required className="font-mono" />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="action">Action</Label>
                <Select id="action" value={editor.action} onChange={(e) => set({ action: e.target.value })}>
                  <option value="BLOCK">BLOCK</option><option value="ALLOW">ALLOW</option>
                </Select>
              </div>
              <div className="space-y-2">
                <Label htmlFor="priority">Priority <span className="text-muted-foreground">(lower first)</span></Label>
                <Input id="priority" type="number" value={editor.priority} onChange={(e) => set({ priority: e.target.value })} />
              </div>
            </div>
            <div className="space-y-2">
              <Label htmlFor="desc">Description</Label>
              <Input id="desc" value={editor.description} onChange={(e) => set({ description: e.target.value })} placeholder="e.g. Corporate VPN" />
            </div>
            <div className="flex items-center gap-2">
              <Switch id="enabled" checked={editor.enabled} onCheckedChange={(v) => set({ enabled: v })} />
              <Label htmlFor="enabled">Enabled</Label>
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => { setEditor(blank); setOpen(false); }}>Cancel</Button>
              <Button type="submit">{editor.id ? "Save changes" : "Create rule"}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

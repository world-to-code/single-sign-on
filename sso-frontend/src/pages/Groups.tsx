import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Lock, Pencil, Plus, Trash2 } from "lucide-react";
import { apiGet } from "../api";
import { createGroup, updateGroup, type Group, type GroupRequest } from "@/groups";
import { PageHeader } from "@/components/PageHeader";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { useEditorForm } from "@/hooks/useEditorForm";

interface User { id: string; username: string }

interface Editor {
  id: string | null;
  name: string;
  description: string;
  externalId: string;
  userIds: string[];
}

const blankEditor: Editor = { id: null, name: "", description: "", externalId: "", userIds: [] };

export default function Groups() {
  const confirmDelete = useDeleteConfirm();
  const [groups, setGroups] = useState<Group[] | null>(null);
  const [users, setUsers] = useState<User[]>([]);

  const {
    editor, set, setEditor, open, setOpen, error, setError, openCreate, openEdit, save,
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

  function reload() {
    apiGet<Group[]>("/api/admin/groups").then(setGroups).catch((e) => setError(String(e)));
  }
  useEffect(() => {
    reload();
    apiGet<User[]>("/api/admin/users").then(setUsers).catch(() => undefined);
  }, []);

  function toggle(list: string[], id: string): string[] {
    return list.includes(id) ? list.filter((x) => x !== id) : [...list, id];
  }

  function editGroup(g: Group) {
    openEdit({
      id: g.id, name: g.name, description: g.description ?? "", externalId: g.externalId ?? "",
      userIds: [...g.memberUserIds],
    });
  }

  async function remove(g: Group) {
    await confirmDelete({
      title: "Delete group?",
      description: `"${g.name}" will be permanently removed.`,
      path: `/api/admin/groups/${g.id}`,
      onDeleted: reload,
    });
  }

  return (
    <>
      <PageHeader
        title="Groups"
        description="Organizational groups bundle users by department/team — separate from RBAC roles."
        actions={<Button onClick={openCreate}><Plus /> New group</Button>}
      />

      <Alert variant="info" className="mb-4">
        <AlertDescription>
          Groups are a <strong>directory</strong> concern for org/department membership, independent of
          roles and access policy. The optional <strong>External ID</strong> is reserved for future
          LDAP/SCIM synchronization.
        </AlertDescription>
      </Alert>

      <DataList
        data={groups}
        error={error}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState title="No groups yet" hint="Create a group to bundle users by department or team." />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Description</TableHead>
                <TableHead>External ID</TableHead>
                <TableHead>Members</TableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((g) => (
                <TableRow key={g.id}>
                  <TableCell className="font-medium">
                    <span className="inline-flex items-center gap-2">
                      <Link to={`/admin/groups/${g.id}`} className="text-primary hover:underline">{g.name}</Link>
                      {g.system && <Badge variant="secondary"><Lock className="size-3" /> System</Badge>}
                    </span>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{g.description || "—"}</TableCell>
                  <TableCell className="text-muted-foreground">
                    {g.externalId ? <Badge variant="outline">{g.externalId}</Badge> : "—"}
                  </TableCell>
                  <TableCell><Badge variant="muted">{g.memberCount}</Badge></TableCell>
                  <TableCell className="text-right">
                    {g.system ? (
                      <span className="text-xs text-muted-foreground">managed</span>
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

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editor.id ? `Edit group: ${editor.name}` : "Create group"}</DialogTitle>
            <DialogDescription>Bundle users into an organizational group.</DialogDescription>
          </DialogHeader>

          {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}

          <form onSubmit={save} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="grp-name">Name</Label>
              <Input id="grp-name" value={editor.name}
                     onChange={(e) => set({ name: e.target.value })} required />
            </div>

            <div className="space-y-2">
              <Label htmlFor="grp-description">Description</Label>
              <Input id="grp-description" value={editor.description}
                     onChange={(e) => set({ description: e.target.value })} />
            </div>

            <div className="space-y-2">
              <Label htmlFor="grp-external-id">External ID (for LDAP/SCIM sync, optional)</Label>
              <Input id="grp-external-id" value={editor.externalId}
                     onChange={(e) => set({ externalId: e.target.value })} />
            </div>

            <div className="space-y-2">
              <Label>Members</Label>
              <div className="max-h-36 space-y-1 overflow-y-auto rounded-md border p-3">
                {users.length === 0 ? <span className="text-sm text-muted-foreground">none</span> : users.map((u) => (
                  <label key={u.id} className="flex items-center gap-2 text-sm">
                    <Checkbox checked={editor.userIds.includes(u.id)}
                              onCheckedChange={() => set({ userIds: toggle(editor.userIds, u.id) })} /> {u.username}
                  </label>
                ))}
              </div>
            </div>

            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => { setEditor(blankEditor); setOpen(false); }}>Cancel</Button>
              <Button type="submit">{editor.id ? "Save changes" : "Create group"}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

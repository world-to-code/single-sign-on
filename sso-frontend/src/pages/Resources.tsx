import { useCallback, useEffect, useState } from "react";
import { Boxes, Plus, Trash2, Layers, ChevronRight } from "lucide-react";
import {
  LEAF_MEMBER_TYPES, MEMBER_TYPES, assignResourceAdmin, attachChild, attachMember, createResource,
  createResourceType, deleteResource, detachChild, detachMember, listResourceTypes, listResources,
  revokeResourceAdmin, type MemberType, type Resource, type ResourceType,
} from "@/resources";
import { PageHeader } from "@/components/PageHeader";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { Field } from "@/components/form/fields";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Checkbox } from "@/components/ui/checkbox";
import { DataList, EmptyState } from "@/components/states";

export default function Resources() {
  const [resources, setResources] = useState<Resource[] | null>(null);
  const [types, setTypes] = useState<ResourceType[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Resource | null>(null);
  const [creating, setCreating] = useState(false);
  const [typing, setTyping] = useState(false);
  const confirmDelete = useDeleteConfirm();

  const reload = useCallback(async () => {
    try {
      const [rs, ts] = await Promise.all([listResources(), listResourceTypes()]);
      setResources(rs);
      setTypes(ts);
      setError(null);
      // Keep the open detail panel in sync with the latest server state.
      setSelected((cur) => (cur ? rs.find((r) => r.id === cur.id) ?? null : null));
    } catch (e) {
      setError(String(e));
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  return (
    <div className="space-y-6">
      <PageHeader
        title="Resources"
        description="Organizational units (a DAG) bundling groups, apps, and child resources — with subtree-scoped admins."
        actions={
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => setTyping(true)}><Layers /> New type</Button>
            <Button onClick={() => setCreating(true)} disabled={!types.length}><Plus /> New resource</Button>
          </div>
        }
      />

      <DataList
        data={resources}
        error={error}
        isEmpty={(r) => r.length === 0}
        empty={<EmptyState icon={<Boxes />} title="No resources yet"
          hint={types.length ? "Create one to start delegating administration." : "Create a resource type first."} />}
      >
        {(rs) => (
          <div className="divide-y">
            {rs.map((r) => (
              <button key={r.id} onClick={() => setSelected(r)}
                className="flex w-full items-center justify-between px-4 py-3 text-left hover:bg-muted/50">
                <div className="flex items-center gap-3">
                  <Boxes className="size-4 text-muted-foreground" />
                  <div>
                    <div className="font-medium">{r.name}</div>
                    <div className="text-xs text-muted-foreground">{r.typeName}</div>
                  </div>
                </div>
                <div className="flex items-center gap-3 text-xs text-muted-foreground">
                  <span>{r.children.length} children</span>
                  <span>{r.members.length} members</span>
                  <span>{r.grants.length} admins</span>
                  <ChevronRight className="size-4" />
                </div>
              </button>
            ))}
          </div>
        )}
      </DataList>

      {creating && (
        <CreateResourceDialog types={types} onClose={() => setCreating(false)}
          onCreated={async () => { setCreating(false); await reload(); }} />
      )}
      {typing && (
        <CreateTypeDialog onClose={() => setTyping(false)}
          onCreated={async () => { setTyping(false); await reload(); }} />
      )}
      {selected && (
        <ResourceDetailDialog
          resource={selected}
          allResources={resources ?? []}
          onClose={() => setSelected(null)}
          onChanged={reload}
          onDelete={() =>
            confirmDelete({
              title: `Delete "${selected.name}"?`,
              description: "Its edges, members, and admin grants are removed. Child resources survive via other parents.",
              run: () => deleteResource(selected.id),
              onDeleted: async () => { setSelected(null); await reload(); },
            })
          }
        />
      )}
    </div>
  );
}

function CreateResourceDialog(
  { types, onClose, onCreated }: { types: ResourceType[]; onClose: () => void; onCreated: () => void },
) {
  const [name, setName] = useState("");
  const [typeName, setTypeName] = useState(types[0]?.name ?? "");
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    try {
      await createResource(name.trim(), typeName);
      onCreated();
    } catch (e) {
      setError(String(e));
    }
  }

  return (
    <Dialog open onOpenChange={onClose}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>New resource</DialogTitle>
          <DialogDescription>A node in the resource graph.</DialogDescription>
        </DialogHeader>
        {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}
        <Field label="Name"><Input value={name} onChange={(e) => setName(e.target.value)} autoFocus /></Field>
        <Field label="Type">
          <Select value={typeName} onChange={(e) => setTypeName(e.target.value)}>
            {types.map((t) => <option key={t.id} value={t.name}>{t.name}</option>)}
          </Select>
        </Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>Cancel</Button>
          <Button onClick={() => void submit()} disabled={!name.trim() || !typeName}>Create</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function CreateTypeDialog({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const [name, setName] = useState("");
  const [kinds, setKinds] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);

  const toggle = (k: string) =>
    setKinds((cur) => (cur.includes(k) ? cur.filter((x) => x !== k) : [...cur, k]));

  async function submit() {
    try {
      await createResourceType(name.trim(), kinds);
      onCreated();
    } catch (e) {
      setError(String(e));
    }
  }

  return (
    <Dialog open onOpenChange={onClose}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>New resource type</DialogTitle>
          <DialogDescription>Which member kinds a resource of this type may contain.</DialogDescription>
        </DialogHeader>
        {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}
        <Field label="Name"><Input value={name} onChange={(e) => setName(e.target.value)} autoFocus /></Field>
        <div className="space-y-2">
          {MEMBER_TYPES.map((k) => (
            <label key={k} className="flex items-center gap-2 text-sm">
              <Checkbox checked={kinds.includes(k)} onCheckedChange={() => toggle(k)} /> {k}
            </label>
          ))}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>Cancel</Button>
          <Button onClick={() => void submit()} disabled={!name.trim() || !kinds.length}>Create</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ResourceDetailDialog(
  { resource, allResources, onClose, onChanged, onDelete }: {
    resource: Resource; allResources: Resource[];
    onClose: () => void; onChanged: () => Promise<void>; onDelete: () => void;
  },
) {
  const [error, setError] = useState<string | null>(null);
  const [childId, setChildId] = useState("");
  const [memberType, setMemberType] = useState<MemberType>("GROUP");
  const [memberId, setMemberId] = useState("");
  const [adminUserId, setAdminUserId] = useState("");

  const run = async (op: () => Promise<unknown>) => {
    try {
      await op();
      await onChanged();
      setError(null);
    } catch (e) {
      setError(String(e));
    }
  };

  const attachable = allResources.filter((r) => r.id !== resource.id
    && !resource.children.some((c) => c.id === r.id));

  return (
    <Dialog open onOpenChange={onClose}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><Boxes className="size-4" /> {resource.name}</DialogTitle>
          <DialogDescription>{resource.typeName}</DialogDescription>
        </DialogHeader>
        {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}

        <Section title="Child resources">
          <ChipList items={resource.children.map((c) => ({ key: c.id, label: c.name }))}
            onRemove={(id) => void run(() => detachChild(resource.id, id))} />
          <div className="flex gap-2">
            <Select className="flex-1" value={childId} onChange={(e) => setChildId(e.target.value)}>
              <option value="">Attach a child…</option>
              {attachable.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
            </Select>
            <Button variant="outline" disabled={!childId}
              onClick={() => void run(() => attachChild(resource.id, childId)).then(() => setChildId(""))}>
              <Plus /> Attach
            </Button>
          </div>
        </Section>

        <Section title="Members">
          <ChipList items={resource.members.map((m) => ({ key: `${m.memberType}:${m.memberId}`,
            label: `${m.memberType} · ${m.memberId}` }))}
            onRemove={(key) => { const [t, id] = key.split(/:(.+)/); void run(() => detachMember(resource.id, t, id)); }} />
          <div className="flex gap-2">
            <Select className="w-36" value={memberType}
              onChange={(e) => setMemberType(e.target.value as MemberType)}>
              {LEAF_MEMBER_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
            </Select>
            <Input className="flex-1" placeholder="member id (uuid or app id)" value={memberId}
              onChange={(e) => setMemberId(e.target.value)} />
            <Button variant="outline" disabled={!memberId.trim()}
              onClick={() => void run(() => attachMember(resource.id, memberType, memberId.trim())).then(() => setMemberId(""))}>
              <Plus /> Add
            </Button>
          </div>
        </Section>

        <Section title="Delegated administrators">
          <ChipList items={resource.grants.map((g) => ({ key: g.userId, label: `${g.userId} · ${g.tier}` }))}
            onRemove={(userId) => void run(() => revokeResourceAdmin(resource.id, userId))} />
          <div className="flex gap-2">
            <Input className="flex-1" placeholder="user id (uuid)" value={adminUserId}
              onChange={(e) => setAdminUserId(e.target.value)} />
            <Button variant="outline" disabled={!adminUserId.trim()}
              onClick={() => void run(() => assignResourceAdmin(resource.id, adminUserId.trim())).then(() => setAdminUserId(""))}>
              <Plus /> Assign admin
            </Button>
          </div>
        </Section>

        <DialogFooter className="justify-between">
          <Button variant="destructive" onClick={onDelete}><Trash2 /> Delete resource</Button>
          <Button variant="outline" onClick={onClose}>Close</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="space-y-2">
      <h4 className="text-sm font-semibold">{title}</h4>
      {children}
    </div>
  );
}

function ChipList({ items, onRemove }: { items: { key: string; label: string }[]; onRemove: (key: string) => void }) {
  if (!items.length) return <p className="text-xs text-muted-foreground">None.</p>;
  return (
    <div className="flex flex-wrap gap-2">
      {items.map((i) => (
        <Badge key={i.key} variant="muted" className="gap-1 font-mono text-xs">
          {i.label}
          <button onClick={() => onRemove(i.key)} className="ml-1 text-muted-foreground hover:text-destructive">
            <Trash2 className="size-3" />
          </button>
        </Badge>
      ))}
    </div>
  );
}

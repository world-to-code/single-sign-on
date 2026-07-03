import { useCallback, useEffect, useState } from "react";
import { Boxes, Plus, Trash2, Layers, ChevronRight } from "lucide-react";
import {
  LEAF_MEMBER_TYPES, MEMBER_TYPES, assignResourceAdmin, attachChild, attachMember, createResource,
  createResourceType, deleteResource, deleteResourceType, detachChild, detachMember, getResourceDetail,
  listApplications, listResourceTypes, listResources, renameResource, revokeResourceAdmin,
  type AppOption, type MemberType, type Resource, type ResourceDetail, type ResourceNode, type ResourceType,
} from "@/resources";
import { searchGroups, searchUsers } from "@/groups";
import { errorMessage } from "@/api";
import { PageHeader } from "@/components/PageHeader";
import { SearchSelect, type Suggestion } from "@/components/SearchSelect";
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

const MEMBER_KIND_LABELS: Record<MemberType, string> = {
  RESOURCE: "Child resources", GROUP: "Groups", APPLICATION: "Applications", USER: "Users",
};
const MEMBER_KIND_HINTS: Record<MemberType, string> = {
  RESOURCE: "Nest other resources under this one (a sub-tree).",
  GROUP: "User groups — every member inherits the delegated access.",
  APPLICATION: "OIDC clients & SAML relying parties.",
  USER: "Individual user accounts, attached directly.",
};

export default function Resources() {
  const [resources, setResources] = useState<Resource[] | null>(null);
  const [types, setTypes] = useState<ResourceType[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<ResourceNode | null>(null);
  const [creating, setCreating] = useState(false);
  const [typing, setTyping] = useState(false);
  const [view, setView] = useState<"resources" | "types">("resources");
  const confirmDelete = useDeleteConfirm();

  const reload = useCallback(async () => {
    try {
      const [rs, ts] = await Promise.all([listResources(), listResourceTypes()]);
      setResources(rs);
      setTypes(ts);
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
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
            <Button onClick={() => setCreating(true)} disabled={!types.length}
              title={types.length ? undefined : "Create a resource type first"}><Plus /> New resource</Button>
          </div>
        }
      />

      <div className="flex gap-1 border-b">
        {(["resources", "types"] as const).map((t) => (
          <button key={t} onClick={() => setView(t)}
            className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium capitalize ${view === t ? "border-primary text-foreground" : "border-transparent text-muted-foreground hover:text-foreground"}`}>
            {t === "resources" ? `Resources${resources ? ` (${resources.length})` : ""}` : `Types (${types.length})`}
          </button>
        ))}
      </div>

      {view === "resources" && (
      <DataList
        data={resources}
        error={error}
        isEmpty={(r) => r.length === 0}
        empty={<EmptyState icon={<Boxes />} title="No resources yet"
          hint={types.length
            ? "Click “New resource” to create one, then attach groups/apps/users and delegate admins."
            : "Two steps: ① “New type” defines which member kinds a resource can hold, then ② “New resource”."} />}
      >
        {(rs) => (
          <div className="divide-y">
            {rs.map((r) => (
              <button key={r.id} onClick={() => setSelected({ id: r.id, name: r.name })}
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
      )}

      {view === "types" && (
        types.length === 0
          ? <EmptyState icon={<Layers />} title="No types yet"
              hint="A type defines which member kinds a resource can hold. Create one with “New type”." />
          : <div className="divide-y rounded-md border">
              {types.map((t) => (
                <div key={t.id} className="flex items-center justify-between px-4 py-3">
                  <div className="flex items-center gap-3">
                    <Layers className="size-4 text-muted-foreground" />
                    <div>
                      <div className="font-medium">{t.name}</div>
                      <div className="mt-1 flex flex-wrap gap-1">
                        {t.allowedMemberTypes.length === 0
                          ? <span className="text-xs text-muted-foreground">No member kinds</span>
                          : t.allowedMemberTypes.map((k) => (
                              <Badge key={k} variant="muted" className="text-xs">{MEMBER_KIND_LABELS[k as MemberType]}</Badge>
                            ))}
                      </div>
                    </div>
                  </div>
                  <Button variant="ghost" size="sm" className="text-muted-foreground hover:text-destructive"
                    onClick={() => { void confirmDelete({
                      title: `Delete type “${t.name}”?`,
                      description: "Only unused types can be deleted; a type still used by any resource is rejected.",
                      run: () => deleteResourceType(t.id),
                      onDeleted: reload,
                    }).catch((e) => setError(errorMessage(e))); }}>
                    <Trash2 className="size-4" />
                  </Button>
                </div>
              ))}
            </div>
      )}

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
          key={selected.id}
          resourceId={selected.id}
          types={types}
          allResources={resources ?? []}
          onClose={() => setSelected(null)}
          onChanged={reload}
          onNavigate={setSelected}
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
      setError(errorMessage(e));
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
      setError(errorMessage(e));
    }
  }

  return (
    <Dialog open onOpenChange={onClose}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>New resource type</DialogTitle>
          <DialogDescription>
            A type is a template for resources. It decides which kinds of members a resource of this type
            can hold — e.g. a “Team” allows Groups + Applications, a “Department” allows child Resources.
          </DialogDescription>
        </DialogHeader>
        {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}
        <Field label="Type name"><Input value={name} placeholder="e.g. Team, Department"
          onChange={(e) => setName(e.target.value)} autoFocus /></Field>
        <Field label="Allowed member kinds">
          <div className="space-y-1.5">
            {MEMBER_TYPES.map((k) => (
              <label key={k} className="flex items-start gap-2 rounded-md border p-2 text-sm has-[:checked]:border-primary has-[:checked]:bg-accent">
                <Checkbox className="mt-0.5" checked={kinds.includes(k)} onCheckedChange={() => toggle(k)} />
                <span><span className="font-medium">{MEMBER_KIND_LABELS[k]}</span>
                  <span className="block text-xs text-muted-foreground">{MEMBER_KIND_HINTS[k]}</span></span>
              </label>
            ))}
          </div>
        </Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>Cancel</Button>
          <Button onClick={() => void submit()} disabled={!name.trim() || !kinds.length}>Create type</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ResourceDetailDialog(
  { resourceId, types, allResources, onClose, onChanged, onNavigate, onDelete }: {
    resourceId: string; types: ResourceType[]; allResources: Resource[];
    onClose: () => void; onChanged: () => Promise<void>;
    onNavigate: (node: ResourceNode) => void; onDelete: () => void;
  },
) {
  const [detail, setDetail] = useState<ResourceDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [childId, setChildId] = useState("");
  const [memberType, setMemberType] = useState<MemberType>("GROUP");
  const [apps, setApps] = useState<AppOption[]>([]);
  const [memberAddKey, setMemberAddKey] = useState(0);
  const [adminAddKey, setAdminAddKey] = useState(0);

  const load = useCallback(async () => {
    try {
      const d = await getResourceDetail(resourceId);
      setDetail(d);
      setName(d.name);
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [resourceId]);

  useEffect(() => { void load(); }, [load]);

  // Refresh both the detail panel and the parent list after any mutation; report success so callers
  // clear their input only when the op actually succeeded (not on a rejected mutation).
  const run = async (op: () => Promise<unknown>): Promise<boolean> => {
    try {
      await op();
      await Promise.all([load(), onChanged()]);
      setError(null);
      return true;
    } catch (e) {
      setError(errorMessage(e));
      return false;
    }
  };

  const allowed = detail ? types.find((t) => t.name === detail.typeName)?.allowedMemberTypes ?? [] : [];
  const allowedLeafTypes: MemberType[] = LEAF_MEMBER_TYPES.filter((t) => allowed.includes(t));
  const canHaveChildren = allowed.includes("RESOURCE");
  const canHaveApps = allowed.includes("APPLICATION");
  const activeMemberType: MemberType = allowedLeafTypes.includes(memberType) ? memberType : (allowedLeafTypes[0] ?? "GROUP");
  const attachable = allResources.filter((r) => r.id !== resourceId
    && !(detail?.children ?? []).some((c) => c.id === r.id));

  // Applications aren't searchable server-side; load them once and filter the (small) list client-side.
  useEffect(() => { if (canHaveApps) listApplications().then(setApps).catch(() => undefined); }, [canHaveApps]);

  // A name typeahead for the active member kind — groups/users search server-side, apps filter locally.
  const memberFetcher = (type: MemberType) => (q: string): Promise<Suggestion[]> => {
    if (type === "GROUP") return searchGroups(q);
    if (type === "USER") return searchUsers(q);
    const needle = q.trim().toLowerCase();
    return Promise.resolve(apps
      .filter((a) => !needle || a.name.toLowerCase().includes(needle))
      .map((a) => ({ id: a.id, label: `${a.name} · ${a.type}` })));
  };

  return (
    <Dialog open onOpenChange={onClose}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><Boxes className="size-4" /> {detail?.name ?? "…"}</DialogTitle>
          <DialogDescription>{detail?.typeName}</DialogDescription>
        </DialogHeader>
        {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}

        {detail && (
        <>
        <Section title="Name">
          <div className="flex gap-2">
            <Input className="flex-1" value={name} onChange={(e) => setName(e.target.value)} />
            <Button variant="outline" disabled={!name.trim() || name.trim() === detail.name}
              onClick={() => void run(() => renameResource(resourceId, name.trim()))}>
              Rename
            </Button>
          </div>
        </Section>

        <Section title="Parents">
          <NavChipList nodes={detail.parents} onNavigate={onNavigate} emptyLabel="None (a root resource)." />
        </Section>

        {canHaveChildren && (
          <Section title="Child resources">
            <NavChipList nodes={detail.children} onNavigate={onNavigate}
              onRemove={(id) => void run(() => detachChild(resourceId, id))} emptyLabel="None." />
            <div className="flex gap-2">
              <Select className="flex-1" value={childId} onChange={(e) => setChildId(e.target.value)}>
                <option value="">Attach a child…</option>
                {attachable.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
              </Select>
              <Button variant="outline" disabled={!childId}
                onClick={() => void run(() => attachChild(resourceId, childId)).then((ok) => { if (ok) setChildId(""); })}>
                <Plus /> Attach
              </Button>
            </div>
          </Section>
        )}

        {allowedLeafTypes.length > 0 && (
        <Section title="Members">
          <ChipList items={detail.members.map((m) => ({ key: `${m.memberType}:${m.memberId}`,
            label: `${m.memberType} · ${m.label ?? m.memberId}` }))}
            onRemove={(key) => { const [t, id] = key.split(/:(.+)/); void run(() => detachMember(resourceId, t, id)); }} />
          <div className="flex gap-2">
            <Select className="w-36" value={activeMemberType}
              onChange={(e) => setMemberType(e.target.value as MemberType)}>
              {allowedLeafTypes.map((t) => <option key={t} value={t}>{t}</option>)}
            </Select>
            <div className="flex-1">
              <SearchSelect key={activeMemberType} resetKey={memberAddKey}
                placeholder={`Search ${activeMemberType.toLowerCase()}s by name…`}
                fetcher={memberFetcher(activeMemberType)}
                onSelect={(s) => { if (s) void run(() => attachMember(resourceId, activeMemberType, s.id))
                  .then((ok) => { if (ok) setMemberAddKey((k) => k + 1); }); }} />
            </div>
          </div>
        </Section>
        )}

        <Section title="Delegated administrators">
          <ChipList items={detail.grants.map((g) => ({ key: g.userId, label: `${g.username ?? g.userId} · ${g.tier}` }))}
            onRemove={(userId) => void run(() => revokeResourceAdmin(resourceId, userId))} />
          <SearchSelect resetKey={adminAddKey} placeholder="Search users to grant admin…"
            fetcher={searchUsers}
            onSelect={(s) => { if (s) void run(() => assignResourceAdmin(resourceId, s.id))
              .then((ok) => { if (ok) setAdminAddKey((k) => k + 1); }); }} />
        </Section>
        </>
        )}

        <DialogFooter className="justify-between">
          <Button variant="destructive" onClick={onDelete}><Trash2 /> Delete resource</Button>
          <Button variant="outline" onClick={onClose}>Close</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Chips for resource nodes: clicking the name navigates to that resource; optional remove button. */
function NavChipList(
  { nodes, onNavigate, onRemove, emptyLabel }: {
    nodes: ResourceNode[]; onNavigate: (node: ResourceNode) => void;
    onRemove?: (id: string) => void; emptyLabel: string;
  },
) {
  if (!nodes.length) return <p className="text-xs text-muted-foreground">{emptyLabel}</p>;
  return (
    <div className="flex flex-wrap gap-2">
      {nodes.map((n) => (
        <Badge key={n.id} variant="muted" className="gap-1 text-xs">
          <button onClick={() => onNavigate(n)} className="hover:underline">{n.name}</button>
          {onRemove && (
            <button onClick={() => onRemove(n.id)} className="ml-1 text-muted-foreground hover:text-destructive">
              <Trash2 className="size-3" />
            </button>
          )}
        </Badge>
      ))}
    </div>
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

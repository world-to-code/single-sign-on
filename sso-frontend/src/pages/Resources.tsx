import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
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

/** Member-kind display labels and hints, resolved against the `console` namespace at the call site. */
function useMemberKind() {
  const { t } = useTranslation("console");
  const labels: Record<MemberType, string> = {
    RESOURCE: t("resourcesMemberKindResource"), GROUP: t("resourcesMemberKindGroup"),
    APPLICATION: t("resourcesMemberKindApplication"), USER: t("resourcesMemberKindUser"),
  };
  const hints: Record<MemberType, string> = {
    RESOURCE: t("resourcesMemberHintResource"), GROUP: t("resourcesMemberHintGroup"),
    APPLICATION: t("resourcesMemberHintApplication"), USER: t("resourcesMemberHintUser"),
  };
  return { labels, hints };
}

export default function Resources() {
  const { t } = useTranslation(["console", "states"]);
  const { labels: kindLabels } = useMemberKind();
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
        title={t("resourcesTitle")}
        description={t("resourcesDescription")}
        actions={
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => setTyping(true)}><Layers /> {t("resourcesNewType")}</Button>
            <Button onClick={() => setCreating(true)} disabled={!types.length}
              title={types.length ? undefined : t("resourcesCreateTypeFirst")}><Plus /> {t("resourcesNewResource")}</Button>
          </div>
        }
      />

      <div className="flex gap-1 border-b">
        {(["resources", "types"] as const).map((tab) => (
          <button key={tab} onClick={() => setView(tab)}
            className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${view === tab ? "border-primary text-foreground" : "border-transparent text-muted-foreground hover:text-foreground"}`}>
            {tab === "resources"
              ? `${t("resourcesTabResources")}${resources ? ` (${resources.length})` : ""}`
              : `${t("resourcesTabTypes")} (${types.length})`}
          </button>
        ))}
      </div>

      {view === "resources" && (
      <DataList
        data={resources}
        error={error}
        isEmpty={(r) => r.length === 0}
        empty={<EmptyState icon={<Boxes />} title={t("states:resourcesEmptyTitle")}
          hint={types.length
            ? t("states:resourcesEmptyHintWithTypes")
            : t("states:resourcesEmptyHintNoTypes")} />}
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
                  <span>{t("resourcesChildren", { count: r.children.length })}</span>
                  <span>{t("resourcesMembers", { count: r.members.length })}</span>
                  <span>{t("resourcesAdmins", { count: r.grants.length })}</span>
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
          ? <EmptyState icon={<Layers />} title={t("states:resourceTypesEmptyTitle")}
              hint={t("states:resourceTypesEmptyHint")} />
          : <div className="divide-y rounded-md border">
              {types.map((rt) => (
                <div key={rt.id} className="flex items-center justify-between px-4 py-3">
                  <div className="flex items-center gap-3">
                    <Layers className="size-4 text-muted-foreground" />
                    <div>
                      <div className="font-medium">{rt.name}</div>
                      <div className="mt-1 flex flex-wrap gap-1">
                        {rt.allowedMemberTypes.length === 0
                          ? <span className="text-xs text-muted-foreground">{t("resourcesNoMemberKinds")}</span>
                          : rt.allowedMemberTypes.map((k) => (
                              <Badge key={k} variant="muted" className="text-xs">{kindLabels[k as MemberType]}</Badge>
                            ))}
                      </div>
                    </div>
                  </div>
                  <Button variant="ghost" size="sm" className="text-muted-foreground hover:text-destructive"
                    onClick={() => { void confirmDelete({
                      title: t("resourcesDeleteTypeTitle", { name: rt.name }),
                      description: t("resourcesDeleteTypeDescription"),
                      run: () => deleteResourceType(rt.id),
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
              title: t("resourcesDeleteResourceTitle", { name: selected.name }),
              description: t("resourcesDeleteResourceDescription"),
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
  const { t } = useTranslation("console");
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
          <DialogTitle>{t("resourcesCreateTitle")}</DialogTitle>
          <DialogDescription>{t("resourcesCreateDescription")}</DialogDescription>
        </DialogHeader>
        {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}
        <Field label={t("resourcesNameLabel")}><Input value={name} onChange={(e) => setName(e.target.value)} autoFocus /></Field>
        <Field label={t("resourcesTypeLabel")}>
          <Select value={typeName} onChange={(e) => setTypeName(e.target.value)}>
            {types.map((rt) => <option key={rt.id} value={rt.name}>{rt.name}</option>)}
          </Select>
        </Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{t("cancel")}</Button>
          <Button onClick={() => void submit()} disabled={!name.trim() || !typeName}>{t("create")}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function CreateTypeDialog({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const { t } = useTranslation("console");
  const { labels: kindLabels, hints: kindHints } = useMemberKind();
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
          <DialogTitle>{t("resourcesCreateTypeTitle")}</DialogTitle>
          <DialogDescription>{t("resourcesCreateTypeDescription")}</DialogDescription>
        </DialogHeader>
        {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}
        <Field label={t("resourcesTypeNameLabel")}><Input value={name} placeholder={t("resourcesTypeNamePlaceholder")}
          onChange={(e) => setName(e.target.value)} autoFocus /></Field>
        <Field label={t("resourcesAllowedMemberKinds")}>
          <div className="space-y-1.5">
            {MEMBER_TYPES.map((k) => (
              <label key={k} className="flex items-start gap-2 rounded-md border p-2 text-sm has-[:checked]:border-primary has-[:checked]:bg-accent">
                <Checkbox className="mt-0.5" checked={kinds.includes(k)} onCheckedChange={() => toggle(k)} />
                <span><span className="font-medium">{kindLabels[k]}</span>
                  <span className="block text-xs text-muted-foreground">{kindHints[k]}</span></span>
              </label>
            ))}
          </div>
        </Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{t("cancel")}</Button>
          <Button onClick={() => void submit()} disabled={!name.trim() || !kinds.length}>{t("resourcesCreateType")}</Button>
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
  const { t } = useTranslation("console");
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

  const allowed = detail ? types.find((rt) => rt.name === detail.typeName)?.allowedMemberTypes ?? [] : [];
  const allowedLeafTypes: MemberType[] = LEAF_MEMBER_TYPES.filter((mt) => allowed.includes(mt));
  const canHaveChildren = allowed.includes("RESOURCE");
  const canHaveApps = allowed.includes("APPLICATION");
  const activeMemberType: MemberType = allowedLeafTypes.includes(memberType) ? memberType : (allowedLeafTypes[0] ?? "GROUP");
  const attachable = allResources.filter((r) => r.id !== resourceId
    && !(detail?.children ?? []).some((c) => c.id === r.id));

  // Applications aren't searchable server-side; load them once and filter the (small) list client-side.
  useEffect(() => { if (canHaveApps) listApplications().then(setApps).catch(() => undefined); }, [canHaveApps]);

  // Per-kind search placeholder — a per-value key, never a runtime `.toLowerCase()` (meaningless in Korean).
  const searchPlaceholder: Record<MemberType, string> = {
    GROUP: t("resourcesSearchGroups"), USER: t("resourcesSearchUsers"),
    APPLICATION: t("resourcesSearchApps"), RESOURCE: t("resourcesSearchGroups"),
  };

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
        <Section title={t("resourcesSectionName")}>
          <div className="flex gap-2">
            <Input className="flex-1" value={name} onChange={(e) => setName(e.target.value)} />
            <Button variant="outline" disabled={!name.trim() || name.trim() === detail.name}
              onClick={() => void run(() => renameResource(resourceId, name.trim()))}>
              {t("rename")}
            </Button>
          </div>
        </Section>

        <Section title={t("resourcesSectionParents")}>
          <NavChipList nodes={detail.parents} onNavigate={onNavigate} emptyLabel={t("resourcesParentsEmpty")} />
        </Section>

        {canHaveChildren && (
          <Section title={t("resourcesSectionChildren")}>
            <NavChipList nodes={detail.children} onNavigate={onNavigate}
              onRemove={(id) => void run(() => detachChild(resourceId, id))} emptyLabel={t("none")} />
            <div className="flex gap-2">
              <Select className="flex-1" value={childId} onChange={(e) => setChildId(e.target.value)}>
                <option value="">{t("resourcesAttachChild")}</option>
                {attachable.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
              </Select>
              <Button variant="outline" disabled={!childId}
                onClick={() => void run(() => attachChild(resourceId, childId)).then((ok) => { if (ok) setChildId(""); })}>
                <Plus /> {t("resourcesAttach")}
              </Button>
            </div>
          </Section>
        )}

        {allowedLeafTypes.length > 0 && (
        <Section title={t("resourcesSectionMembers")}>
          <ChipList items={detail.members.map((m) => ({ key: `${m.memberType}:${m.memberId}`,
            label: `${m.memberType} · ${m.label ?? m.memberId}` }))}
            onRemove={(key) => { const [mt, id] = key.split(/:(.+)/); void run(() => detachMember(resourceId, mt, id)); }} />
          <div className="flex gap-2">
            <Select className="w-36" value={activeMemberType}
              onChange={(e) => setMemberType(e.target.value as MemberType)}>
              {allowedLeafTypes.map((mt) => <option key={mt} value={mt}>{mt}</option>)}
            </Select>
            <div className="flex-1">
              <SearchSelect key={activeMemberType} resetKey={memberAddKey}
                placeholder={searchPlaceholder[activeMemberType]}
                fetcher={memberFetcher(activeMemberType)}
                onSelect={(s) => { if (s) void run(() => attachMember(resourceId, activeMemberType, s.id))
                  .then((ok) => { if (ok) setMemberAddKey((k) => k + 1); }); }} />
            </div>
          </div>
        </Section>
        )}

        <Section title={t("resourcesSectionAdmins")}>
          <ChipList items={detail.grants.map((g) => ({ key: g.userId, label: `${g.username ?? g.userId} · ${g.tier}` }))}
            onRemove={(userId) => void run(() => revokeResourceAdmin(resourceId, userId))} />
          <SearchSelect resetKey={adminAddKey} placeholder={t("resourcesSearchAdmin")}
            fetcher={searchUsers}
            onSelect={(s) => { if (s) void run(() => assignResourceAdmin(resourceId, s.id))
              .then((ok) => { if (ok) setAdminAddKey((k) => k + 1); }); }} />
        </Section>
        </>
        )}

        <DialogFooter className="justify-between">
          <Button variant="destructive" onClick={onDelete}><Trash2 /> {t("resourcesDeleteResource")}</Button>
          <Button variant="outline" onClick={onClose}>{t("close")}</Button>
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
  const { t } = useTranslation("console");
  if (!items.length) return <p className="text-xs text-muted-foreground">{t("none")}</p>;
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

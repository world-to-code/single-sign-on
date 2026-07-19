import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Pencil, Plus, RefreshCw, Trash2, X } from "lucide-react";
import { useApiData } from "@/useApiData";
import { useEditorForm } from "@/hooks/useEditorForm";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { errorMessage } from "@/api";
import {
  deleteDirectoryConnector,
  directoryConnectorsPath,
  mapDirectoryAttribute,
  mappingsPath,
  runsPath,
  saveDirectoryConnector,
  syncDirectoryNow,
  unmapDirectoryAttribute,
  type DirectoryAttributeMapping,
  type DirectoryConnector,
  type DirectorySyncRun,
} from "@/directoryConnectors";
import { attributeDefinitionsPath, type AttributeDefinition } from "@/attributeDefinitions";
import { PageHeader } from "@/components/PageHeader";
import { DataList, EmptyState } from "@/components/states";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";

type Tab = "connections" | "mappings" | "runs";
const TABS = ["connections", "mappings", "runs"] as const satisfies readonly Tab[];

interface Editor {
  id: string | null;
  name: string;
  displayName: string;
  enabled: boolean;
  host: string;
  port: number;
  useSsl: boolean;
  bindDn: string;
  bindPassword: string;
  baseDn: string;
  userFilter: string;
  externalIdAttribute: string;
}

const blank: Editor = {
  id: null, name: "", displayName: "", enabled: true, host: "", port: 636, useSsl: true,
  bindDn: "", bindPassword: "", baseDn: "", userFilter: "(objectClass=person)",
  externalIdAttribute: "entryUUID",
};

/**
 * Directory sync: connections that PULL profile attributes from a tenant's own directory.
 *
 * <p>Only attributes — accounts come from SCIM or federation. That is why the run summary reports "skipped"
 * as prominently as "updated": entries with no local account are the normal case while a tenant is still
 * provisioning, not an error.
 */
export default function DirectorySync() {
  const { t } = useTranslation(["console", "states"]);
  const [tab, setTab] = useState<Tab>("connections");
  const [selected, setSelected] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const connectors = useApiData<DirectoryConnector[]>(directoryConnectorsPath);
  const confirmDelete = useDeleteConfirm();
  const active = selected ?? connectors.data?.[0]?.name ?? null;

  const editor = useEditorForm<Editor>({
    blank,
    toRequest: (e) => ({
      displayName: e.displayName.trim(),
      kind: "LDAP" as const,
      enabled: e.enabled,
      host: e.host.trim(),
      port: e.port,
      // 636 is implicit TLS; 389 is only permitted when StartTLS upgrades it before the bind. The form does
      // not offer cleartext at all, so the pair is derived rather than asked for twice.
      useSsl: e.port === 636,
      startTls: e.port === 389,
      bindDn: e.bindDn.trim(),
      bindPassword: e.bindPassword,
      baseDn: e.baseDn.trim(),
      userFilter: e.userFilter.trim(),
      externalIdAttribute: e.externalIdAttribute.trim(),
    }),
    create: (body) => saveDirectoryConnector((body as { displayName: string }).displayName, body as never),
    update: (id, body) => saveDirectoryConnector(id, body as never),
    onSaved: connectors.reload,
  });

  async function runNow(name: string) {
    setNotice(null);
    setError(null);
    try {
      const run = await syncDirectoryNow(name);
      setNotice(t("dirSyncRanSummary", {
        read: run.entriesRead, matched: run.matched, updated: run.updated, skipped: run.skipped,
      }));
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title={t("dirSyncTitle")}
        description={t("dirSyncDescription")}
        actions={<Button onClick={editor.openCreate}><Plus /> {t("dirSyncNew")}</Button>}
      />

      {notice && <Alert><AlertDescription>{notice}</AlertDescription></Alert>}
      {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}

      <div className="flex gap-1 border-b border-border">
        {TABS.map((name) => (
          <button
            key={name}
            type="button"
            onClick={() => setTab(name)}
            className={cn("-mb-px border-b-2 px-4 py-2 text-sm",
              name === tab
                ? "border-primary font-medium text-foreground"
                : "border-transparent text-muted-foreground hover:text-foreground")}
          >
            {t(`dirSyncTab_${name}`)}
          </button>
        ))}
      </div>

      {tab === "connections" && (
        <DataList
          data={connectors.data}
          error={connectors.error}
          cause={connectors.cause}
          onRetry={connectors.reload}
          isEmpty={(rows) => rows.length === 0}
          empty={<EmptyState title={t("states:dirSyncEmptyTitle")} hint={t("states:dirSyncEmptyHint")} />}
        >
          {(rows) => (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("dirSyncColName")}</TableHead>
                  <TableHead>{t("dirSyncColHost")}</TableHead>
                  <TableHead>{t("dirSyncColStatus")}</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((c) => (
                  <TableRow key={c.id}>
                    <TableCell className="font-medium">{c.displayName}</TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">
                      {c.host}:{c.port}
                    </TableCell>
                    <TableCell>
                      <div className="flex gap-1">
                        <Badge variant={c.enabled ? "success" : "muted"}>
                          {c.enabled ? t("dirSyncEnabled") : t("dirSyncDisabled")}
                        </Badge>
                        <Badge variant="muted">{c.useSsl ? "LDAPS" : "StartTLS"}</Badge>
                      </div>
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="icon" aria-label={t("dirSyncRunNow")}
                                onClick={() => void runNow(c.name)}><RefreshCw /></Button>
                        <Button variant="ghost" size="icon" onClick={() => {
                          setSelected(c.name);
                          editor.openEdit({
                            id: c.name, name: c.name, displayName: c.displayName, enabled: c.enabled,
                            host: c.host, port: c.port, useSsl: c.useSsl, bindDn: c.bindDn ?? "",
                            bindPassword: "", baseDn: c.baseDn, userFilter: c.userFilter,
                            externalIdAttribute: c.externalIdAttribute,
                          });
                        }}><Pencil /></Button>
                        <Button variant="ghost" size="icon"
                                className="text-muted-foreground hover:text-destructive"
                                onClick={() => void confirmDelete({
                                  title: t("dirSyncDeleteTitle"),
                                  description: t("dirSyncDeleteDescription", { name: c.displayName }),
                                  run: () => deleteDirectoryConnector(c.name),
                                  onDeleted: connectors.reload,
                                })}><Trash2 /></Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </DataList>
      )}

      {tab === "mappings" && (active
        ? <MappingsTab connector={active} />
        : <Card><CardContent className="pt-6 text-sm text-muted-foreground">{t("dirSyncPickFirst")}</CardContent></Card>)}

      {tab === "runs" && (active
        ? <RunsTab connector={active} />
        : <Card><CardContent className="pt-6 text-sm text-muted-foreground">{t("dirSyncPickFirst")}</CardContent></Card>)}

      <Dialog open={editor.open} onOpenChange={editor.setOpen}>
        <DialogContent className="max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{editor.editor.id ? t("dirSyncDialogEdit") : t("dirSyncDialogCreate")}</DialogTitle>
            <DialogDescription>{t("dirSyncDialogDescription")}</DialogDescription>
          </DialogHeader>
          {editor.error && (
            <Alert variant="destructive"><AlertDescription>{editor.error}</AlertDescription></Alert>
          )}
          <form id="dir-form" className="space-y-4" onSubmit={(e) => void editor.save(e)}>
            <div className="space-y-2">
              <Label htmlFor="dir-name">{t("dirSyncNameLabel")}</Label>
              <Input id="dir-name" value={editor.editor.displayName}
                     onChange={(e) => editor.set({ displayName: e.target.value })} />
            </div>
            <div className="grid grid-cols-3 gap-2">
              <div className="col-span-2 space-y-2">
                <Label htmlFor="dir-host">{t("dirSyncHostLabel")}</Label>
                <Input id="dir-host" className="font-mono" value={editor.editor.host}
                       onChange={(e) => editor.set({ host: e.target.value })} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="dir-port">{t("dirSyncPortLabel")}</Label>
                <select id="dir-port" className="h-9 w-full rounded-md border bg-background px-3 text-sm"
                        value={editor.editor.port}
                        onChange={(e) => editor.set({ port: Number(e.target.value) })}>
                  <option value={636}>636 · LDAPS</option>
                  <option value={389}>389 · StartTLS</option>
                </select>
              </div>
            </div>
            <p className="text-xs text-muted-foreground">{t("dirSyncTransportHint")}</p>
            <div className="space-y-2">
              <Label htmlFor="dir-bind-dn">{t("dirSyncBindDnLabel")}</Label>
              <Input id="dir-bind-dn" className="font-mono" value={editor.editor.bindDn}
                     onChange={(e) => editor.set({ bindDn: e.target.value })} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="dir-bind-pw">{t("dirSyncBindPasswordLabel")}</Label>
              <Input id="dir-bind-pw" type="password" value={editor.editor.bindPassword}
                     onChange={(e) => editor.set({ bindPassword: e.target.value })} />
              <p className="text-xs text-muted-foreground">{t("dirSyncBindPasswordHint")}</p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="dir-base-dn">{t("dirSyncBaseDnLabel")}</Label>
              <Input id="dir-base-dn" className="font-mono" value={editor.editor.baseDn}
                     onChange={(e) => editor.set({ baseDn: e.target.value })} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="dir-filter">{t("dirSyncFilterLabel")}</Label>
              <Input id="dir-filter" className="font-mono" value={editor.editor.userFilter}
                     onChange={(e) => editor.set({ userFilter: e.target.value })} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="dir-extid">{t("dirSyncExternalIdLabel")}</Label>
              <Input id="dir-extid" className="font-mono" value={editor.editor.externalIdAttribute}
                     onChange={(e) => editor.set({ externalIdAttribute: e.target.value })} />
              <p className="text-xs text-muted-foreground">{t("dirSyncExternalIdHint")}</p>
            </div>
            <div className="flex items-center justify-between rounded-lg border p-3">
              <div>
                <p className="text-sm font-medium">{t("dirSyncEnabledLabel")}</p>
                <p className="text-xs text-muted-foreground">{t("dirSyncEnabledHint")}</p>
              </div>
              <Switch checked={editor.editor.enabled} onCheckedChange={(v) => editor.set({ enabled: v })} />
            </div>
          </form>
          <DialogFooter>
            <Button variant="ghost" onClick={() => editor.setOpen(false)}>{t("cancel")}</Button>
            <Button type="submit" form="dir-form">{t("save")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

/** Which directory attribute fills which declared profile attribute. */
function MappingsTab({ connector }: { connector: string }) {
  const { t } = useTranslation(["console", "states"]);
  const mappings = useApiData<DirectoryAttributeMapping[]>(mappingsPath(connector));
  // Only DIRECTORY-owned attributes are mappable: the store refuses a sync writing anything else, so offering
  // them here would only produce a failure the admin has to decode from a run record.
  const definitions = useApiData<AttributeDefinition[]>(attributeDefinitionsPath("USER"));
  const targets = (definitions.data ?? []).filter((d) => d.source === "DIRECTORY");
  const [source, setSource] = useState("");
  const [target, setTarget] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function add() {
    if (!source.trim() || !target) return;
    try {
      await mapDirectoryAttribute(connector, source.trim(), target);
      setSource("");
      setTarget("");
      setError(null);
      mappings.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <Card>
      <CardContent className="space-y-4 pt-6">
        {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}
        {targets.length === 0 && (
          <p className="text-sm text-muted-foreground">{t("dirSyncNoDirectoryAttrs")}</p>
        )}
        <div className="space-y-2">
          {(mappings.data ?? []).map((m) => (
            <div key={m.id} className="flex items-center gap-2 rounded-lg border p-2 text-sm">
              <span className="font-mono text-xs">{m.sourceAttribute}</span>
              <span className="text-muted-foreground">→</span>
              <span className="font-mono text-xs">{m.targetKey}</span>
              <Button variant="ghost" size="icon" className="ml-auto text-muted-foreground hover:text-destructive"
                      aria-label={t("dirSyncUnmap", { source: m.sourceAttribute })}
                      onClick={() => void unmapDirectoryAttribute(connector, m.id).then(mappings.reload)}>
                <X className="size-4" />
              </Button>
            </div>
          ))}
        </div>
        <div className="flex flex-wrap items-end gap-2">
          <div className="space-y-1">
            <Label htmlFor="map-source">{t("dirSyncSourceLabel")}</Label>
            <Input id="map-source" className="max-w-44 font-mono" value={source}
                   placeholder="department" onChange={(e) => setSource(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="map-target">{t("dirSyncTargetLabel")}</Label>
            <select id="map-target" className="h-9 max-w-52 rounded-md border bg-background px-3 text-sm"
                    value={target} onChange={(e) => setTarget(e.target.value)}>
              <option value="">{t("dirSyncTargetLabel")}</option>
              {targets.map((d) => <option key={d.id} value={d.key}>{d.displayName}</option>)}
            </select>
          </div>
          <Button variant="outline" size="sm" onClick={() => void add()}
                  disabled={!source.trim() || !target}>
            <Plus /> {t("dirSyncMap")}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

/** What each run did — the only way an unattended sync's outcome is knowable. */
function RunsTab({ connector }: { connector: string }) {
  const { t } = useTranslation(["console", "states"]);
  const runs = useApiData<DirectorySyncRun[]>(runsPath(connector));

  return (
    <DataList
      data={runs.data}
      error={runs.error}
      cause={runs.cause}
      onRetry={runs.reload}
      isEmpty={(rows) => rows.length === 0}
      empty={<EmptyState title={t("states:dirSyncRunsEmptyTitle")} hint={t("states:dirSyncRunsEmptyHint")} />}
    >
      {(rows) => (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("dirSyncColStarted")}</TableHead>
              <TableHead>{t("dirSyncColStatus")}</TableHead>
              <TableHead>{t("dirSyncColResult")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((run) => (
              <TableRow key={run.id}>
                <TableCell className="text-xs text-muted-foreground">
                  {new Date(run.startedAt).toLocaleString()}
                </TableCell>
                <TableCell>
                  <Badge variant={run.status === "SUCCEEDED" ? "success"
                    : run.status === "FAILED" ? "destructive" : "muted"}>
                    {t(`dirSyncStatus_${run.status}`)}
                  </Badge>
                </TableCell>
                <TableCell className="text-xs text-muted-foreground">
                  {run.error ?? t("dirSyncRanSummary", {
                    read: run.entriesRead, matched: run.matched,
                    updated: run.updated, skipped: run.skipped,
                  })}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </DataList>
  );
}

import { useState } from "react";
import { Trans, useTranslation } from "react-i18next";
import { Pencil, Plus, Save, Trash2, Users } from "lucide-react";
import {
  createMappingRule, previewMappingRule, updateMappingRule,
  type MappingAttrOp, type MappingPreview, type MappingRule, type MappingRuleRequest, type MappingTargetKind,
} from "@/mapping";
import { searchGroups } from "@/groups";
import { listRoles } from "@/roles";
import { listResources } from "@/resources";
import { errorMessage } from "@/api";
import { PageHeader } from "@/components/PageHeader";
import { SearchSelect, type Suggestion } from "@/components/SearchSelect";
import { Select } from "@/components/ui/select";
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
import { useApiData } from "@/useApiData";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { useEditorForm } from "@/hooks/useEditorForm";

interface Editor {
  id: string | null;
  attrKey: string;
  attrOp: MappingAttrOp;
  attrValue: string;
  thenKind: MappingTargetKind;
  targetId: string;
  targetName: string;
}
const blank: Editor = { id: null, attrKey: "", attrOp: "EQUALS", attrValue: "", thenKind: "GROUP", targetId: "", targetName: "" };

const MAPPING_OPERATORS: MappingAttrOp[] = ["EQUALS", "EXISTS"];

/** Build the request body — the EXISTS operator drops the value the backend rejects. */
function toRuleRequest(e: Editor): MappingRuleRequest {
  const base = { attrKey: e.attrKey.trim(), attrOp: e.attrOp, thenKind: e.thenKind, targetId: e.targetId };
  return e.attrOp === "EXISTS" ? base : { ...base, attrValue: e.attrValue.trim() };
}

const roleFetcher = (q: string): Promise<Suggestion[]> =>
  listRoles().then((rs) => rs
    .filter((r) => r.name.toLowerCase().includes(q.toLowerCase()))
    .map((r) => ({ id: r.id, label: r.name })));

const resourceFetcher = (q: string): Promise<Suggestion[]> =>
  listResources().then((rs) => rs
    .filter((r) => r.name.toLowerCase().includes(q.toLowerCase()))
    .map((r) => ({ id: r.id, label: r.name })));

/** Per-kind target config. `satisfies` forces every MappingTargetKind to be wired (adding a kind is a compile
 *  error) while keeping the key strings as literals so the typed `t(...)` accepts them. */
const targetConfig = {
  GROUP: { fetcher: searchGroups, placeholderKey: "mappingRulesGroupPlaceholder", optionKey: "mappingRulesKind_GROUP" },
  ROLE: { fetcher: roleFetcher, placeholderKey: "mappingRulesRolePlaceholder", optionKey: "mappingRulesKind_ROLE" },
  RESOURCE_MEMBER: {
    fetcher: resourceFetcher,
    placeholderKey: "mappingRulesResourcePlaceholder",
    optionKey: "mappingRulesKind_RESOURCE_MEMBER",
  },
} as const satisfies Record<MappingTargetKind, { fetcher: (q: string) => Promise<Suggestion[]>; placeholderKey: string; optionKey: string }>;

/** Auto-mapping rules: users carrying a metadata attribute (key = value) are kept in a target group. */
export default function MappingRules() {
  const { t } = useTranslation(["console", "states"]);
  const { data, error, cause, reload } = useApiData<MappingRule[]>("/api/admin/mapping-rules");
  const confirmDelete = useDeleteConfirm();
  const [actionError, setActionError] = useState<string | null>(null);
  const [preview, setPreview] = useState<MappingPreview | null>(null);
  const [previewing, setPreviewing] = useState(false);

  const { editor, set, open, setOpen, error: formError, openCreate, openEdit, save } = useEditorForm<Editor>({
    blank,
    toRequest: toRuleRequest,
    create: (body) => createMappingRule(body as MappingRuleRequest),
    update: (id, body) => updateMappingRule(id, body as MappingRuleRequest),
    onSaved: reload,
  });

  const editRule = (r: MappingRule) => {
    setPreview(null);
    // A rule stored before operators existed loads as EQUALS.
    openEdit({ id: r.id, attrKey: r.attrKey, attrOp: r.attrOp ?? "EQUALS", attrValue: r.attrValue ?? "",
               thenKind: r.thenKind, targetId: r.targetId, targetName: r.targetName ?? "" });
  };
  const startCreate = () => { setPreview(null); openCreate(); };
  const pickKind = (k: MappingTargetKind) => { setPreview(null); set({ thenKind: k, targetId: "", targetName: "" }); };

  const needsValue = editor.attrOp === "EQUALS";
  const canSave = editor.attrKey.trim() && (!needsValue || editor.attrValue.trim()) && editor.targetId;

  async function runPreview() {
    if (!canSave) return;
    setPreviewing(true);
    setPreview(null);
    try {
      setPreview(await previewMappingRule(toRuleRequest(editor)));
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setPreviewing(false);
    }
  }

  const remove = (r: MappingRule) => {
    setActionError(null);
    return confirmDelete({
      title: t("mappingRulesDeleteTitle"),
      description: r.attrOp === "EXISTS"
        ? t("mappingRulesDeleteDescriptionExists", { key: r.attrKey, target: r.targetName ?? "" })
        : t("mappingRulesDeleteDescription", { key: r.attrKey, value: r.attrValue ?? "", target: r.targetName ?? "" }),
      path: `/api/admin/mapping-rules/${r.id}`,
      onDeleted: reload,
      onError: setActionError,
    });
  };

  return (
    <>
      <PageHeader
        title={t("mappingRulesTitle")}
        description={t("mappingRulesDescription")}
        actions={<Button onClick={startCreate}><Plus /> {t("mappingRulesNew")}</Button>}
      />

      <Alert variant="info" className="mb-4">
        <AlertDescription>
          <Trans t={t} i18nKey="mappingRulesInfo" components={[<strong key="0" />]} />
        </AlertDescription>
      </Alert>

      {actionError && <Alert variant="destructive" className="mb-4"><AlertDescription>{actionError}</AlertDescription></Alert>}

      <DataList
        data={data}
        error={error}
        cause={cause}
        isEmpty={(rows) => rows.length === 0}
        empty={<EmptyState title={t("states:mappingRulesEmptyTitle")} hint={t("states:mappingRulesEmptyHint")} />}
      >
        {(rows) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("mappingRulesColPredicate")}</TableHead>
                <TableHead>{t("mappingRulesColTarget")}</TableHead>
                <TableHead>{t("mappingRulesColAssigned")}</TableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((r) => (
                <TableRow key={r.id}>
                  <TableCell>
                    <span className="font-mono">{r.attrKey}</span>
                    {r.attrOp === "EXISTS" ? (
                      <span className="text-muted-foreground"> {t("mappingRulesExists")}</span>
                    ) : (
                      <>
                        <span className="text-muted-foreground"> = </span>
                        <span className="font-mono">{r.attrValue}</span>
                      </>
                    )}
                  </TableCell>
                  <TableCell>
                    <Badge variant="muted" className="mr-2">{t(`mappingRulesKind_${r.thenKind}`)}</Badge>
                    <span className="font-medium">{r.targetName ?? "—"}</span>
                  </TableCell>
                  <TableCell><Badge variant="muted">{r.assignedCount}</Badge></TableCell>
                  <TableCell className="text-right">
                    <div className="flex justify-end gap-1">
                      <Button variant="ghost" size="icon" onClick={() => editRule(r)}><Pencil /></Button>
                      <Button variant="ghost" size="icon" className="text-muted-foreground hover:text-destructive"
                              onClick={() => remove(r)}><Trash2 /></Button>
                    </div>
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
            <DialogTitle>{editor.id ? t("mappingRulesDialogEdit") : t("mappingRulesDialogCreate")}</DialogTitle>
            <DialogDescription>{t("mappingRulesDialogDescription")}</DialogDescription>
          </DialogHeader>

          {formError && <Alert variant="destructive"><AlertDescription>{formError}</AlertDescription></Alert>}

          <form onSubmit={save} className="space-y-4">
            <div className="grid grid-cols-2 gap-2">
              <div className="space-y-2">
                <Label htmlFor="mr-key">{t("mappingRulesKeyLabel")}</Label>
                <Input id="mr-key" className="font-mono" value={editor.attrKey}
                       onChange={(e) => set({ attrKey: e.target.value })} placeholder="department" required />
              </div>
              <div className="space-y-2">
                <Label htmlFor="mr-op">{t("mappingRulesOpLabel")}</Label>
                <Select id="mr-op" value={editor.attrOp} onChange={(e) => set({ attrOp: e.target.value as MappingAttrOp })}>
                  {MAPPING_OPERATORS.map((o) => (
                    <option key={o} value={o}>{t(`mappingRulesOp_${o}`)}</option>
                  ))}
                </Select>
              </div>
            </div>
            {needsValue && (
              <div className="space-y-2">
                <Label htmlFor="mr-value">{t("mappingRulesValueLabel")}</Label>
                <Input id="mr-value" className="font-mono" value={editor.attrValue}
                       onChange={(e) => set({ attrValue: e.target.value })} placeholder="engineering" required />
              </div>
            )}

            <div className="grid grid-cols-2 gap-2">
              <div className="space-y-2">
                <Label htmlFor="mr-kind">{t("mappingRulesKindLabel")}</Label>
                <Select id="mr-kind" value={editor.thenKind} onChange={(e) => pickKind(e.target.value as MappingTargetKind)}>
                  {(Object.keys(targetConfig) as MappingTargetKind[]).map((k) => (
                    <option key={k} value={k}>{t(targetConfig[k].optionKey)}</option>
                  ))}
                </Select>
              </div>
              <div className="space-y-2">
                <Label>{t("mappingRulesTargetLabel")}</Label>
                <SearchSelect
                  key={editor.thenKind}
                  placeholder={t(targetConfig[editor.thenKind].placeholderKey)}
                  fetcher={targetConfig[editor.thenKind].fetcher}
                  onSelect={(s) => set({ targetId: s?.id ?? "", targetName: s?.label ?? "" })}
                  resetKey={open}
                />
              </div>
            </div>
            {editor.targetId && (
              <p className="text-sm">{t("mappingRulesGroupSelected")} <Badge variant="muted">{editor.targetName}</Badge></p>
            )}

            <div className="space-y-2">
              <Button type="button" variant="outline" size="sm" disabled={!canSave || previewing} onClick={runPreview}>
                <Users /> {t("mappingRulesPreview")}
              </Button>
              {preview && (
                <div className="rounded-md border p-2 text-sm">
                  <p>{t("mappingRulesPreviewCount", { count: preview.matchedCount })}</p>
                  {preview.sample.length > 0 && (
                    <div className="mt-1 flex flex-wrap gap-1">
                      {preview.sample.map((u) => <Badge key={u.id} variant="muted">{u.username}</Badge>)}
                    </div>
                  )}
                </div>
              )}
            </div>

            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setOpen(false)}>{t("cancel")}</Button>
              <Button type="submit" disabled={!canSave}><Save /> {editor.id ? t("saveChanges") : t("mappingRulesCreate")}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

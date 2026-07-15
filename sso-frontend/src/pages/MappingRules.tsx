import { useState } from "react";
import { Trans, useTranslation } from "react-i18next";
import { Pencil, Plus, Save, Trash2, Users } from "lucide-react";
import {
  createMappingRule, previewMappingRule, updateMappingRule, type MappingPreview, type MappingRule,
} from "@/mapping";
import { searchGroups } from "@/groups";
import { errorMessage } from "@/api";
import { PageHeader } from "@/components/PageHeader";
import { SearchSelect } from "@/components/SearchSelect";
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
  attrValue: string;
  groupId: string;
  groupName: string;
}
const blank: Editor = { id: null, attrKey: "", attrValue: "", groupId: "", groupName: "" };

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
    toRequest: (e) => ({ attrKey: e.attrKey.trim(), attrValue: e.attrValue.trim(), groupId: e.groupId }),
    create: (body) => createMappingRule(body as never),
    update: (id, body) => updateMappingRule(id, body as never),
    onSaved: reload,
  });

  const editRule = (r: MappingRule) => {
    setPreview(null);
    openEdit({ id: r.id, attrKey: r.attrKey, attrValue: r.attrValue, groupId: r.groupId, groupName: r.groupName ?? "" });
  };
  const startCreate = () => { setPreview(null); openCreate(); };

  const canSave = editor.attrKey.trim() && editor.attrValue.trim() && editor.groupId;

  async function runPreview() {
    if (!editor.attrKey.trim() || !editor.attrValue.trim() || !editor.groupId) return;
    setPreviewing(true);
    setPreview(null);
    try {
      setPreview(await previewMappingRule({
        attrKey: editor.attrKey.trim(), attrValue: editor.attrValue.trim(), groupId: editor.groupId,
      }));
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
      description: t("mappingRulesDeleteDescription", { key: r.attrKey, value: r.attrValue, group: r.groupName ?? "" }),
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
                <TableHead>{t("mappingRulesColGroup")}</TableHead>
                <TableHead>{t("mappingRulesColAssigned")}</TableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((r) => (
                <TableRow key={r.id}>
                  <TableCell>
                    <span className="font-mono">{r.attrKey}</span>
                    <span className="text-muted-foreground"> = </span>
                    <span className="font-mono">{r.attrValue}</span>
                  </TableCell>
                  <TableCell className="font-medium">{r.groupName ?? "—"}</TableCell>
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
                <Label htmlFor="mr-value">{t("mappingRulesValueLabel")}</Label>
                <Input id="mr-value" className="font-mono" value={editor.attrValue}
                       onChange={(e) => set({ attrValue: e.target.value })} placeholder="engineering" required />
              </div>
            </div>

            <div className="space-y-2">
              <Label>{t("mappingRulesGroupLabel")}</Label>
              {editor.groupId && (
                <p className="text-sm">{t("mappingRulesGroupSelected")} <Badge variant="muted">{editor.groupName}</Badge></p>
              )}
              <SearchSelect
                placeholder={t("mappingRulesGroupPlaceholder")}
                fetcher={searchGroups}
                onSelect={(s) => set({ groupId: s?.id ?? "", groupName: s?.label ?? "" })}
                resetKey={open}
              />
            </div>

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

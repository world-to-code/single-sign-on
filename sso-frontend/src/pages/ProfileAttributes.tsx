import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Pencil, Plus, Trash2 } from "lucide-react";
import { useApiData } from "@/useApiData";
import { useTenantProfile } from "@/hooks/useTenantProfile";
import { useEditorForm } from "@/hooks/useEditorForm";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import {
  attributeDefinitionsPath,
  deleteAttributeDefinition,
  saveAttributeDefinition,
  type AttributeDataType,
  type AttributeDefinition,
  type AttributeEntityKind,
  type AttributeSource,
} from "@/attributeDefinitions";
import { PageHeader } from "@/components/PageHeader";
import { DataList, EmptyState } from "@/components/states";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";

const KINDS = ["USER", "GROUP"] as const satisfies readonly AttributeEntityKind[];
const TYPES = ["STRING", "INTEGER", "BOOLEAN", "DATE", "ENUM"] as const satisfies readonly AttributeDataType[];
const SOURCES = ["LOCAL", "DIRECTORY"] as const satisfies readonly AttributeSource[];

interface Editor {
  id: string | null;
  entityKind: AttributeEntityKind;
  key: string;
  displayName: string;
  description: string;
  dataType: AttributeDataType;
  enumValues: string;
  multiValued: boolean;
  required: boolean;
  source: AttributeSource;
  sortOrder: number;
}

const blank = (entityKind: AttributeEntityKind): Editor => ({
  id: null, entityKind, key: "", displayName: "", description: "",
  dataType: "STRING", enumValues: "", multiValued: false, required: false, source: "LOCAL", sortOrder: 0,
});

const splitValues = (raw: string) => raw.split(",").map((v) => v.trim()).filter(Boolean);

/**
 * The organization's profile schema — which attributes exist on a user or group, what they hold, and who owns
 * the value. Declaring an attribute here is what gives the metadata editor a key to offer instead of a blank
 * text box, and what a directory connector maps onto.
 */
export default function ProfileAttributes() {
  const { t } = useTranslation(["console", "states"]);
  const [kind, setKind] = useState<AttributeEntityKind>("USER");
  const profile = useTenantProfile();
  const definitions = useApiData<AttributeDefinition[]>(attributeDefinitionsPath(kind, profile?.id));
  const confirmDelete = useDeleteConfirm();

  const editor = useEditorForm<Editor>({
    blank: blank(kind),
    toRequest: (e) => ({
      entityKind: e.entityKind,
      key: e.key.trim(),
      displayName: e.displayName.trim(),
      description: e.description.trim(),
      dataType: e.dataType,
      enumValues: e.dataType === "ENUM" ? splitValues(e.enumValues) : [],
      multiValued: e.multiValued,
      required: e.required,
      source: e.source,
      sortOrder: e.sortOrder,
    }),
    // The key is the identity, so the backend upserts on (kind, key) — both paths POST the same body.
    create: (body) => saveAttributeDefinition(body as never, profile?.id),
    update: (_id, body) => saveAttributeDefinition(body as never, profile?.id),
    onSaved: definitions.reload,
  });

  function edit(d: AttributeDefinition) {
    editor.openEdit({
      id: d.id, entityKind: d.entityKind, key: d.key, displayName: d.displayName,
      description: d.description ?? "", dataType: d.dataType, enumValues: d.enumValues.join(", "),
      multiValued: d.multiValued, required: d.required, source: d.source, sortOrder: d.sortOrder,
    });
  }

  function remove(d: AttributeDefinition) {
    void confirmDelete({
      title: t("profileAttrDeleteTitle"),
      description: t("profileAttrDeleteDescription", { name: d.displayName }),
      run: () => deleteAttributeDefinition(d.id),
      onDeleted: definitions.reload,
    });
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title={t("profileAttrTitle")}
        description={t("profileAttrDescription")}
        actions={
          <Button onClick={editor.openCreate}>
            <Plus /> {t("profileAttrNew")}
          </Button>
        }
      />

      <div className="flex gap-1 border-b border-border">
        {KINDS.map((k) => (
          <button
            key={k}
            type="button"
            onClick={() => setKind(k)}
            className={cn("-mb-px border-b-2 px-4 py-2 text-sm",
              k === kind
                ? "border-primary font-medium text-foreground"
                : "border-transparent text-muted-foreground hover:text-foreground")}
          >
            {t(`profileAttrKind_${k}`)}
          </button>
        ))}
      </div>

      <DataList
        data={definitions.data}
        error={definitions.error}
        cause={definitions.cause}
        onRetry={definitions.reload}
        isEmpty={(rows) => rows.length === 0}
        empty={<EmptyState title={t("states:profileAttrEmptyTitle")} hint={t("states:profileAttrEmptyHint")} />}
      >
        {(rows) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("profileAttrColName")}</TableHead>
                <TableHead>{t("profileAttrColKey")}</TableHead>
                <TableHead>{t("profileAttrColType")}</TableHead>
                <TableHead>{t("profileAttrColOwner")}</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((d) => (
                <TableRow key={d.id ?? d.key}>
                  <TableCell className="font-medium">{d.displayName}</TableCell>
                  <TableCell><Badge variant="muted" className="font-mono">{d.key}</Badge></TableCell>
                  <TableCell className="text-muted-foreground">
                    {t(`profileAttrType_${d.dataType}`)}{d.multiValued ? ` · ${t("profileAttrMulti")}` : ""}
                  </TableCell>
                  <TableCell>
                    <Badge variant={d.base ? "secondary" : d.source === "DIRECTORY" ? "default" : "muted"}>
                      {d.base ? t("profileAttrBuiltIn") : t(`profileAttrSource_${d.source}`)}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-right">
                    {/* A built-in is an app_user column shown for context: there is no declaration to edit. */}
                    {!d.base && (
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="icon" onClick={() => edit(d)}><Pencil /></Button>
                        <Button variant="ghost" size="icon"
                                className="text-muted-foreground hover:text-destructive"
                                onClick={() => remove(d)}><Trash2 /></Button>
                      </div>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>

      <Dialog open={editor.open} onOpenChange={editor.setOpen}>
        <DialogContent className="max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>
              {editor.editor.id ? t("profileAttrDialogEdit") : t("profileAttrDialogCreate")}
            </DialogTitle>
            <DialogDescription>{t("profileAttrDialogDescription")}</DialogDescription>
          </DialogHeader>

          {editor.error && (
            <Alert variant="destructive"><AlertDescription>{editor.error}</AlertDescription></Alert>
          )}

          <form id="attr-form" className="space-y-4" onSubmit={(e) => void editor.save(e)}>
            <div className="space-y-2">
              <Label htmlFor="attr-key">{t("profileAttrKeyLabel")}</Label>
              <Input id="attr-key" className="font-mono" value={editor.editor.key}
                     disabled={editor.editor.id !== null}
                     onChange={(e) => editor.set({ key: e.target.value })} />
              <p className="text-xs text-muted-foreground">{t("profileAttrKeyHint")}</p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="attr-name">{t("profileAttrNameLabel")}</Label>
              <Input id="attr-name" value={editor.editor.displayName}
                     onChange={(e) => editor.set({ displayName: e.target.value })} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="attr-desc">{t("profileAttrDescriptionLabel")}</Label>
              <Input id="attr-desc" value={editor.editor.description}
                     onChange={(e) => editor.set({ description: e.target.value })} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="attr-type">{t("profileAttrTypeLabel")}</Label>
              <select id="attr-type"
                      className="h-9 w-full rounded-md border bg-background px-3 text-sm"
                      value={editor.editor.dataType}
                      onChange={(e) => editor.set({ dataType: e.target.value as AttributeDataType })}>
                {TYPES.map((type) => (
                  <option key={type} value={type}>{t(`profileAttrType_${type}`)}</option>
                ))}
              </select>
            </div>
            {editor.editor.dataType === "ENUM" && (
              <div className="space-y-2">
                <Label htmlFor="attr-enum">{t("profileAttrEnumLabel")}</Label>
                <Input id="attr-enum" value={editor.editor.enumValues}
                       placeholder={t("profileAttrEnumPlaceholder")}
                       onChange={(e) => editor.set({ enumValues: e.target.value })} />
                <p className="text-xs text-muted-foreground">{t("profileAttrEnumHint")}</p>
              </div>
            )}
            <div className="space-y-2">
              <Label htmlFor="attr-source">{t("profileAttrOwnerLabel")}</Label>
              <select id="attr-source"
                      className="h-9 w-full rounded-md border bg-background px-3 text-sm"
                      value={editor.editor.source}
                      onChange={(e) => editor.set({ source: e.target.value as AttributeSource })}>
                {SOURCES.map((source) => (
                  <option key={source} value={source}>{t(`profileAttrSource_${source}`)}</option>
                ))}
              </select>
              <p className="text-xs text-muted-foreground">{t("profileAttrOwnerHint")}</p>
            </div>
            <div className="flex items-center justify-between rounded-lg border p-3">
              <div>
                <p className="text-sm font-medium">{t("profileAttrMultiLabel")}</p>
                <p className="text-xs text-muted-foreground">{t("profileAttrMultiHint")}</p>
              </div>
              <Switch checked={editor.editor.multiValued}
                      onCheckedChange={(v) => editor.set({ multiValued: v })} />
            </div>
            <div className="flex items-center justify-between rounded-lg border p-3">
              <div>
                <p className="text-sm font-medium">{t("profileAttrRequiredLabel")}</p>
                <p className="text-xs text-muted-foreground">{t("profileAttrRequiredHint")}</p>
              </div>
              <Switch checked={editor.editor.required}
                      onCheckedChange={(v) => editor.set({ required: v })} />
            </div>
          </form>

          <DialogFooter>
            <Button variant="ghost" onClick={() => editor.setOpen(false)}>{t("cancel")}</Button>
            <Button type="submit" form="attr-form">{t("save")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

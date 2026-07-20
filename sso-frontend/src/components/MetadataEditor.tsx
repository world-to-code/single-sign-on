import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Plus, Tags, X } from "lucide-react";
import {
  addAttribute,
  getAttributes,
  removeAttribute,
  removeAttributeValue,
  type Attribute,
  type MetadataKind,
} from "@/metadata";
import { errorMessage } from "@/api";
import { listAttributeDefinitions, type AttributeDefinition } from "@/attributeDefinitions";
import { useTenantProfile } from "@/hooks/useTenantProfile";
import { Badge } from "@/components/ui/badge";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

/**
 * The entity's profile attributes. Schema-aware where a schema exists: a declared attribute is chosen by name,
 * gets an input that matches its type, and — if a directory owns it — is shown read-only, because a sync would
 * otherwise overwrite whatever was typed here. Keys that predate the schema stay free-form and editable, so
 * nothing that already worked stops working.
 *
 * <p>The backend enforces the same ownership rule; this only stops an administrator being offered an edit that
 * would be refused, or worse, silently reverted hours later.
 */
export function MetadataEditor({ kind, entityId }: { kind: MetadataKind; entityId: string }) {
  const { t } = useTranslation("console");
  const [attrs, setAttrs] = useState<Attribute[] | null>(null);
  const [definitions, setDefinitions] = useState<AttributeDefinition[]>([]);
  const [key, setKey] = useState("");
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const profile = useTenantProfile();

  const reload = useCallback(() => {
    getAttributes(kind, entityId).then(setAttrs).catch((e) => setError(errorMessage(e)));
  }, [kind, entityId]);
  useEffect(reload, [reload]);

  // A missing or forbidden schema is not an error here — the editor simply falls back to free-form keys.
  useEffect(() => {
    const entityKind = kind === "groups" ? "GROUP" : kind === "users" ? "USER" : "RESOURCE";
    listAttributeDefinitions(entityKind, profile?.id).then(setDefinitions).catch(() => setDefinitions([]));
  }, [kind, profile?.id]);

  const definitionOf = (attrKey: string) => definitions.find((d) => d.key === attrKey);
  /** Only attributes an administrator owns can be added here; a directory fills the rest. */
  const editable = definitions.filter((d) => d.source === "LOCAL");

  async function add(e: React.FormEvent) {
    e.preventDefault();
    if (!key.trim() || !value.trim()) return;
    setBusy(true);
    try {
      setAttrs(await addAttribute(kind, entityId, key.trim(), value.trim()));
      setKey("");
      setValue("");
      setError(null);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function removeValue(attrKey: string, attrValue: string) {
    try {
      await removeAttributeValue(kind, entityId, attrKey, attrValue);
      setError(null);
      reload();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function removeKey(attrKey: string) {
    try {
      await removeAttribute(kind, entityId, attrKey);
      setError(null);
      reload();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  // A key may repeat once per value; group values under their key so each renders as a set of pills.
  const groups = attrs ? groupByKey(attrs) : [];

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
        <Tags className="size-4" /> {t("metadataTitle")}
      </div>
      <p className="text-xs text-muted-foreground">{t("metadataHint")}</p>
      {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}

      {attrs && attrs.length === 0 && <p className="text-sm text-muted-foreground">{t("metadataNone")}</p>}
      {groups.length > 0 && (
        <div className="flex flex-col gap-1.5">
          {groups.map(({ key: k, values }) => (
            <div key={k} className="flex flex-wrap items-center gap-1.5">
              {definitionOf(k)?.source === "DIRECTORY" ? (
                <span className="font-mono text-xs text-muted-foreground">{definitionOf(k)?.displayName ?? k}</span>
              ) : (
                <button
                  type="button"
                  aria-label={t("metadataRemove", { key: k })}
                  className="font-mono text-xs text-muted-foreground hover:text-destructive"
                  onClick={() => void removeKey(k)}
                >
                  {definitionOf(k)?.displayName ?? k}
                </button>
              )}
              <span className="text-xs text-muted-foreground">=</span>
              {values.map((v) => (
                <span
                  key={`${k}:${v}`}
                  className="inline-flex items-center gap-1 rounded-full bg-muted py-0.5 pl-2.5 pr-1 text-xs"
                >
                  {v}
                  {definitionOf(k)?.source !== "DIRECTORY" && (
                    <button
                      type="button"
                      aria-label={t("metadataRemoveValue", { key: k, value: v })}
                      className="rounded-full p-0.5 text-muted-foreground hover:text-destructive"
                      onClick={() => void removeValue(k, v)}
                    >
                      <X className="size-3" />
                    </button>
                  )}
                </span>
              ))}
              {definitionOf(k)?.source === "DIRECTORY" && (
                <Badge variant="muted">{t("metadataDirectoryOwned")}</Badge>
              )}
            </div>
          ))}
        </div>
      )}

      <form onSubmit={add} className="flex flex-wrap items-center gap-2">
        {editable.length > 0 ? (
          <select
            className="h-9 max-w-52 rounded-md border bg-background px-3 text-sm"
            value={key}
            onChange={(e) => setKey(e.target.value)}
            aria-label={t("metadataKey")}
          >
            <option value="">{t("metadataKey")}</option>
            {editable.map((d) => (
              <option key={d.id} value={d.key}>{d.displayName}</option>
            ))}
          </select>
        ) : (
          <Input className="max-w-44" value={key} onChange={(e) => setKey(e.target.value)}
                 placeholder={t("metadataKey")} />
        )}
        {definitionOf(key)?.dataType === "ENUM" ? (
          <select
            className="h-9 max-w-52 rounded-md border bg-background px-3 text-sm"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            aria-label={t("metadataValue")}
          >
            <option value="">{t("metadataValue")}</option>
            {definitionOf(key)?.enumValues.map((v) => <option key={v} value={v}>{v}</option>)}
          </select>
        ) : (
          <Input className="max-w-52" value={value} onChange={(e) => setValue(e.target.value)}
                 type={definitionOf(key)?.dataType === "DATE" ? "date"
                   : definitionOf(key)?.dataType === "INTEGER" ? "number" : "text"}
                 placeholder={t("metadataValue")} />
        )}
        <Button type="submit" size="sm" variant="outline" disabled={busy || !key.trim() || !value.trim()}>
          <Plus /> {t("metadataAdd")}
        </Button>
      </form>
    </div>
  );
}

/** Collapse the flat attribute rows into one entry per key, preserving first-seen key order. */
function groupByKey(attrs: Attribute[]): { key: string; values: string[] }[] {
  const byKey = new Map<string, string[]>();
  for (const a of attrs) {
    const values = byKey.get(a.key);
    if (values) values.push(a.value);
    else byKey.set(a.key, [a.value]);
  }
  return [...byKey].map(([key, values]) => ({ key, values }));
}

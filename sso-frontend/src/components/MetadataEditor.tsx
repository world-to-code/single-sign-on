import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Plus, Tags, X } from "lucide-react";
import { getAttributes, removeAttribute, setAttribute, type Attribute, type MetadataKind } from "@/metadata";
import { errorMessage } from "@/api";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

/**
 * Reusable key/value metadata (tag) editor for an entity. Loads the entity's attributes and lets an admin add
 * or remove tags; the backend org-scopes and authorizes per entity kind. Embedded on user/group/resource
 * detail views.
 */
export function MetadataEditor({ kind, entityId }: { kind: MetadataKind; entityId: string }) {
  const { t } = useTranslation("console");
  const [attrs, setAttrs] = useState<Attribute[] | null>(null);
  const [key, setKey] = useState("");
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(() => {
    getAttributes(kind, entityId).then(setAttrs).catch((e) => setError(errorMessage(e)));
  }, [kind, entityId]);
  useEffect(reload, [reload]);

  async function add(e: React.FormEvent) {
    e.preventDefault();
    if (!key.trim() || !value.trim()) return;
    setBusy(true);
    try {
      setAttrs(await setAttribute(kind, entityId, key.trim(), value.trim()));
      setKey("");
      setValue("");
      setError(null);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function remove(attrKey: string) {
    try {
      await removeAttribute(kind, entityId, attrKey);
      setError(null);
      reload();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
        <Tags className="size-4" /> {t("metadataTitle")}
      </div>
      <p className="text-xs text-muted-foreground">{t("metadataHint")}</p>
      {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}

      {attrs && attrs.length === 0 && <p className="text-sm text-muted-foreground">{t("metadataNone")}</p>}
      {attrs && attrs.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {attrs.map((a) => (
            <span key={a.key} className="inline-flex items-center gap-1 rounded-full bg-muted py-0.5 pl-2.5 pr-1 text-xs">
              <span className="font-mono">{a.key}</span>
              <span className="text-muted-foreground">=</span>
              {a.value}
              <button
                type="button"
                aria-label={t("metadataRemove", { key: a.key })}
                className="rounded-full p-0.5 text-muted-foreground hover:text-destructive"
                onClick={() => void remove(a.key)}
              >
                <X className="size-3" />
              </button>
            </span>
          ))}
        </div>
      )}

      <form onSubmit={add} className="flex flex-wrap items-center gap-2">
        <Input className="max-w-44" value={key} onChange={(e) => setKey(e.target.value)} placeholder={t("metadataKey")} />
        <Input className="max-w-52" value={value} onChange={(e) => setValue(e.target.value)} placeholder={t("metadataValue")} />
        <Button type="submit" size="sm" variant="outline" disabled={busy || !key.trim() || !value.trim()}>
          <Plus /> {t("metadataAdd")}
        </Button>
      </form>
    </div>
  );
}

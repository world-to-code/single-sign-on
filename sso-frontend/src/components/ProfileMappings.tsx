import { useTranslation } from "react-i18next";
import { X } from "lucide-react";
import { useApiData } from "@/useApiData";
import {
  mapProfileAttribute,
  profileMappingsPath,
  unmapProfileAttribute,
  type Profile,
  type ProfileMapping,
} from "@/attributeDefinitions";
import { useAttributeTargets } from "@/hooks/useAttributeTargets";
import { errorMessage } from "@/api";
import { DataList, EmptyState } from "@/components/states";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { useState } from "react";

/**
 * What a source profile feeds into the tenant's own.
 *
 * <p>A mapping is the whole point of having two profiles: one describes what an identity source PROVIDES, the
 * other what this organization KEEPS, and this says which fills which. Without it a source profile is a
 * description nobody acts on — which is what SCIM's was until now.
 */
export function ProfileMappings({ source, tenant }: { source: Profile; tenant: Profile }) {
  const { t } = useTranslation(["console", "states"]);
  const mappings = useApiData<ProfileMapping[]>(profileMappingsPath(source.id));
  const targets = useAttributeTargets(tenant.id);
  const [sourceKey, setSourceKey] = useState("");
  const [targetKey, setTargetKey] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function add() {
    if (!sourceKey.trim() || !targetKey) return;
    setError(null);
    try {
      await mapProfileAttribute(source.id, sourceKey.trim(), tenant.id, targetKey);
      setSourceKey(""); setTargetKey("");
      mappings.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function remove(id: string) {
    setError(null);
    try {
      await unmapProfileAttribute(source.id, id);
      mappings.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div className="mt-8 space-y-3">
      <div>
        <h3 className="text-sm font-semibold">{t("profileMappingsTitle")}</h3>
        <p className="text-xs text-muted-foreground">
          {t("profileMappingsDesc", { source: source.name, tenant: tenant.name })}
        </p>
      </div>

      {error && <p className="text-sm text-destructive">{error}</p>}

      <DataList
        data={mappings.data}
        error={mappings.error}
        cause={mappings.cause}
        onRetry={mappings.reload}
        isEmpty={(rows) => rows.length === 0}
        empty={<EmptyState title={t("profileMappingsEmpty")} />}
      >
        {(rows) => (
          <div className="space-y-1">
            {rows.map((m) => (
              <div key={m.id} className="flex items-center gap-2 rounded-md border px-3 py-2 text-sm">
                <code className="font-mono">{m.sourceKey}</code>
                <span className="text-muted-foreground">→</span>
                <code className="font-mono">{m.targetKey}</code>
                <Button variant="ghost" size="icon" className="ml-auto" onClick={() => remove(m.id)}>
                  <X />
                </Button>
              </div>
            ))}
          </div>
        )}
      </DataList>

      <div className="flex items-end gap-2">
        <div className="flex-1 space-y-1.5">
          <Label htmlFor="mapping-source">{t("profileMappingsSource")}</Label>
          <Input id="mapping-source" value={sourceKey} onChange={(e) => setSourceKey(e.target.value)}
                 placeholder={t("profileMappingsSourcePlaceholder")} />
        </div>
        <div className="flex-1 space-y-1.5">
          <Label htmlFor="mapping-target">{t("profileMappingsTarget")}</Label>
          <Select id="mapping-target" value={targetKey} onChange={(e) => setTargetKey(e.target.value)}>
            <option value="">{t("profileMappingsPickTarget")}</option>
            {targets.map((d) => <option key={d.key} value={d.key}>{d.displayName}</option>)}
          </Select>
        </div>
        <Button onClick={add} disabled={!sourceKey.trim() || !targetKey}>{t("add")}</Button>
      </div>
    </div>
  );
}

import { useEffect, useState } from "react";
import { X } from "lucide-react";
import { searchUsers, usersByIds } from "@/groups";
import { SearchSelect, type Suggestion } from "@/components/SearchSelect";
import { Badge } from "@/components/ui/badge";

/**
 * Multi-select of users by typeahead search, showing the selection as removable chips. Scales past the
 * old all-users checkbox list: candidates come from the server search, and already-selected ids are
 * resolved to usernames via the batch by-ids lookup (so editing an existing selection shows names).
 */
export function UserMultiSelect({ selected, onChange, placeholder }: {
  selected: string[];
  onChange: (ids: string[]) => void;
  placeholder?: string;
}) {
  const [labels, setLabels] = useState<Record<string, string>>({});
  const [addKey, setAddKey] = useState(0);

  useEffect(() => {
    const missing = selected.filter((id) => !(id in labels));
    if (missing.length === 0) return;
    usersByIds(missing)
      .then((sugs) => setLabels((prev) => ({ ...prev, ...Object.fromEntries(sugs.map((s) => [s.id, s.label])) })))
      .catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected]);

  function add(s: Suggestion | null) {
    if (!s || selected.includes(s.id)) return;
    setLabels((prev) => ({ ...prev, [s.id]: s.label }));
    onChange([...selected, s.id]);
    setAddKey((k) => k + 1);
  }

  return (
    <div className="space-y-2">
      {selected.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {selected.map((id) => (
            <Badge key={id} variant="secondary" className="gap-1">
              {labels[id] ?? id}
              <button type="button" onClick={() => onChange(selected.filter((x) => x !== id))}
                      className="text-muted-foreground hover:text-destructive" aria-label="Remove">
                <X className="size-3" />
              </button>
            </Badge>
          ))}
        </div>
      )}
      <SearchSelect resetKey={addKey} placeholder={placeholder ?? "Search users by name…"} fetcher={searchUsers} onSelect={add} />
    </div>
  );
}

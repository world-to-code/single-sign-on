import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Input } from "@/components/ui/input";

export interface Suggestion {
  id: string;
  label: string;
}

/**
 * Async typeahead picker: debounced search against a backend endpoint, dropdown of suggestions.
 * Used where the candidate set can be large (dozens/hundreds of groups or users).
 */
export function SearchSelect({ placeholder, fetcher, onSelect, resetKey }: {
  placeholder?: string;
  fetcher: (q: string) => Promise<Suggestion[]>;
  onSelect: (s: Suggestion | null) => void;
  resetKey?: unknown;
}) {
  const { t } = useTranslation();
  const [q, setQ] = useState("");
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<Suggestion[]>([]);
  const [loading, setLoading] = useState(false);
  const boxRef = useRef<HTMLDivElement>(null);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  // Clear the field when the caller resets (e.g. after a successful add).
  useEffect(() => { setQ(""); onSelect(null); /* eslint-disable-next-line */ }, [resetKey]);

  useEffect(() => {
    if (!open) return;
    let cancel = false;
    setLoading(true);
    const t = setTimeout(() => {
      fetcherRef.current(q)
        .then((r) => { if (!cancel) setItems(r); })
        .catch(() => { if (!cancel) setItems([]); })
        .finally(() => { if (!cancel) setLoading(false); });
    }, 200);
    return () => { cancel = true; clearTimeout(t); };
  }, [q, open]);

  useEffect(() => {
    function onDoc(e: MouseEvent) {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, []);

  return (
    <div className="relative flex-1" ref={boxRef}>
      <Input value={q} placeholder={placeholder} onFocus={() => setOpen(true)}
             onChange={(e) => { setQ(e.target.value); onSelect(null); setOpen(true); }} />
      {open && (
        <div className="absolute z-50 mt-1 max-h-56 w-full overflow-y-auto rounded-md border bg-popover shadow-md">
          {loading ? (
            <div className="px-3 py-2 text-sm text-muted-foreground">{t("searching")}</div>
          ) : items.length === 0 ? (
            <div className="px-3 py-2 text-sm text-muted-foreground">{t("noMatches")}</div>
          ) : items.map((s) => (
            <button type="button" key={s.id}
                    className="block w-full px-3 py-2 text-left text-sm hover:bg-accent"
                    onClick={() => { onSelect(s); setQ(s.label); setOpen(false); }}>
              {s.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

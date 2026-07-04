import type { FormEvent, ReactNode } from "react";
import { Link } from "react-router-dom";
import { ChevronRight } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { LoadingCard } from "@/components/states";
import { cn } from "@/lib/utils";

export interface EditorTab<K extends string> {
  key: K;
  label: string;
}

/**
 * Shared shell for the Okta-style, full-width create/edit PAGES (session policy, users, auth policies,
 * relying parties, clients). Gives every editor the same breadcrumb, header, optional tab bar, a single
 * `<form>` for its body, and a sticky bottom Save/Cancel bar — so margins and layout stay uniform with
 * the list pages they open from. The body is supplied per entity (usually SettingsSection groups).
 */
export function EditorPage<K extends string>({
  backTo, backLabel, crumb, title, description,
  tabs, activeTab, onTab,
  error, formId, onSubmit, busy, submitLabel, onCancel, loading, children,
}: {
  backTo: string;
  backLabel: string;
  crumb: string;
  title: string;
  description?: string;
  tabs?: EditorTab<K>[];
  activeTab?: K;
  onTab?: (key: K) => void;
  error?: string | null;
  formId: string;
  onSubmit: (event: FormEvent) => void;
  busy?: boolean;
  submitLabel: string;
  onCancel: () => void;
  loading?: boolean;
  children: ReactNode;
}) {
  return (
    <div>
      <nav className="mb-5 flex items-center gap-1.5 text-sm text-muted-foreground">
        <Link to={backTo} className="hover:text-foreground">{backLabel}</Link>
        <ChevronRight className="size-3.5 opacity-60" />
        <span className="font-medium text-foreground">{crumb}</span>
      </nav>

      <header className="mb-8">
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        {description && <p className="mt-1.5 text-sm text-muted-foreground">{description}</p>}
      </header>

      {tabs && (
        <div className="mb-2 flex gap-7 border-b border-border">
          {tabs.map((t) => (
            <button key={t.key} type="button" onClick={() => onTab?.(t.key)}
                    className={cn("-mb-px border-b-2 pb-3 text-sm font-medium transition-colors",
                      activeTab === t.key
                        ? "border-primary text-foreground"
                        : "border-transparent text-muted-foreground hover:text-foreground")}>
              {t.label}
            </button>
          ))}
        </div>
      )}

      {error && <Alert variant="destructive" className="mt-4"><AlertDescription>{error}</AlertDescription></Alert>}

      {loading ? (
        <div className="mt-6">{error ? null : <LoadingCard />}</div>
      ) : (
        <>
          <form id={formId} onSubmit={onSubmit}>{children}</form>

          <div className="sticky bottom-0 z-10 mt-2 flex items-center justify-end gap-3 border-t border-border bg-background/90 py-4 backdrop-blur">
            <Button type="button" variant="outline" onClick={onCancel}>Cancel</Button>
            <Button form={formId} type="submit" disabled={busy}>{submitLabel}</Button>
          </div>
        </>
      )}
    </div>
  );
}

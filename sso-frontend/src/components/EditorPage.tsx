import type { FormEvent, ReactNode } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ChevronRight } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { LoadingCard } from "@/components/states";
import { cn } from "@/lib/utils";

export interface EditorTab<K extends string> {
  key: K;
  label: string;
}

/** One changed field for the diff save bar — the bar names the diff, not the intent (DESIGN.md §4). */
export interface DiffEntry {
  label: string;
  from: ReactNode;
  to: ReactNode;
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
  error, formId, onSubmit, busy, submitLabel, onCancel, loading, diff, children,
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
  /**
   * Opt-in diff save bar. When provided, the bar names the changed fields and disables submit while
   * empty ("Nothing to save"). Omit it (the default) to keep the always-enabled bar used by create pages.
   */
  diff?: DiffEntry[];
  children: ReactNode;
}) {
  const { t } = useTranslation("console");
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

          <div className="sticky bottom-0 z-10 mt-2 flex items-center justify-between gap-4 border-t border-border bg-background/90 py-4 backdrop-blur">
            <div className="min-w-0 flex-1 overflow-x-auto text-sm">
              {diff !== undefined && (diff.length === 0
                ? <span className="text-muted-foreground">{t("editorNothingToSave")}</span>
                : (
                  <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
                    {diff.map((d, i) => (
                      <span key={i} className="inline-flex items-center gap-1.5 whitespace-nowrap">
                        <span className="text-muted-foreground">{d.label}</span>
                        <span className="text-faint line-through">{d.from}</span>
                        <span className="text-faint">→</span>
                        <span className="font-medium text-ink">{d.to}</span>
                      </span>
                    ))}
                  </div>
                ))}
            </div>
            <div className="flex shrink-0 items-center gap-3">
              <Button type="button" variant="outline" onClick={onCancel}>{t("editorCancel")}</Button>
              <Button form={formId} type="submit" disabled={busy || (diff !== undefined && diff.length === 0)}>{submitLabel}</Button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

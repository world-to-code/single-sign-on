import type { ReactNode } from "react";
import { AlertTriangle, RefreshCw, SearchX, ServerCrash, ShieldX, WifiOff } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { ApiError } from "@/api";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

/** Card-wrapped loading skeleton for list/table pages. */
export function LoadingCard({ rows = 5 }: { rows?: number }) {
  return (
    <Card>
      <CardContent className="space-y-3 p-6">
        {Array.from({ length: rows }).map((_, i) => (
          <Skeleton key={i} className="h-10 w-full" />
        ))}
      </CardContent>
    </Card>
  );
}

export function ErrorCard({ message }: { message: string }) {
  return (
    <Card className="border-destructive/40">
      <CardContent className="flex items-center gap-3 p-6 text-destructive">
        <AlertTriangle className="size-5" /> <span className="text-sm">{message}</span>
      </CardContent>
    </Card>
  );
}

export function EmptyState({ icon, title, hint }: { icon?: React.ReactNode; title: string; hint?: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-12 text-center">
      {icon && <div className="text-muted-foreground/60">{icon}</div>}
      <p className="text-sm font-medium">{title}</p>
      {hint && <p className="max-w-sm text-sm text-muted-foreground">{hint}</p>}
    </div>
  );
}

/** The four load failures of DESIGN.md §5, keyed by cause rather than by a raw status. */
export type FailureKind = "network" | "forbidden" | "notFound" | "server";

/**
 * Derive the failure kind from a caught error. 403 → forbidden, 404 → notFound, ≥500 → server;
 * a fetch reject or any other status → network. Never leaks existence — 403 and 404 stay distinct
 * only in the copy each maps to, and neither offers a retry (see FailurePanel).
 */
export function failureKind(error: unknown): FailureKind {
  if (error instanceof ApiError) {
    if (error.status === 403) return "forbidden";
    if (error.status === 404) return "notFound";
    if (error.status >= 500) return "server";
  }
  return "network";
}

interface FailureCopy {
  icon: LucideIcon;
  title: string;
  hint: string;
  retryable: boolean;
}

// A 403/404 is NOT retryable — offering a retry teaches people to hammer a wall (DESIGN.md §5). A 403
// discloses nothing about existence. Only server/network failures carry a retry and a trace ID.
const FAILURES: Record<FailureKind, FailureCopy> = {
  network: {
    icon: WifiOff,
    title: "Couldn't reach the server",
    hint: "Check your connection, then try again.",
    retryable: true,
  },
  forbidden: {
    icon: ShieldX,
    title: "You don't have permission to view this",
    hint: "Ask an administrator if you need access to this area.",
    retryable: false,
  },
  notFound: {
    icon: SearchX,
    title: "Not found",
    hint: "It may have been removed, or the link is out of date.",
    retryable: false,
  },
  server: {
    icon: ServerCrash,
    title: "The request couldn't be completed",
    hint: "This is on our side. Try again in a moment.",
    retryable: false, // overridden below to true; kept explicit for readability
  },
};
FAILURES.server.retryable = true;

/**
 * Replaces a panel's body on a load failure. Retry + a trace ID appear only for network/server
 * failures — the two the operator can act on. forbidden/notFound get neither.
 */
export function FailurePanel({ kind, traceId, onRetry }: { kind: FailureKind; traceId?: string; onRetry?: () => void }) {
  const copy = FAILURES[kind];
  const Icon = copy.icon;
  return (
    <Card>
      <CardContent className="flex flex-col items-center gap-3 px-6 py-12 text-center">
        <Icon className="size-8 text-muted-foreground/70" />
        <div className="space-y-1">
          <p className="text-sm font-semibold text-ink">{copy.title}</p>
          <p className="max-w-sm text-sm text-muted-foreground">{copy.hint}</p>
        </div>
        {copy.retryable && onRetry && (
          <Button variant="secondary" size="sm" onClick={onRetry} data-failure-retry>
            <RefreshCw className="size-4" /> Try again
          </Button>
        )}
        {copy.retryable && traceId && (
          <p className="font-mono text-xs text-faint" data-failure-trace>
            Trace ID {traceId}
          </p>
        )}
      </CardContent>
    </Card>
  );
}

/**
 * Standard list-page body: shows a failure (before any data arrives), then a LoadingCard while the
 * request is in flight, then a Card wrapping either `empty` or `children(data)`. When the caller passes
 * the raw `cause` and it is an ApiError, the designed FailurePanel replaces the generic ErrorCard.
 */
export function DataList<T>({ data, error, cause, onRetry, isEmpty, empty, loadingRows, errorAlways, children }: {
  data: T | null;
  error: string | null;
  /** The raw caught error. When it's an ApiError, DataList renders a kind-specific FailurePanel. */
  cause?: unknown;
  /** Retry handler wired to network/server FailurePanels (typically the loader's `reload`). */
  onRetry?: () => void;
  isEmpty: (data: T) => boolean;
  empty: ReactNode;
  loadingRows?: number;
  /** Show the failure whenever `error` is set, even if stale data is present (default: only when no data). */
  errorAlways?: boolean;
  children: (data: T) => ReactNode;
}) {
  if (error && (errorAlways || !data)) {
    if (cause instanceof ApiError) {
      return <FailurePanel kind={failureKind(cause)} traceId={cause.traceId} onRetry={onRetry} />;
    }
    return <ErrorCard message={error} />;
  }
  if (!data) return <LoadingCard rows={loadingRows} />;
  return (
    <Card>
      <CardContent className="p-0">{isEmpty(data) ? empty : children(data)}</CardContent>
    </Card>
  );
}

import type { ReactNode } from "react";
import { AlertTriangle } from "lucide-react";
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

/**
 * Standard list-page body: shows an ErrorCard (load failure, before any data arrives), then a
 * LoadingCard while the request is in flight, then a Card wrapping either `empty` or `children(data)`.
 */
export function DataList<T>({ data, error, isEmpty, empty, loadingRows, errorAlways, children }: {
  data: T | null;
  error: string | null;
  isEmpty: (data: T) => boolean;
  empty: ReactNode;
  loadingRows?: number;
  /** Show the ErrorCard whenever `error` is set, even if stale data is present (default: only when no data). */
  errorAlways?: boolean;
  children: (data: T) => ReactNode;
}) {
  if (error && (errorAlways || !data)) return <ErrorCard message={error} />;
  if (!data) return <LoadingCard rows={loadingRows} />;
  return (
    <Card>
      <CardContent className="p-0">{isEmpty(data) ? empty : children(data)}</CardContent>
    </Card>
  );
}

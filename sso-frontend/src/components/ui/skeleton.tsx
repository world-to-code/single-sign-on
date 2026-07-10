import { cn } from "@/lib/utils";

/** Placeholder block with a 1.4s shimmer sweep (disabled under prefers-reduced-motion via index.css). */
function Skeleton({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div className={cn("relative overflow-hidden rounded-md bg-muted", className)} {...props}>
      <div className="absolute inset-0 -translate-x-full animate-shimmer bg-gradient-to-r from-transparent via-foreground/[0.08] to-transparent" />
    </div>
  );
}
export { Skeleton };

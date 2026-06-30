import { ShieldCheck } from "lucide-react";
import { cn } from "@/lib/utils";

/** Product wordmark: a shield glyph + "Mini SSO". */
export function Brand({ className, subtitle = "Identity Provider" }: { className?: string; subtitle?: string }) {
  return (
    <div className={cn("flex items-center gap-2.5", className)}>
      <div className="flex size-9 items-center justify-center rounded-lg bg-primary text-primary-foreground shadow-sm">
        <ShieldCheck className="size-5" />
      </div>
      <div className="leading-tight">
        <div className="text-sm font-semibold tracking-tight">Mini SSO</div>
        <div className="text-[11px] text-muted-foreground">{subtitle}</div>
      </div>
    </div>
  );
}

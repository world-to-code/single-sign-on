import type { ReactNode } from "react";
import { Info } from "lucide-react";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";

/** A small "i" icon that reveals a concise note on hover/focus — for inline help that would clutter the page. */
export function InfoHint({ children, label = "More information" }: { children: ReactNode; label?: string }) {
  return (
    <TooltipProvider delayDuration={150}>
      <Tooltip>
        <TooltipTrigger asChild>
          <button type="button" aria-label={label}
                  className="inline-flex items-center text-muted-foreground transition-colors hover:text-foreground">
            <Info className="size-4" />
          </button>
        </TooltipTrigger>
        <TooltipContent>{children}</TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}

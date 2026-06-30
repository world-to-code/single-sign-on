import { Loader2 } from "lucide-react";
import { Brand } from "@/components/Brand";

/**
 * Full-screen branded splash shown while the session is being probed — matches the AuthLayout
 * backdrop so the first paint is on-brand rather than a bare "Loading…" line.
 */
export default function LoadingScreen() {
  return (
    <div className="relative flex min-h-screen flex-col items-center justify-center gap-7 overflow-hidden bg-background">
      {/* same decorative backdrop as the auth screens */}
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(60rem_40rem_at_50%_-10%,hsl(var(--primary)/0.12),transparent)]" />
      <div className="pointer-events-none absolute inset-0 [background-image:linear-gradient(to_right,hsl(var(--border)/0.4)_1px,transparent_1px),linear-gradient(to_bottom,hsl(var(--border)/0.4)_1px,transparent_1px)] [background-size:36px_36px] [mask-image:radial-gradient(40rem_30rem_at_50%_0%,black,transparent)]" />
      <div className="relative flex flex-col items-center gap-4">
        <Brand />
        <Loader2 className="size-5 animate-spin text-primary" aria-label="Loading" />
      </div>
    </div>
  );
}

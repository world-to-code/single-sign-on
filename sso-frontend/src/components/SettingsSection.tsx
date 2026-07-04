import type { ReactNode } from "react";

/**
 * One settings row in the Okta/Stripe/GitHub "settings page" pattern: a left column that NAMES and
 * explains the group, and a right column with the actual controls, divided from the next section. Used to
 * give long admin edit forms a scannable two-column structure instead of a flat stack of fields.
 */
export function SettingsSection({ title, description, children }: {
  title: string;
  description?: string;
  children: ReactNode;
}) {
  return (
    <section className="grid gap-x-10 gap-y-4 border-b border-border py-8 first:pt-2 last:border-b-0 md:grid-cols-[16rem_1fr]">
      <div>
        <h3 className="text-sm font-semibold text-foreground">{title}</h3>
        {description && <p className="mt-1.5 text-sm leading-relaxed text-muted-foreground">{description}</p>}
      </div>
      <div className="min-w-0 space-y-5">{children}</div>
    </section>
  );
}

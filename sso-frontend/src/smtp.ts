import { apiGet, apiPut, apiDelete } from "./api";

/** How a tenant's mail leaves. SMTP needs a relay; an HTTP provider needs a key and is reachable on 443. */
export type EmailProvider = "SMTP" | "RESEND";

/**
 * The acting tenant's mail configuration, as returned by the admin API. Neither secret travels back —
 * {@link configured} tells whether the tier has its own (else it inherits the platform default, and the other
 * fields are null/defaults).
 */
export interface SmtpSettings {
  configured: boolean;
  provider: EmailProvider;
  host: string | null;
  port: number | null;
  username: string | null;
  fromAddress: string | null;
  starttls: boolean;
}

/**
 * What the settings form submits. Both secrets are write-only: leave one blank to KEEP the stored value
 * unchanged (the backend retains it); a blank username means an unauthenticated relay (no password).
 */
export interface SmtpSettingsInput {
  provider: EmailProvider;
  host: string;
  port: number;
  username: string | null;
  password: string | null;
  apiKey: string | null;
  fromAddress: string | null;
  starttls: boolean;
}

export const getSmtpSettings = (): Promise<SmtpSettings> => apiGet<SmtpSettings>("/api/admin/smtp-settings");

export const updateSmtpSettings = (body: SmtpSettingsInput): Promise<SmtpSettings> =>
  apiPut<SmtpSettings>("/api/admin/smtp-settings", body);

/** Removes the tier's own relay — its mail reverts to the platform default. */
export const deleteSmtpSettings = (): Promise<void> => apiDelete("/api/admin/smtp-settings");

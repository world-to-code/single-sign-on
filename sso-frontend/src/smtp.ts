import { apiGet, apiPut, apiDelete } from "./api";

/**
 * The acting tenant's SMTP relay configuration, as returned by the admin API. The password is WRITE-ONLY and
 * never travels back — {@link configured} tells whether the tier has its own relay (else it inherits the
 * platform default, and the other fields are null/defaults).
 */
export interface SmtpSettings {
  configured: boolean;
  host: string | null;
  port: number | null;
  username: string | null;
  fromAddress: string | null;
  starttls: boolean;
}

/**
 * What the settings form submits. {@link password} is write-only: leave it blank to KEEP the stored one
 * unchanged (the backend retains it); a blank username means an unauthenticated relay (no password).
 */
export interface SmtpSettingsInput {
  host: string;
  port: number;
  username: string | null;
  password: string | null;
  fromAddress: string | null;
  starttls: boolean;
}

export const getSmtpSettings = (): Promise<SmtpSettings> => apiGet<SmtpSettings>("/api/admin/smtp-settings");

export const updateSmtpSettings = (body: SmtpSettingsInput): Promise<SmtpSettings> =>
  apiPut<SmtpSettings>("/api/admin/smtp-settings", body);

/** Removes the tier's own relay — its mail reverts to the platform default. */
export const deleteSmtpSettings = (): Promise<void> => apiDelete("/api/admin/smtp-settings");

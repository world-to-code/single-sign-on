import { apiGet, apiPut, apiDelete } from "./api";

/** The gateways this build can send through. A value here must have a client on the backend. */
export type SmsProvider = "SOLAPI" | "TWILIO";

/**
 * The acting tenant's SMS gateway configuration, as returned by the admin API. The API secret is WRITE-ONLY
 * and never travels back — {@link configured} tells whether the tier has its own gateway (else it inherits the
 * platform account, and the other fields are null).
 */
export interface SmsSettings {
  configured: boolean;
  provider: SmsProvider | null;
  apiKey: string | null;
  senderNumber: string | null;
}

/**
 * What the settings form submits. {@link apiSecret} is write-only: leave it blank to KEEP the stored one
 * unchanged (the backend retains it), which is what editing only the sender number does.
 */
export interface SmsSettingsInput {
  provider: SmsProvider;
  apiKey: string;
  apiSecret: string | null;
  senderNumber: string;
}

export const getSmsSettings = (): Promise<SmsSettings> => apiGet<SmsSettings>("/api/admin/sms-settings");

export const updateSmsSettings = (body: SmsSettingsInput): Promise<SmsSettings> =>
  apiPut<SmsSettings>("/api/admin/sms-settings", body);

/** Removes the tier's own gateway — its messages revert to the platform account. */
export const deleteSmsSettings = (): Promise<void> => apiDelete("/api/admin/sms-settings");

import { apiGet, apiPut, apiDelete, apiPost } from "./api";

/** The email events a tenant can brand, one template slot each. */
export type EmailEvent = "EMAIL_VERIFICATION_CODE" | "ONBOARDING_INVITATION" | "SIGNUP_VERIFICATION";

/**
 * One event's template as the admin API returns it. {@link configured} is false when the tier inherits the
 * platform/built-in default — the other fields then carry that DEFAULT content, so the editor opens with a
 * sensible starting point. {@link variables} are exactly the `{{names}}` a template for this event may use.
 */
export interface EmailTemplate {
  event: EmailEvent;
  configured: boolean;
  subject: string;
  htmlBody: string;
  textBody: string | null;
  logoUrl: string | null;
  variables: string[];
}

/** What the editor submits. `logoUrl` must be an https URL (or blank); the body is rendered logic-less. */
export interface EmailTemplateInput {
  subject: string;
  htmlBody: string;
  textBody: string | null;
  logoUrl: string | null;
}

/** A server-rendered preview of unsaved content with sample data — safe to show only inside a sandboxed frame. */
export interface EmailTemplatePreview {
  subject: string;
  html: string;
  text: string;
}

const BASE = "/api/admin/email-templates";

export const listEmailTemplates = (): Promise<EmailTemplate[]> => apiGet<EmailTemplate[]>(BASE);

/** PUT/DELETE return the full refreshed set (the backend answers with the whole list). */
export const updateEmailTemplate = (event: EmailEvent, body: EmailTemplateInput): Promise<EmailTemplate[]> =>
  apiPut<EmailTemplate[]>(`${BASE}/${event}`, body);

export const deleteEmailTemplate = (event: EmailEvent): Promise<EmailTemplate[]> =>
  apiDelete(`${BASE}/${event}`).then(listEmailTemplates);

export const previewEmailTemplate = (event: EmailEvent, body: EmailTemplateInput): Promise<EmailTemplatePreview> =>
  apiPost<EmailTemplatePreview>(`${BASE}/${event}/preview`, body);

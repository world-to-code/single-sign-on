import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { EmailTemplateEditor } from "./EmailTemplateEditor";
import { previewEmailTemplate } from "@/emailTemplates";
import type { EmailTemplate, EmailTemplatePreview } from "@/emailTemplates";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));
vi.mock("../../emailTemplates", () => ({
  previewEmailTemplate: vi.fn(),
  updateEmailTemplate: vi.fn(),
  deleteEmailTemplate: vi.fn(),
}));
vi.mock("../ToastProvider", () => ({ useToast: () => vi.fn() }));
vi.mock("../ConfirmProvider", () => ({ useConfirm: () => vi.fn() }));
// CodeMirror does not render meaningfully in jsdom and is not what these tests are about; the contract that
// matters here is that editing the body drives a new preview.
vi.mock("./TemplateSourceEditor", () => ({
  TemplateSourceEditor: ({ value, onChange, ariaLabel }: {
    value: string; onChange: (next: string) => void; ariaLabel: string;
  }) => <textarea aria-label={ariaLabel} value={value} onChange={(e) => onChange(e.target.value)} />,
}));

const TEMPLATE: EmailTemplate = {
  event: "EMAIL_VERIFICATION_CODE",
  configured: true,
  subject: "Your code",
  htmlBody: "<p>one</p>",
  textBody: null,
  logoUrl: null,
  variables: ["code"],
};

const rendered = (html: string): EmailTemplatePreview => ({ subject: "Your code", html, text: html });

/**
 * The preview is the only place an administrator can see what a template will actually look like, so a
 * preview that silently disagrees with the editor beside it is worse than none — it is authoritative-looking
 * and wrong. Both failures below were reported as "the preview does not reflect my change".
 */
describe("EmailTemplateEditor preview", () => {
  const preview = vi.mocked(previewEmailTemplate);

  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  const type = (body: string) =>
    fireEvent.change(screen.getByLabelText("customizeHtmlBody"), { target: { value: body } });

  it("ignores a superseded render that resolves after a newer one", async () => {
    // Renders are not equally fast — a template that grew a table takes longer than the one before it — so
    // the older answer can land last and leave the frame showing markup the editor no longer contains.
    let finishFirst: (value: EmailTemplatePreview) => void = () => {};
    preview
      .mockImplementationOnce(() => new Promise((resolve) => { finishFirst = resolve; }))
      .mockResolvedValue(rendered("<p>second</p>"));

    render(<EmailTemplateEditor template={TEMPLATE} onSaved={vi.fn()} />);
    await act(async () => { await vi.advanceTimersByTimeAsync(500); }); // first request is in flight

    type("<p>second</p>");
    await act(async () => { await vi.advanceTimersByTimeAsync(500); });
    await waitFor(() => expect(frameHtml()).toContain("second"));

    await act(async () => { finishFirst(rendered("<p>one</p>")); });

    expect(frameHtml()).toContain("second");
  });

  it("says the preview is stale when the render is refused, instead of presenting the old one", async () => {
    // Half-typed markup is refused constantly and normally: every "{{" is invalid until its braces are closed.
    preview
      .mockResolvedValueOnce(rendered("<p>one</p>"))
      .mockRejectedValue(new Error("template syntax"));

    render(<EmailTemplateEditor template={TEMPLATE} onSaved={vi.fn()} />);
    await act(async () => { await vi.advanceTimersByTimeAsync(500); });
    await waitFor(() => expect(frameHtml()).toContain("one"));

    type("<p>{{");
    await act(async () => { await vi.advanceTimersByTimeAsync(500); });

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("customizePreviewStale"));
  });

  it("renders the body through the code editor, not a bare textarea", async () => {
    render(<EmailTemplateEditor template={TEMPLATE} onSaved={vi.fn()} />);

    // Lazily loaded, so it arrives a tick later — until then the fallback shows the markup read-only.
    await waitFor(() => expect(screen.getByLabelText("customizeHtmlBody")).toHaveValue("<p>one</p>"));
  });

  function frameHtml(): string {
    return screen.getByTitle("customizePreview").getAttribute("srcdoc") ?? "";
  }
});

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import AuditExport from "./AuditExport";
import { ConfirmProvider } from "@/components/ConfirmProvider";
import { ToastProvider } from "@/components/ToastProvider";
import { getAuditExport, updateAuditExport, type AuditExportSettings } from "@/auditExport";
import type { SessionView } from "@/auth";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

vi.mock("@/auditExport", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/auditExport")>()),
  getAuditExport: vi.fn(),
  updateAuditExport: vi.fn().mockResolvedValue(undefined),
  deleteAuditExport: vi.fn().mockResolvedValue(undefined),
}));

const session = (...permissions: string[]) =>
  ({ username: "root", roles: ["ROLE_ADMIN"], permissions } as unknown as SessionView);

const renderPage = (view: SessionView) => render(
  <ToastProvider><ConfirmProvider><AuditExport session={view} /></ConfirmProvider></ToastProvider>);

const CONFIGURED: AuditExportSettings = {
  endpointUrl: "https://collector.example.com/ingest",
  enabled: true,
  includePii: false,
  updatedAt: "2026-08-10T00:00:00Z",
  updatedBy: "root",
  health: { lastSuccessAt: "2026-08-10T00:00:00Z", position: "2026-08-10T00:00:00Z", behindBySeconds: 12,
            consecutiveFailures: 0 },
};

/**
 * The console for the audit export. Three things are worth pinning, and none of them is the happy path.
 *
 * The credential is write-only, so the form must be able to save WITHOUT it and must not send an empty string
 * — that would read as "clear the credential" rather than "keep it", and would leave an export that can no
 * longer authenticate.
 *
 * Shipping actor identifiers needs a SECOND grant, because those are exactly the fields the audit screen
 * redacts without it. The control is hidden as a courtesy; the assertion here is only that the courtesy
 * exists, never that it is the enforcement.
 *
 * And a failed load must render the refusal, not an empty form — an admin who was denied would otherwise be
 * shown a blank collector and invited to configure one.
 */
describe("AuditExport", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(getAuditExport).mockResolvedValue(CONFIGURED);
  });

  async function loaded(view: SessionView = session("audit:export", "audit:read:pii")) {
    const rendered = renderPage(view);
    await waitFor(() =>
      expect(screen.getByDisplayValue("https://collector.example.com/ingest")).toBeInTheDocument());
    return rendered;
  }

  it("never shows the stored credential", async () => {
    await loaded();

    expect(screen.getByLabelText("auditExportCredential")).toHaveValue("");
  });

  it("sends null rather than an empty string when the credential is left alone", async () => {
    await loaded();

    fireEvent.click(screen.getByText("save"));

    await waitFor(() => expect(updateAuditExport).toHaveBeenCalledWith(
      expect.objectContaining({ credential: null })));
  });

  it("refuses a first save with no credential before making the request", async () => {
    vi.mocked(getAuditExport).mockResolvedValue(undefined);
    renderPage(session("audit:export"));
    await waitFor(() => expect(screen.getByLabelText("auditExportEndpoint")).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText("auditExportEndpoint"),
      { target: { value: "https://collector.example.com/ingest" } });
    fireEvent.click(screen.getByText("save"));

    await waitFor(() => expect(screen.getByText("auditExportCredentialRequired")).toBeInTheDocument());
    expect(updateAuditExport).not.toHaveBeenCalled();
  });

  it("does not offer to turn on PII export without the grant that shows those fields", async () => {
    await loaded(session("audit:export"));

    expect(screen.getByText("auditExportIncludePiiDenied")).toBeInTheDocument();
  });

  it("offers it to an admin who may already read those fields", async () => {
    await loaded();

    expect(screen.getByText("auditExportIncludePiiHint")).toBeInTheDocument();
  });

  /** No errors is not the same as arriving, which is the whole reason this block is on the page. */
  it("reports how far behind the collector is", async () => {
    await loaded();

    expect(screen.getByText("auditExportBehind")).toBeInTheDocument();
    expect(screen.getByText("auditExportBehindValue")).toBeInTheDocument();
  });

  it("renders a refused load instead of an empty collector form", async () => {
    vi.mocked(getAuditExport).mockRejectedValue(new Error("you may not read this"));

    renderPage(session("audit:export"));

    // The message itself, not merely the absence of a form — absent is also what LOADING looks like, and an
    // admin who was denied must not be shown a blank collector and invited to configure one.
    await waitFor(() => expect(screen.getByText("you may not read this")).toBeInTheDocument());
    expect(screen.queryByLabelText("auditExportEndpoint")).not.toBeInTheDocument();
  });
});

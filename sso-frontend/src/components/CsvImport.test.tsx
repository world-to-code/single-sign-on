import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ApiError } from "@/api";
import { CsvImport } from "./CsvImport";
import {
  applyCsvImport,
  downloadCsvTemplate,
  previewCsvImport,
  type CsvRowFailure,
  type Profile,
} from "@/attributeDefinitions";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) =>
      opts ? `${key}:${JSON.stringify(opts)}` : key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

vi.mock("@/attributeDefinitions", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/attributeDefinitions")>()),
  previewCsvImport: vi.fn(),
  applyCsvImport: vi.fn(),
  downloadCsvTemplate: vi.fn(),
}));

const PROFILE: Profile = {
  id: "8c2d1e7a-0000-4000-8000-000000000001",
  name: "Employees",
} as Profile;

const csv = (name = "people.csv") =>
  new File(["username,email\nada,ada@x.io\n"], name, { type: "text/csv" });

const preview = (over: Partial<Awaited<ReturnType<typeof previewCsvImport>>> = {}) => ({
  rowsRead: 1,
  toCreate: [{ line: 2, username: "ada", base: {}, attributes: {}, groups: [] }],
  existing: [],
  failures: [],
  ...over,
});

/** The hidden input is the only way in; the visible button just clicks it. */
function pick(file: File) {
  const input = screen.getByLabelText("csvImportChooseFile");
  fireEvent.change(input, { target: { files: [file] } });
}

describe("CsvImport", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(previewCsvImport).mockResolvedValue(preview());
    vi.mocked(applyCsvImport).mockResolvedValue({ created: 1, existing: [], failures: [] });
  });

  /**
   * The property the whole three-step shape exists for: choosing a file must PREVIEW, never apply. Every other
   * import path in this system fills existing accounts; this is the one that creates them, so a file aimed at
   * the wrong profile has to become a count on screen before it becomes a tenant full of accounts.
   */
  it("previews a chosen file and writes nothing", async () => {
    render(<CsvImport profile={PROFILE} />);

    pick(csv());

    await waitFor(() => expect(previewCsvImport).toHaveBeenCalledWith(PROFILE.id, expect.any(File)));
    expect(applyCsvImport).not.toHaveBeenCalled();
  });

  /**
   * Confirm re-sends the FILE, not the preview it just received. The server re-reads and re-plans it, so what
   * was applied is the thing the administrator saw rather than an instruction this client composed.
   */
  it("confirms by re-sending the file rather than the plan", async () => {
    render(<CsvImport profile={PROFILE} />);
    pick(csv());
    await screen.findByRole("button", { name: /csvImportConfirm/ });

    fireEvent.click(screen.getByRole("button", { name: /csvImportConfirm/ }));

    await waitFor(() => expect(applyCsvImport).toHaveBeenCalledWith(PROFILE.id, expect.any(File)));
    const [, sent] = vi.mocked(applyCsvImport).mock.calls[0];
    expect((sent as File).name).toBe("people.csv");
  });

  /** Confirming a file that creates nobody reads as a broken button, so it is disabled instead. */
  it("cannot confirm a file that would create nobody", async () => {
    vi.mocked(previewCsvImport).mockResolvedValue(preview({ toCreate: [], existing: ["ada"] }));
    render(<CsvImport profile={PROFILE} />);

    pick(csv());

    await waitFor(() =>
      expect(screen.getByRole("button", { name: /csvImportConfirm/ })).toBeDisabled(),
    );
  });

  /**
   * A refused row is shown BEFORE anything is applied, with its line number — that is what finds the row in
   * the file the administrator still has.
   */
  it("shows refused rows in the preview, with their line numbers", async () => {
    vi.mocked(previewCsvImport).mockResolvedValue(
      preview({ failures: [{ line: 7, reason: "There is no group named ops." }] }),
    );
    render(<CsvImport profile={PROFILE} />);

    pick(csv());

    expect(await screen.findByText(/There is no group named ops\./)).toBeInTheDocument();
    expect(screen.getByText(/csvImportLine.*7/)).toBeInTheDocument();
  });

  /**
   * The server sends a reason and never the offending cell values — a failure report is read in a console and
   * pasted into tickets, and the rows that fail hold somebody's name or address. Rendering must not reintroduce
   * what the server withheld.
   */
  it("renders only the line and the reason for a failure", async () => {
    // The fixture carries a field the server does NOT send, cast past the type on purpose: a server that
    // regrows one is not a compile error here, and the assertion has to fail on rendering it. Without a
    // hazard in the fixture this test passed even if the component rendered the whole object.
    vi.mocked(previewCsvImport).mockResolvedValue(
      preview({
        failures: [
          { line: 3, reason: "email is required.", detail: "ada@x.io" } as unknown as CsvRowFailure,
        ],
      }),
    );
    render(<CsvImport profile={PROFILE} />);

    pick(csv());

    await screen.findByText(/email is required\./);
    expect(screen.queryByText(/ada@x\.io/)).not.toBeInTheDocument();
  });

  /** A partial apply still reports what happened; the result replaces the preview so it cannot be re-confirmed. */
  it("reports the outcome and clears the preview once applied", async () => {
    vi.mocked(applyCsvImport).mockResolvedValue({
      created: 4,
      existing: ["grace"],
      failures: [{ line: 9, reason: "username is required." }],
    });
    render(<CsvImport profile={PROFILE} />);
    pick(csv());
    fireEvent.click(await screen.findByRole("button", { name: /csvImportConfirm/ }));

    expect(await screen.findByText(/csvImportDone/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /csvImportConfirm/ })).not.toBeInTheDocument();
  });

  /**
   * A refusal is shown, not swallowed. Rejected with the ApiError production actually throws — a plain Error
   * takes errorMessage's final fallback, a branch the real client never reaches, so the test was exercising
   * the wrong path and could not see the status mapping at all.
   */
  it("surfaces a refusal the server explained", async () => {
    vi.mocked(previewCsvImport).mockRejectedValue(
      new ApiError(400, "The file has more than 500 rows."),
    );
    render(<CsvImport profile={PROFILE} />);

    pick(csv());

    expect(await screen.findByText(/more than 500 rows/)).toBeInTheDocument();
    expect(applyCsvImport).not.toHaveBeenCalled();
  });

  /**
   * The container rejects an oversized upload before the application sees it, so there is no explanation to
   * pass on — the user still has to be told what happened rather than shown a status number.
   */
  it("says a file is too large when the container refuses it", async () => {
    vi.mocked(previewCsvImport).mockRejectedValue(new ApiError(413));
    render(<CsvImport profile={PROFILE} />);

    pick(csv());

    expect(await screen.findByText(/too large/i)).toBeInTheDocument();
  });

  /** A failed apply keeps the plan on screen, so the administrator can see what happened and retry. */
  it("keeps the preview when applying fails", async () => {
    vi.mocked(applyCsvImport).mockRejectedValue(new ApiError(409, "Someone else changed it."));
    render(<CsvImport profile={PROFILE} />);
    pick(csv());
    fireEvent.click(await screen.findByRole("button", { name: /csvImportConfirm/ }));

    expect(await screen.findByText(/Someone else changed it\./)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /csvImportConfirm/ })).toBeInTheDocument();
  });

  /** Cancelling drops the plan, so a later confirm cannot apply a file the administrator backed out of. */
  it("cancelling clears the preview", async () => {
    render(<CsvImport profile={PROFILE} />);
    pick(csv());
    await screen.findByRole("button", { name: /csvImportConfirm/ });

    fireEvent.click(screen.getByRole("button", { name: /csvImportCancel/ }));

    await waitFor(() =>
      expect(screen.queryByRole("button", { name: /csvImportConfirm/ })).not.toBeInTheDocument(),
    );
    expect(applyCsvImport).not.toHaveBeenCalled();
  });

  /** The template comes from the PROFILE, so the file's columns cannot disagree with what it declares. */
  it("downloads the template for the profile being imported into", async () => {
    vi.mocked(downloadCsvTemplate).mockResolvedValue({
      filename: "employees.csv",
      content: "username,email\n",
    });
    render(<CsvImport profile={PROFILE} />);

    fireEvent.click(screen.getByRole("button", { name: /csvImportDownloadTemplate/ }));

    await waitFor(() => expect(downloadCsvTemplate).toHaveBeenCalledWith(PROFILE.id));
  });
});

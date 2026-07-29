import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import SmsSettings from "./SmsSettings";
import { ConfirmProvider } from "@/components/ConfirmProvider";
import { ToastProvider } from "@/components/ToastProvider";
import { getSmsSettings, updateSmsSettings, type SmsSettings as Settings } from "@/sms";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

vi.mock("@/sms", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/sms")>()),
  getSmsSettings: vi.fn(),
  updateSmsSettings: vi.fn().mockResolvedValue(undefined),
  deleteSmsSettings: vi.fn().mockResolvedValue(undefined),
}));

const renderPage = () => render(
  <ToastProvider><ConfirmProvider><SmsSettings /></ConfirmProvider></ToastProvider>);

const CONFIGURED: Settings =
  { configured: true, provider: "SOLAPI", apiKey: "KEY-1", senderNumber: "010-1234-5678" };
const UNCONFIGURED: Settings = { configured: false, provider: null, apiKey: null, senderNumber: null };

/**
 * The console half of the per-tenant SMS gateway. The behaviour worth pinning here is the write-only secret:
 * the server never returns it, so the form has to be able to save WITHOUT it — and must not send an empty
 * string, which would read as "clear the credential" rather than "keep it".
 */
describe("SmsSettings", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(getSmsSettings).mockResolvedValue(CONFIGURED);
  });

  async function loaded() {
    const view = renderPage();
    await waitFor(() => expect(screen.getByDisplayValue("KEY-1")).toBeInTheDocument());
    return view;
  }

  function save() {
    fireEvent.click(screen.getByRole("button", { name: /save/i }));
  }

  it("shows the stored configuration but never a secret", async () => {
    await loaded();

    expect(screen.getByDisplayValue("010-1234-5678")).toBeInTheDocument();
    // The secret field is present and EMPTY: the server has never sent it, so there is nothing to prefill.
    const secret = document.querySelector<HTMLInputElement>('input[type="password"]');
    expect(secret).not.toBeNull();
    expect(secret!.value).toBe("");
  });

  /**
   * Editing only the sending number must not force an administrator to re-enter a credential the console was
   * never given. `null` is what the API contract means by "keep the stored one"; `""` would not be.
   */
  it("sends a null secret when it was left blank, so the stored one is kept", async () => {
    await loaded();

    fireEvent.change(screen.getByDisplayValue("010-1234-5678"), { target: { value: "010-9999-8888" } });
    save();

    await waitFor(() => expect(updateSmsSettings).toHaveBeenCalledWith({
      provider: "SOLAPI",
      apiKey: "KEY-1",
      apiSecret: null,
      senderNumber: "010-9999-8888",
    }));
  });

  /** A FIRST save has nothing stored to fall back on, so a blank secret is refused before the round trip. */
  it("refuses a first save with no secret", async () => {
    vi.mocked(getSmsSettings).mockResolvedValue(UNCONFIGURED);
    renderPage();
    await waitFor(() => expect(screen.getByText("smsInheritedHint")).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText("smsApiKey"), { target: { value: "KEY-NEW" } });
    fireEvent.change(screen.getByLabelText("smsSenderNumber"), { target: { value: "010-1234-5678" } });
    save();

    await waitFor(() => expect(screen.getByText("smsSecretRequired")).toBeInTheDocument());
    expect(updateSmsSettings).not.toHaveBeenCalled();
  });

  /** The key reaches Twilio's request PATH, so a pasted value with a separator is caught before it is stored. */
  it("refuses an api key outside the opaque-token charset", async () => {
    await loaded();

    fireEvent.change(screen.getByLabelText("smsApiKey"), { target: { value: "AC-SID/../Accounts" } });
    save();

    await waitFor(() => expect(screen.getByText("smsApiKeyInvalid")).toBeInTheDocument());
    expect(updateSmsSettings).not.toHaveBeenCalled();
  });

  it("refuses a malformed sending number before sending it", async () => {
    await loaded();

    fireEvent.change(screen.getByDisplayValue("010-1234-5678"), { target: { value: "010 not a number" } });
    save();

    await waitFor(() => expect(screen.getByText("smsSenderInvalid")).toBeInTheDocument());
    expect(updateSmsSettings).not.toHaveBeenCalled();
  });

  /** Switching provider carries the whole credential pair — a Twilio SID under a Solapi key would 401. */
  it("submits the selected provider", async () => {
    await loaded();

    fireEvent.change(screen.getByLabelText("smsProvider"), { target: { value: "TWILIO" } });
    fireEvent.change(screen.getByLabelText("smsApiKey"), { target: { value: "AC-SID" } });
    fireEvent.change(screen.getByDisplayValue("010-1234-5678"), { target: { value: "+15550000000" } });
    save();

    await waitFor(() => expect(updateSmsSettings).toHaveBeenCalledWith(
      expect.objectContaining({ provider: "TWILIO", apiKey: "AC-SID", senderNumber: "+15550000000" })));
  });

  /** A refused load must say so, not render an empty form that looks like "nothing is configured". */
  it("reports a failed load instead of showing a blank form", async () => {
    vi.mocked(getSmsSettings).mockRejectedValue(new Error("forbidden"));
    renderPage();

    await waitFor(() => expect(screen.queryByLabelText("smsApiKey")).toBeNull());
    expect(screen.getByText(/forbidden/i)).toBeInTheDocument();
  });
});

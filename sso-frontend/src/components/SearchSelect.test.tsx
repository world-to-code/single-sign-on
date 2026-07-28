import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { SearchSelect } from "./SearchSelect";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

/**
 * "No matches" and "the search failed" are different answers, and this control decides who gets added to a
 * group or a role. Collapsing a refusal into an empty result tells an administrator the person does not exist
 * when what actually happened is that they were not allowed to look, or the request never landed.
 */
describe("SearchSelect", () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));

  const open = () => fireEvent.focus(screen.getByPlaceholderText("find"));

  it("says the search failed rather than showing no matches", async () => {
    render(<SearchSelect placeholder="find" fetcher={() => Promise.reject(new Error("403"))}
                         onSelect={vi.fn()} />);
    open();

    await waitFor(() => expect(screen.getByText("searchFailed")).toBeInTheDocument());
    expect(screen.queryByText("noMatches")).not.toBeInTheDocument();
  });

  it("still says no matches when the search genuinely returned none", async () => {
    render(<SearchSelect placeholder="find" fetcher={() => Promise.resolve([])} onSelect={vi.fn()} />);
    open();

    await waitFor(() => expect(screen.getByText("noMatches")).toBeInTheDocument());
    expect(screen.queryByText("searchFailed")).not.toBeInTheDocument();
  });

  it("clears a previous failure when the next search succeeds", async () => {
    // Otherwise one transient failure marks the control broken for the rest of the session.
    const fetcher = vi.fn()
      .mockRejectedValueOnce(new Error("503"))
      .mockResolvedValue([{ id: "u1", label: "Ada" }]);
    render(<SearchSelect placeholder="find" fetcher={fetcher} onSelect={vi.fn()} />);
    open();
    await waitFor(() => expect(screen.getByText("searchFailed")).toBeInTheDocument());

    fireEvent.change(screen.getByPlaceholderText("find"), { target: { value: "ad" } });

    await waitFor(() => expect(screen.getByText("Ada")).toBeInTheDocument());
    expect(screen.queryByText("searchFailed")).not.toBeInTheDocument();
  });
});

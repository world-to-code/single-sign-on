import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { HtmlCodeEditor } from "./HtmlCodeEditor";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

/**
 * The reported problem was that an email template arrives as one unbroken line of markup, so finding the block
 * you meant to edit is the slow part of every change. Highlighting makes it scannable; only a pretty-printer
 * makes it structured.
 */
describe("HtmlCodeEditor reformat", () => {
  const ONE_LINE = "<html><body><h1>Hi</h1><p>Your code is {{code}}.</p></body></html>";

  it("breaks a single-line template onto indented lines", () => {
    // The first version of this used CodeMirror's own indentSelection, which fixes the indentation of lines
    // that already exist and never inserts a break — so a one-line template came back as one line, and the
    // button appeared to do nothing at all. Indenting and pretty-printing are different operations.
    const onChange = vi.fn();
    render(<HtmlCodeEditor value={ONE_LINE} onChange={onChange} ariaLabel="body" />);

    fireEvent.click(screen.getByRole("button", { name: /customizeFormatHtml/ }));

    const formatted: string = onChange.mock.calls[0][0];
    expect(formatted.split("\n").length).toBeGreaterThan(5);
    // Nested content is indented under its parent — the structure is what makes the template scannable.
    expect(formatted).toMatch(/\n {2}<h1>/);
  });

  /** The template's own variables must survive it — a formatter that mangles {{code}} breaks every send. */
  it("leaves the template variables untouched", () => {
    const onChange = vi.fn();
    render(<HtmlCodeEditor value={ONE_LINE} onChange={onChange} ariaLabel="body" />);

    fireEvent.click(screen.getByRole("button", { name: /customizeFormatHtml/ }));

    expect(onChange.mock.calls[0][0]).toContain("{{code}}");
  });

  /** Idempotent: formatting an already-formatted template must not keep adding structure to it. */
  it("is stable when applied twice", () => {
    const first = vi.fn();
    render(<HtmlCodeEditor value={ONE_LINE} onChange={first} ariaLabel="body" />);
    fireEvent.click(screen.getByRole("button", { name: /customizeFormatHtml/ }));
    const once: string = first.mock.calls[0][0];

    const second = vi.fn();
    render(<HtmlCodeEditor value={once} onChange={second} ariaLabel="body" />);
    fireEvent.click(screen.getAllByRole("button", { name: /customizeFormatHtml/ })[1]);

    expect(second.mock.calls[0][0]).toEqual(once);
  });
});

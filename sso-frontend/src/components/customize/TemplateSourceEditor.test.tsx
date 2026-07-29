import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { TemplateSourceEditor } from "./TemplateSourceEditor";

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
describe("TemplateSourceEditor", () => {
  const ONE_LINE = "<html><body><h1>Hi</h1><p>Your code is {{code}}.</p></body></html>";

  it("breaks a single-line template onto indented lines", () => {
    // The first version of this used CodeMirror's own indentSelection, which fixes the indentation of lines
    // that already exist and never inserts a break — so a one-line template came back as one line, and the
    // button appeared to do nothing at all. Indenting and pretty-printing are different operations.
    const onChange = vi.fn();
    render(<TemplateSourceEditor value={ONE_LINE} onChange={onChange} ariaLabel="body" variables={["code"]} />);

    fireEvent.click(screen.getByRole("button", { name: /customizeFormatHtml/ }));

    const formatted: string = onChange.mock.calls[0][0];
    expect(formatted.split("\n").length).toBeGreaterThan(5);
    // Nested content is indented under its parent — the structure is what makes the template scannable.
    expect(formatted).toMatch(/\n {2}<h1>/);
  });

  /** The template's own variables must survive it — a formatter that mangles {{code}} breaks every send. */
  it("leaves the template variables untouched", () => {
    const onChange = vi.fn();
    render(<TemplateSourceEditor value={ONE_LINE} onChange={onChange} ariaLabel="body" variables={["code"]} />);

    fireEvent.click(screen.getByRole("button", { name: /customizeFormatHtml/ }));

    expect(onChange.mock.calls[0][0]).toContain("{{code}}");
  });

  /**
   * The placeholder highlighting, which is the part both bodies share.
   *
   * <p>An unknown name is not an error anywhere: the renderer is logic-less and substitutes an empty string,
   * so nothing fails and nothing is logged — the mail just goes out with a gap where the code should have
   * been. That makes a typo the most expensive mistake on this screen and the least visible one, since
   * "{{cdoe}}" reads exactly like "{{code}}".
   */
  it("marks a placeholder the event does not supply differently from one it does", () => {
    const { container } = render(
      <TemplateSourceEditor value="<p>{{code}} {{cdoe}}</p>" onChange={vi.fn()} ariaLabel="body"
                            variables={["code"]} />);

    expect(container.querySelectorAll(".cm-template-var")).toHaveLength(1);
    expect(container.querySelectorAll(".cm-template-var-unknown")).toHaveLength(1);
  });

  /**
   * And it must follow the document. Decorating only what was there at construction would mean the typo you
   * just TYPED is the one thing never flagged — which is every typo, since nobody pastes their mistakes in.
   */
  it("flags a placeholder that becomes wrong as the document changes", () => {
    const { container, rerender } = render(
      <TemplateSourceEditor value="<p>{{code}}</p>" onChange={vi.fn()} ariaLabel="body" variables={["code"]} />);
    expect(container.querySelectorAll(".cm-template-var-unknown")).toHaveLength(0);

    rerender(
      <TemplateSourceEditor value="<p>{{code}} {{cdoe}}</p>" onChange={vi.fn()} ariaLabel="body"
                            variables={["code"]} />);

    expect(container.querySelectorAll(".cm-template-var-unknown")).toHaveLength(1);
  });

  /** The text body is not markup: colouring it as HTML would assert a structure it does not have. */
  it("offers no reformat action for the plain-text body", () => {
    render(<TemplateSourceEditor value="Your code is {{code}}." onChange={vi.fn()} ariaLabel="text"
                                 variables={["code"]} language="text" />);

    expect(screen.queryByRole("button", { name: /customizeFormatHtml/ })).not.toBeInTheDocument();
  });

  /** Idempotent: formatting an already-formatted template must not keep adding structure to it. */
  it("is stable when applied twice", () => {
    const first = vi.fn();
    render(<TemplateSourceEditor value={ONE_LINE} onChange={first} ariaLabel="body" variables={["code"]} />);
    fireEvent.click(screen.getByRole("button", { name: /customizeFormatHtml/ }));
    const once: string = first.mock.calls[0][0];

    const second = vi.fn();
    render(<TemplateSourceEditor value={once} onChange={second} ariaLabel="body" variables={["code"]} />);
    fireEvent.click(screen.getAllByRole("button", { name: /customizeFormatHtml/ })[1]);

    expect(second.mock.calls[0][0]).toEqual(once);
  });
});

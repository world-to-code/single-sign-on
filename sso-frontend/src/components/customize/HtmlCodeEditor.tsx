import { useEffect, useMemo, useRef, useState } from "react";
import CodeMirror, { EditorView, type ReactCodeMirrorRef } from "@uiw/react-codemirror";
import { html } from "@codemirror/lang-html";
import { html as beautifyHtml } from "js-beautify";
import { useTranslation } from "react-i18next";
import { WandSparkles } from "lucide-react";
import { resolvedTheme } from "@/lib/prefs";
import { Button } from "@/components/ui/button";

/**
 * An HTML source editor for the email templates: syntax highlighting, line numbers, bracket matching, and a
 * language-aware re-indent.
 *
 * <p>It replaces a bare textarea, which is a fine control for prose and a poor one for markup — a template of
 * any size arrives as one unbroken wall of tags, so finding the block you meant to edit is the slow part of
 * every change. Highlighting is what makes it scannable; the reformat button is what recovers a template that
 * was pasted, or hand-edited, into a single line.
 *
 * <p>Loaded LAZILY by its parent. CodeMirror is a large dependency and this is one admin screen, so it must
 * not sit in the bundle every sign-in downloads first. The toolbar lives in here rather than in the parent so
 * the {@code EditorView} never crosses that boundary — the parent would otherwise have to import CodeMirror to
 * hold a reference to it, which is the thing being deferred.
 *
 * <p>Not a security boundary. The value is HTML the tenant authors and the SERVER renders; this shows the
 * SOURCE as text and never executes it, and the preview beside it is sandboxed ({@code HtmlPreview}).
 */
export function HtmlCodeEditor({ value, onChange, ariaLabel, rows = 16 }: {
  value: string;
  onChange: (next: string) => void;
  ariaLabel: string;
  rows?: number;
}) {
  const { t } = useTranslation("console");
  const editor = useRef<ReactCodeMirrorRef>(null);
  const [dark, setDark] = useState(() => resolvedTheme() === "dark");

  // The theme lives on <html data-theme>, written by the toggle and at boot — follow the attribute rather than
  // re-reading a stored preference that may have changed since this screen mounted.
  useEffect(() => {
    const root = document.documentElement;
    const observer = new MutationObserver(() => setDark(root.dataset.theme === "dark"));
    observer.observe(root, { attributes: true, attributeFilter: ["data-theme"] });
    return () => observer.disconnect();
  }, []);

  /**
   * The editor surface is bound to the app's design tokens rather than left on CodeMirror's own light/dark
   * palette, which reads as a foreign control pasted into the form — a grey slab inside a white card. Only the
   * SURFACE is taken over; the syntax colours stay with the theme, since that is what the theme is for.
   */
  const extensions = useMemo(() => [
    html(),
    EditorView.lineWrapping,
    EditorView.theme({
      "&": { backgroundColor: "hsl(var(--background))", color: "hsl(var(--foreground))", fontSize: "0.8125rem" },
      "&.cm-focused": { outline: "none" },
      ".cm-gutters": {
        backgroundColor: "hsl(var(--sunken))",
        color: "hsl(var(--muted-foreground))",
        border: "none",
        borderRight: "1px solid hsl(var(--border))",
      },
      ".cm-activeLine, .cm-activeLineGutter": { backgroundColor: "hsl(var(--muted))" },
      ".cm-content": { fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", padding: "0.5rem 0" },
      ".cm-cursor": { borderLeftColor: "hsl(var(--foreground))" },
    }),
  ], []);

  /**
   * Pretty-prints the document: breaks the markup onto lines AND indents it.
   *
   * <p>The first attempt at this used CodeMirror's own indentation service, on the reasoning that one
   * mechanism is better than two. It does not do this job — {@code indentSelection} fixes the indentation of
   * lines that already exist and never inserts a break, so a template pasted as one long line came back as
   * one long line. Which is the exact complaint. A pretty-printer is a different function from an indenter,
   * and the editor only has the second.
   *
   * <p>Configured to two-space indent to match the editor's own, so typing after a reformat continues in the
   * same shape rather than fighting it. {@code wrap_line_length: 0} leaves long lines alone: an email
   * template's inline styles are long by nature and folding them helps nobody.
   */
  function reformat(): void {
    onChange(beautifyHtml(value, {
      indent_size: 2,
      wrap_line_length: 0,
      preserve_newlines: true,
      max_preserve_newlines: 1,
      end_with_newline: true,
    }));
    editor.current?.view?.focus();
  }

  return (
    <div className="grid gap-1.5">
      <div className="flex justify-end">
        <Button type="button" variant="ghost" size="sm" onClick={reformat}>
          <WandSparkles /> {t("customizeFormatHtml")}
        </Button>
      </div>
      <div className="overflow-hidden rounded-md border border-input">
        <CodeMirror
          ref={editor}
          value={value}
          onChange={onChange}
          extensions={extensions}
          theme={dark ? "dark" : "light"}
          minHeight={`${rows * 1.5}rem`}
          maxHeight="32rem"
          basicSetup={{ autocompletion: false }}
          aria-label={ariaLabel}
        />
      </div>
    </div>
  );
}

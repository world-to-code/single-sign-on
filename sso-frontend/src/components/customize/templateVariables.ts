import { Decoration, MatchDecorator, ViewPlugin, type DecorationSet, type EditorView } from "@codemirror/view";

/** Every `{{name}}` in the source, however it is spelled — the point is to catch the ones that are wrong. */
const PLACEHOLDER = /\{\{\s*([\w.]+)\s*\}\}/g;

const KNOWN = Decoration.mark({ class: "cm-template-var" });
const UNKNOWN = Decoration.mark({ class: "cm-template-var-unknown" });

/**
 * Marks the `{{variables}}` in a mail template, and marks the ones this event does not supply differently.
 *
 * <p>An unknown placeholder is not a syntax error — the renderer is logic-less and substitutes an empty string
 * — so nothing fails, nothing is logged, and the mail simply goes out with a gap where the code should have
 * been. That makes a typo the most expensive mistake available on this screen and the least visible one, since
 * {@code &#123;&#123;cdoe&#125;&#125;} reads exactly like {@code &#123;&#123;code&#125;&#125;} at a glance.
 *
 * <p>Applied to the text body as well as the HTML one: both are rendered through the same substitution, so a
 * typo costs the same either way.
 */
export function templateVariableHighlighter(known: readonly string[]) {
  const supplied = new Set(known);
  const matcher = new MatchDecorator({
    regexp: PLACEHOLDER,
    decoration: (match) => (supplied.has(match[1]) ? KNOWN : UNKNOWN),
  });
  return ViewPlugin.fromClass(
    class {
      decorations: DecorationSet;

      constructor(view: EditorView) {
        this.decorations = matcher.createDeco(view);
      }

      update(update: { view: EditorView; docChanged: boolean; viewportChanged: boolean }) {
        if (update.docChanged || update.viewportChanged) {
          this.decorations = matcher.createDeco(update.view);
        }
      }
    },
    { decorations: (plugin) => plugin.decorations },
  );
}

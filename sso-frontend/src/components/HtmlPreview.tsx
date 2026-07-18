/**
 * Renders untrusted HTML (a server-rendered email preview, tenant-authored) inside a sandboxed iframe. The
 * `sandbox=""` attribute with NO `allow-scripts` means any `<script>` or `on*` handler in the HTML is inert —
 * this is the ONLY safe way to show it. Never render such HTML with `dangerouslySetInnerHTML`.
 */
export function HtmlPreview({ html, title, className }: { html: string; title: string; className?: string }) {
  return (
    <iframe
      title={title}
      srcDoc={html}
      sandbox=""
      className={className ?? "h-96 w-full rounded-md border border-input bg-white"}
    />
  );
}

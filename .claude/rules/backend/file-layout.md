---
paths:
  - "sso-backend/**/*.java"
---

# File layout & statics

- **One public type per file.** Avoid nested classes — a type worth naming is worth its own file
  (and nested types dodge the module-visibility rules).
- **No `private static` methods unless `static` is genuinely required** (no instance state AND
  called from a static context). A helper that only reads instance fields is an instance method;
  a helper used by one method belongs inline or in a collaborator.
  `private static final` **constants are fine**.

Why: gratuitous `static` hides dependencies from the constructor, blocks substitution in tests,
and accretes into util-class god files.

Verify: `rg "private static (?!final)" sso-backend/src --glob '*.java' -P`

Related: [immutability](immutability.md); reviewer: `.claude/agents/god-class-reviewer.md`.

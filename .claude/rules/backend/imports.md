---
paths:
  - "sso-backend/**/*.java"
---

# Imports — never inline fully-qualified names

Reference every type via an `import` statement; never write an inline fully-qualified name in
code. This includes `package-info.java` (module declarations import their annotation types) and
test code.

```java
// DO
import com.example.sso.shared.IdName;
...
IdName owner = ...;

// DON'T
com.example.sso.shared.IdName owner = ...;
```

Tolerated FQNs: a genuine same-file name collision an import cannot resolve (rare — prefer
renaming first), JPQL constructor expressions (`select new com.example...Row(...)` — JPQL
requires the FQN), javadoc `{@link}`/`{@code}` references, and a name inside a STRING literal
(`Class.forName("com.example.sso…")` for a package-private type in another module) — that is data,
not a type reference, and no import can express it.

Verify after structural changes — this is the exact CI command, so a clean run here means a green
Hygiene workflow (`.github/workflows/hygiene.yml`):

```
grep -rnP --include='*.java' \
  '^(?!\s*(import|package)\b).*(?<!")\bcom\.example\.sso\.[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*\.[A-Z]' \
  sso-backend/src | grep -vP '\{@(link|code)'
```

Reviewer: `.claude/agents/hygiene-reviewer.md` runs this (and the other mechanical rules) before the
build does — this rule kept reaching CI instead of review, which is a wasted round trip.

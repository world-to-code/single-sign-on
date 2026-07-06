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
requires the FQN), and javadoc `{@link}`/`{@code}` references.

Verify after structural changes (hits should only be the tolerated cases above):

```
rg -P '^(?!\s*(?:import|package)\b).*\bcom\.example\.sso\.[a-z]+\.' sso-backend/src --glob '*.java'
```

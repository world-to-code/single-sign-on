---
paths:
  - "sso-backend/**/*.java"
---

# No magic strings or numbers

- **Protocol values** (scope names, claim names, header names, grant types, HTTP methods) →
  enum or shared constant. **Reuse the existing ones first**: `OidcScopes`, `HttpMethod`, Spring
  Security's constants — only mint a new constant when none exists.
- **Tunables** (timeouts, limits, sizes, windows) → configuration, never a literal in code or an
  annotation default — see [config-tunables](config-tunables.md).
- The same literal appearing twice is already a constant waiting to be named; a literal whose
  meaning needs a comment should have been a named constant instead.

```java
// DO
request.scopes().contains(OidcScopes.OPENID)

// DON'T
request.scopes().contains("openid")
```

Related: [config-tunables](config-tunables.md), [error-handling](error-handling.md).

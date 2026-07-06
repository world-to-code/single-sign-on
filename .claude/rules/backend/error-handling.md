---
paths:
  - "sso-backend/src/main/java/**/*.java"
---

# Error handling — no HTTP-status branching on domain results

- Services signal 4xx outcomes by throwing the shared **`ApiException` subtypes** in
  `shared.error`: `NotFoundException`, `BadRequestException`, `ConflictException`,
  `ForbiddenException`, `UnauthorizedException`, `LockedException` (with `ErrorCode`).
- `GlobalExceptionHandler` maps them to responses. Controllers and services NEVER build a
  `ResponseEntity.status(4xx)` for a domain outcome, and never `try/catch` a domain exception to
  translate it locally.
- Reserve `IllegalStateException` / `IllegalArgumentException` for **500-class invariant
  violations** ("this cannot happen unless the code is wrong"), not for user-triggerable errors.
- Error responses stay **non-revealing**: no account-enumeration hints, stack traces, or internal
  identifiers in bodies (security invariant — never trade it for a friendlier message).

```java
// DO
UserGroup group = repository.findById(id)
        .orElseThrow(() -> new NotFoundException("group not found"));

// DON'T
Optional<UserGroup> group = repository.findById(id);
if (group.isEmpty()) return ResponseEntity.status(404).body(...);   // status branching in code
```

Related: [thin-controllers](thin-controllers.md), [no-magic-values](no-magic-values.md).

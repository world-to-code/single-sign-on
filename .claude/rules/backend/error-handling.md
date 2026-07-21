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
        .orElseThrow(() -> NotFoundException.of("user.group.notFound"));

// DON'T
Optional<UserGroup> group = repository.findById(id);
if (group.isEmpty()) return ResponseEntity.status(404).body(...);   // status branching in code
```

## User-visible messages are message KEYS, never literal prose

Every `ApiException` subtype has an `of(messageKey, args...)` factory. Use it. A literal string is
English forever: the SPA sends `Accept-Language` and the handler resolves keys against
`messages_{en,ko}.properties`, so a hardcoded message is the one part of the response that ignores
the user's language. This regressed in exactly that way once — a Korean console answering in English.

- Keys follow `<module>.<subject>[.<qualifier>].<condition>`, each segment lowerCamelCase.
- Add the key to **both** bundles in the same commit. `MessageBundleParityTest` enforces it, and it
  has to: `useCodeAsDefaultMessage(true)` means a one-sided key renders the raw key string to the
  user instead of failing.
- Interpolate with `{0}` args, never string concatenation — and never concatenate a caught
  exception's own message, which leaks library internals into the response.
- Reuse an existing key when the wording is the same. One key per *message*, not per throw site.
- The bare `new UnauthorizedException()` is deliberate: non-revealing 401s share one message, because
  a per-site message is the account-enumeration hint they exist to avoid.

```java
// DO
throw BadRequestException.of("federation.provider.aliasInvalid");
throw NotFoundException.of("resource.member.notFound", memberType);

// DON'T
throw new BadRequestException("The alias must be 2-64 lowercase letters.");   // English forever
throw new BadRequestException("Bad template: " + e.getMessage());             // leaks internals
```

Exception: the `scim` module answers in the SCIM error format via the vendor SDK's own exceptions.

Related: [thin-controllers](thin-controllers.md), [no-magic-values](no-magic-values.md).

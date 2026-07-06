---
paths:
  - "sso-backend/src/main/java/**/internal/api/**/*.java"
---

# Thin controllers

A `@RestController` does exactly three things: **bind HTTP → call ONE service method → shape the
response.** Nothing else.

Forbidden in a controller:

- orchestration (calling two services, conditional business flow),
- `try/catch` on domain outcomes (exceptions are mapped by `GlobalExceptionHandler` — see
  [error-handling](error-handling.md)),
- entity→DTO mapping logic (belongs in `internal/application`, or `View.of(domain)`),
- auditing, session manipulation, permission computation.

If no single service method fits the endpoint, **extract a service method** — do not fatten the
controller.

```java
// DO
@PostMapping
public ResponseEntity<GroupView> create(@Valid @RequestBody GroupRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(userGroupService.create(request.toSpec()));
}

// DON'T: orchestration + mapping + catch in the adapter
@PostMapping
public ResponseEntity<?> create(@RequestBody GroupRequest request) {
    try {
        var group = groupRepo.save(...);          // repository from a controller
        auditService.record(...);                  // second service call
        return ResponseEntity.ok(map(group));      // mapping here
    } catch (ConflictException e) { ... }          // domain outcome handled here
}
```

Status codes via `ResponseEntity` (project style: no `@ResponseStatus`).

Related: [dto-placement](dto-placement.md), [error-handling](error-handling.md),
[step-up](step-up.md).

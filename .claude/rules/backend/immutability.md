---
paths:
  - "sso-backend/**/*.java"
---

# Immutability — no setters, records first, Lombok whitelist

- **No setters anywhere.** State changes go through intention-revealing methods
  (`lockout.recordFailure()`, `group.rename(name)`) on fully-initializing constructors.
- **Records for immutables**: DTOs, views, commands, specs, event payloads. Use a class only when
  a record cannot express it (JPA entities).
- **JPA entities** use field access and a `protected` no-arg constructor (JPA-only); public
  constructors fully initialize.
- **Lombok whitelist:** `@Getter`, `@RequiredArgsConstructor`, `@Slf4j`, `@Builder`.
  **Never** `@Setter` or `@Data`.

```java
// DO: behavior on the object, no setter
public void deactivate() { this.active = false; this.deactivatedAt = Instant.now(); }

// DON'T
public void setActive(boolean active) { this.active = active; }
```

Verify: `rg "@Setter|@Data\b|public void set[A-Z]" sso-backend/src --glob '*.java'`

Related: [constructors-factories](constructors-factories.md), [entity-design](entity-design.md).

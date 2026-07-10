# Localized error messages (`MessageSource`)

The SPA renders RFC-7807 `detail` **verbatim** for HTTP 400 and 409. Before this change, ~104 throw
sites carried hard-coded English, so a Korean UI showed English error text. The backend now resolves
those messages from a `MessageSource` using the request's `Accept-Language`.

Status: **done.** 104 call sites migrated, 87 keys, `ko`/`en` bundles byte-identical in key set,
full suite green (1109 tests).

---

## The three moving parts

| File | Role |
|---|---|
| `config/internal/MessageSourceConfig.java` | `MessageSource` bean + `AcceptHeaderLocaleResolver` |
| `shared/error/ApiException.java` | carries an **optional** `messageKey` + `messageArgs` |
| `shared/web/GlobalExceptionHandler.java` | resolves the key against the request locale |

`src/main/resources/messages_ko.properties` · `messages_en.properties` hold the copy.

### Why the key is optional

`ApiException` keeps its original `(ErrorCode, String message)` constructor **and** gains
`(ErrorCode, String messageKey, Object[] args)`. `GlobalExceptionHandler` branches:

```java
String detail = ex.getMessageKey() != null
        ? messageSource.getMessage(ex.getMessageKey(), ex.getMessageArgs(), LocaleContextHolder.getLocale())
        : ex.getMessage();
```

A half-migrated codebase therefore compiles and passes. An unmigrated exception simply keeps
returning its English literal. Do not "tidy this up" by removing the string constructor until every
throw site is converted — the escape hatch is load-bearing (see *Deliberately not localized*).

---

## Adding a new localized error

1. Throw with a key, not a sentence:

   ```java
   throw BadRequestException.of("session.cidr.invalid", cidr);
   ```

   Factories: `BadRequestException.of(String messageKey, Object... args)` and
   `ConflictException.of(...)`.

2. Add the key to **both** bundles. The key set must stay identical — a key present in one file only
   is a latent production bug.

   ```properties
   # messages_en.properties
   session.cidr.invalid=invalid CIDR: {0}
   # messages_ko.properties
   session.cidr.invalid=유효하지 않은 CIDR: {0}
   ```

3. Key naming: `<module>.<subject>.<problem>`, lowercase dotted. Existing prefixes:
   `user` `saml` `session` `resource` `auth` `onboarding` `admin` `authpolicy` `organization`
   `crypto` `portal` `slug`.

4. Korean copy is imperative and concise. Say what went wrong and, where the English did, how to fix
   it. No 존댓말 종결 on short fragments.

### Traps

- **`MessageFormat` eats a lone apostrophe.** In any value that takes arguments, an apostrophe must
  be doubled: `group ''{0}'' is privileged`. In a value with **no** arguments it must not be. This
  bites silently — the apostrophe just disappears.
- `useCodeAsDefaultMessage(true)`: a missing key renders as the key itself instead of throwing. Good
  for a partial migration, bad for silence — grep for a dotted key leaking into a response.
- `fallbackToSystemLocale(false)`: without it an `en` request on a Korean-locale JVM picks up `ko`.

---

## Locale resolution

`AcceptHeaderLocaleResolver`, supported `[ko, en]`, **default `en`**.

The default is English on purpose. A caller that omits `Accept-Language` is a machine — SCIM
provisioners, `curl`, the `scripts/` live-flow verifiers — and handing it a Hangul `detail` breaks
integrations that never asked for one. `scripts/network_zone_flow.py:47` asserts
`"invalid CIDR" in bad.text`; it passes precisely because the header-less default is English.

The SPA is expected to send `Accept-Language` explicitly. **That change has not landed yet** (it is
Phase 4 of the frontend rollout: `api.ts#send()` gains the header). Until it does, the console shows
English error detail — the status quo, not a regression.

---

## `traceId`

Every `ProblemDetail` now carries `traceId`: 12 hex chars, `ThreadLocalRandom`, no tracing
dependency. It is logged at DEBUG alongside `code` and `status`.

- **The detail is never logged.** It can echo user-supplied input, which may be PII. This project has
  been burned by that before.
- **No `@ExceptionHandler(Exception.class)` catch-all was added**, even though the frontend design
  asks for a trace ID on 5xx. This app relies on method security: a catch-all would intercept
  `AccessDeniedException` and framework 4xx and turn them into 500s, silently regressing the
  authorization mapping. Unhandled 5xx stays on Boot's default path.

---

## Deliberately not localized

| What | Why |
|---|---|
| `NotFoundException`, `ForbiddenException`, `UnauthorizedException` | The SPA replaces their `detail` with generic client-side copy (`errorMessage()` in `src/api.ts` ignores server detail for 401/403/404). Localizing them would be dead work — and a 403 must not disclose whether the object exists. |
| `scim/**` — `BadRequestException` / `ConflictException` | **These are not ours.** They come from `de.captaingoldfish.scim.sdk.common.exceptions` and render SCIM-shaped errors that never touch our RFC-7807 handler. An earlier plan miscounted them as ours; converting them fails to compile. |
| `UserAdminService:126` `new ConflictException(e.getMessage())` | Wraps a caught `IllegalArgumentException`'s dynamic message. There is no fixed key to name. |

## Still English (known gaps, not yet scoped)

- `GlobalExceptionHandler:56` — the `"Validation failed"` fallback literal.
- `GlobalExceptionHandler:54` — `error.getField() + ": " + error.getDefaultMessage()`. The field name
  is an English identifier by definition. The **default message** comes from Hibernate Validator,
  which ships `ValidationMessages_ko.properties`; Spring's `LocalValidatorFactoryBean` *should* route
  the request locale through `LocaleContextMessageInterpolator`. **This was not verified.** To check:
  POST an invalid body with `Accept-Language: ko` and see whether `detail` reads `must not be blank`
  or `공백일 수 없습니다`.
- `GlobalExceptionHandler:61` — `handleIllegalArgument` passes `ex.getMessage()` straight through.
  One explicit throw site in `src/main/java`, plus anything the framework raises.

---

## Testing

`GlobalExceptionHandlerI18nTest` asserts that one exception renders Korean under `ko` and English
under `en`, argument interpolation included. It sets the locale via `LocaleContextHolder` directly,
so it is independent of the resolver's default.

**Gradle will lie to you.** `./gradlew test` reports `BUILD SUCCESSFUL` with `Task :test UP-TO-DATE`
when nothing ran. Use `./gradlew cleanTest test`, and confirm the count:

```bash
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
t=f=e=0
for p in glob.glob('build/test-results/test/*.xml'):
    r = ET.parse(p).getroot()
    t += int(r.get('tests',0)); f += int(r.get('failures',0)); e += int(r.get('errors',0))
print(f"tests={t} failures={f} errors={e}")
PY
```

Two existing tests were rewritten to assert on keys rather than English text:
`SessionPolicyServiceImplTest` (`invalid CIDR`) and `ReauthServiceTest`
(`not an allowed re-auth factor`).

---

## What the frontend expects from us

- `detail` — localized, human, rendered verbatim for 400/409.
- `code` — stable `ErrorCode` name, for branching.
- `traceId` — shown in the network/5xx failure panels.
- `instance` — the request URI.

See `sso-frontend/DESIGN.md` §5 (*Loading, empty, failure*) for how these surface.

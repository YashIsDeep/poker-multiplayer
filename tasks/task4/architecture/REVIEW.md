# T4 Architecture Review

## Review result

All findings from both review passes are addressed in the architecture and
test plan. The second pass validated the plan against the current repository
state (pom.xml, SecurityConfig, main/dev/test YAML, vite.config.ts); its
findings below were accepted by the user and folded back into `README.md` and
`TEST_PLAN.md`. The plan is implementation-ready and T4 moves to
`ARCHITECTURE_REVIEWED`.

## Second-pass findings (resolved)

### [RESOLVED P1] `spring-boot-starter-flyway` does not exist

README "Configuration and profiles" and implementation step 1 instruct adding
`spring-boot-starter-flyway`. Spring Boot ships no such starter; the correct
dependency is `org.flywaydb:flyway-core` (version managed by the Boot 3.5
parent; H2 support ships inside `flyway-core`, no `flyway-database-*` module is
needed). The "Dependency and repository fit" section also omits Flyway from the
new-dependency list even though it is the only new artifact T4 adds.

Resolution: the plan now names `org.flywaydb:flyway-core` (Boot-managed
version, H2 support in core) and the dependency-fit section lists it as the
single new artifact.

### [RESOLVED P2] CSRF token delivery to the SPA is unspecified

The plan keeps CSRF enabled and defines logout as `POST /logout`, and the test
plan asserts "`POST /logout` requires CSRF" — but nothing states how the React
client obtains and submits the token. Server-side tests can use the
`csrf()` MockMvc post-processor, so the tests pass while no real client can
log out. Either (a) specify the SPA mechanism now
(`CookieCsrfTokenRepository.withHttpOnlyFalse()` with the client reading
`XSRF-TOKEN`, plus the Spring Security 6 `XorCsrfTokenRequestAttributeHandler`
single-page-app caveat), or (b) explicitly scope client-visible logout out of
T4 and record that the token-repository decision lands with the first client
mutation (T5 STOMP CONNECT). The acceptance criteria currently promise logout
behavior that only MockMvc can exercise.

Resolution: option (b) adopted — client-visible logout is explicitly out of T4
scope, tests use the `csrf()` post-processor, and the token-repository
decision (`CookieCsrfTokenRepository` + SPA handler caveat) is recorded as a
T5 dependency because STOMP CONNECT protection builds on it.

### [RESOLVED P2] Loopback binding conflicts with the Vite proxy's `localhost` target

`application-dev.yml` gains `server.address: 127.0.0.1`, but every
`vite.config.ts` proxy entry targets `http://localhost:8080`. The repository
already documents the Windows IPv4/IPv6 hazard in the opposite direction
(Vite binds `127.0.0.1` explicitly because `localhost` can resolve to `::1`
only). Node's proxy may likewise resolve `localhost` to `::1`, which a server
bound only to `127.0.0.1` does not answer, producing intermittent proxy
failures by Node version. The same change must pin the proxy targets to
`http://127.0.0.1:8080`. The plan currently asserts proxy compatibility
("Vite's existing `changeOrigin: false` setting is therefore retained")
without this.

Resolution: the plan pins every proxy target to `http://127.0.0.1:8080` in the
same commit that adds `server.address: 127.0.0.1` (implementation step 8), and
the client test checklist verifies it.

### [RESOLVED P3] Test-classpath configuration is a required edit the plan never names

`server/src/test/resources/application.yml` shadows the main configuration (its
own header says so) and still sets `ddl-auto: update`; the plan's "Hibernate
uses `validate` in normal and test configurations" requires editing this file,
but it appears in neither the file layout nor the implementation sequence.
`SchemaMigrationTest` is likewise defined in TEST_PLAN.md but missing from
README's test file layout.

Resolution: the file layout now lists `server/src/test/resources/application.yml`
and `SchemaMigrationTest.java`; step 1 and a regression gate require `validate`
on the test classpath.

### [RESOLVED P3] Shared in-memory test database across Spring contexts

The test datasource is a single `jdbc:h2:mem:test;DB_CLOSE_DELAY=-1` shared by
every context in the test JVM. With Flyway plus several `@SpringBootTest`
contexts (security, dev-login, session persistence) and the two-thread
provisioning race test, user rows written by one context are visible to the
next, making assertions order-dependent. Require per-context database names or
explicit cleanup in the integration tests that count rows.

Resolution: the test strategy now mandates uniquely named per-context databases
(preferred) or explicit cleanup, and forbids order-dependent assertions.

### [RESOLVED P3] Remove the dead `poker.dev-login` flag

The flag exists in `application.yml`, `application-dev.yml`, and the test
configuration, is read by no Java code, and is superseded by `@Profile("dev")`.
Leaving it creates a second apparent source of truth for whether dev login is
enabled. The plan should name its removal.

Resolution: step 1 deletes the property from all three YAML files and a
regression gate asserts no YAML file still contains it.

### [RESOLVED P3] Non-dev response for `GET /auth/dev` is permitted-then-404, not denied

The filter chain permits `GET /auth/dev` unconditionally while the controller
exists only under the dev profile, so in non-dev profiles the anonymous
request reaches MVC and returns 404 — slightly contradicting "unmatched backend
routes are denied". Harmless, but either gate the permit rule by profile too or
state 404 as the expected non-dev status so `SecurityIntegrationTest` pins an
exact code rather than "unavailable".

Resolution: 404 through the permitted `/error` route is documented as the
defined non-dev contract, and the test now asserts exactly 404.

## Refinements applied

### Google and Discord use different protocol adapters

The initial task label says OAuth for both providers, but Google login is OIDC
when the `openid` scope is present. The plan now uses a Google
`OidcUserService` and a Discord `OAuth2UserService`, then converges both on
`CurrentUserPrincipal`. This avoids forcing Google through the wrong Spring
extension point.

### Dev startup is independent of provider credentials

OAuth registrations were separated into an explicit `oauth` profile. The
ordinary `dev` and test contexts therefore start without real secrets, while an
oauth deployment fails fast when required values are absent. `dev,oauth`
supports local end-to-end provider testing.

### Stable UUID is the only downstream identity

Provider IDs remain opaque strings in a composite unique key, while a generated
UUID becomes `Principal.getName()`. This keeps T5 independent of Google,
Discord, display names, and database entities. Email-based account linking was
removed from scope as an unnecessary security boundary.

### Session principal is deliberately small and token-free

The plan now converts the transient OAuth/OIDC authentication into a plain
`SessionUserPrincipal` in the success handler and discards the authorized
client, because the game does not call provider APIs after login. This prevents
provider tokens and raw payloads from entering the application principal, user
table, Spring Session, or `/api/me`. A serialization test and a real Spring
Session JDBC round trip are required because a principal that works in one
MockMvc request can still fail when JDBC session persistence serializes it.

### Dev login uses Spring Security persistence APIs

The exact GET route is retained as required, but it must run session-fixation
protection and save through `SecurityContextRepository`. Direct writes to the
internal session security-context key are explicitly prohibited.

### Existing client guest state is not authentication

The local-storage guest placeholder is replaced by navigation through the dev
login endpoint, and the lobby consumes `/api/me`. This prevents T5 from
accidentally treating a client-editable string as identity. The plan flags the
current T7 UI changes as concurrent work that must be preserved.

### CSRF remains enabled

The permissive placeholder currently disables CSRF globally. T4 restores the
default protection, uses POST logout, and leaves STOMP CONNECT protection for
T5. This avoids baking a broad security exception into the auth foundation.

## Dependency and repository fit

- Required starters already exist: Security, OAuth2 Client, Data JPA, Spring
  Session JDBC, H2, and Spring Security Test. The single new artifact is
  `org.flywaydb:flyway-core` (Boot-managed version; H2 support included).
- The current file-backed H2 and in-memory test datasource are compatible with
  the planned entity and session repository.
- The Vite proxy already forwards `/api`, `/auth`, `/oauth2`, `/login`, and
  `/logout`, and keeps `changeOrigin: false` for callback URL generation.
- The current placeholder `SecurityConfig` is the intended replacement seam.
- The pure `com.poker.engine` packages stay dependency-free and unchanged.

## Risks retained for implementation verification

- The OAuth success handler must explicitly overwrite the initially saved
  protocol authentication with the token-free application authentication; the
  session test verifies the final serialized value.
- Flyway, Spring Session schema initialization, and Hibernate validation startup
  ordering must be covered by migration, context, and session tests.
- A unique-key collision retry must cross a clean transaction boundary after an
  insert failure.
- OAuth redirect behavior must be checked through the Vite proxy so the browser
  returns to port 5173 rather than exposing the backend origin.
- UI edits overlap with uncommitted T7 work and require a narrow patch during
  implementation.

These risks remain implementation checkpoints after the architecture findings
below were resolved.

## Final decision record

- Table: Flyway V1-managed `app_user` with named unique
  `(provider, provider_subject)`; existing H2 schemas baseline at version 0 and
  Hibernate validates.
- App identity: generated UUID.
- Account linking: excluded.
- Google: OIDC `sub`; Discord: OAuth2 string `id`; dev: normalized name.
- Session: Spring Session JDBC with a serializable token-free app principal;
  provider authorized clients are discarded after UserInfo provisioning.
- API: authenticated `GET /api/me`, allow-list response only.
- Dev route: `@Profile("dev")`, loopback-only, no-store, fixation-safe,
  repository-persisted context.
- Provider secrets: environment variables under explicit `oauth` profile.
- Security: public health/meta/login endpoints, authenticated `/api/**` and
  `/ws/**`, API 401 responses, CSRF enabled, POST logout.
- Client authority: server session only; never local storage.
- Provisioning collision: non-transactional coordinator plus separately proxied
  `REQUIRES_NEW` write/recovery operations and one bounded retry.
- Input policy: deterministic NFC/`Locale.ROOT` display normalization, opaque
  bounded subjects, and optional HTTPS-only avatars.

## Resolved review findings

### [RESOLVED P1] Define an executable transaction boundary for duplicate-login recovery

`UserProvisioningService.provision()` is described as transactional while also
catching a unique-key insert failure and reloading the winner in a new
transaction. After a JPA flush/constraint failure, the current transaction can
be rollback-only and its persistence context is not safe for the recovery
query. Merely calling another `@Transactional` method on the same object also
bypasses Spring's proxy, so the promised behavior is easy to implement
incorrectly.

Revise the package/file design and sequence to name the mechanism: for example,
an outer non-transactional coordinator plus a separate proxied collaborator for
the create attempt and a bounded `REQUIRES_NEW` reload, or explicit
`TransactionTemplate` scopes. Specify where `flush()` occurs, which exact
constraint is recognized, and that unrelated integrity failures are rethrown.
Add a real two-transaction concurrency integration test; the currently planned
mocked/simulated race cannot validate rollback-only and persistence-context
behavior.

Resolution: the plan now names a non-transactional coordinator, a separate
proxied transaction bean, `saveAndFlush()`, the named constraint, independent
`REQUIRES_NEW` scopes, one bounded recovery, and a real two-thread integration
test.

### [RESOLVED P1] Replace Hibernate `ddl-auto: update` as the persisted schema contract

T4 introduces the first durable application identity table and a security-
critical unique constraint, but the plan leaves the file-backed database on
best-effort Hibernate schema mutation. That gives no reviewed, versioned path
for existing databases and no deterministic rollback/upgrade history.

Choose one explicit application-schema mechanism (preferably a Flyway migration
for `app_user`), set Hibernate to `validate` outside disposable tests, and state
how the existing H2 file is baselined. Keep Spring Session's framework-owned
schema initialization decision separate and document its startup ordering.
Repository tests should execute the same application migration rather than
having Hibernate invent a test-only schema.

Resolution: Flyway owns `app_user` through V1, existing nonempty H2 schemas
baseline at version 0, Hibernate uses `validate`, and migration tests cover both
fresh and existing schemas. Spring Session retains ownership only of its own
framework tables.

### [RESOLVED P1] Constrain the dev-login trust boundary, not only bean creation

`@Profile("dev")` prevents the route from existing in other profiles, but the
current backend binds port 8080 on all interfaces. If the normal `npm run dev`
process is reachable from the LAN, `/auth/dev?u=...` is an unauthenticated
account-creation/login endpoint. It also changes state through GET, a method
that browsers, crawlers, and prefetchers are allowed to treat as safe.

Because the task contract requires this GET route, document the exception and
add compensating controls: bind the server to loopback in the dev profile,
return `Cache-Control: no-store`, and ensure the client only renders the dev
control from an explicit build-time development flag. Add a test for the
profile's loopback binding/configuration. If remote dev access is required,
replace the route with a CSRF-protected POST rather than weakening the boundary.

Resolution: the required GET is documented as a dev-only protocol exception;
the dev server binds to `127.0.0.1`, responses use `Cache-Control: no-store`,
and the client control is gated directly by `import.meta.env.DEV`.

### [RESOLVED P1] Specify the controlled failure path for an unexpected principal

The plan promises HTTP 401 for an authenticated principal of the wrong type,
but `@AuthenticationPrincipal CurrentUserPrincipal` alone does not define that
behavior. A type mismatch can reach MVC argument invocation and become a 500,
depending on how it is resolved.

Specify a concrete fail-closed implementation, such as accepting
`Authentication`, checking `principal instanceof CurrentUserPrincipal`, and
returning/throwing through a tested 401 handler without including `toString()`.
The integration test must exercise the production controller and exception
mapping, not a mocked controller shortcut.

Resolution: `MeController` now accepts `Authentication`, guards both the
authenticated flag and principal type, and returns an empty 401 directly. Tests
exercise the production controller for both invalid shapes.

### [RESOLVED P2] Make provider-input validation match the database constraints

The schema caps `provider_subject` at 255 characters and `avatar_url` at 1024,
but mapping currently validates only subject presence/type and does not define
an avatar policy. A syntactically valid provider response can therefore fail as
a database truncation error, and a non-HTTPS or malformed avatar value can be
persisted and later rendered by the client.

Define validation at the `ExternalIdentity` boundary: subject maximum length,
avatar URI parsing, accepted scheme/host policy, overlength behavior, and
control-character rejection. Provider subjects must remain opaque—validation
must not parse or case-fold them. Prefer dropping an invalid optional avatar to
blocking login, while rejecting an invalid required subject before opening a
transaction.

Resolution: the plan defines provider-neutral subject bounds and forbidden
characters, protocol-specific Discord ID validation, and a strict optional
HTTPS avatar policy that drops invalid avatars without blocking login.

### [RESOLVED P2] Make display-name canonicalization deterministic for all Unicode input

The policy says “whitespace,” “control characters,” lowercase DEV subjects, and
final-six-character fallback without defining Unicode normalization, locale,
or code-point behavior for each operation. Implementations based on default
locale lowercasing or UTF-16 substring can produce environment-dependent IDs or
split surrogate pairs; format/bidirectional controls are not all ISO control
characters.

Specify `Locale.ROOT`, a normalization form, code-point-safe truncation and
suffix extraction, the exact whitespace definition, and treatment of Unicode
format/bidi characters. Add Turkish-locale, combining-character, bidi-control,
non-BMP, and fallback-suffix tests.

Resolution: NFC, `Locale.ROOT`, exact whitespace/category rules, and code-point-
safe truncation/suffix behavior are specified with locale, combining-character,
bidi, and non-BMP tests.

### [RESOLVED P2] Test session contents structurally before scanning serialized bytes

Searching JDBC blobs for token strings is a brittle negative assertion and is
not a complete proof that forbidden object types were excluded. Make the main
test deserialize the stored `SecurityContext` and recursively assert the
allowed authentication/principal shape and absence of OAuth/OIDC/authorized-
client types. Retain byte-string checks only as supplementary leakage tests.

Resolution: structural deserialization and an allow-listed reachable graph are
now the primary assertions; raw byte scanning is explicitly supplementary.

### Review completion gate

Every finding from both review passes is now reflected in `README.md` and
`TEST_PLAN.md`. The user accepted the second-pass findings and their fixes on
2026-07-12; `TASKS.md` moves to `ARCHITECTURE_REVIEWED`.

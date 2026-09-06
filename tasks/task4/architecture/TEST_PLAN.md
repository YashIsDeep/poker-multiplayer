# T4 Test Plan — Authentication and Users

## Test strategy

Tests are layered so provider mapping and display rules run without network or
Spring, persistence rules run against H2, and only session/security behavior
boots the full application. No automated test contacts Google or Discord and no
real client secret is required.

The shared test-classpath datasource (`jdbc:h2:mem:test;DB_CLOSE_DELAY=-1`)
lives for the whole test JVM, so rows written by one Spring context are visible
to the next. Every integration test that counts or asserts absence of
`app_user` or session rows must either use a uniquely named per-context
database (preferred, via `@TestPropertySource`/`@DynamicPropertySource`) or
clean the tables it asserts on. Assertions must not depend on test-class
execution order.

The full suite must run with the existing root command:

```powershell
npm.cmd test
```

Client integration also requires:

```powershell
npm.cmd --prefix client run build
```

## Display-name policy

`DisplayNamePolicyTest` covers:

- leading/trailing trim and internal whitespace collapse;
- ordinary ASCII and Unicode names;
- Unicode code-point length rather than UTF-16 code-unit length;
- NFC normalization before and after case conversion;
- whitespace defined by `Character.isWhitespace` plus `Character.isSpaceChar`;
- empty, whitespace-only, control, format, bidi-control, line-separator, and
  paragraph-separator rejection;
- dev rejection above 20 code points;
- provider truncation above 20 code points without splitting a surrogate pair;
- deterministic code-point-safe fallback suffix from a non-BMP subject;
- case-preserving display value versus `Locale.ROOT` lowercase DEV subject;
- Turkish default-locale behavior remains identical to every other locale; and
- composed and decomposed combining-character inputs canonicalize identically.

## Persistence and provisioning

`SchemaMigrationTest` starts with both an empty H2 schema and a representative
pre-T4 H2 schema containing only Spring Session tables. It verifies Flyway
baselines a nonempty existing schema at version 0, executes V1 in both cases,
creates the named `uk_app_user_provider_subject` constraint exactly once, and
allows Hibernate `validate` to start without schema mutation. A second startup
is idempotent. Tests fail if Hibernate is configured with `update`, `create`, or
`create-drop` outside a deliberately isolated disposable-test profile.

`UserAccountRepositoryTest` uses `@DataJpaTest` with the in-memory test
datasource and the production Flyway migration:

- generated UUID is present after insert;
- provider and provider subject round-trip unchanged;
- nullable avatar is accepted;
- `(provider, provider_subject)` rejects duplicates;
- the same subject under different providers is allowed; and
- provider-subject lookup does not use display name.

`UserProvisioningServiceTest` uses a fixed `Clock` and verifies:

- first login creates exactly one row with equal creation/login instants;
- repeat login returns the same UUID;
- repeat login updates display name, avatar, and `lastLoginAt` only;
- Google and Discord accounts with the same display name stay distinct;
- DEV names differing only by normalized case resolve to one account;
- malformed `ExternalIdentity` causes no write; and
- an unrelated integrity exception is rethrown without entering collision
  recovery.

`UserProvisioningConcurrencyTest` uses the real H2 datasource, two executor
threads, two independent transactions, and latches/barriers around the first
insert. Both calls use the same provider key and start concurrently. It verifies
one row and one UUID result, forces the losing `saveAndFlush()` path, confirms
the named constraint is the only caught failure, and confirms recovery executes
once in a clean `REQUIRES_NEW` transaction. A failed recovery lookup rethrows
the original exception; there is no loop or second retry.

## Provider mapping and principal tests

`OAuthIdentityMappingTest` supplies local claim maps and mocked delegate user
services. It does not invoke remote endpoints.

Google cases:

- `sub`, `name`, and `picture` map to GOOGLE;
- `sub` is the only identity key even when email changes;
- missing/blank/non-string `sub` fails authentication before provisioning;
- over-255-code-point or control-containing `sub` fails before provisioning;
- a valid `sub` is never trimmed, case-folded, or parsed;
- malformed, non-HTTPS, hostless, user-info-bearing, control-containing, and
  overlong `picture` values are discarded to null without blocking login;
- overlong provider name is safely truncated;
- provisioned OIDC adapter carries the expected application UUID; and
- roles contain only `ROLE_USER`.

Discord cases:

- `id`, `global_name`, `username`, and `avatar` map correctly;
- `global_name` wins over `username`;
- username is the fallback when global name is null;
- generated avatar URL contains the opaque string ID and hash;
- null avatar remains null;
- a snowflake larger than signed 64-bit range stays a string;
- missing/wrong-type/non-ASCII-digit/over-20-character `id` fails before
  provisioning;
- malformed avatar hashes cannot create an unsafe URI;
- registration IDs other than `discord` fail closed; and
- provisioned OAuth2 adapter carries the expected application UUID.

The success handler test starts with each protocol authentication and verifies
that the saved context contains `SessionUserPrincipal`, not an OAuth/OIDC
principal. `SessionUserPrincipal` is serialized and deserialized with standard
Java object streams. UUID, provider, display name, avatar, immutable authorities,
explicit serialization version, and `getName()` must survive; credentials are
absent. The deserialized reachable object graph contains no OAuth/OIDC principal,
authorized client, token, or provider attribute-map type. Synthetic access,
refresh, and ID token strings must also not appear in the serialized bytes as a
supplementary leakage assertion. The authorized client repository returns null
after every save attempt.

## Security filter-chain integration

`SecurityIntegrationTest` boots without `dev` or `oauth` profiles and uses
MockMvc:

- `/api/health` and `/api/meta` are public;
- anonymous `/api/me` returns 401 and does not redirect;
- anonymous `/api/unknown` returns 401;
- anonymous `/ws` handshake is rejected;
- an unmatched backend path is denied;
- `GET /auth/dev?u=alice` returns exactly 404 (permit rule static, controller
  bean absent outside the dev profile);
- unsafe requests without CSRF are rejected where applicable;
- `POST /logout` requires CSRF (exercised with the `csrf()` post-processor;
  client-visible logout is out of T4 scope per the architecture);
- authenticated logout invalidates the session and clears `SESSION`; and
- no default generated password/user authentication path is enabled.

An oauth-profile context test supplies dummy non-secret client IDs and verifies
both registrations bind, their redirect templates are correct, Google requests
`openid,profile`, Discord requests only `identify`, and Discord uses
`client_secret_basic`. It inspects registration metadata without beginning a
remote authorization request.

## Dev login integration

`DevLoginIntegrationTest` boots with `dev` and uses MockMvc plus the real
in-memory Spring Session JDBC repository:

- valid `/auth/dev?u=Alice` returns 302 `/`;
- the response includes `Cache-Control: no-store`;
- the effective dev configuration binds `server.address` to `127.0.0.1`;
- response creates/rotates a `SESSION` cookie;
- following `/api/me` with that cookie returns the stored UUID and DEV provider;
- `Alice` then `alice` resolves to one user row and the same UUID;
- missing, blank, control-character, and overlong names return 400;
- invalid names create neither an app user nor an authenticated session;
- an existing anonymous session is not reused unchanged after login; and
- a second valid dev login replaces identity safely rather than retaining the
  previous principal.

The test checks behavior through public HTTP/session contracts and does not set
`SPRING_SECURITY_CONTEXT` directly.

## `/api/me` contract

`MeControllerTest` invokes the production controller with an authenticated
`Authentication` carrying a `CurrentUserPrincipal` and asserts exact JSON:

```json
{
  "id": "91dfb7f8-01e6-4a28-8b64-650588c98f63",
  "displayName": "Alice",
  "avatarUrl": null,
  "provider": "DEV"
}
```

It additionally verifies:

- provider subject, email, authorities, session ID, and claims are absent;
- nullable and non-null avatar cases;
- content type is JSON;
- an anonymous call is 401; and
- an unexpected authenticated-principal type returns an empty 401 directly,
  without a cast, stack trace, or principal `toString()`; and
- an authentication object whose authenticated flag is false returns the same
  empty 401.

## Session persistence

`SessionPersistenceIntegrationTest` verifies the actual persistence boundary:

1. perform dev login;
2. assert a Spring Session row and serialized security-context attribute exist;
3. clear the thread-local security context;
4. call `/api/me` in a new request with only the `SESSION` cookie; and
5. assert the same application UUID is restored without another user write.

The test loads and deserializes the stored security-context attribute. It asserts
the exact allowed authentication and principal classes, null credentials, the
single `ROLE_USER` authority, and absence of OAuth/OIDC principals, authorized
clients, token objects, and provider maps from the reachable graph. Raw bytes
are then scanned only as a supplementary check for synthetic secret strings.
The test also advances the session repository time to verify expiry causes 401.
It must not print attribute bytes, cookie values, tokens, or session IDs in
assertion messages.

## Failure and privacy checks

Tests capture application logs for representative provider failures and assert
that synthetic access tokens, authorization codes, client secrets, and complete
attribute maps do not appear. OAuth error redirects expose only the stable
login-error marker, not remote error descriptions.

The `app_user` schema is inspected to confirm it has no token, email, claim,
cookie, or session columns. API serialization tests use an allow-list response
record rather than serializing the entity or protocol principal.

## Client checks

Client tests or build-time assertions cover:

- the Google and Discord links retain their standard endpoint paths;
- dev form submission performs a browser navigation with URL-encoded `u`;
- no `guestName` value is used as authenticated identity;
- lobby content is sourced from `/api/me`;
- a 401 on a guarded route sends the user to the sign-in page at `/login`, while the lobby at `/` renders its signed-out state;
- every Vite proxy target points at `http://127.0.0.1:8080`, matching the
  loopback-bound dev server; and
- production build does not display the Vite-development dev-login control.

Because T7 edits the same UI, verification is behavior-based and must not rely
on wholesale snapshots of `Landing.tsx` or `Lobby.tsx`.

## Regression gates

Before marking implementation complete:

- all T2 card/evaluator tests remain green;
- all T3 engine, scenario, architecture, and conservation tests remain green;
- `PokerApplicationTests.contextLoads` passes with no OAuth secrets;
- application context loads under `dev` without OAuth secrets;
- the empty-schema and existing-schema Flyway migration tests pass with
  Hibernate validation enabled;
- the test-classpath `application.yml` uses `ddl-auto: validate` and no YAML
  file still contains `poker.dev-login`;
- the real two-transaction provisioning race test passes repeatedly;
- oauth-profile binding passes with dummy test-only credentials;
- no production class under `com.poker.engine` imports Spring, JPA, auth, or db;
- root `npm.cmd test` passes; and
- client production build passes.

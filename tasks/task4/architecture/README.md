# T4 Code Architecture — Authentication and Users

## Goal

Add the authenticated-user boundary used by every later lobby, room, and game
operation. T4 supports Google OpenID Connect, Discord OAuth 2.0, and a local
development-only login. Every successful flow must:

1. map provider data to one provider-neutral identity;
2. upsert an `app_user` row in H2;
3. put a small, serializable application principal in Spring Security;
4. persist the security context through Spring Session JDBC; and
5. expose only the safe current-user view from `GET /api/me`.

The resulting `Authentication.getPrincipal()` is the identity contract that T5
will read during the STOMP handshake. Client-supplied names, user IDs, and
`localStorage` values are never identities.

## Scope boundary

T4 owns login, logout, user persistence, session persistence, and current-user
lookup. It does not own account linking, passwords, roles beyond `ROLE_USER`,
refresh-token storage, provider API calls after login, room membership, chips,
presence, or WebSocket authorization rules beyond reserving `/ws/**` for an
authenticated session.

One provider account maps to one poker account. Signing in through Google and
Discord creates two accounts even if both providers report the same email or
display name. Automatic linking by email is deliberately excluded because it
creates an account-takeover boundary and is not needed by the game.

## Package and file layout

```text
server/src/main/java/com/poker/db/user/
  UserAccount.java                 JPA entity mapped to app_user
  UserAccountRepository.java       lookup by provider + provider subject

server/src/main/java/com/poker/auth/
  AuthProvider.java                GOOGLE, DISCORD, DEV
  ExternalIdentity.java            validated provider-neutral login input
  DisplayNamePolicy.java           whitespace, control-char, and length rules
  UserProvisioningService.java     non-transactional provisioning coordinator
  UserProvisioningTransactions.java
                                    proxied new-transaction write/recovery steps
  CurrentUserPrincipal.java        shared stable-principal interface
  SessionUserPrincipal.java        small token-free serializable principal
  ProvisionedOAuth2User.java       transient Discord protocol adapter
  ProvisionedOidcUser.java         transient Google protocol adapter
  DiscordOAuth2UserService.java    delegate, map, upsert, wrap
  GoogleOidcUserService.java       delegate, map, upsert, wrap
  OAuthLoginSuccessHandler.java    replace protocol auth with session auth
  DiscardingAuthorizedClientRepository.java
                                    do not retain provider tokens after login
  DevLoginController.java          dev-profile GET /auth/dev
  SecurityConfig.java              common rules plus oauth/non-oauth chains

server/src/main/java/com/poker/api/
  MeController.java                authenticated GET /api/me
  MeResponse.java                  safe current-user response

server/src/main/resources/
  application.yml                  database, session, secure common defaults
  application-dev.yml              loopback-only local development flow
  application-oauth.yml            Google and Discord client registrations
  db/migration/V1__create_app_user.sql
                                    versioned application-user schema

server/src/test/resources/
  application.yml                  shadows main config; must also use validate

server/src/test/java/com/poker/db/user/
  SchemaMigrationTest.java
  UserAccountRepositoryTest.java

server/src/test/java/com/poker/auth/
  DisplayNamePolicyTest.java
  UserProvisioningServiceTest.java
  UserProvisioningConcurrencyTest.java
  OAuthIdentityMappingTest.java
  SecurityIntegrationTest.java
  DevLoginIntegrationTest.java
  SessionPersistenceIntegrationTest.java

server/src/test/java/com/poker/api/
  MeControllerTest.java
```

The package is `db.user`, rather than a broad `db`, so persistence classes do
not accumulate into an unrelated catch-all package. OAuth wire attributes stay
inside `auth`; neither controllers nor later room code inspect provider maps.

## Persisted user model

`UserAccount` maps to `app_user` (not `user`, which is a keyword in several SQL
dialects):

```text
id                  UUID primary key, generated once
provider            VARCHAR(16), enum stored by name
provider_subject    VARCHAR(255), immutable provider-owned identifier
display_name        VARCHAR(40), nonblank
avatar_url          VARCHAR(1024), nullable
created_at          TIMESTAMP WITH TIME ZONE, immutable
last_login_at       TIMESTAMP WITH TIME ZONE
```

There is a database unique constraint on `(provider, provider_subject)` and a
matching repository method:

```java
Optional<UserAccount> findByProviderAndProviderSubject(
    AuthProvider provider,
    String providerSubject
);
```

The UUID, never a provider subject or display name, is the application user ID.
Provider subjects remain strings: Google `sub` and Discord snowflakes are opaque
identifiers and must not be parsed into Java numeric types. No access token,
refresh token, authorization code, email, raw provider response, or session ID
is stored in `app_user`.

The constraint is named `uk_app_user_provider_subject` so collision recovery can
distinguish the one expected race from unrelated integrity failures. JPA uses
`GenerationType.UUID`. Equality is not used as a persistence lookup;
repository queries use the explicit provider key. `createdAt` is set only for a
new row. Every successful login updates `lastLoginAt`, display name, and avatar
to the latest normalized provider values without changing identity.

`app_user` is created by `db/migration/V1__create_app_user.sql`, managed by
Flyway. The existing H2 file may already contain Spring Session tables, so
Flyway is configured with `baseline-on-migrate: true` and `baseline-version: 0`;
this records an existing nonempty schema and still executes V1. Hibernate uses
`ddl-auto: validate` in normal and test configurations. Flyway owns application
tables; Spring Session's initializer owns only its framework tables.

## Display-name policy

The poker display name is presentation data, not identity. A shared policy:

- normalizes to Unicode NFC before and after other transformations;
- defines whitespace as `Character.isWhitespace(cp) || Character.isSpaceChar(cp)`,
  trims it, and collapses internal runs to one ASCII space;
- rejects empty strings and code points in Unicode `CONTROL`, `FORMAT`,
  `LINE_SEPARATOR`, or `PARAGRAPH_SEPARATOR` categories, including bidi and
  zero-width formatting controls;
- limits the result to 20 Unicode code points, matching the current client;
- rejects an overlong dev-supplied name with HTTP 400; and
- truncates an overlong provider-supplied name by code point, because a remote
  profile rename must not make an otherwise valid login impossible.

Google uses `name`; Discord uses `global_name`, then `username`. If a provider
omits every usable display value, the fallback is `Player-` plus the final six
Unicode code points of its subject. DEV subjects are derived from the normalized
display value with `toLowerCase(Locale.ROOT)` followed by NFC normalization.
Display names are not unique.

## Provider-neutral provisioning

Provider adapters construct this internal value only after validating required
attributes:

```java
public record ExternalIdentity(
    AuthProvider provider,
    String providerSubject,
    String displayName,
    String avatarUrl
) {
    // compact constructor enforces the provider-neutral invariants below
}
```

The compact constructor rejects a null provider, a blank subject, subjects over
255 Unicode code points, and subjects containing control/format/separator code
points. It never trims, case-folds, parses, or otherwise rewrites a provider
subject. Known adapters add their protocol checks: Google `sub` remains a
nonblank opaque string; Discord `id` must be 1-20 ASCII digits but is never
parsed as a number. DEV receives only the policy-derived subject.

An optional avatar is parsed as an absolute URI and retained only when it uses
HTTPS, has a nonblank host, contains no user-info or control characters, and is
at most 1024 characters. Invalid or overlong optional avatars become `null`
rather than preventing login. Discord avatar URLs are constructed only from a
validated ID and avatar hash. No server-side request is made to an avatar URI.

`UserProvisioningService.provision(ExternalIdentity)` is deliberately
non-transactional. It coordinates calls to a separate Spring-proxied
`UserProvisioningTransactions` bean:

1. `upsertInNewTransaction(identity, instant)` runs with `REQUIRES_NEW`, looks
   up the provider key, updates an existing row or inserts a new row, and calls
   `saveAndFlush()` so a uniqueness failure occurs before the transaction
   returns.
2. The coordinator returns immediately on success. It catches
   `DataIntegrityViolationException` only when the cause chain identifies the
   named `uk_app_user_provider_subject` constraint; every other database error
   is rethrown unchanged.
3. For that one race, `reloadAndRefreshInNewTransaction(identity, instant)` runs
   once with `REQUIRES_NEW`, reloads the committed winner, refreshes its mutable
   fields and login time, and returns it. If no winner is visible, the original
   exception is rethrown. There is no unbounded retry.

The separate bean is required so proxy-based transactions are not bypassed by
self-invocation. Each failed persistence context is discarded with its physical
transaction before recovery begins. The database constraint remains the final
defense against duplicate first logins.

The injected clock makes timestamp behavior deterministic in tests. Provider
mapping is kept out of the transaction so malformed remote data fails before a
database write begins.

## Stable application principal

Later code needs one provider-independent interface:

```java
public interface CurrentUserPrincipal extends Principal, Serializable {
    UUID userId();
    AuthProvider provider();
    String displayName();
    String avatarUrl();

    @Override
    default String getName() {
        return userId().toString();
    }
}
```

`SessionUserPrincipal` is the only principal allowed to remain in the persisted
security context. It contains UUID, provider, display name, avatar URL, and
`ROLE_USER`; it contains no provider claims or tokens. It declares an explicit
`serialVersionUID = 1L`, uses immutable fields/authorities, and never stores
credentials.

During the provider callback only, `ProvisionedOAuth2User` implements
`OAuth2User` for Discord and `ProvisionedOidcUser` implements `OidcUser` for
Google. Each carries the provisioned `SessionUserPrincipal` beside the protocol
delegate so Spring can finish validating the login. `OAuthLoginSuccessHandler`
then replaces the `OAuth2AuthenticationToken` with an authenticated
`UsernamePasswordAuthenticationToken` whose principal is the session value,
runs the normal context persistence path, and redirects to `/lobby`.

The configured `DiscardingAuthorizedClientRepository` accepts Spring's
post-login save callback but retains nothing; the app has no reason to call a
provider API after UserInfo is read. This prevents access/refresh tokens from
being serialized into Spring Session. The success handler explicitly saves the
replacement context because the OAuth filter initially authenticated the
protocol principal. `getName()` is always the application UUID, so HTTP,
session indexing, logs, and future STOMP identity agree.

## Google and Discord mapping

Google is OpenID Connect because the built-in registration requests `openid`.
`GoogleOidcUserService` delegates remote verification to `OidcUserService`,
requires a nonblank `sub`, reads `name` and `picture`, provisions `GOOGLE`, and
returns the transient `ProvisionedOidcUser`. It never treats email as identity.

Discord is plain OAuth 2.0. `DiscordOAuth2UserService` delegates the UserInfo
request to `DefaultOAuth2UserService`, accepts only registration ID `discord`,
requires string field `id`, maps the display and avatar fields, provisions
`DISCORD`, and returns the transient `ProvisionedOAuth2User`. The requested
scope is only `identify`.
The avatar URL is derived from the documented user ID plus avatar hash; it is
null when Discord returns no avatar hash.

An unknown registration ID, missing subject, malformed attribute type, or
provisioning failure becomes `OAuth2AuthenticationException` with a stable
internal error code. Provider payloads and tokens are not included in client
errors or logs.

## Configuration and profiles

Common `application.yml` keeps the file-backed H2 datasource and Spring Session
JDBC settings. Add `org.flywaydb:flyway-core` — the only new dependency; its
version is managed by the Boot parent and H2 support ships inside the core
artifact (there is no `spring-boot-starter-flyway`). Enable Flyway baseline
version 0 for the pre-existing H2 schema and set JPA to `ddl-auto: validate` in
**both** `application.yml` and the shadowing test-classpath
`server/src/test/resources/application.yml`, which otherwise silently retains
`update`. Flyway runs the versioned application migrations before JPA
validation. Spring Session's initializer remains enabled for H2 but owns only
its `SPRING_SESSION*` tables. The `poker.dev-login` property is dead
configuration superseded by the `dev` profile and is removed from all three
YAML files. The cookie is `HttpOnly`, `SameSite=Lax`, and `Secure` is controlled by
deployment configuration. The 30-day timeout is retained for this small private
game.

`application-dev.yml` additionally sets `server.address: 127.0.0.1`. The dev
profile is therefore intentionally local-only even if port 8080 is otherwise
allowed through the host firewall. Remote development must use real OAuth or a
future separately designed, CSRF-protected login flow; it must not relax this
binding.

Because the backend now answers only on IPv4 loopback, every `vite.config.ts`
proxy target changes from `http://localhost:8080` to `http://127.0.0.1:8080` in
the same commit. Node can resolve `localhost` to `::1`, which a
`127.0.0.1`-bound server does not answer — the same Windows IPv4/IPv6 hazard
already documented (in the opposite direction) by the Vite `host` comment.

Provider registrations live in `application-oauth.yml`, activated explicitly
with the `oauth` profile. This prevents a normal `dev` or test startup from
failing merely because real client secrets are absent.

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}
            scope: openid,profile
          discord:
            client-id: ${DISCORD_CLIENT_ID}
            client-secret: ${DISCORD_CLIENT_SECRET}
            client-authentication-method: client_secret_basic
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope: identify
            client-name: Discord
        provider:
          discord:
            authorization-uri: https://discord.com/oauth2/authorize
            token-uri: https://discord.com/api/oauth2/token
            user-info-uri: https://discord.com/api/users/@me
            user-name-attribute: id
```

Google uses Spring's built-in provider metadata. Discord supports HTTP Basic
client authentication for the authorization-code exchange, so its registration
explicitly selects `client_secret_basic` instead of relying on a framework
default.

Expected launch modes:

```text
dev                 local dev login, no provider secrets required
oauth               Google + Discord, production-like login
dev,oauth           all three flows for local provider testing
test/default         no provider registration and no dev endpoint
```

Production must activate `oauth` and supply all four environment variables.
Secrets are never committed or given defaults.

## HTTP and security contract

The security filter chain permits:

```text
GET /api/health
GET /api/meta
/oauth2/authorization/**
/login/oauth2/code/**
GET /auth/dev              route exists only under the dev profile
/error
```

It requires authentication for `/api/**` and reserves `/ws/**` for an
authenticated HTTP session. Unmatched backend routes are denied. An
unauthenticated API request returns HTTP 401 rather than an HTML login redirect;
OAuth browser entry points continue to redirect normally.

The `/auth/dev` permit rule is static while the controller bean is
profile-gated, so outside the `dev` profile an anonymous `GET /auth/dev`
reaches MVC with no handler and returns HTTP 404 through the permitted `/error`
route. That 404 is the defined non-dev contract — the route does not exist —
and tests pin the exact status rather than a vague "unavailable".

CSRF protection stays enabled. T4 adds no unsafe JSON mutation endpoint. Logout
is `POST /logout`, invalidates the HTTP session, clears the `SESSION` cookie,
and redirects to `/`. WebSocket CONNECT CSRF handling belongs to T5 and must not
be preempted by globally disabling CSRF here.

A client-visible logout control is explicitly out of T4 scope: the SPA has no
way to read the default session-bound CSRF token, so T4 exercises `POST
/logout` only through MockMvc's `csrf()` post-processor. The CSRF
token-delivery decision for the browser client
(`CookieCsrfTokenRepository.withHttpOnlyFalse()` plus the Spring Security 6
`XorCsrfTokenRequestAttributeHandler` single-page-app handling) is deferred to
T5, which introduces the first client-initiated mutation (STOMP CONNECT). T4
must not choose a token repository casually here, because T5's WebSocket
handshake protection depends on it.

OAuth success redirects to `/lobby`; failure redirects to `/?loginError` with
no provider error detail. Because the browser remains on the Vite origin while
the callback is proxied, relative redirects preserve the `localhost:5173`
origin. Vite's existing `changeOrigin: false` setting is therefore retained.

### `GET /api/me`

Authenticated response:

```json
{
  "id": "91dfb7f8-01e6-4a28-8b64-650588c98f63",
  "displayName": "Alice",
  "avatarUrl": "https://example.invalid/avatar.png",
  "provider": "GOOGLE"
}
```

`avatarUrl` is nullable. The response never exposes provider subject, email,
authorities, session ID, or tokens. The controller accepts Spring Security's
`Authentication`, checks both `isAuthenticated()` and
`principal instanceof CurrentUserPrincipal`, and only then constructs the
allow-listed response. A missing or unexpected principal returns an empty HTTP
401 response directly; it is never cast blindly, logged, or interpolated into
an exception. The controller does not re-query by a client-supplied ID.
Anonymous access is rejected by the filter chain before MVC with the same 401
contract.

## Development login

`DevLoginController` is guarded by `@Profile("dev")`, not merely by a mutable
request parameter. This GET endpoint is a deliberate development-only exception
to safe HTTP method semantics required by the task contract. Its exact contract
is:

```text
GET /auth/dev?u=alice
  -> validate and normalize alice
  -> provision (DEV, "alice", "alice", null)
  -> create ROLE_USER authentication with CurrentUserPrincipal
  -> run the session-fixation strategy
  -> explicitly save SecurityContext through SecurityContextRepository
  -> add Cache-Control: no-store
  -> 302 /lobby
```

The normalized lowercase name is the DEV provider subject, while the normalized
display casing is retained. Thus `Alice` and `alice` intentionally resolve to
the same local user. A missing or invalid `u` returns HTTP 400 without creating
a session or user. When the dev profile is absent, the controller bean and route
do not exist; setting a query parameter or request header cannot enable it. When
the profile is present, `server.address: 127.0.0.1` prevents LAN access.

Manual authentication must use Spring Security's context repository and
session-authentication strategy. Writing `SPRING_SECURITY_CONTEXT` directly to
the servlet session is prohibited because it bypasses fixation protection and
couples the controller to an internal key.

## Minimal client integration

The existing Google and Discord anchors already use Spring Security's standard
authorization paths and remain unchanged. The current guest implementation is
not trusted authentication and must be replaced:

- only when `import.meta.env.DEV` is true, the guest form navigates the browser to
  `/auth/dev?u=<encoded name>` instead of writing `guestName` to local storage;
- `Lobby.tsx` fetches `/api/me`, renders the server response, and returns to `/`
  on 401; and
- no client route decides whether a user is authenticated from local storage.

These are narrow behavioral edits. T4 implementation must preserve unrelated
T7 work already present in `Landing.tsx`, `Lobby.tsx`, and the styles rather
than replacing those files wholesale.

## Session and downstream contract

Spring Session JDBC persists Spring Security's context under the `SESSION`
cookie. The principal and authentication graph must pass a Java serialization
round-trip test. A subsequent request carrying the cookie must recover the same
UUID and pass `/api/me` without re-running provider provisioning.

The persisted attribute is inspected structurally: after deserialization the
authentication must be exactly the intended token type, its principal must be a
`SessionUserPrincipal`, credentials must be absent, and its only authority must
be `ROLE_USER`. OAuth/OIDC principals, authorized clients, provider claim maps,
and token objects are forbidden anywhere in the reachable stored graph. A raw
byte search for synthetic secrets is supplementary leakage detection, not the
primary proof.

T5 obtains identity from the authenticated handshake and stores the application
UUID as the room-level user key. It may use `Principal.getName()` for STOMP
destinations because that name is defined as the UUID. T5 must not cast to
`OidcUser`, inspect Discord attributes, or accept a user ID in a message as
authority.

## Failure and transaction behavior

- Invalid provider payload: OAuth login fails; no user or authenticated session.
- Database failure during provisioning: OAuth/dev login fails atomically.
- Duplicate first-login race: `saveAndFlush()` exposes the named constraint in
  one physical transaction; a separate `REQUIRES_NEW` operation reloads and
  refreshes the winner once.
- Invalid dev name: HTTP 400; no user and no session.
- Anonymous `/api/me`: HTTP 401 with no redirect.
- Authenticated principal of an unexpected type: HTTP 401/controlled failure,
  never a `ClassCastException` response body.
- Logout: session invalidated and its cookie removed.

Authentication failures must not log tokens, authorization codes, full provider
attribute maps, session IDs, or client secrets.

## Implementation sequence

1. Add `flyway-core`, baseline the existing H2 schema at version 0, create
   `app_user` in V1 with the named unique constraint, and switch Hibernate to
   validation in both the main and the shadowing test `application.yml`;
   delete the dead `poker.dev-login` property everywhere.
2. Add the entity, repository, provider enum, deterministic Unicode display
   policy, and validated `ExternalIdentity`; lock them with focused tests.
3. Add the non-transactional provisioning coordinator and separate
   `REQUIRES_NEW` transaction bean with an injected clock; verify create, repeat
   login, profile refresh, unrelated integrity failure propagation, and a real
   two-transaction collision.
4. Add the common principal plus Discord OAuth and Google OIDC wrappers/mappers;
   verify required claims and minimal stored attributes without network calls.
5. Replace the permissive placeholder filter chain, keeping a no-oauth startup
   path and an oauth-profile path.
6. Make the dev profile loopback-only and add the dev controller using the
   security context repository, session fixation strategy, and no-store cache
   policy.
7. Add `/api/me` with an explicit principal type guard, logout behavior, and API
   401 handling.
8. Replace client `localStorage` guest identity with the dev endpoint, make
   the lobby consume `/api/me`, and pin the Vite proxy targets to
   `http://127.0.0.1:8080`, carefully preserving concurrent T7 edits.
9. Add oauth profile configuration and README setup instructions with the exact
   Google and Discord redirect URIs.
10. Run focused auth tests, Java serialization/session tests, then the full root
   `npm test` suite and client build.

## Acceptance criteria

- Google and Discord each provision a user using their immutable provider ID.
- Repeat login updates profile fields but preserves the application UUID.
- Provider identities are unique and email/display names never link accounts.
- The versioned V1 migration creates the reviewed schema, existing H2 databases
  baseline at version 0, and Hibernate validates rather than mutates it.
- A concurrent duplicate first login produces one user and recovers only from
  the named unique constraint across clean physical transactions.
- The dev route exists only with the `dev` profile and creates the same principal
  shape as OAuth; the dev server binds only to loopback and the response is not
  cacheable.
- `/api/me` returns exactly the safe application-user fields; anonymous and
  unexpected-principal calls receive an empty 401 rather than a 500.
- `/api/**` and `/ws/**` cannot trust anonymous or client-asserted identity.
- The security context survives a Spring Session JDBC cookie round trip.
- No provider token, secret, raw attributes, or session identifier is persisted
  in `app_user`, Spring Session, or returned by application APIs.
- Dev and test startup do not need OAuth credentials; oauth startup fails fast
  when required environment variables are missing.
- CSRF is not globally disabled, logout invalidates the session (exercised via
  MockMvc; a client logout control is deferred with the T5 CSRF token-delivery
  decision), and login rotates/replaces the session identifier as appropriate.
- The existing Vite proxy preserves provider callback and redirect behavior.
- T4 tests, all existing engine tests, and the client production build pass.

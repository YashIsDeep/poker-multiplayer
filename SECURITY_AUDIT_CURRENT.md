# Repository security audit — current working tree, 10 September 2026

**Result: one confirmed medium-severity finding (P2). No high- or critical-severity issue was confirmed in the reviewed scope.**

Reviewed the working tree based on commit `cfd3b9d62a20acbc176c8b2461f603b52016ea60`, including the existing uncommitted security changes. Application source, dependency versions, and earlier audit reports were not changed by this review. This report and an isolated reproduction were added.

**1. [P2 / Medium] Unauthenticated OAuth callbacks still allocate persistent database sessions**

Location: [SecurityConfig.java:144](C:/code/poker-multiplayer/server/src/main/java/com/poker/auth/SecurityConfig.java:144). Session retention: [application.yml:21](C:/code/poker-multiplayer/server/src/main/resources/application.yml:21).

When OAuth is configured, the application installs `SimpleUrlAuthenticationFailureHandler("/login?loginError")` with session creation enabled by default. A request to `/login/oauth2/code/{anything}` that has no authorization request or valid OAuth response reaches this handler. It creates a session to store `SPRING_SECURITY_LAST_EXCEPTION` before redirecting to the login page. Spring Security's [6.5.11 implementation](https://github.com/spring-projects/spring-security/blob/6.5.11/web/src/main/java/org/springframework/security/web/authentication/SimpleUrlAuthenticationFailureHandler.java) confirms this behavior.

An unauthenticated caller can repeatedly omit cookies and create JDBC session rows and serialized exception attributes without completing authentication, contacting a provider, or knowing a valid provider registration ID. These records use the application's 12-hour session lifetime. Neither `NullRequestCache` nor `SessionBoundCsrfTokenRepository` governs the failure handler's session creation, and the application budgets for STOMP commands and ranked reads do not cover this path. This leaves a database write/storage denial-of-service avenue on an OAuth deployment that exposes the callback route without effective request limits.

The local reproduction made 30 malformed callbacks. Every callback returned 302 and created one anonymous session and one exception attribute:

| Synthetic callback | Requests | New sessions | New exception attributes |
| --- | ---: | ---: | ---: |
| `/login/oauth2/code/audit` with no query parameters | 10 | 10 | 10 |
| `/login/oauth2/code/audit?code=synthetic&state=synthetic` | 10 | 10 | 10 |
| `/login/oauth2/code/unknown?error=access_denied&state=synthetic` | 10 | 10 | 10 |

All created sessions had `MAX_INACTIVE_INTERVAL=43200` and no authenticated principal. No resource exhaustion or production disruption was attempted; practical availability impact depends on traffic volume and deployment limits.

**Recommended remediation:** configure the failure handler with `setAllowSessionCreation(false)`, or use a redirect-only failure handler that does not persist exceptions. The SPA does not consume the stored exception. Add regressions with OAuth enabled for missing parameters, unknown registrations, invalid state, and missing/invalid/expired cookies, asserting that failed callbacks create no new session or attribute rows. Preserve normal OAuth state validation and successful login behavior.

Also bound legitimate anonymous session creation at `/oauth2/authorization/**`. Three initiation requests in the same probe created three additional authorization-state sessions with the same 12-hour timeout. This state is necessary for login, but needs request admission and an appropriate pre-authentication lifetime. Disabling exception-session creation alone does not bound this separate, intentional allocation path. Validate any edge limits in the intended deployment.

Reproduction: [OAuthBoundaryProbe.java](C:/code/poker-multiplayer/artifacts/security/audit-current-2026-09-10/OAuthBoundaryProbe.java), [PowerShell runner](C:/code/poker-multiplayer/artifacts/security/audit-current-2026-09-10/run-oauth-probe.ps1), and [recorded results](C:/code/poker-multiplayer/artifacts/security/audit-current-2026-09-10/oauth-probe.log). The runner uses the backend test classpath and copies dependency JARs into its ignored artifact directory to accommodate the local compiler sandbox. Run backend tests before using it.

The probe uses the production configuration directory, a random loopback port, Secure cookies, a synthetic OAuth registration, and a separate in-memory H2 database. Guest login, live administration, hand archives, and house-table seeding are disabled. Redirects are not followed. No real credentials, provider accounts, or existing application database were used. The server closes on completion.

**Earlier findings verified as addressed**

- The session allocation finding in `SECURITY_AUDIT_2026-09-10.md` is fixed for the tested rejected API requests. The current [session-bound CSRF repository](C:/code/poker-multiplayer/server/src/main/java/com/poker/auth/SessionBoundCsrfTokenRepository.java:74) prevents new token sessions. Forty-eight GET/POST/PUT/DELETE requests to `/api/me`, with absent or invalid cookies, produced the expected 401/403 responses and created zero sessions or attributes.
- [Vitest is now 4.1.11](C:/code/poker-multiplayer/client/package.json:30), which the [upstream advisory](https://github.com/vitest-dev/vitest/security/advisories/GHSA-82fw-gwwq-j7x9) identifies as patched. Dependency matching against today's saved advisory snapshot found no remaining matches in either npm lockfile or the resolved Java inventories.
- WebSocket validation closes transports after a failed expiry lookup, and ordered inbound processing is enabled. The associated configuration, expiry, and resource regression tests passed. This review did not repeat the earlier forced broker-ordering race experiment.

**Verification performed**

| Check | Result |
| --- | --- |
| Backend suite | 297 tests; zero failures, errors, or skips; all test reports refreshed during this review |
| Client unit suite | 115 tests across 21 files passed, using Vitest 4.1.11 |
| Launcher and analytics security suite | 5 tests passed, including hostile-archive Chromium rendering |
| Gitleaks history | 70 commits scanned; no detections |
| Gitleaks tracked/nonignored working files | No detections |
| OSV against the 10 September advisory snapshot | No matches: 122 server, 4 admin-console, 177 client, and 62 root package entries |
| Additional bounded HTTP probe | Confirmed the OAuth finding and verified the API session-allocation fix |

The first cached scan was clean. The scan was then repeated using the newer complete Maven/npm advisory databases already downloaded on **10 September 2026 at approximately 09:00 UTC**. Their SHA-256 hashes were verified against the saved receipts. Maven data was published at 04:05:13 UTC; npm data at 07:53:28 UTC. No new advisory download was performed in this review, and no source code, credentials, or dependency inventory was uploaded. The newer scan exited 0. [Scan report](C:/code/poker-multiplayer/artifacts/security/audit-current-2026-09-10/osv-current.json), [Maven receipt](C:/code/poker-multiplayer/artifacts/security/audit-2026-09-10/Maven-database-receipt.json), [npm receipt](C:/code/poker-multiplayer/artifacts/security/audit-2026-09-10/npm-database-receipt.json).

Java matching reused the resolved SBOMs, both newer than their POMs; it did not regenerate the dependency graphs. npm matching read the current lockfiles. Initial backend compilation encountered a sandbox path-access error; the subsequent suite refreshed all 297 test results successfully. The additional probe compiled and ran using workspace copies of its dependencies.

Manual inspection focused on HTTP/STOMP authorization, session and CSRF handling, provider identity and moderator checks, room membership and private-room discovery, card/history redaction, dealing randomness, socket and subscription quotas, command validation, SQL construction, browser rendering and redirects, live administration, analytics/archive handling, launchers, and CI configuration. No further actionable security issue was confirmed in those inspected paths.

Real Google/Discord login, public tunnels, HTTPS/reverse-proxy behavior, firewall and filesystem permissions, the full gameplay browser suite, and production load behavior were not tested. Secret scanning excluded ignored files such as `.env`. These results describe the reviewed code and local evidence, not a guarantee that a deployed service is secure. Probe files, logs, and copied dependencies remain under the git-ignored `artifacts/security/audit-current-2026-09-10/` directory.

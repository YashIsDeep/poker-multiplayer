# Repository security audit — 10 September 2026

**Result: one confirmed medium-severity application issue and one lower-priority development dependency advisory. No high- or critical-severity issue was confirmed in the reviewed scope.**

Reviewed the working tree based on commit `cfd3b9d62a20acbc176c8b2461f603b52016ea60`, including its existing uncommitted security changes. This audit did not modify application source or dependency versions. The earlier audit reports describe historical findings; this report records the current checks and remaining work.

**1. [P2 / Medium] Rejected anonymous requests still create durable sessions through CSRF handling**

Location: [SecurityConfig.java:59](C:/code/poker-multiplayer/server/src/main/java/com/poker/auth/SecurityConfig.java:59), used by the CSRF filter configuration at line 87. Retention is configured in [application.yml:21](C:/code/poker-multiplayer/server/src/main/resources/application.yml:21).

The new `NullRequestCache` prevents rejected anonymous GET requests from saving requests into sessions. However, `HttpSessionCsrfTokenRepository` can independently create a session when the CSRF filter materializes an expected token for an unsafe HTTP method. With no cookie or submitted CSRF token, anonymous POST, PUT and DELETE requests to `/api/me` return 403 and still save a CSRF token in a newly created JDBC session. These requests never reach an application controller or its admission controls. The framework's token persistence and validation sequence is documented in [Spring Security 6.5's CSRF reference](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html).

An unauthenticated caller can repeatedly omit cookies and allocate database rows retained for 12 hours. This creates a database storage and write-load denial-of-service avenue on a reachable application listener. Deployment request limits can reduce the impact, but their presence and effectiveness were not verified. No authentication bypass, data disclosure, or production resource exhaustion was demonstrated.

A bounded HTTP probe used the production configuration directory, a random loopback port, Secure cookies, and a separate in-memory H2 database. Guest authentication, live administration, hand archives and house-table seeding were disabled. No credentials or existing application data were used.

| Requests to `/api/me` | Responses | New session rows | New session attributes |
| --- | --- | ---: | ---: |
| 10 anonymous GETs | 10 × 401 | 0 | 0 |
| 10 anonymous POSTs | 10 × 403 | 10 | 10 |
| 10 anonymous PUTs | 10 × 403 | 10 | 10 |
| 10 anonymous DELETEs | 10 × 403 | 10 | 10 |

All 30 stored attributes were CSRF tokens, and all 30 sessions had `MAX_INACTIVE_INTERVAL=43200`. The existing regression in [SecurityIntegrationTest.java:49](C:/code/poker-multiplayer/server/src/test/java/com/poker/auth/SecurityIntegrationTest.java:49) tests GET requests only.

**Recommended fix:** reject requests that cannot pass authentication/CSRF checks without creating a new persistent session. Restrict session creation for CSRF bootstrap to the intended flows, or add an appropriate rejection boundary before CSRF token generation. Preserve CSRF checks for authenticated HTTP requests, guest login and STOMP CONNECT. Add regressions for unsafe methods with absent, invalid and stale cookies, asserting that rejected requests create no session or attribute rows. Legitimate anonymous session creation, such as OAuth initiation, also needs bounded admission.

Reproduction: [HttpBoundaryProbe.java](C:/code/poker-multiplayer/artifacts/security/audit-2026-09-10/HttpBoundaryProbe.java), [runner](C:/code/poker-multiplayer/artifacts/security/audit-2026-09-10/run-http-probe.ps1), and [HTTP evidence](C:/code/poker-multiplayer/artifacts/security/audit-2026-09-10/http-probe.log). Run the backend tests first to populate the dependency classpath, then run the probe runner from PowerShell. The probe closes its server on completion.

**2. [P3 / Low repository priority] Vitest 3.2.7 matches a moderate file-read advisory**

Locations: [client/package.json:30](C:/code/poker-multiplayer/client/package.json:30), [locked Vitest:2693](C:/code/poker-multiplayer/client/package-lock.json:2693), and [locked mocker:1668](C:/code/poker-multiplayer/client/package-lock.json:1668).

Fresh advisory matching identifies `vitest@3.2.7` and `@vitest/mocker@3.2.7` under **GHSA-82fw-gwwq-j7x9 / CVE-2026-84373**, upstream severity **Moderate, CVSS 5.9**. This is one advisory affecting two package entries. Exposed development servers using the standalone mocker/interceptor plugins can disclose local files through redirect mocks. The patched stable version is **4.1.11**; the advisory states that the 3.x line will not receive this fix. [Published advisory](https://github.com/advisories/GHSA-82fw-gwwq-j7x9).

Repository reachability review found Node-environment unit tests in [vitest.config.ts](C:/code/poker-multiplayer/client/vitest.config.ts:5), no browser-mode configuration, and no registration of the standalone mocker/interceptor plugins in application or development-server configuration. A remotely exploitable path was not established here. The lower repository priority reflects that configuration; it does not change the upstream severity.

**Recommended fix:** upgrade Vitest and its matching internal packages to a supported patched release, regenerate the client lockfile, and run the unit suite and build. The declared `^3.2.4` range cannot resolve the patched 4.x release. Do not expose the affected standalone plugin while the old version remains installed.

**Verification and coverage**

| Check | Result |
| --- | --- |
| Full backend suite | 297 tests passed; zero failures, errors or skips |
| Client unit suite | 115 tests across 21 files passed |
| Launcher and analytics security suite | 5 tests passed, including hostile-archive Chromium rendering |
| Gitleaks history | 70 commits scanned; no detected secrets |
| Gitleaks tracked/nonignored working files | No detected secrets |
| OSV, refreshed Maven database | 122 server and 4 admin-console packages; no matches |
| OSV, refreshed npm database | 62 root packages: no matches; 185 client packages: two affected entries under one advisory |
| New local HTTP boundary probe | Confirmed finding 1; GET control remained session-free |

The older cached advisory scan initially returned no findings. Both complete public advisory databases were then downloaded on **10 September 2026 at approximately 09:00 UTC**, and dependency matching was rerun locally with checksum-verified OSV-Scanner 2.5.1. The refreshed scan exited **1 because it found the Vitest advisory**. No repository source, credentials or dependency inventory was uploaded. Maven analysis reused the resolved SBOMs; both were newer than their unchanged POMs. npm analysis read the current lockfiles.

Database publication times: Maven **2026-09-10 04:05:13 UTC**; npm **2026-09-10 07:53:28 UTC**. Receipts include their SHA-256 hashes. Evidence: [scan report](C:/code/poker-multiplayer/artifacts/security/audit-2026-09-10/osv-current.json), [coverage](C:/code/poker-multiplayer/artifacts/security/audit-2026-09-10/coverage.json), [Maven receipt](C:/code/poker-multiplayer/artifacts/security/audit-2026-09-10/Maven-database-receipt.json), [npm receipt](C:/code/poker-multiplayer/artifacts/security/audit-2026-09-10/npm-database-receipt.json), and [source hashes](C:/code/poker-multiplayer/artifacts/security/audit-2026-09-10/source-hashes.json).

The previously reported failed-lookup WebSocket expiry issue now closes transports on lookup failure, and its regression passed. Inbound message ordering is enabled and its configuration regression passed. Anonymous GET request caching is fixed, as confirmed by both the existing regression and the new HTTP control. The POST/PUT/DELETE allocation path remains distinct from request caching.

Manual review covered authentication and moderator identity, session/CSRF behavior, HTTP and STOMP authorization, subscription and transport quotas, room membership, private-room discovery, card/history redaction, random dealing, SQL use, frontend rendering/redirects, local administration, analytics, archives, launchers and CI. Existing tests exercised these boundaries, including real WebSocket session expiry and heartbeat limits. No additional actionable issue was confirmed in those reviewed paths.

This was a source/configuration review with local tests and bounded probes. Real Google/Discord login, public tunnels, HTTPS/reverse-proxy behavior, firewall and filesystem permissions, full gameplay browser E2E, and production load behavior were not tested. Secret scanning excluded ignored files such as `.env`; a clean scan cannot establish that every possible secret is absent. All audit probes and logs are retained under the git-ignored `artifacts/security/audit-2026-09-10/` directory.

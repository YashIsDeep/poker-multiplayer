# Security audit follow-up — 9 September 2026

Reviewed the current working tree at base commit `cfd3b9d62a20acbc176c8b2461f603b52016ea60`, including its pre-existing uncommitted security changes. This review found three remaining medium-priority issues. No high- or critical-severity issue was confirmed in the reviewed scope. Application source and existing audit documents were not changed.

## 1. [P2 / Medium] One failed database lookup disables WebSocket expiry enforcement

Location: [WebSocketTransportRegistry.java:155](C:/code/poker-multiplayer/server/src/main/java/com/poker/ws/WebSocketTransportRegistry.java:155).

`validateSession` clears `monitor.scheduled` before calling `sessions.findById`. If that lookup throws, execution skips both transport closure and `scheduleValidation`. There is no retry or independent sweep of these monitors. A transient database failure at the scheduled validation deadline therefore leaves existing sockets without future expiry checks. A new connection for that HTTP session could restore scheduling, but a silent client does not need to open one.

An authenticated client that negotiated `heart-beat:0,0` can retain its private subscriptions after its backing session expires. Inbound session validation does not help a client that only receives messages. The JDBC session repository does not publish automatic session lifecycle events that would otherwise close the connection. [Spring Session JDBC documentation](https://docs.spring.io/spring-session/reference/3.5/api.html#api-jdbcindexedsessionrepository).

**Evidence:** A focused test of the production registry recorded one scheduled callback, no retry, and an open transport after a simulated lookup exception. A real local Spring/Tomcat/WebSocket probe then injected exactly one lookup failure into the expiry monitor while retaining the real JDBC repository for normal requests. After a three-second session expired, `/api/me` returned 401, but the original socket still received a synthetic private `/user/queue/room` publication. The database failure was deliberately injected; no actual database outage or production service was induced.

**Fix:** Close the affected transports when expiry cannot be validated, or suspend their access while performing bounded retries. Ensure every exit path leaves the monitor closed or scheduled. Add a regression covering one failed deadline lookup, later database recovery, and no private delivery after expiry.

## 2. [P2 / Medium] Subscription bookkeeping can diverge from the broker under message reordering

Locations: [SubscriptionOwnershipInterceptor.java:42](C:/code/poker-multiplayer/server/src/main/java/com/poker/ws/SubscriptionOwnershipInterceptor.java:42) and [WebSocketConfig.java:47](C:/code/poker-multiplayer/server/src/main/java/com/poker/ws/WebSocketConfig.java:47).

The ownership interceptor adds and removes subscription IDs synchronously in `preSend`, while Spring dispatches the accepted messages to broker handlers on an executor. The endpoint does not enable ordered receiving. Spring documents that handlers can process messages from the same client in a different order from their arrival. [Spring Framework 6.2 message ordering](https://docs.spring.io/spring-framework/reference/6.2/web/websocket/stomp/ordered-messages.html).

For `SUBSCRIBE A` followed by `UNSUBSCRIBE A`, the ownership map correctly ends empty. If the broker processes the unsubscribe first, it subsequently registers A and leaves it active. A new subscription B to that destination then passes the application's duplicate check. Repeating this interleaving can accumulate broker entries outside the ownership quota and multiply outbound publications.

**Evidence and limitation:** Sixty ordinary subscribe/unsubscribe pairs at eight lifecycle frames per second left zero tracked and zero broker subscriptions. A second test used an executor latch to force the permitted out-of-order scheduling on the real local broker. After that interleaving, the application tracked one subscription, the broker held two, and one lobby publication produced two messages on the socket. The socket stayed open. This confirms a concurrency defect and duplicate-destination limit bypass; it does not establish the frequency of that interleaving under ordinary load or a reliable external denial-of-service attack. No new destination authorization or cross-user data access was demonstrated.

**Fix:** Enable `registry.setPreserveReceiveOrder(true)` for the STOMP endpoint, or otherwise serialize per-socket lifecycle processing through broker completion. Add a broker-level regression that races unique subscribe/unsubscribe IDs, checks actual subscription counts, and verifies delivery counts after a single publication. The existing ownership-map and duplicate-close tests do not exercise this ordering case.

## 3. [P2 / Medium] Rejected anonymous API requests allocate durable database sessions

Location: [SecurityConfig.java:114](C:/code/poker-multiplayer/server/src/main/java/com/poker/auth/SecurityConfig.java:114), with the 12-hour session timeout in [application.yml](C:/code/poker-multiplayer/server/src/main/resources/application.yml).

Configuring an HTTP 401 entry point does not disable Spring Security's request cache. The chain leaves `HttpSessionRequestCache` enabled, so a fresh unauthenticated GET to a protected API can save its request in a newly created JDBC session before returning 401. An unauthenticated caller can repeatedly omit cookies and generate persisted sessions and request attributes without logging in. These allocations precede the application's ranked-read and authenticated WebSocket budgets and are retained until session cleanup.

**Evidence:** Ten independent anonymous requests to `/api/me?audit=<counter>` all returned 401 and created ten rows in `SPRING_SESSION`. The probe used the production configuration files, with a local listener and an isolated in-memory database. No large-scale disk exhaustion was attempted. Public exploit impact depends on deployment request limits; those limits are documented requirements but are not implemented or verified in this repository.

**Fix:** Use `NullRequestCache`, or exclude `/api/**` and `/ws/**` from request saving. The OAuth success handler already redirects to `/`, and the frontend preserves its room redirect separately. This change need not disable authenticated HTTP sessions or OAuth authorization-state storage. Also apply bounded admission to endpoints that legitimately create anonymous sessions, such as OAuth initiation. Verify that repeated anonymous API failures allocate no session rows. [Spring Security request-cache configuration](https://docs.spring.io/spring-security/reference/servlet/architecture.html).

## Verification and scope

| Check performed in this review | Result |
| --- | --- |
| Full backend suite | 292 tests; zero failures, errors, or skips |
| Client unit suite | 115 tests across 21 files passed |
| Launcher and analytics security tests | 5 passed, including the hostile-archive Chromium test |
| Offline OSV dependency scan | No findings across 122 server, 4 admin-console, 185 client, and 62 root package entries |
| Gitleaks history and working-file scans | No detections; 70 commits scanned |
| Additional boundary probes | Findings above reproduced, with the explicit failure/scheduling injection limits described above |

Dependency matching reused the existing resolved Maven SBOMs and cached Maven/npm advisory databases from 8 September 2026. The scan checked that each SBOM was not older than its POM. Advisory data was not refreshed, and this result is not a guarantee against newly published vulnerabilities. Secret scanning covered git history and tracked/nonignored working files; it excluded ignored local files such as `.env`.

Manual review covered authentication, session handling, local administration, HTTP/STOMP authorization, room membership, card/history redaction, resource controls, ranked reads, SQL construction, frontend rendering/redirects, analytics/archive handling, launchers, dependencies, and CI. Existing secure random dealing, parameterized SQL, per-viewer card redaction, explicit STOMP destination authorization, and local-admin restrictions were checked as part of that review.

The full browser gameplay suite, real Google/Discord login, HTTPS/proxy deployment, firewall rules, filesystem access controls, and production load behavior were not tested in this review. The two fault-injection probes characterize specific failure modes; they do not claim an attacker can directly control the database or thread scheduler.

Evidence is retained in [BoundaryProbe.java](C:/code/poker-multiplayer/artifacts/security/audit-followup-2026-09-09/BoundaryProbe.java), [observations.txt](C:/code/poker-multiplayer/artifacts/security/audit-followup-2026-09-09/observations.txt), and [run.ps1](C:/code/poker-multiplayer/artifacts/security/audit-followup-2026-09-09/run.ps1). The probe uses synthetic accounts and an ephemeral database, and shuts down its server on completion. Its directory is git-ignored. Run the backend tests before the probe so the runner can obtain the resolved dependency classpath.

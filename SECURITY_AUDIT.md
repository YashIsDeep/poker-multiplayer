# Repository security audit — 9 September 2026

Reviewed the current working tree, including the existing uncommitted security changes, against base commit `cfd3b9d62a20acbc176c8b2461f603b52016ea60`. Application source was not changed during this audit. Reproduction programs and logs are in the ignored `artifacts/security/audit-2026-09-09/` directory.

**Original result: three medium-priority findings and one low-priority supply-chain finding.** Two WebSocket findings were reproduced through a real local Spring/Tomcat/STOMP server. The analytics finding was verified at the HTTP boundary; a complete browser DNS-rebinding attack was not performed. No compromised dependency is alleged in the supply-chain finding.

## Remediation status — fixed 9 September 2026

All four findings are remediated in the current working tree:

1. Physical WebSocket transports are indexed by their HTTP session and scheduled for revalidation at the JDBC session deadline. A refreshed session reschedules the check; an expired/deleted session closes every associated transport before any later publication can use it.
2. STOMP heartbeats have a dedicated bounded token bucket ahead of `SessionRepositoryMessageInterceptor`; excess heartbeats close the transport. Defaults allow a burst of 20 and refill at two frames per second.
3. Analytics rejects missing, malformed, wrong-host, wrong-port, cross-origin, and cross-site API requests. Non-loopback binding fails startup unless an explicit host allowlist and HTTP Basic credentials are configured.
4. `localtunnel` is an exact, lockfile-covered runtime dependency and is invoked through its installed entry point. Its Axios dependency is overridden to a reviewed non-vulnerable release, and tunnel children receive only a small provider/runtime environment rather than application OAuth and database credentials.

Post-remediation verification: **292 backend tests**, **115 client tests**, and **5 launcher/analytics security tests** passed. The expiry regression uses a real server, disables client heartbeats, waits for JDBC expiry, checks transport closure and publishes a private post-expiry message. The heartbeat regression sends 250 individual heartbeat frames, verifies policy closure, and asserts bounded H2 session statements. Online `npm audit` reported zero vulnerabilities. The offline OSV scan reported no findings across 122 server, 4 admin-console, 185 client, and 62 root package entries; its advisory database was not refreshed during remediation.

## 1. [P2 / Medium] Expired sessions retain private WebSocket subscriptions

Location: [ActiveWebSocketSessionInterceptor.java:32](server/src/main/java/com/poker/ws/ActiveWebSocketSessionInterceptor.java#L32), with inbound-only registration in [WebSocketConfig.java](server/src/main/java/com/poker/ws/WebSocketConfig.java).

Session validity is checked only when a client sends a message. A client can negotiate `heart-beat:0,0`, subscribe while authenticated, and then stop sending frames. Once its HTTP session expires, the established subscription continues receiving server publications. This extends access to private room updates and chat beyond session expiry. It requires a previously authenticated connection; it does not let an anonymous client establish a new subscription.

The backing JDBC session repository does not publish expiration events, so Spring's event-based WebSocket closure does not solve this case. The explicit logout bridge covers logout, but there is no equivalent automatic expiry bridge. This matches [Spring Session's documented JDBC behavior](https://docs.spring.io/spring-session/reference/api.html#api-jdbcindexedsessionrepository).

**Reproduction:** the isolated probe connected and subscribed to `/user/queue/room`, expired the in-memory session, and ran `JdbcIndexedSessionRepository.cleanUpExpiredSessions()`. It verified that no session rows remained and `/api/me` returned **401**, then delivered a synthetic private message through `SimpMessagingTemplate`. The expired connection received it and remained open.

**Fix:** enforce expiry independently of inbound activity. Track sockets by HTTP session and revalidate/close them at the backing session's expiry, accounting for legitimate session refreshes. Ensure expired sessions cannot receive outbound private messages. Add a real-broker regression with client heartbeats disabled, JDBC expiry cleanup, and a post-expiry publication. Retain the existing logout tests.

## 2. [P2 / Medium] Heartbeats bypass abuse limits and trigger database work

Location: [InboundFrameBudgetInterceptor.java:78](server/src/main/java/com/poker/hardening/InboundFrameBudgetInterceptor.java#L78), followed by [ActiveWebSocketSessionInterceptor.java:41](server/src/main/java/com/poker/ws/ActiveWebSocketSessionInterceptor.java#L41).

The frame limiter handles application messages and selected lifecycle commands, but returns heartbeat messages before consuming a budget. Those heartbeats then reach `sessions.findById(...)`. An authenticated client can send repeated one-byte newline frames to cause unbounded session database reads and message-channel work, bypassing the advertised rate limits. Physical socket quotas do not bound the request rate on one socket.

**Reproduction:** with a frame burst of **2** and refill of **0.01/second**, one real socket sent **250** heartbeat frames. All **250** passed the inbound channel, H2 query statistics recorded **250 session SQL executions**, and the connection remained open. This proves the bypass and work amplification; production service degradation was not load-tested.

**Fix:** apply a bounded budget to heartbeats before session-repository access, with headroom for normal heartbeat traffic. Close connections that exceed it. Add a network regression that checks accepted frames, database work, and transport closure. Keep expiry enforcement independent so reducing heartbeat database reads does not worsen finding 1.

## 3. [P2 / Medium, conditional] Local analytics accepts arbitrary hostnames

Location: [analytics/server.mjs:218](analytics/server.mjs#L218), especially the unprotected `/api/hands` response at line 233.

The dashboard binds to loopback by default, but its request handler validates neither `Host` nor authentication before returning archives, including folded hole cards. In a browser/network environment that permits DNS rebinding to loopback, an attacker-controlled hostname can retain its browser origin while resolving to this service. The response CSP does not authorize API readers or protect an attacker page loaded earlier. Node's own inspector uses [Host validation as a DNS-rebinding defense](https://nodejs.org/learn/getting-started/debugging#browsers-websockets-and-same-origin-policy).

**Reproduction:** a separate analytics process using only synthetic archives returned **200** and the synthetic folded cards to `/api/hands` with `Host: audit-attacker.invalid:<port>` and the matching untrusted Origin. No real archives were read. This establishes the missing server-side boundary; browser local-network protections may block the complete attack in some environments.

**Fix:** reject unexpected hostnames before routing, using an explicit allowlist of intended loopback hosts and ports. Add Origin/Fetch Metadata checks as defense in depth. Require authentication for shared access. Add regressions for untrusted, absent, and malformed Host headers and all archive API routes.

## 4. [P3 / Low] Tunnel startup executes an unlocked package outside scan coverage

Location: [scripts/dev-proxy.mjs:42](scripts/dev-proxy.mjs#L42) and [scripts/dev-proxy.mjs:233](scripts/dev-proxy.mjs#L233).

The default tunnel launcher runs `npx --yes localtunnel` without a version or a dependency in either lockfile. On a fresh environment this can download and execute an unreviewed release and its dependency graph. Both development and production proxy launchers use this path. The process inherits the environment containing the OAuth credentials required by the launcher.

This is a reproducibility and supply-chain coverage gap, not evidence that localtunnel is malicious or currently vulnerable. The clean lockfile scans do not cover this dynamically installed tool.

**Fix:** declare and lock the tunnel package, install it through the normal reviewed dependency workflow, and execute the installed binary without permitting runtime installation. Include it in dependency scanning and pass only necessary environment variables to the tunnel process.

## Verification performed

| Check | Result |
| --- | --- |
| Backend auth, WebSocket, hardening, API, admin and ranked tests | 156 tests across 39 suites; zero failures, errors or skips |
| Client unit tests | 115 tests across 21 files passed |
| Launcher and analytics security tests | 4 passed, including Chromium hostile-archive rendering |
| OSV offline dependency scan | No findings across 122 server, 4 admin-console, 185 client and 20 root package entries |
| Gitleaks | No detections in 70 commits or the current tracked/nonignored working files |
| New bounded audit probes | Expired-subscription and heartbeat findings reproduced over real WebSockets; analytics hostname finding reproduced over HTTP |

Existing checks cover guest-login CSRF/session rotation, local-only authentication and administration, moderator policy, subscription ownership, transport quotas, logout, and card redaction. Their passing result does not cover the newly demonstrated cases.

The dependency scan reused resolved Maven SBOMs and cached advisory databases. The npm receipt records download on 8 September 2026; the prior dependency review dates the Maven snapshot to the same day. Advisory databases were **not refreshed** in this audit. Secret scanning excludes ignored local files such as `.env` and cannot prove the absence of every secret.

## Scope and limits

Manual review covered authentication and session configuration, HTTP/STOMP authorization, room membership and card redaction, rate/resource limits, SQL construction, client rendering and redirects, analytics/archive handling, launchers, dependency declarations and CI. Validation used ephemeral local services and synthetic data; no deployed service was attacked.

A real OAuth/HTTPS/proxy smoke test, the full gameplay browser suite, production load testing, and the previously documented unique-subscription-ID flood/broadcast-count regression were not run in this audit. Deployment firewall, filesystem permissions, provider registrations and actual edge limits were not verified.

Evidence: [Java probe](artifacts/security/audit-2026-09-09/AuditProbe.java), [Java results](artifacts/security/audit-2026-09-09/java-probe.log), [analytics probe](artifacts/security/audit-2026-09-09/analytics-host-probe.mjs), and [repeatable probe runner](artifacts/security/audit-2026-09-09/run-probes.ps1). The runner requires a completed backend test run to supply its resolved classpath.

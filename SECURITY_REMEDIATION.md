# Security remediation backlog

Each numbered item is assigned to a subagent. This file records implementation,
verification, and remaining deployment requirements; status is updated as work lands.

| # | Item | Owner | Status |
| --- | --- | --- | --- |
| 1 | Secure local authentication | auth_sessions (resumed local_auth) | Implemented; auth-mode, server, and browser regressions pass |
| 2 | Harden live administration | admin_cards_sockets (resumed live_admin) | Implemented; token, loopback, forwarding-spoof, and startup tests pass |
| 3 | Remove analytics stored XSS | analytics_ranked (resumed analytics_xss) | Implemented; Chromium regression passes |
| 4 | Prevent historical card disclosure after seat changes | admin_cards_sockets | Implemented; standing, leaving, busting, and vacated-seat tests pass |
| 5 | Bound WebSocket resource consumption | admin_cards_sockets | Implemented; quota close and cleanup tests pass; unique-subscription flood/broadcast-count regression remains |
| 6 | Secure production sessions | auth_sessions | Implemented; secure-cookie, lifetime, and startup validation tests pass |
| 7 | Bound ranked read work | analytics_ranked | Implemented; indexed SQL, bounded cache/budget, query-count, and dataset tests pass |
| 8 | Remove implicit moderator | auth_sessions | Implemented; empty-default and startup privilege tests pass |
| 9 | Dependency and supply-chain review | supply_chain | Implemented; npm audits, offline OSV, Gitleaks, pinned wrapper/actions, and CI workflow added |
| 10 | Final security regression and deployment documentation | browser_regression, root integration | Local suites exercised; deployment OAuth/HTTPS smoke test remains |

## Verification

- Backend suite: September 9 verification rerun passed 288 tests with zero failures or errors.
- Client suite: September 9 verification rerun passed 21 files and 115 tests; production build passed.
- Browser suite: September 9 verification rerun passed all 21 scenarios in 2.4 minutes and exited with code 0, including guest-auth, privacy, ranked, and logout flows. This run used approved execution outside the sandbox; the earlier sandboxed run stalled during Windows web-server teardown. Abuse limits are covered by the backend suite, subject to the item 5 coverage gap below.
- Analytics hostile-archive browser test: `node --test analytics/security.test.mjs` passed.
- Launcher tests: all 3 startup/tunnel policy tests passed (4 tests combined with analytics).
- Initial npm audits: root 2 high findings, client 5 high findings. Compatible
  fixes installed; both npm audit-fix runs report zero vulnerabilities.
- Gitleaks 8.30.1 initial history scan: 70 commits, no leaks found.
- Java scan uses a Maven-resolved CycloneDX SBOM; scanning the POM alone did not
  resolve Spring-managed dependencies and is not accepted as coverage.
- The first full server run exposed migrated fixture assumptions; anonymous GET
  rejection, duplicate ranked fixture IDs, reconnect cookies, and Windows
  temporary-directory cleanup were corrected before the final run.
- Final-phase secret scan: 70 commits and 479 nonignored source files clean.
- September 9 verification reran the offline dependency and secret scan successfully: no findings against the cached vulnerability databases and resolved SBOMs. This does not refresh the advisory databases.

## Deployment requirements

Verification review on September 9 found a remaining test-coverage gap in item 5:
the 5,000-iteration ownership test uses different socket IDs, not thousands of
unique subscription IDs on one socket. Existing network tests prove duplicate
subscription closure and unsubscribe-flood closure, but do not count broker
broadcast deliveries. A real-broker unique-ID flood and broadcast-count regression
is still required to satisfy that explicit backlog acceptance criterion.

See [SECURITY_DEPLOYMENT.md](SECURITY_DEPLOYMENT.md). Real Google/Discord login
and HTTPS/proxy configuration require a deployment smoke test; local regression
does not authenticate to the user's provider accounts.

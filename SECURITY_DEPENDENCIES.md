# Dependency and supply-chain review

Reviewed September 8–9, 2026. Scope: both npm lockfiles; resolved direct and transitive Maven dependencies in `server` and `admin-console`, including test dependencies; Maven bootstrap integrity; CI actions; and secrets in git history and current tracked/nonignored files.

## Findings and remediation

| Surface | Initial finding | Remediation and result |
| --- | --- | --- |
| Root npm tooling | npm audit reported 2 high-severity affected package entries (`concurrently`, `shell-quote`). | Lockfile remediation within declared ranges; subsequent npm audit: **0 vulnerabilities**. |
| Client npm dependencies | npm audit reported 5 high-severity affected package entries (`browserslist`, `nanoid`, `postcss`, `react-router`, `react-router-dom`). | Lockfile remediation within declared ranges; subsequent npm audit: **0 vulnerabilities**. |
| Server Java | Jackson Databind 2.21.4, Log4j API 2.24.3 and Tomcat 10.1.55 advisories. | Override the Spring Boot BOM with Jackson 2.21.5, Log4j 2.25.5 and Tomcat 10.1.59. All 122 resolved components scan clean. |
| Admin console Java | Jackson Core/Databind 2.18.2 advisories. | Jackson 2.18.9. All 4 resolved components scan clean. |
| Secrets | No detected secrets in the initial full-history or working-file scans. | Gitleaks scans repeated after changes; no findings. |

The initial Java inventory produced 16 package/advisory matches covering 13 unique advisories. All were patched; there are no suppression rules or accepted vulnerability exceptions. Jackson findings covered deserialization/property-validation bypasses, async parser denial of service and eager DNS resolution. Tomcat findings covered authentication and authorization. The Log4j finding concerned JSON encoding of non-finite numbers. Reachability was not used to waive a finding.

Tomcat 10.1.58 was not a released fix: its release vote failed. The chosen 10.1.59 release contains those fixes, as recorded in the [official Tomcat security advisories](https://tomcat.apache.org/security-10.html). The Jackson and Log4j findings and affected version ranges are retained in the initial OSV report. These explicit BOM overrides should be revisited when Spring Boot's managed versions include the fixes.

## Repeatable checks

On Windows with PowerShell 7, Git and JDK 21:

```powershell
npm run security:scan
pwsh -NoProfile -File scripts/security-scan.ps1 -Scope Dependencies
pwsh -NoProfile -File scripts/security-scan.ps1 -Scope Secrets
```

The dependency check resolves both Maven graphs with the pinned CycloneDX Maven plugin 2.9.2, then scans those SBOMs and both npm lockfiles using OSV-Scanner 2.5.1. Production, development and test dependencies are included. It downloads the complete public Maven and npm vulnerability databases and performs matching with `--offline`; dependency inventories are not uploaded to OSV. Coverage checks require all four inputs and every Maven component. Findings at any severity, missing inventory, download errors, checksum mismatches and scanner errors fail the check.

`-Offline` disables downloads and Maven resolution and validates previously generated SBOMs, cached tools/databases and the current npm lockfiles. It refuses an absent SBOM or one older than its POM. This mode is for rechecking the saved inventory; use the normal command to regenerate inventories after dependency or Maven configuration changes. Database age is visible in the saved receipts and is not refreshed in this mode.

The script uses checksum-pinned OSV and Gitleaks downloads. Gitleaks 8.30.1 scans all available git refs and a snapshot of the current tracked and nonignored untracked files, with findings redacted. It cannot prove absence of every possible secret. CI fetches full history; a shallow local clone covers only locally available history.

The Maven 3.9.16 distribution is pinned with `distributionSha256Sum`. Its ZIP was verified against Maven Central's published SHA-512 before deriving the SHA-256 pin. GitHub Actions use verified full commit SHAs, read-only repository permissions and disabled credential persistence. Weekly Dependabot checks cover npm, Maven and GitHub Actions. Scanner/plugin version and checksum updates require explicit maintenance in the script.

## Validation and evidence

The npm audit commands were run separately during remediation and returned zero findings for both lockfiles. The automated script uses OSV for npm as well as Java; it does not run npm audit.

The tunnel launcher is also covered by the root lockfile: `localtunnel` is pinned to
2.0.2, its transitive Axios is overridden to 1.20.0, and the post-remediation
online npm audit and offline OSV scan both reported zero findings.

The resolved patched Java SBOMs contain 126 components and scan clean. A control scan of the original vulnerable SBOMs against the same offline database reproduced all 16 initial matches, confirming the scanner was inspecting real resolved versions. Scanning raw Maven POMs had failed to resolve dependency-management versions; that incomplete attempt is not treated as clean evidence.

The offline Maven database snapshot was published September 8, 2026 at 05:36:12 UTC, SHA-256 `6773e90042e1c1118492e65e10804e1415d576316f2e60e263c86b03de4fe326`. The npm database was downloaded September 8 UTC (September 9 locally). The scan receipts record exact download times and database hashes. Results describe known advisories at those snapshots; scheduled CI downloads fresh data on each run.

Raw JSON evidence stays in the git-ignored `artifacts/security/` directory: `npm-*-initial.json`, `npm-*-audit.json`, `*-initial.cdx.json`, `*-final.cdx.json`, `osv-java-sbom-initial*.json`, `osv-java-sbom-final.json`, `osv-dependencies-final.json`, database receipts and `gitleaks-*-final.json`. The Security workflow runs on push, pull request, Monday schedule and manual dispatch, uploading reports for 14 days even when a check fails. The ordinary CI workflow also runs launcher/analytics security regressions and builds the server JAR before isolated browser tests.

The online combined scan was rejected by automatic approval review because it could transmit private dependency metadata. Local validation therefore used cached SBOMs and offline matching; it did not transmit inventories. CI configuration has been reviewed locally and will first execute in GitHub after these changes are pushed.

Primary tool references: [OSV offline scanning](https://google.github.io/osv-scanner/usage/offline-mode/), [OSV 2.5.1 release](https://github.com/google/osv-scanner/releases/tag/v2.5.1), [Gitleaks 8.30.1 release](https://github.com/gitleaks/gitleaks/releases/tag/v8.30.1), [CycloneDX Maven plugin](https://cyclonedx.github.io/cyclonedx-maven-plugin/).

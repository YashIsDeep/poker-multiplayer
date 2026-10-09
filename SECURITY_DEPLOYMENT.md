# Deployment security

## Authentication and HTTPS

Use OAuth for any shared or public deployment. The `dev` and `local-auth`
profiles permit guest login only on loopback, and must never be exposed through
a tunnel or reverse proxy. Guest display names are labels; signing in again with
the same name must not recover another guest's identity. Retain the existing
browser session to resume a guest account.

`npm run start` selects `oauth` by default. `OAUTH_ENABLED=false` selects the
loopback-only `local-auth` profile for local use. Both `dev` and `local-auth`
require a numeric loopback `SERVER_ADDRESS` and reject production profiles or
tunnel configuration. `npm run dev:proxy` and `npm run prod:proxy` require all
four provider credentials and an OAuth-only profile. Guest sign-in is a
CSRF-protected POST; the former GET URL does not authenticate a session.

Public OAuth deployments require HTTPS and Secure session cookies. Register the
exact public Google and Discord callback URLs, and keep provider credentials in
deployment secrets. Keep `.env`, session data, database backups, and hand archives
out of source control and public web roots.

Secure cookies are enabled by default. `SESSION_COOKIE_SECURE=false` and guest
moderator privileges are accepted only in strictly local development. The
application refuses to start when those exceptions are enabled outside its
local authentication profiles.

## Reverse proxies

Allow access to the Spring listener only from trusted reverse proxies. Strip
client-supplied `Forwarded` and `X-Forwarded-*` headers and set the original host
and protocol at the trusted edge. Forwarded headers are not authentication.
Configure an explicit WebSocket origin allowlist containing only the deployed
application origins, and support WebSocket upgrades and heartbeats.

When guest authentication or live administration is enabled, use `none` or
`framework` for `server.forward-headers-strategy` and leave Tomcat's
`server.tomcat.remoteip.remote-ip-header` and `protocol-header` overrides unset.
Those overrides install container forwarding before the raw-peer guard runs,
so startup rejects them for these local-only features.
Set `poker.websocket.allowed-origins` to the deployed HTTPS origins in the
application configuration or through `SPRING_APPLICATION_JSON`.

Do not proxy `/internal/admin/**` publicly. Apply edge request and connection
limits as well as the application limits, especially for authentication, public
statistics, and WebSocket upgrades.

The application permits four physical sockets per user and HTTP session, eight
subscriptions per socket, and one subscription per destination per socket.
STOMP lifecycle frames share a 10/second budget with a burst of 20. Configure
these under `poker.abuse`; exceeding a connection/subscription limit closes the
connection. Socket state is removed on disconnect. Bounded identity rate buckets
remain until their TTL expires so reconnecting cannot reset an abuse budget.

## Administration and moderation

Enable the live-admin API only when needed and configure a separate nonempty
admin token. Supply it in `X-Admin-Token`; do not put it in URLs or frontend
bundles. Admin endpoints contain private cards and require direct local access.
Use a local connection or SSH port forwarding, and keep the token confidential.
Set `POKER_ADMIN_LIVE_ENABLED=true` and `POKER_ADMIN_LIVE_TOKEN` together.

Configure `POKER_MODERATOR_EMAILS` explicitly using verified provider email
addresses. An empty allowlist grants no provider account moderator privileges.
Guest moderator privileges are for loopback development only.

## Analytics

The analytics dashboard and raw archives contain all dealt cards, including
mucked cards. It binds to loopback by default and rejects unexpected Host,
Origin, and Fetch Metadata values. A non-loopback `ANALYTICS_HOST` additionally
requires `ANALYTICS_ALLOWED_HOSTS`, `ANALYTICS_USERNAME`, and
`ANALYTICS_PASSWORD`; still place shared access behind a private network and TLS
terminator. A Content Security Policy and safe rendering do not make private
poker records suitable for public access. Restrict filesystem permissions and
define an archive retention policy for the deployment.

## Release verification

Run server tests, client unit tests and production build, browser gameplay and
security regressions, analytics hostile-archive tests, secret scanning, and npm
and Java vulnerability scanning before release. Review vulnerability reports by
production reachability rather than dismissing all transitive dependencies.

A real provider login and HTTPS/proxy check must be performed in the intended
deployment: authenticate with each configured provider, verify the Secure cookie,
refresh and reconnect, then log out and confirm the previous HTTP session and
WebSocket cannot continue accessing protected state. Automated mocked provider
tests do not establish provider registration or deployment configuration.

See [SECURITY_REMEDIATION.md](SECURITY_REMEDIATION.md) for implementation status,
executed verification, and any outstanding checks.

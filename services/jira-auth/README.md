# Jira authentication broker

Standalone Node 24 service for ByteQuay's single Atlassian OAuth 2.0 (3LO)
integration. It holds the application secret and rotating refresh tokens;
the desktop backend receives short-lived access tokens. There are no npm
dependencies, Jira request proxies, webhooks, or tenant-write endpoints.

This code has local protocol tests. Atlassian registration, hosting, and a real
tenant consent test are deployment steps; they have not been performed here.

## Configure and run

Create one OAuth 2.0 integration in the [Atlassian developer console](https://developer.atlassian.com/console/myapps/).
Enable sharing for distribution. Configure the callback to exactly
`https://YOUR-BROKER-HOST/oauth/callback` and enable the scopes listed below.
Add the User Identity API's `read:me` permission so the service can bind each
authorization to its actual Atlassian account. Register this application once
for ByteQuay; individual desktop users sign in and consent to that application.
An administrator may need to approve the app for a customer's site.

Provide these environment variables through the deployment's secret store:

| Variable | Value |
| --- | --- |
| `ATLASSIAN_CLIENT_ID` | The integration's client ID |
| `ATLASSIAN_CLIENT_SECRET` | The integration's secret, never shipped in ByteQuay |
| `JIRA_AUTH_PUBLIC_URL` | HTTPS origin, such as `https://jira-auth.example.com`, with no path |
| `JIRA_AUTH_ENCRYPTION_KEY` | Base64-encoded random 32-byte key; generate with `openssl rand -base64 32` |
| `JIRA_AUTH_DATA_DIR` | Persistent private directory outside the checkout |
| `JIRA_AUTH_HOST` | Optional bind address; defaults to `127.0.0.1` |
| `PORT` | Optional listening port; defaults to `8080` |

Run `npm start` from this directory. Missing or invalid configuration prevents
startup. The process prints its bind address only; it does not log requests,
credentials, callback parameters, or upstream response bodies.

Terminate TLS at a trusted reverse proxy. Keep this Node port private, and have
the proxy replace `X-Forwarded-Proto` with `https`; the service rejects requests
without that header in production. Do not record query strings, authorization
headers, request/response bodies, or cookies in proxy logs. Route both `/oauth/*`
and `/v1/*` to this service. No CORS access is provided. `/health` returns a small
readiness response without contacting Atlassian.

Deploy **one process and one replica per data directory**, on a filesystem
supporting atomic rename and fsync. An exclusive `.lock` file rejects a second
instance. SIGINT/SIGTERM drains work and removes it. After an ungraceful stop,
confirm the process ID in `.lock` is no longer running before removing the
lock and restarting. Do not clear it while another replica is using the store.
For containers, stop all containers sharing that volume before removing a stale
lock; its recorded PID belongs to the container's process namespace.

Connection files use AES-256-GCM with random nonces and the connection ID as
authenticated data. Writes use private temporary files, fsync, and atomic
rename. Only hashes of pairing credentials and browser/state bindings are
stored. Keep the encryption key outside the data directory and back it up
separately. Changing or losing it requires reconnecting every account; there
is no key migration API. Storage corruption or the wrong key prevents startup.

The broker limits creation to 20 attempts per socket address in ten minutes,
caps stored connections at 10,000, and caps bodies and upstream responses.
Apply suitable global/per-client rate limits at the reverse proxy as well.
Forwarded client-IP headers are not trusted; a shared proxy address shares
the built-in limit. Pending/error connections are reclaimed one day after
pairing expiry, and connections unused for 90 days are reclaimed. Reclamation
runs at startup and during connection creation, not on a background timer.

## Container deployment

The Dockerfile runs the dependency-free service as a non-root user. Build from
the repository root:

```bash
docker build -t bytequay-jira-auth services/jira-auth
```

Supply the variables above through the host's secret manager. On a single
server, a private environment file outside the checkout is also supported;
restrict it to the operator, and never put it in the image or Git. Mount a
persistent volume at `/data`. The image creates that directory for UID 1000;
an operator-provided bind mount needs matching ownership. Run only one replica.

For a reverse proxy running on the same host:

```bash
docker run --detach --name bytequay-jira-auth --restart unless-stopped \
  --stop-timeout 60 \
  --env-file /srv/bytequay/jira-auth.env \
  --mount type=volume,source=bytequay-jira-auth,target=/data \
  --publish 127.0.0.1:8080:8080 \
  --read-only --cap-drop ALL --security-opt no-new-privileges \
  bytequay-jira-auth
```

The public hostname must resolve to the TLS proxy and match
`JIRA_AUTH_PUBLIC_URL`. For example, a host Caddy configuration can use:

```caddyfile
jira-auth.example.com {
    reverse_proxy 127.0.0.1:8080 {
        header_up X-Forwarded-Proto https
    }
}
```

The domain above is a placeholder, not an existing ByteQuay service. Keep
request/access logging disabled for these paths, including proxy error logging
that might include callback query strings. Only the proxy should reach port
8080; setting a header does not authenticate a publicly exposed backend.
The container health check verifies process readiness, not Atlassian consent.
Use an immutable image digest for a release and back up the encrypted volume
and encryption key separately. A stateless host without persistent storage is
not suitable for this service.

## Configure the desktop distribution

Set the public service origin when building the Electron app:

```bash
cd frontend
BYTEQUAY_JIRA_AUTH_URL=https://jira-auth.example.com npm run package
```

The URL is public configuration. OAuth client secrets and refresh tokens must
never be build variables for the desktop. GitHub release builds use the
repository variable `BYTEQUAY_JIRA_AUTH_URL`; configure it after the service is
deployed. A runtime variable of the same name overrides the packaged origin.
For a separately launched development backend, set that variable on the JVM
process (Spring property `bytequay.jira.auth-url`).

Without an origin, the desktop reports sign-in as unavailable. Settings does
not ask ordinary users to enter a broker URL. Existing connections retain their
stored origin so removing or reconnecting them does not depend on the current
new-connection configuration.

## Desktop contract

Call `/v1/*` from ByteQuay's backend. Requests carrying browser `Origin` or
`Sec-Fetch-Site` headers are rejected. JSON requests use
`Content-Type: application/json`; responses have `Cache-Control: no-store`.

1. `POST /v1/connections` with `{}` returns HTTP 201:
   `{ id, pairingToken, pairingCode, authorizationUrl }`.
   Store the bearer pairing token in the desktop's protected credential store.
   Show the eight-character pairing code in ByteQuay, and open the URL in the
   user's browser. Never put the pairing token/code into a URL.
2. The user enters that code on the broker page, then grants access on
   Atlassian. The ten-minute, one-use state is bound to this connection and an
   HttpOnly, SameSite=Lax, Secure browser cookie. A copied consent link alone
   cannot finish pairing. Users should enter only the code shown by their own
   ByteQuay application, never a code sent by someone else.
3. `GET /v1/connections/{id}` with `Authorization: Bearer PAIRING_TOKEN`
   returns `{ id, state, sites, account, error? }`. State is `pending`,
   `connected`, or `error`. Account is null while pending, including during
   reconnect; the desktop retains any previously verified identity. Once
   connected, account is
   `{ accountId, displayName, email? }` from Atlassian's `/me` API. Email may be
   unavailable and is never used as the identity key. Sites are
   `{ id, url, name }`; `error` is safe display text. Poll
   moderately while connecting, stop on a terminal state, and let the user
   choose an authorized Jira site. Only canonical HTTPS `*.atlassian.net`
   site origins from Atlassian's accessible-resources response are accepted.
4. `POST /v1/connections/{id}/token` with the same bearer and
   `{ "cloudId": "AUTHORIZED-SITE-UUID" }` returns
   `{ accessToken, expiresAt }`, with an ISO-8601 timestamp. Cache this token
   only in the desktop backend until shortly before expiry. It must never
   enter renderer state, agent prompts, logs, or source snapshots. Refresh
   tokens and the application secret never leave the broker.
5. `DELETE /v1/connections/{id}` with the same bearer returns HTTP 204 and
   durably removes the broker connection. The desktop removes its connection
   and credential, but retains saved issue context and task links. Retained
   context does not authorize further Jira requests.
   Already-issued access tokens can remain valid until expiry. To revoke the
   Atlassian grant itself, the user can remove ByteQuay in their Atlassian
   account's Connected apps settings; this endpoint does not claim remote
   grant revocation.

Token issuance rechecks accessible sites and serializes refresh per connection.
The requested cloud ID is an admission check, not a claim that Atlassian's
access token is restricted to just that site: its grant may cover other sites.
The desktop must keep its own connection/site routing checks. Refresh rotation
is durably saved before returning a token. An interrupted or failed refresh
requires reconnecting rather than retrying an ambiguously consumed token.
Newly authorized sites appear after token issuance refreshes the site list.

`POST /v1/connections/{id}/reconnect` uses the same desktop pairing bearer and
starts browser authorization for that existing connection. It returns the new
authorization URL and pairing code. Reconnect does not create a new desktop
connection ID. A successful callback must match the previously authenticated
account ID; choosing another account reports a mismatch. Use Add account for a
different identity. Denied or expired reconnects retain the prior authorization
where available, and return the error alongside that state. Desktop polling must
not interpret a connected state with an error as successful reauthorization.

Account identity and tokens are not deduplicated across desktop pairing
credentials. In particular, two grants belonging to the same Atlassian account
may authorize different sites. Local ByteQuay connection management identifies
equivalent account/site sets without copying, merging or revoking their grants.

Non-success API responses are `{ code, error }`. HTTP 400 means malformed
input; 401 means missing pairing authentication or a required reconnect; 403
means browser access or the selected site is disallowed; 404 means an unknown
connection; 429 means creation is rate-limited; 502 means the provider could
not complete the request. `GET` reports expired initial pairing as
`state: "error"`; an expired reconnect can instead return `connected` with an
error when the prior authorization remains available.
Reconnect the existing account after an authentication error. An unfinished
initial connection can be removed without affecting other accounts.

## Atlassian scopes

The release requests this fixed set (see `SCOPES` in `broker.mjs`):

```text
offline_access
read:me
read:jira-work
write:jira-work
read:jira-user
read:board-scope:jira-software
read:project:jira
read:sprint:jira-software
write:sprint:jira-software
read:issue-details:jira
read:jql:jira
```

Platform classic scopes cover issue reads/edits, comments, users, and workflow
transitions. Software scopes cover boards, sprint reads, backlog and sprint
issues, and moving an issue into a sprint. Every operation still obeys the
connected user's permissions. No webhook or board administration scope is
requested. `read:me` identifies the signed-in account. Changing scopes needs
renewed consent.

Official contracts: [3LO authentication](https://developer.atlassian.com/cloud/jira/platform/oauth-2-3lo-apps/),
[platform scopes](https://developer.atlassian.com/cloud/jira/platform/scopes-for-oauth-2-3LO-and-forge-apps/),
[board APIs](https://developer.atlassian.com/cloud/jira/software/rest/api-group-board/),
[sprint APIs](https://developer.atlassian.com/cloud/jira/software/rest/api-group-sprint/).

## Local verification

`npm test` uses Node's built-in test runner, temporary encrypted storage, and a
loopback-only OAuth stub. No Atlassian account, network service, or tenant write
is involved. It checks pairing/CSRF, callback replay and expiry, bearer and site
authorization, encrypted persistence/restart, concurrent refresh rotation,
resource URL validation, input bounds, disconnect, account identity, reconnect,
and rejection of account changes during reconnect.

With the repository's frontend dependencies installed, `npm run test:browser`
also runs the native pairing form through a local OAuth redirect and callback in
Electron's Chromium. This catches browser CSP/referrer/cookie behavior that HTTP
tests cannot cover. It uses temporary browser/storage directories and no live
Atlassian account. Electron is only a repository test dependency; the deployed
broker still uses Node's standard library alone.

For manual localhost work, set `JIRA_AUTH_ALLOW_LOCALHOST=true` and use an HTTP
loopback public URL. Cookies then omit Secure. The optional
`JIRA_AUTH_TEST_UPSTREAM=http://127.0.0.1:PORT` redirects OAuth requests only in
this loopback mode; it is rejected with a public deployment URL. Production
always uses `auth.atlassian.com` and `api.atlassian.com` and never follows their
HTTP redirects. The registered Atlassian callback must still exactly match the
configured callback when testing real consent.

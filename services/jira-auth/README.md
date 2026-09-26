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
   returns `{ id, state, sites, error? }`. State is `pending`, `connected`, or
   `error`. Sites are `{ id, url, name }`; `error` is safe display text. Poll
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
   durably removes the connection. Delete the desktop credential/cache too.
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

Non-success API responses are `{ code, error }`. HTTP 400 means malformed
input; 401 means missing pairing authentication or a required reconnect; 403
means browser access or the selected site is disallowed; 404 means an unknown
connection; 429 means creation is rate-limited; 502 means the provider could
not complete the request. `GET` reports expired pairing as `state: "error"`.
Start a new connection after an error and delete the superseded pairing.

## Atlassian scopes

The release requests this fixed set (see `SCOPES` in `broker.mjs`):

```text
offline_access
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
requested. Changing scopes needs renewed consent.

Official contracts: [3LO authentication](https://developer.atlassian.com/cloud/jira/platform/oauth-2-3lo-apps/),
[platform scopes](https://developer.atlassian.com/cloud/jira/platform/scopes-for-oauth-2-3LO-and-forge-apps/),
[board APIs](https://developer.atlassian.com/cloud/jira/software/rest/api-group-board/),
[sprint APIs](https://developer.atlassian.com/cloud/jira/software/rest/api-group-sprint/).

## Local verification

`npm test` uses Node's built-in test runner, temporary encrypted storage, and a
loopback-only OAuth stub. No Atlassian account, network service, or tenant write
is involved. It checks pairing/CSRF, callback replay and expiry, bearer and site
authorization, encrypted persistence/restart, concurrent refresh rotation,
resource URL validation, input bounds, and disconnect.

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

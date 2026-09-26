/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import assert from 'node:assert/strict';
import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';
import { mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { createBroker, readConfig, SCOPES } from './broker.mjs';

const CLOUD_ID = 'ea477bbe-0874-4dc8-9d58-31d7954a8e9c';
const OTHER_CLOUD = '8b584b41-6f28-4c73-9cde-79c53c96e22a';
const ACCOUNT = { accountId: 'atlassian-account-1', displayName: 'Example User', email: 'user@example.com' };
const environment = directory => ({
  JIRA_AUTH_PUBLIC_URL: 'http://127.0.0.1:8080', JIRA_AUTH_ALLOW_LOCALHOST: 'true',
  ATLASSIAN_CLIENT_ID: 'test-client', ATLASSIAN_CLIENT_SECRET: 'test-client-secret',
  JIRA_AUTH_ENCRYPTION_KEY: randomBytes(32).toString('base64'), JIRA_AUTH_DATA_DIR: directory,
});
const listen = async server => { await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); return `http://127.0.0.1:${server.address().port}`; };

async function fixture(t) {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-jira-auth-'));
  let instant = Date.now();
  const tokens = []; const reads = [];
  const fixture = {
    directory, tokens, reads, advance: milliseconds => { instant += milliseconds; }, refreshFailure: false, resourcesFailure: false, redirect: false,
    resources: [{ id: CLOUD_ID, url: 'https://example.atlassian.net', name: 'Example Jira', scopes: ['read:jira-work'] }],
    profile: { account_id: ACCOUNT.accountId, name: ACCOUNT.displayName, email: ACCOUNT.email },
  };
  const oauth = createServer(async (request, response) => {
    const json = value => { response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(value)); };
    if (request.url === '/oauth/token') {
      const chunks = []; for await (const chunk of request) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks)); tokens.push(body);
      assert.equal(body.client_id, 'test-client'); assert.equal(body.client_secret, 'test-client-secret');
      if (fixture.redirect) { response.writeHead(302, { Location: `${fixture.oauthOrigin}/stolen` }); response.end(); return; }
      if (fixture.refreshFailure && body.grant_type === 'refresh_token') {
        response.statusCode = 400; json({ error: 'invalid_grant', error_description: 'secret-upstream-detail' }); return;
      }
      if (body.grant_type === 'authorization_code') {
        assert.equal(body.code, 'accepted-code');
        assert.equal(body.redirect_uri, 'http://127.0.0.1:8080/oauth/callback');
      }
      else assert.equal(body.refresh_token, `refresh-secret-${tokens.length - 1}`);
      // An asynchronous response makes simultaneous requests overlap if the broker lacks serialization.
      await new Promise(resolve => setTimeout(resolve, 20));
      json({ token_type: 'Bearer', access_token: `access-secret-${tokens.length}`, refresh_token: `refresh-secret-${tokens.length}`, expires_in: 3600 });
    }
    else if (request.url === '/oauth/token/accessible-resources') {
      reads.push(request.headers.authorization);
      if (fixture.resourcesFailure) { response.statusCode = 503; json({ error: 'secret-resource-detail' }); }
      else json(fixture.resources);
    }
    else if (request.url === '/me') {
      assert.match(request.headers.authorization, /^Bearer access-secret-\d+$/);
      json(fixture.profile);
    }
    else { response.statusCode = 404; json({ error: 'Unexpected endpoint' }); }
  });
  fixture.oauthOrigin = await listen(oauth);
  fixture.config = readConfig({ ...environment(directory), JIRA_AUTH_TEST_UPSTREAM: fixture.oauthOrigin });
  let broker; let origin;
  fixture.start = async () => { broker = await createBroker(fixture.config, { now: () => instant }); origin = await listen(broker.server); };
  fixture.restart = async () => { await broker.close(); await fixture.start(); };
  fixture.request = (path, options) => fetch(`${origin}${path}`, { ...options, redirect: 'manual' });
  fixture.create = async () => {
    const result = await fixture.request('/v1/connections', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
    assert.equal(result.status, 201); return result.json();
  };
  fixture.api = (connection, suffix = '', options = {}) => fixture.request(`/v1/connections/${connection.id}${suffix}`, {
    ...options, headers: { Authorization: `Bearer ${connection.pairingToken}`, 'Content-Type': 'application/json', ...options.headers },
  });
  fixture.reconnect = async connection => {
    const result = await fixture.api(connection, '/reconnect', { method: 'POST', body: '{}' });
    assert.equal(result.status, 200); return { ...connection, ...await result.json() };
  };
  fixture.startBrowser = async connection => {
    const path = new URL(connection.authorizationUrl);
    const result = await fixture.request(path.pathname + path.search);
    assert.equal(result.status, 200);
    assert.match(await result.text(), /Connection code/);
    const cookie = result.headers.get('set-cookie').split(';')[0];
    const start = await fixture.request(path.pathname + path.search, {
      method: 'POST', headers: { Origin: fixture.config.publicOrigin, Cookie: cookie, 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ code: connection.pairingCode }),
    });
    assert.equal(start.status, 303);
    const authorization = new URL(start.headers.get('location'));
    assert.equal(authorization.origin, fixture.oauthOrigin);
    assert.equal(authorization.pathname, '/authorize');
    assert.deepEqual(authorization.searchParams.get('scope').split(' '), SCOPES);
    assert.equal(authorization.searchParams.get('state'), path.searchParams.get('state'));
    return { cookie, state: authorization.searchParams.get('state') };
  };
  fixture.finishBrowser = (browser, query = 'code=accepted-code') => fixture.request(`/oauth/callback?state=${browser.state}&${query}`, { headers: { Cookie: browser.cookie } });
  fixture.connect = async () => {
    const connection = await fixture.create(); const browser = await fixture.startBrowser(connection);
    const result = await fixture.finishBrowser(browser); assert.equal(result.status, 200); return { connection, browser };
  };
  fixture.decrypt = async connection => {
    const bytes = await readFile(join(directory, `${connection.id}.enc`));
    const cipher = createDecipheriv('aes-256-gcm', fixture.config.encryptionKey, bytes.subarray(4, 16));
    cipher.setAAD(Buffer.from(connection.id)); cipher.setAuthTag(bytes.subarray(16, 32));
    return JSON.parse(Buffer.concat([cipher.update(bytes.subarray(32)), cipher.final()]));
  };
  fixture.writeRecord = async record => {
    const iv = randomBytes(12); const cipher = createCipheriv('aes-256-gcm', fixture.config.encryptionKey, iv);
    cipher.setAAD(Buffer.from(record.id));
    const encrypted = Buffer.concat([cipher.update(JSON.stringify(record)), cipher.final()]);
    await writeFile(join(directory, `${record.id}.enc`), Buffer.concat([Buffer.from('BQJ1'), iv, cipher.getAuthTag(), encrypted]));
  };
  await fixture.start();
  t.after(async () => { await broker.close(); await new Promise(resolve => oauth.close(resolve)); await rm(directory, { recursive: true }); });
  return fixture;
}

test('pairs once, persists encrypted credentials, survives restart, and disconnects', async t => {
  const f = await fixture(t); const { connection, browser } = await f.connect();
  assert.match(connection.pairingCode, /^[A-F0-9]{8}$/);
  assert.equal((await f.finishBrowser(browser)).status, 400);
  assert.equal(f.tokens.length, 1);
  const status = await f.api(connection);
  assert.equal(status.headers.get('cache-control'), 'no-store');
  assert.equal(status.headers.get('access-control-allow-origin'), null);
  assert.deepEqual(await status.json(), { id: connection.id, state: 'connected', account: ACCOUNT,
    sites: [{ id: CLOUD_ID, url: 'https://example.atlassian.net', name: 'Example Jira' }] });
  const bytes = await readFile(join(f.directory, `${connection.id}.enc`));
  for (const secret of [connection.pairingToken, connection.pairingCode, 'access-secret', 'refresh-secret', 'test-client-secret']) assert.ok(!bytes.includes(Buffer.from(secret)));
  const record = await f.decrypt(connection);
  assert.equal(record.pairingToken, undefined); assert.equal(record.pairHash.length, 64);
  assert.equal(record.codeHash, undefined); assert.equal(record.stateHash, undefined);
  assert.equal((await stat(join(f.directory, `${connection.id}.enc`))).mode & 0o777, 0o600);
  await f.restart();
  const token = await f.api(connection, '/token', { method: 'POST', body: JSON.stringify({ cloudId: CLOUD_ID }) });
  assert.equal(token.status, 200);
  const value = await token.json(); assert.equal(value.accessToken, 'access-secret-1'); assert.ok(Number.isFinite(Date.parse(value.expiresAt)));
  assert.equal(value.refreshToken, undefined);
  assert.equal((await f.api(connection, '', { method: 'DELETE' })).status, 204);
  assert.equal((await f.api(connection)).status, 404);
});

test('reconnect keeps the paired ID and credential, replaces accepted site access and survives restart', async t => {
  const f = await fixture(t); const { connection } = await f.connect();
  const before = await f.decrypt(connection);
  const reconnect = await f.reconnect(connection);
  assert.equal(reconnect.id, connection.id);
  assert.notEqual(reconnect.authorizationUrl, connection.authorizationUrl);
  assert.notEqual(reconnect.pairingCode, connection.pairingCode);
  assert.equal((await (await f.api(connection)).json()).account, null);
  assert.equal((await f.decrypt(connection)).pairHash, before.pairHash);
  assert.equal((await f.api(connection, '/token', { method: 'POST', body: JSON.stringify({ cloudId: CLOUD_ID }) })).status, 401);
  const browser = await f.startBrowser(reconnect);
  f.resources = [{ id: OTHER_CLOUD, url: 'https://other.atlassian.net', name: 'Other site', scopes: ['read:jira-work'] }];
  delete f.profile.email;
  assert.equal((await f.finishBrowser(browser)).status, 200);
  await f.restart();
  const status = await (await f.api(connection)).json();
  assert.deepEqual(status.account, { accountId: ACCOUNT.accountId, displayName: ACCOUNT.displayName });
  assert.deepEqual(status.sites.map(site => site.id), [OTHER_CLOUD]);
  assert.equal(status.state, 'connected'); assert.equal(status.error, undefined);
  assert.equal((await f.decrypt(connection)).tokens.refreshToken, 'refresh-secret-2');
});

test('wrong account, denial, expiration and interrupted reconnect preserve the original authorization', async t => {
  const f = await fixture(t); const { connection } = await f.connect();
  const before = await f.decrypt(connection);
  const unchanged = async message => {
    const status = await (await f.api(connection)).json();
    assert.equal(status.state, 'connected'); assert.match(status.error, message); assert.deepEqual(status.account, ACCOUNT);
    const record = await f.decrypt(connection);
    assert.deepEqual(record.tokens, before.tokens); assert.deepEqual(record.sites, before.sites);
    assert.equal(record.stateHash, undefined); assert.equal(record.browserHash, undefined);
  };
  let browser = await f.startBrowser(await f.reconnect(connection));
  f.profile.account_id = 'different-account';
  const mismatch = await f.finishBrowser(browser);
  assert.equal(mismatch.status, 409); assert.match(await mismatch.text(), /original Atlassian account/);
  await unchanged(/original Atlassian account/);
  assert.equal((await f.finishBrowser(browser)).status, 400);
  browser = await f.startBrowser(await f.reconnect(connection));
  assert.equal((await f.finishBrowser(browser, 'error=access_denied')).status, 400);
  await unchanged(/not completed/);
  browser = await f.startBrowser(await f.reconnect(connection));
  f.advance(10 * 60_000);
  assert.equal((await f.finishBrowser(browser)).status, 410);
  await unchanged(/expired/);
  await f.reconnect(connection);
  const interrupted = await f.decrypt(connection); interrupted.inFlight = true; await f.writeRecord(interrupted);
  await f.restart(); await unchanged(/interrupted/);
});

test('reconnect requires desktop authentication and new pairing, and cannot silently bind an unidentified old account', async t => {
  const f = await fixture(t); const { connection } = await f.connect();
  const request = { method: 'POST', body: '{}' };
  assert.equal((await f.api(connection, '/reconnect', { ...request, headers: { Authorization: `Bearer ${'x'.repeat(43)}` } })).status, 401);
  assert.equal((await f.api(connection, '/reconnect', { ...request, headers: { Origin: f.config.publicOrigin } })).status, 403);
  assert.equal((await f.api(connection, '/reconnect', { method: 'POST', body: '{"accountId":"injected"}' })).status, 400);
  const first = await f.reconnect(connection); const browser = await f.startBrowser(first);
  const second = await f.reconnect(connection);
  assert.equal((await f.finishBrowser(browser)).status, 400);
  const next = await f.startBrowser(second);
  assert.equal((await f.finishBrowser({ ...next, cookie: browser.cookie })).status, 403);
  assert.equal((await f.finishBrowser(next)).status, 200);
  const old = await f.decrypt(connection); delete old.account; await f.writeRecord(old);
  const result = await f.api(connection, '/reconnect', request);
  assert.equal(result.status, 409); assert.equal((await result.json()).code, 'ACCOUNT_IDENTITY_REQUIRED');
  assert.deepEqual((await f.decrypt(connection)).tokens, old.tokens);
});

test('identity must be authenticated and bounded; duplicate accounts keep separate pairing credentials and grants', async t => {
  const f = await fixture(t);
  const first = await f.connect(); const second = await f.connect();
  assert.notEqual(first.connection.id, second.connection.id);
  const firstRecord = await f.decrypt(first.connection); const secondRecord = await f.decrypt(second.connection);
  assert.notEqual(firstRecord.pairHash, secondRecord.pairHash);
  assert.notEqual(firstRecord.tokens.refreshToken, secondRecord.tokens.refreshToken);
  assert.deepEqual(firstRecord.account, secondRecord.account);
  assert.equal((await f.api({ ...first.connection, pairingToken: second.connection.pairingToken })).status, 401);
  for (const profile of [null, { account_id: '', name: 'User' }, { account_id: 'x', name: 'x'.repeat(257) }]) {
    f.profile = profile;
    const connection = await f.create(); const browser = await f.startBrowser(connection);
    assert.equal((await f.finishBrowser(browser)).status, 502);
    const status = await (await f.api(connection)).json();
    assert.equal(status.state, 'error'); assert.equal(status.account, null);
    assert.equal((await f.decrypt(connection)).tokens, undefined);
  }
  assert.ok(SCOPES.includes('read:me'));
});

test('copied links, missing browser binding, wrong codes and CSRF cannot complete OAuth', async t => {
  const f = await fixture(t); const connection = await f.create();
  const url = new URL(connection.authorizationUrl); const state = url.searchParams.get('state');
  assert.equal((await f.request(`/oauth/callback?state=${state}&code=accepted-code`)).status, 403);
  const page = await f.request(url.pathname + url.search);
  const cookie = page.headers.get('set-cookie').split(';')[0];
  assert.match(page.headers.get('set-cookie'), /HttpOnly; SameSite=Lax/);
  const post = (code, origin = f.config.publicOrigin) => f.request(url.pathname + url.search, {
    method: 'POST', headers: { Origin: origin, Cookie: cookie, 'Content-Type': 'application/x-www-form-urlencoded' }, body: `code=${code}`,
  });
  assert.equal((await post(connection.pairingCode, 'https://attacker.example')).status, 403);
  for (let i = 0; i < 5; i++) assert.equal((await post('INVALID0')).status, 400);
  assert.equal((await post(connection.pairingCode)).status, 400);
  assert.equal((await (await f.api(connection)).json()).state, 'error');
  assert.equal(f.tokens.length, 0);

  const second = await f.create(); const browser = await f.startBrowser(second);
  assert.equal((await f.finishBrowser({ ...browser, cookie: cookie })).status, 403);
  assert.equal((await f.finishBrowser({ ...browser, state: `${browser.state.slice(0, -1)}!` })).status, 400);
  assert.equal((await f.finishBrowser(browser)).status, 200);
  assert.equal(f.tokens.length, 1);
});

test('pairing and callbacks expire; errors never reflect upstream details', async t => {
  const f = await fixture(t); const expired = await f.create(); const browser = await f.startBrowser(expired);
  f.advance(10 * 60_000);
  assert.equal((await f.finishBrowser(browser)).status, 410);
  assert.match((await (await f.api(expired)).json()).error, /expired/);
  assert.equal(f.tokens.length, 0);
  const denied = await f.create(); const deniedBrowser = await f.startBrowser(denied);
  const deniedResult = await f.finishBrowser(deniedBrowser, 'error=access_denied&error_description=<script>secret</script>');
  assert.equal(deniedResult.status, 400); assert.doesNotMatch(await deniedResult.text(), /<script>|secret/);
  assert.equal((await (await f.api(denied)).json()).state, 'error');
});

test('refresh is serialized, rotates once, and stores the newest token before returning it', async t => {
  const f = await fixture(t); const { connection } = await f.connect(); f.advance(3_590_000);
  const requests = await Promise.all(Array.from({ length: 8 }, () => f.api(connection, '/token', { method: 'POST', body: JSON.stringify({ cloudId: CLOUD_ID }) })));
  for (const result of requests) { assert.equal(result.status, 200); assert.equal((await result.json()).accessToken, 'access-secret-2'); }
  assert.equal(f.tokens.length, 2); assert.equal((await f.decrypt(connection)).tokens.refreshToken, 'refresh-secret-2');
  await f.restart(); f.advance(3_590_000);
  assert.equal((await f.api(connection, '/token', { method: 'POST', body: JSON.stringify({ cloudId: CLOUD_ID }) })).status, 200);
  assert.equal(f.tokens.at(-1).refresh_token, 'refresh-secret-2');
  f.refreshFailure = true; f.advance(3_590_000);
  const failed = await f.api(connection, '/token', { method: 'POST', body: JSON.stringify({ cloudId: CLOUD_ID }) });
  assert.equal(failed.status, 401); assert.doesNotMatch(await failed.text(), /secret-upstream-detail|refresh-secret/);
  assert.equal((await f.decrypt(connection)).tokens, undefined);
  assert.equal((await (await f.api(connection)).json()).state, 'error');
});

test('revalidates site authorization, refuses untrusted resource URLs and does not follow OAuth redirects', async t => {
  const f = await fixture(t); const { connection } = await f.connect();
  const token = cloudId => f.api(connection, '/token', { method: 'POST', body: JSON.stringify({ cloudId }) });
  assert.equal((await token(OTHER_CLOUD)).status, 403);
  f.resources = [];
  assert.equal((await token(CLOUD_ID)).status, 403);
  assert.deepEqual((await (await f.api(connection)).json()).sites, []);
  for (const siteUrl of ['http://example.atlassian.net', 'https://example.atlassian.net.evil.test', 'https://user:secret@example.atlassian.net', 'https://127.0.0.1', 'https://example.atlassian.net/path']) {
    f.resources = [{ id: CLOUD_ID, url: siteUrl, name: 'bad', scopes: ['read:jira-work'] }];
    const bad = await f.create(); const browser = await f.startBrowser(bad);
    assert.equal((await f.finishBrowser(browser)).status, 502);
    assert.equal((await (await f.api(bad)).json()).state, 'error');
  }
  f.redirect = true;
  const redirect = await f.create(); const browser = await f.startBrowser(redirect);
  assert.equal((await f.finishBrowser(browser)).status, 502);
  assert.equal((await f.decrypt(redirect)).tokens, undefined);
});

test('keeps the rotated token across a resource outage and requires reconnect after an interrupted exchange', async t => {
  const f = await fixture(t); const { connection } = await f.connect(); f.advance(3_590_000);
  const token = () => f.api(connection, '/token', { method: 'POST', body: JSON.stringify({ cloudId: CLOUD_ID }) });
  f.resourcesFailure = true;
  const unavailable = await token(); assert.equal(unavailable.status, 502);
  assert.doesNotMatch(await unavailable.text(), /secret-resource-detail/);
  assert.equal((await f.decrypt(connection)).tokens.refreshToken, 'refresh-secret-2');
  f.resourcesFailure = false;
  assert.equal((await token()).status, 200); assert.equal(f.tokens.length, 2);
  const record = await f.decrypt(connection); record.inFlight = true; await f.writeRecord(record);
  await f.restart();
  assert.equal((await (await f.api(connection)).json()).state, 'error');
  assert.equal((await f.decrypt(connection)).tokens, undefined);
  assert.equal((await token()).status, 401); assert.equal(f.tokens.length, 2);
});

test('production requires trusted HTTPS termination and uses secure browser cookies', async t => {
  const f = await fixture(t);
  f.config.secure = true; f.config.publicOrigin = 'https://jira-auth.example.com';
  assert.equal((await f.request('/health')).status, 400);
  const created = await f.request('/v1/connections', { method: 'POST', headers: { 'X-Forwarded-Proto': 'https', 'Content-Type': 'application/json' }, body: '{}' });
  assert.equal(created.status, 201); const connection = await created.json();
  const url = new URL(connection.authorizationUrl);
  assert.equal(url.origin, 'https://jira-auth.example.com');
  const page = await f.request(url.pathname + url.search, { headers: { 'X-Forwarded-Proto': 'https' } });
  assert.equal(page.status, 200);
  assert.match(page.headers.get('set-cookie'), /^__Host-bq-jira-/);
  assert.match(page.headers.get('set-cookie'), /; Secure/);
  assert.match(page.headers.get('content-security-policy'), /frame-ancestors 'none'/);
  assert.ok(page.headers.get('content-security-policy').includes(`form-action 'self' ${f.config.authOrigin} https://id.atlassian.com;`));
  assert.equal(page.headers.get('referrer-policy'), 'same-origin');
});

test('API requires the paired desktop credential, rejects browser origins and bounds input', async t => {
  const f = await fixture(t); const { connection } = await f.connect();
  assert.equal((await f.request(`/v1/connections/${connection.id}`)).status, 401);
  assert.equal((await f.api(connection, '', { headers: { Authorization: `Bearer ${'a'.repeat(43)}` } })).status, 401);
  assert.equal((await f.api(connection, '', { headers: { Origin: f.config.publicOrigin } })).status, 403);
  assert.equal((await f.api(connection, '', { headers: { 'Sec-Fetch-Site': 'same-origin' } })).status, 403);
  const huge = await f.request('/v1/connections', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ input: 'x'.repeat(5000) }) });
  assert.equal(huge.status, 413);
  assert.equal((await f.request('/v1/connections', { method: 'POST', body: '{}' })).status, 400);
  assert.equal((await f.api(connection, '/token', { method: 'POST', body: JSON.stringify({ cloudId: CLOUD_ID, url: 'https://attacker.test' }) })).status, 400);
  for (let i = 1; i < 20; i++) await f.create();
  assert.equal((await f.request('/v1/connections', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })).status, 429);
});

test('configuration fails closed for missing secrets, non-HTTPS origins and remote test overrides', () => {
  assert.throws(() => readConfig({}), /Missing required configuration/);
  const env = environment('/tmp/unused');
  assert.throws(() => readConfig({ ...env, JIRA_AUTH_ALLOW_LOCALHOST: '' }), /HTTPS origin/);
  assert.throws(() => readConfig({ ...env, JIRA_AUTH_PUBLIC_URL: 'http://example.com' }), /HTTPS origin/);
  assert.throws(() => readConfig({ ...env, JIRA_AUTH_PUBLIC_URL: 'https://example.com/path' }), /HTTPS origin/);
  assert.throws(() => readConfig({ ...env, JIRA_AUTH_ENCRYPTION_KEY: 'unsafe' }), /32-byte key/);
  assert.throws(() => readConfig({ ...env, JIRA_AUTH_TEST_UPSTREAM: 'http://attacker.test' }), /loopback/);
  assert.throws(() => readConfig({ ...env, ATLASSIAN_CLIENT_SECRET: '' }), /ATLASSIAN_CLIENT_SECRET/);
  const production = readConfig({ ...env, JIRA_AUTH_PUBLIC_URL: 'https://jira-auth.example.com', JIRA_AUTH_ALLOW_LOCALHOST: '' });
  assert.equal(production.authOrigin, 'https://auth.atlassian.com'); assert.equal(production.apiOrigin, 'https://api.atlassian.com');
});

test('rejects a second instance and detects corrupted encrypted storage on restart', async t => {
  const f = await fixture(t); const { connection } = await f.connect();
  await assert.rejects(createBroker(f.config), /directory is locked/);
  const file = join(f.directory, `${connection.id}.enc`); const bytes = await readFile(file);
  bytes[bytes.length - 1] ^= 1; await writeFile(file, bytes);
  await assert.rejects(f.restart(), /Cannot read Jira auth storage/);
  // Restore the file so the fixture can restart and close its owned service normally.
  bytes[bytes.length - 1] ^= 1; await writeFile(file, bytes); await f.start();
});

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
import { createCipheriv, createDecipheriv, createHash, createHmac, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { chmod, mkdir, open, readFile, readdir, rename, unlink } from 'node:fs/promises';
import { createServer } from 'node:http';
import { isIP } from 'node:net';
import { join, resolve } from 'node:path';

export const SCOPES = [
  'offline_access', 'read:me', 'read:jira-work', 'write:jira-work', 'read:jira-user',
  'read:board-scope:jira-software', 'read:project:jira',
  'read:sprint:jira-software', 'write:sprint:jira-software', 'read:issue-details:jira', 'read:jql:jira',
];
const ID = /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/;
const SECRET = /^[A-Za-z0-9_-]{43}$/;
const AUTH_TTL = 10 * 60_000;
const RETENTION = 90 * 24 * 60 * 60_000;
const MAX_CONNECTIONS = 10_000;
const hash = value => createHash('sha256').update(value).digest('hex');
const random = () => randomBytes(32).toString('base64url');
const equal = (a, b) => typeof a === 'string' && typeof b === 'string' && Buffer.byteLength(a) === Buffer.byteLength(b)
  && timingSafeEqual(Buffer.from(a), Buffer.from(b));

class Problem extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}
const invalid = () => new Problem(400, 'INVALID_REQUEST', 'The request is invalid.');
const upstream = () => new Problem(502, 'ATLASSIAN_UNAVAILABLE', 'Atlassian could not complete this request. Try again.');
const reconnect = () => new Problem(401, 'RECONNECT_REQUIRED', 'Reconnect Jira in ByteQuay.');

export function readConfig(env = process.env) {
  const required = name => {
    if (!env[name]?.trim()) throw new Error(`Missing required configuration: ${name}`);
    return env[name].trim();
  };
  const publicUrl = new URL(required('JIRA_AUTH_PUBLIC_URL'));
  const local = env.JIRA_AUTH_ALLOW_LOCALHOST === 'true';
  const loopback = url => ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname);
  if (publicUrl.username || publicUrl.password || publicUrl.pathname !== '/' || publicUrl.search || publicUrl.hash
      || !(publicUrl.protocol === 'https:' || (local && publicUrl.protocol === 'http:' && loopback(publicUrl)))) {
    throw new Error('JIRA_AUTH_PUBLIC_URL must be an HTTPS origin (HTTP loopback requires JIRA_AUTH_ALLOW_LOCALHOST=true).');
  }
  const encodedKey = required('JIRA_AUTH_ENCRYPTION_KEY');
  if (!/^[A-Za-z0-9+/]{43}=$/.test(encodedKey) || Buffer.from(encodedKey, 'base64').length !== 32) {
    throw new Error('JIRA_AUTH_ENCRYPTION_KEY must be a base64-encoded random 32-byte key.');
  }
  let authOrigin = 'https://auth.atlassian.com';
  let apiOrigin = 'https://api.atlassian.com';
  if (env.JIRA_AUTH_TEST_UPSTREAM) {
    const testUrl = new URL(env.JIRA_AUTH_TEST_UPSTREAM);
    if (!local || !loopback(publicUrl) || !loopback(testUrl) || testUrl.protocol !== 'http:'
        || testUrl.username || testUrl.password || testUrl.pathname !== '/' || testUrl.search || testUrl.hash) {
      throw new Error('JIRA_AUTH_TEST_UPSTREAM is allowed only with HTTP loopback test endpoints.');
    }
    authOrigin = apiOrigin = testUrl.origin;
  }
  const port = Number(env.PORT ?? 8080);
  const host = env.JIRA_AUTH_HOST ?? '127.0.0.1';
  if (!Number.isInteger(port) || port < 1 || port > 65535 || !isIP(host)) throw new Error('Invalid PORT or JIRA_AUTH_HOST.');
  return {
    publicOrigin: publicUrl.origin, secure: publicUrl.protocol === 'https:', authOrigin, apiOrigin,
    clientId: required('ATLASSIAN_CLIENT_ID'), clientSecret: required('ATLASSIAN_CLIENT_SECRET'),
    encryptionKey: Buffer.from(encodedKey, 'base64'), dataDir: resolve(required('JIRA_AUTH_DATA_DIR')), host, port,
  };
}

class ConnectionFiles {
  constructor(config) { this.directory = config.dataDir; this.key = config.encryptionKey; this.ids = new Set(); }
  async start() {
    await mkdir(this.directory, { recursive: true, mode: 0o700 });
    await chmod(this.directory, 0o700);
    // ponytail: one process per data directory; use a shared transactional store before running replicas.
    this.lock = await open(join(this.directory, '.lock'), 'wx', 0o600)
      .catch(() => { throw new Error('Jira auth data directory is locked. Run one instance; remove .lock only after confirming its process stopped.'); });
    await this.lock.writeFile(String(process.pid));
    for (const name of await readdir(this.directory)) {
      if (name.endsWith('.enc') && ID.test(name.slice(0, -4))) this.ids.add(name.slice(0, -4));
    }
  }
  async read(id) {
    if (!this.ids.has(id)) throw new Problem(404, 'NOT_FOUND', 'Connection not found.');
    const bytes = await readFile(join(this.directory, `${id}.enc`));
    if (bytes.subarray(0, 4).toString() !== 'BQJ1') throw new Error('Invalid encrypted connection file.');
    const cipher = createDecipheriv('aes-256-gcm', this.key, bytes.subarray(4, 16));
    cipher.setAAD(Buffer.from(id));
    cipher.setAuthTag(bytes.subarray(16, 32));
    const record = JSON.parse(Buffer.concat([cipher.update(bytes.subarray(32)), cipher.final()]).toString());
    if (record.id !== id) throw new Error('Invalid encrypted connection identity.');
    return record;
  }
  async save(record) {
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.key, iv);
    cipher.setAAD(Buffer.from(record.id));
    const encrypted = Buffer.concat([cipher.update(JSON.stringify(record)), cipher.final()]);
    const path = join(this.directory, `${record.id}.enc`);
    const temporary = `${path}.${randomUUID()}.tmp`;
    const file = await open(temporary, 'wx', 0o600);
    try {
      await file.writeFile(Buffer.concat([Buffer.from('BQJ1'), iv, cipher.getAuthTag(), encrypted]));
      await file.sync();
      await file.close();
      await rename(temporary, path);
      await this.syncDirectory();
      this.ids.add(record.id);
    }
    finally { await file.close(); await unlink(temporary).catch(() => {}); }
  }
  async syncDirectory() {
    const directory = await open(this.directory, 'r');
    try { await directory.sync(); } finally { await directory.close(); }
  }
  async remove(id) { await unlink(join(this.directory, `${id}.enc`)); await this.syncDirectory(); this.ids.delete(id); }
  async close() { await this.lock.close(); await unlink(join(this.directory, '.lock')); }
}

async function boundedBody(stream, limit) {
  const chunks = [];
  let size = 0;
  for await (const chunk of stream) {
    size += chunk.length;
    if (size > limit) throw new Problem(413, 'REQUEST_TOO_LARGE', 'Request is too large.');
    chunks.push(Buffer.from(chunk));
  }
  return Buffer.concat(chunks).toString('utf8');
}

async function jsonBody(request) {
  if (request.headers['content-type']?.split(';')[0] !== 'application/json') throw invalid();
  try {
    const value = JSON.parse(await boundedBody(request, 4096));
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw invalid();
    return value;
  }
  catch (error) { if (error instanceof Problem) throw error; throw invalid(); }
}

function html(message, form = '') {
  return `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Connect Jira to ByteQuay</title>
<style>body{font:17px system-ui;line-height:1.6;max-width:32rem;margin:12vh auto;padding:24px}input,button{font:inherit;padding:10px;margin-top:12px}input{display:block;text-transform:uppercase;letter-spacing:.2em}button{cursor:pointer}</style>
<main><h1>Connect Jira to ByteQuay</h1><p>${message}</p>${form}</main></html>`;
}

export async function createBroker(config, { now = Date.now } = {}) {
  const store = new ConnectionFiles(config);
  const locks = new Map();
  const rate = new Map();
  let createTail = Promise.resolve();
  let lastCleanup = now();
  const serial = async (id, work) => {
    const previous = locks.get(id) ?? Promise.resolve();
    const next = previous.catch(() => {}).then(work);
    locks.set(id, next);
    try { return await next; } finally { if (locks.get(id) === next) locks.delete(id); }
  };
  const fail = async (record, message, retainAuthorization = false) => {
    record.state = retainAuthorization && record.tokens ? 'connected' : 'error'; record.error = message;
    if (!retainAuthorization) delete record.tokens;
    delete record.stateHash; delete record.codeHash; delete record.browserHash; delete record.inFlight;
    await store.save(record);
  };
  const failAuthorization = (record, message) => fail(record, message, true);
  const expired = record => now() - record.updatedAt > RETENTION
    || (!record.tokens && record.state !== 'connected' && now() > record.authExpiresAt + 24 * 60 * 60_000);
  await store.start();
  try {
    for (const id of store.ids) {
      const record = await store.read(id);
      if (expired(record)) await store.remove(id);
      else if (record.inFlight) await fail(record, 'The previous connection request was interrupted. Reconnect Jira in ByteQuay.', record.state === 'pending');
    }
  }
  catch { await store.close(); throw new Error('Cannot read Jira auth storage. Check the encryption key and data directory.'); }

  const remoteJson = async (path, options = {}) => {
    let response;
    try {
      response = await fetch(path, { ...options, redirect: 'error', signal: AbortSignal.timeout(15_000) });
      if (!response.ok) {
        await response.body?.cancel();
        if ([400, 401, 403].includes(response.status)) throw reconnect();
        throw upstream();
      }
      return JSON.parse(await boundedBody(response.body, 256 * 1024));
    }
    catch (error) { if (error instanceof Problem && error.status !== 413) throw error; throw upstream(); }
  };
  const exchange = async data => {
    const token = await remoteJson(`${config.authOrigin}/oauth/token`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ ...data, client_id: config.clientId, client_secret: config.clientSecret }),
    });
    const validToken = value => typeof value === 'string' && value.length > 0 && value.length <= 16_384 && !/[\s\x00-\x1f]/.test(value);
    if (!validToken(token?.access_token) || !validToken(token.refresh_token) || !Number.isInteger(token.expires_in)
        || token.expires_in < 1 || token.expires_in > 86_400
        || (token.token_type !== undefined && (typeof token.token_type !== 'string' || token.token_type.toLowerCase() !== 'bearer'))) throw upstream();
    return { accessToken: token.access_token, refreshToken: token.refresh_token, expiresAt: now() + token.expires_in * 1000 };
  };
  const accountFor = async token => {
    const profile = await remoteJson(`${config.apiOrigin}/me`, { headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' } });
    const text = (value, limit) => typeof value === 'string' && value.trim().length > 0 && value.length <= limit && !/[\x00-\x1f\x7f]/.test(value);
    if (!text(profile?.account_id, 256) || !text(profile.name, 256)) throw upstream();
    return { accountId: profile.account_id, displayName: profile.name, ...(text(profile.email, 320) ? { email: profile.email } : {}) };
  };
  const sitesFor = async token => {
    const resources = await remoteJson(`${config.apiOrigin}/oauth/token/accessible-resources`, { headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' } });
    if (!Array.isArray(resources) || resources.length > 200) throw upstream();
    const sites = new Map();
    for (const item of resources) {
      if (!Array.isArray(item?.scopes) || !item.scopes.some(scope => typeof scope === 'string' && /:jira(?:-work|-user|-software)?$/.test(scope))) continue;
      let url;
      try { url = new URL(item.url); } catch { throw upstream(); }
      if (!ID.test(item.id) || typeof item.name !== 'string' || item.name.length > 256 || url.protocol !== 'https:'
          || !/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.atlassian\.net$/.test(url.hostname)
          || url.username || url.password || url.port || url.pathname !== '/' || url.search || url.hash) throw upstream();
      if (sites.has(item.id) && sites.get(item.id).url !== url.origin) throw upstream();
      sites.set(item.id, { id: item.id, url: url.origin, name: item.name });
    }
    return [...sites.values()];
  };
  const cookieName = id => `${config.secure ? '__Host-' : ''}bq-jira-${id}`;
  const cookie = (request, id) => request.headers.cookie?.split(';').map(part => part.trim()).find(part => part.startsWith(`${cookieName(id)}=`))?.split('=').slice(1).join('=');
  const signCookie = (state, nonce) => createHmac('sha256', config.encryptionKey).update(`browser:${state}:${nonce}`).digest('base64url');
  const validCookie = (value, state) => typeof value === 'string' && value.length === 87 && SECRET.test(value.slice(0, 43))
    && equal(value.slice(44), signCookie(state, value.slice(0, 43)));
  const setCookie = (response, id, value, age = 600) => response.setHeader('Set-Cookie', `${cookieName(id)}=${value}; Path=/; Max-Age=${age}; HttpOnly; SameSite=Lax${config.secure ? '; Secure' : ''}`);
  const pending = async (record, state) => {
    if (record.state !== 'pending' || !equal(record.stateHash, hash(state))) throw new Problem(400, 'INVALID_STATE', 'This connection link is invalid or has already been used.');
    if (now() >= record.authExpiresAt) {
      await failAuthorization(record, 'The connection link expired. Start connecting again in ByteQuay.');
      throw new Problem(410, 'AUTH_EXPIRED', record.error);
    }
  };
  const authorize = async (request, record) => {
    const token = request.headers.authorization?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1];
    if (!token || !equal(record.pairHash, hash(token))) throw new Problem(401, 'UNAUTHORIZED', 'Connection authentication is required.');
  };
  const send = (response, status, body) => { response.writeHead(status, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(body)); };
  const page = (response, status, message, form) => { response.writeHead(status, { 'Content-Type': 'text/html; charset=utf-8' }); response.end(html(message, form)); };
  const beginAuthorization = async record => {
    const pairingCode = randomBytes(4).toString('hex').toUpperCase();
    const state = `${record.id}.${random()}`;
    Object.assign(record, { state: 'pending', codeHash: hash(pairingCode), stateHash: hash(state),
      authExpiresAt: now() + AUTH_TTL, attempts: 0, updatedAt: now() });
    delete record.browserHash; delete record.inFlight; delete record.error;
    await store.save(record);
    return { id: record.id, state: 'pending', account: null, sites: record.sites, pairingCode,
      authorizationUrl: `${config.publicOrigin}/oauth/start?state=${state}` };
  };

  const server = createServer({ maxHeaderSize: 8192, requestTimeout: 20_000, headersTimeout: 10_000 }, async (request, response) => {
    response.setHeader('Cache-Control', 'no-store');
    // Keep the same-origin form's Origin header while hiding callback/state URLs from other sites.
    response.setHeader('Referrer-Policy', 'same-origin');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('X-Frame-Options', 'DENY');
    // Chromium checks redirects after form submission, including Atlassian's login hop.
    response.setHeader('Content-Security-Policy', `default-src 'none'; style-src 'unsafe-inline'; form-action 'self' ${config.authOrigin} https://id.atlassian.com; frame-ancestors 'none'; base-uri 'none'`);
    if (config.secure) response.setHeader('Strict-Transport-Security', 'max-age=31536000');
    let url;
    try {
      if (!request.url?.startsWith('/') || request.url.startsWith('//') || request.url.length > 4096) throw invalid();
      if (config.secure && request.headers['x-forwarded-proto'] !== 'https') throw new Problem(400, 'HTTPS_REQUIRED', 'HTTPS is required.');
      url = new URL(request.url, config.publicOrigin);
      if (url.pathname.startsWith('/v1/') && (request.headers.origin !== undefined || request.headers['sec-fetch-site'] !== undefined)) {
        throw new Problem(403, 'DESKTOP_ONLY', 'Use this endpoint from the ByteQuay backend.');
      }
      if (url.pathname === '/health' && request.method === 'GET') return send(response, 200, { status: 'ok' });
      if (url.pathname === '/v1/connections' && request.method === 'POST') {
        const body = await jsonBody(request);
        if (Object.keys(body).length || url.search) throw invalid();
        const address = request.socket.remoteAddress;
        for (const [key, value] of rate) if (value.until <= now()) rate.delete(key);
        const limit = rate.get(address) ?? { count: 0, until: now() + AUTH_TTL };
        if (++limit.count > 20 || rate.size > 10_000) throw new Problem(429, 'RATE_LIMITED', 'Too many connection attempts. Try again later.');
        rate.set(address, limit);
        const create = createTail.catch(() => {}).then(async () => {
          if (now() - lastCleanup >= AUTH_TTL) {
            for (const id of [...store.ids]) await serial(id, async () => { if (expired(await store.read(id))) await store.remove(id); });
            lastCleanup = now();
          }
          if (store.ids.size >= MAX_CONNECTIONS) throw new Problem(503, 'CAPACITY', 'The connection service is at capacity.');
          const pairingToken = random();
          return { ...await beginAuthorization({ id: randomUUID(), pairHash: hash(pairingToken), sites: [] }), pairingToken };
        });
        createTail = create;
        return send(response, 201, await create);
      }
      if (['/oauth/start', '/oauth/callback'].includes(url.pathname)) {
        const state = url.searchParams.get('state') ?? '';
        const id = state.split('.')[0];
        if (!ID.test(id) || !SECRET.test(state.slice(37)) || url.searchParams.getAll('state').length !== 1) throw invalid();
        return await serial(id, async () => {
          const record = await store.read(id);
          await pending(record, state);
          if (url.pathname === '/oauth/start') {
            if (record.browserHash) throw new Problem(409, 'AUTH_STARTED', 'Sign-in has already started. Complete the Atlassian page or reconnect in ByteQuay.');
            if (request.method === 'GET') {
              const nonce = random();
              setCookie(response, id, `${nonce}.${signCookie(state, nonce)}`);
              return page(response, 200, 'Enter the eight-character code shown in your ByteQuay app. Do not enter a code sent by someone else.',
                `<form method="post" action="/oauth/start?state=${state}"><label for="code">Connection code</label><input id="code" name="code" required minlength="8" maxlength="8" autocomplete="off" pattern="[A-Fa-f0-9]{8}" autofocus><button type="submit">Continue to Atlassian</button></form>`);
            }
            if (request.method !== 'POST' || request.headers.origin !== config.publicOrigin
                || request.headers['content-type']?.split(';')[0] !== 'application/x-www-form-urlencoded'
                || !validCookie(cookie(request, id), state)) throw new Problem(403, 'INVALID_BROWSER', 'Open the connection link again from ByteQuay.');
            const form = new URLSearchParams(await boundedBody(request, 1024));
            const code = form.get('code')?.toUpperCase() ?? '';
            if (form.getAll('code').length !== 1 || !/^[A-F0-9]{8}$/.test(code) || !equal(record.codeHash, hash(code))) {
              record.attempts++;
              if (record.attempts >= 5) await failAuthorization(record, 'Too many incorrect connection codes. Start connecting again in ByteQuay.');
              else await store.save(record);
              throw new Problem(400, 'INVALID_CODE', 'The connection code is incorrect. Open the link from ByteQuay and try again.');
            }
            record.browserHash = hash(cookie(request, id)); delete record.codeHash;
            await store.save(record);
            const target = new URL('/authorize', config.authOrigin);
            target.search = new URLSearchParams({ audience: 'api.atlassian.com', client_id: config.clientId, scope: SCOPES.join(' '),
              redirect_uri: `${config.publicOrigin}/oauth/callback`, state, response_type: 'code', prompt: 'consent' }).toString();
            response.writeHead(303, { Location: target.href }); response.end(); return;
          }
          if (request.method !== 'GET' || !validCookie(cookie(request, id), state) || !equal(record.browserHash, hash(cookie(request, id)))) {
            throw new Problem(403, 'INVALID_BROWSER', 'Complete sign-in in the browser where you entered the connection code.');
          }
          delete record.stateHash; record.inFlight = true;
          await store.save(record);
          setCookie(response, id, '', 0);
          try {
            const code = url.searchParams.get('code');
            if (url.searchParams.has('error')) throw new Problem(400, 'AUTH_DENIED', 'Atlassian sign-in was not completed. Start connecting again in ByteQuay.');
            if (!code || code.length > 2048 || url.searchParams.getAll('code').length !== 1) throw invalid();
            const tokens = await exchange({ grant_type: 'authorization_code', code, redirect_uri: `${config.publicOrigin}/oauth/callback` });
            const account = await accountFor(tokens.accessToken);
            if (record.account && record.account.accountId !== account.accountId) {
              throw new Problem(409, 'ACCOUNT_MISMATCH', 'Sign in with the original Atlassian account to reconnect. Use Add account in ByteQuay to connect a different account.');
            }
            const sites = await sitesFor(tokens.accessToken);
            if (!sites.length) throw new Problem(403, 'NO_JIRA_SITES', 'No Jira sites were authorized. Check app access with your Jira administrator.');
            record.tokens = tokens; record.account = account; record.sites = sites;
            record.state = 'connected'; record.updatedAt = now();
            delete record.inFlight; delete record.browserHash;
            await store.save(record);
            return page(response, 200, 'Jira is connected. Return to ByteQuay to choose your site and board. You can close this page.');
          }
          catch (error) { await failAuthorization(record, error instanceof Problem ? error.message : 'Connection could not be saved. Reconnect Jira in ByteQuay.'); throw error; }
        });
      }
      const match = url.pathname.match(/^\/v1\/connections\/([a-f0-9-]+)(\/token|\/reconnect)?$/);
      if (!match || !ID.test(match[1]) || url.search) throw new Problem(404, 'NOT_FOUND', 'Endpoint not found.');
      return await serial(match[1], async () => {
        const record = await store.read(match[1]);
        await authorize(request, record);
        if (!match[2] && request.method === 'DELETE') { await store.remove(record.id); response.writeHead(204); response.end(); return; }
        if (match[2] === '/reconnect' && request.method === 'POST') {
          if (Object.keys(await jsonBody(request)).length) throw invalid();
          if (!record.account && (record.tokens || record.sites.length)) {
            throw new Problem(409, 'ACCOUNT_IDENTITY_REQUIRED', 'Remove this connection and add the account again to verify its Atlassian identity.');
          }
          return send(response, 200, await beginAuthorization(record));
        }
        if (record.inFlight || now() - record.updatedAt > RETENTION) {
          await fail(record, 'The Jira session must be connected again in ByteQuay.');
          throw reconnect();
        }
        if (record.state === 'pending' && now() >= record.authExpiresAt) await failAuthorization(record, 'The connection link expired. Start connecting again in ByteQuay.');
        if (!match[2] && request.method === 'GET') return send(response, 200, { id: record.id, state: record.state, sites: record.sites,
          account: record.state === 'pending' ? null : record.account ?? null, ...(record.error ? { error: record.error } : {}) });
        if (match[2] === '/token' && request.method === 'POST') {
          const body = await jsonBody(request);
          if (!ID.test(body.cloudId) || Object.keys(body).some(key => key !== 'cloudId')) throw invalid();
          if (record.state !== 'connected') throw reconnect();
          if (!record.sites.some(site => site.id === body.cloudId)) throw new Problem(403, 'SITE_NOT_AUTHORIZED', 'This Jira site is not authorized for the connection.');
          if (record.tokens.expiresAt <= now() + 30_000) {
            record.inFlight = true; await store.save(record);
            try {
              record.tokens = await exchange({ grant_type: 'refresh_token', refresh_token: record.tokens.refreshToken });
              delete record.inFlight; record.updatedAt = now();
              await store.save(record);
            }
            catch { await fail(record, 'The Jira session could not be renewed. Reconnect Jira in ByteQuay.'); throw reconnect(); }
          }
          try { record.sites = await sitesFor(record.tokens.accessToken); }
          catch (error) { if (error.status === 401) await fail(record, error.message); throw error; }
          record.updatedAt = now(); await store.save(record);
          if (!record.sites.some(site => site.id === body.cloudId)) throw new Problem(403, 'SITE_NOT_AUTHORIZED', 'This Jira site is no longer authorized for the connection.');
          return send(response, 200, { accessToken: record.tokens.accessToken, expiresAt: new Date(record.tokens.expiresAt).toISOString() });
        }
        throw new Problem(405, 'METHOD_NOT_ALLOWED', 'Method not allowed.');
      });
    }
    catch (error) {
      const problem = error instanceof Problem ? error : new Problem(500, 'INTERNAL_ERROR', 'The connection service could not complete this request.');
      // Never log URLs, bearer credentials, authorization codes, or upstream response bodies.
      if (url?.pathname.startsWith('/oauth/')) page(response, problem.status, problem.message);
      else send(response, problem.status, { code: problem.code, error: problem.message });
    }
  });
  server.maxHeadersCount = 32;
  server.keepAliveTimeout = 5000;
  return { server, close: async () => {
    await new Promise(resolveClose => server.close(resolveClose));
    await Promise.allSettled([createTail, ...locks.values()]);
    await store.close();
  } };
}

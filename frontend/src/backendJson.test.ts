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
import { afterEach, expect, it, vi } from 'vitest';
import { UNCONFIRMED_REQUEST } from './agents/contracts';
import { backendJson, backendJsonIfChanged } from './backendJson';

vi.mock('./backendProcess', () => ({ sessionToken: () => 'desktop-session' }));
afterEach(() => { vi.unstubAllGlobals(); });

it.each<HeadersInit>([{ 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'forged' },
  [['Content-Type', 'application/json'], ['X-ByteQuay-Session', 'forged']],
  new Headers({ 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'forged' })])(
  'authenticates every header representation while preserving caller headers', async supplied => {
    const fetch = vi.fn(async () => Response.json({ value: 7 }));
    vi.stubGlobal('fetch', fetch);
    const signal = new AbortController().signal;
    await expect(backendJson('/local', { headers: supplied, signal })).resolves.toEqual({ value: 7 });
    const init = (fetch.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect(new Headers(init.headers).get('X-ByteQuay-Session')).toBe('desktop-session');
    expect(new Headers(init.headers).get('Content-Type')).toBe('application/json');
    expect(init.signal).toBe(signal);
    expect(new Headers(supplied).get('X-ByteQuay-Session')).toBe('forged');
  },
);

it.each([new Response(null, { status: 204 }), new Response('')])('returns null for an empty response', async response => {
  vi.stubGlobal('fetch', vi.fn(async () => response));
  await expect(backendJson('/local')).resolves.toBeNull();
});

it.each([
  [JSON.stringify({ message: 'Busy' }), 409, 'Busy'],
  [JSON.stringify({ message: 'Busy', detail: 'Other detail' }), 409, 'Busy'],
  [JSON.stringify({ code: 'IDE_FILE_CHANGED', message: 'Reload first' }), 409, 'IDE_FILE_CHANGED: Reload first'],
  [JSON.stringify({ type: 'about:blank', title: 'Not Found', status: 404, detail: 'The GitLab project is missing or inaccessible.\n\nRequest: GET /api/v4/projects/:project\nGitLab request ID: request-123' }),
    404, 'The GitLab project is missing or inaccessible.\n\nRequest: GET /api/v4/projects/:project\nGitLab request ID: request-123'],
  [JSON.stringify({ detail: ' ' }), 500, JSON.stringify({ detail: ' ' })],
  ['Unavailable', 503, 'Unavailable'],
  ['', 500, 'GET /local returned 500'],
])('retains meaningful failure details', async (body, status, message) => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(body, { status })));
  await expect(backendJson('/local')).rejects.toThrow(message);
});

it('answers a timeout with a request that may still finish', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => { throw new DOMException('The operation timed out.', 'TimeoutError'); }));
  await expect(backendJson('/local')).rejects.toThrow(UNCONFIRMED_REQUEST);
});

it('keeps a write uncertain when the accepted response is lost before headers or during its body', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('fetch failed'); }));
  await expect(backendJson('/turns', { method: 'POST' })).rejects.toThrow(UNCONFIRMED_REQUEST);
  await expect(backendJson('/turns')).rejects.toThrow('fetch failed');

  const response = new Response(new ReadableStream({ start(controller) { controller.error(new Error('socket closed')); } }));
  vi.stubGlobal('fetch', vi.fn(async () => response));
  await expect(backendJson('/turns', { method: 'POST' })).rejects.toThrow(UNCONFIRMED_REQUEST);
});

it('keeps a server rejection distinct from a lost write response', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => Response.json({ message: 'Task version changed' }, { status: 409 })));
  await expect(backendJson('/turns', { method: 'POST' })).rejects.toThrow('Task version changed');
});

it('preserves the provider sign-in details in a typed failure', async () => {
  const detail = { timestamp: '2026-10-16T00:00:00Z', status: 503, code: 'AGENT_PROVIDER_UNAVAILABLE', laneId: 'claude',
    readiness: 'AUTHENTICATION_REQUIRED', diagnostic: 'Sign in', signInCommand: 'claude auth login', path: '/local' };
  vi.stubGlobal('fetch', vi.fn(async () => Response.json(detail, { status: 503 })));
  await expect(backendJson('/local')).rejects.toThrow(`AGENT_PROVIDER_UNAVAILABLE:${JSON.stringify(detail)}`);
});

it('names the held copy by its ETag and reads nothing back when it is unchanged', async () => {
  const fetch = vi.fn(async (_input: string, init: RequestInit) => new Headers(init.headers).get('If-None-Match') === '"v1"'
    ? new Response(null, { status: 304 })
    : Response.json([{ id: 7 }], { headers: { ETag: '"v1"' } }));
  vi.stubGlobal('fetch', fetch);

  await expect(backendJsonIfChanged('/prs/attention', null)).resolves.toEqual({ changed: true, etag: '"v1"', value: [{ id: 7 }] });
  await expect(backendJsonIfChanged('/prs/attention', '"v1"')).resolves.toEqual({ changed: false });
  const first = (fetch.mock.calls[0] as unknown as [string, RequestInit])[1];
  expect(new Headers(first.headers).has('If-None-Match')).toBe(false);
  expect(new Headers(first.headers).get('X-ByteQuay-Session')).toBe('desktop-session');
});

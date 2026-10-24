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
import { sessionToken } from './backendProcess';
import { parseAgentProviderUnavailable, UNCONFIRMED_REQUEST } from './agents/contracts';

/** Authenticated localhost JSON transport. The session secret stays in the main process. */
export async function backendJson<T = unknown>(input: string | URL, init?: RequestInit): Promise<T> {
  return body<T>(await send(input, init), init);
}

/** A GET that names the copy the caller holds by its ETag; the backend answers 304 instead of rebuilding an unchanged reply. */
export async function backendJsonIfChanged<T>(input: string | URL, etag: string | null): Promise<{ changed: false } | { changed: true; etag: string | null; value: T }> {
  const response = await send(input, etag === null ? undefined : { headers: { 'If-None-Match': etag } });
  if (response.status === 304) return { changed: false };
  return { changed: true, etag: response.headers.get('ETag'), value: await body<T>(response) };
}

async function send(input: string | URL, init?: RequestInit): Promise<Response> {
  const headers = new Headers(init?.headers);
  headers.set('X-ByteQuay-Session', sessionToken());
  const mutation = !['GET', 'HEAD'].includes((init?.method ?? 'GET').toUpperCase());
  const response = await fetch(input, { signal: AbortSignal.timeout(60_000), ...init, headers }).catch((failure: unknown) => {
    // A lost connection cannot prove that a write was rejected; its request key must survive retry.
    throw mutation || (failure as { name?: unknown } | null)?.name === 'TimeoutError' ? new Error(UNCONFIRMED_REQUEST) : failure;
  });
  if (!response.ok && response.status !== 304) {
    const text = await response.text().catch(() => '');
    let body: unknown;
    try { body = JSON.parse(text); }
    catch { /* Non-JSON failures retain the server's text. */ }
    if (body !== null && typeof body === 'object') {
      if (response.status === 503 && 'code' in body && body.code === 'AGENT_PROVIDER_UNAVAILABLE') {
        let detail;
        try { detail = parseAgentProviderUnavailable(body); }
        catch { /* Malformed typed errors use the ordinary HTTP failure below. */ }
        if (detail) throw new Error(`AGENT_PROVIDER_UNAVAILABLE:${JSON.stringify(detail)}`);
      }
      if ('message' in body && typeof body.message === 'string' && body.message) {
        throw new Error('code' in body && body.code === 'IDE_FILE_CHANGED' ? `IDE_FILE_CHANGED: ${body.message}` : body.message);
      }
    }
    throw new Error(text || `${init?.method ?? 'GET'} ${input.toString()} returned ${response.status}`);
  }
  return response;
}

async function body<T>(response: Response, init?: RequestInit): Promise<T> {
  const mutation = !['GET', 'HEAD'].includes((init?.method ?? 'GET').toUpperCase());
  if (response.status === 204) return null as T;
  try {
    const text = await response.text();
    return text ? JSON.parse(text) as T : null as T;
  }
  catch (failure) {
    // Headers may arrive before a connection fails midway through the accepted response body.
    throw mutation ? new Error(UNCONFIRMED_REQUEST) : failure;
  }
}

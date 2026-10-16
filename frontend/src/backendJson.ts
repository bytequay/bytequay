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
  const headers = new Headers(init?.headers);
  headers.set('X-ByteQuay-Session', sessionToken());
  const response = await fetch(input, { signal: AbortSignal.timeout(60_000), ...init, headers }).catch((failure: unknown) => {
    // A timeout says nothing about whether the backend acted, so it answers with that rather than an abort error.
    throw (failure as { name?: unknown } | null)?.name === 'TimeoutError' ? new Error(UNCONFIRMED_REQUEST) : failure;
  });
  if (!response.ok) {
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
  if (response.status === 204) return null as T;
  const text = await response.text();
  return text ? JSON.parse(text) as T : null as T;
}

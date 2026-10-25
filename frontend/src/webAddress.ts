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
export function normalizeWebAddress(value: unknown): string {
  if (typeof value !== 'string' || !value.trim()) throw new Error('Enter a web address.');
  const address = value.trim();
  const hostWithPort = /^(?:localhost|[^/?#:\s]+\.[^/?#:\s]+):\d+(?:[/?#]|$)/i.test(address);
  const hasScheme = !hostWithPort && /^[a-z][a-z\d+.-]*:/i.test(address);
  let url: URL;
  try {
    url = new URL(hasScheme ? address : `https://${address}`);
  }
  catch {
    throw new Error('Enter a valid HTTP or HTTPS address.');
  }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) {
    throw new Error('Use an HTTP or HTTPS address without embedded credentials.');
  }
  return url.href;
}

/** Address-bar text: spaced words or a bare word search Google, like a browser's address bar; anything else must be an address. */
export function typedWebAddress(value: string): string {
  const text = value.trim();
  const words = /\s/.test(text) && !/^https?:\/\//i.test(text);
  const bareWord = !/[.:]/.test(text) && text.toLowerCase() !== 'localhost';
  if (text && (words || bareWord)) return `https://www.google.com/search?q=${encodeURIComponent(text)}`;
  return normalizeWebAddress(text);
}

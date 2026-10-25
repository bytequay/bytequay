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
import { describe, expect, it } from 'vitest';
import { normalizeWebAddress, typedWebAddress } from './webAddress';

describe('typed browser addresses', () => {
  it.each([
    ['calendar.google.com/calendar/u/0/r', 'https://calendar.google.com/calendar/u/0/r'],
    ['  https://calendar.google.com/calendar/u/0/r  ', 'https://calendar.google.com/calendar/u/0/r'],
    ['http://localhost:3000/path?next=calendar#today', 'http://localhost:3000/path?next=calendar#today'],
    ['HTTPS://EXAMPLE.COM/path', 'https://example.com/path'],
    ['localhost:3000', 'https://localhost:3000/'],
    ['example.com:8443/path', 'https://example.com:8443/path'],
  ])('opens %s without losing its path, query or fragment', (address, expected) => {
    expect(normalizeWebAddress(address)).toBe(expected);
  });

  it.each(['', '   ', 'not a hostname', 'javascript:alert(1)', 'javascript:123', 'file:///etc/passwd', 'data:text/html,test',
    'https://user:password@example.com', null, 42])('rejects invalid or unsafe input %s', address => {
    expect(() => normalizeWebAddress(address)).toThrow();
  });
});

describe('address-bar text', () => {
  it.each([
    ['release notes', 'release%20notes'],
    ['  weather  ', 'weather'],
    ['c++ & rust?', 'c%2B%2B%20%26%20rust%3F'],
    ['javascript:alert(1) now', 'javascript%3Aalert(1)%20now'],
  ])('searches Google for %s', (text, query) => {
    expect(typedWebAddress(text)).toBe(`https://www.google.com/search?q=${query}`);
  });

  it.each([
    ['example.com/docs', 'https://example.com/docs'],
    ['localhost', 'https://localhost/'],
    ['localhost:3000', 'https://localhost:3000/'],
    ['https://example.com/a b', 'https://example.com/a%20b'],
  ])('opens %s as an address', (text, expected) => {
    expect(typedWebAddress(text)).toBe(expected);
  });

  it.each(['', '   ', 'javascript:alert(1)', 'file:///etc/passwd'])('still rejects %s', text => {
    expect(() => typedWebAddress(text)).toThrow();
  });
});

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
import { expect, it } from 'vitest';
import { parseCodeQuery, parseCodeQueryReply } from './codeQuery';

it('accepts only fixed semantic operations and bounded workspace locations', () => {
  expect(parseCodeQuery({ operation: 'SYMBOLS', path: 'src/測試.go', query: 'Writer', line: null, column: null, limit: 20 })).toEqual({
    operation: 'SYMBOLS', path: 'src/測試.go', query: 'Writer', limit: 20,
  });
  for (const patch of [{ path: '../outside' }, { path: '/etc/passwd' }, { path: '.git/config' }, { path: 'src/.GIT/config' }, { path: 'a//b' },
    { operation: 'executeCommand' }, { query: 'x'.repeat(201) }, { limit: 201 }, { line: 0 }]) {
    expect(() => parseCodeQuery({ operation: 'REFERENCES', path: 'src/main.rs', line: 2, column: 3, limit: 20, ...patch })).toThrow();
  }
});

it('bounds provider replies and strips arbitrary payloads before transport', () => {
  const item = { path: 'src/main.rs', line: 2, column: 3, endLine: 2, endColumn: 5, privateToken: 'never forward' };
  const reply = { status: 'READY', items: [item], truncated: false, message: 'Imported project' };
  expect(parseCodeQueryReply(reply).items[0]).toEqual({ path: item.path, line: 2, column: 3, endLine: 2, endColumn: 5, calls: [] });
  for (const patch of [{ status: 'UNSAVED' }, { items: Array.from({ length: 201 }, () => item) },
    { items: [{ ...item, path: '../secret' }] }, { items: [{ ...item, name: 'x'.repeat(2001) }] },
    { items: [{ ...item, calls: [{ ...item, path: '.git/config' }] }] }]) expect(() => parseCodeQueryReply({ ...reply, ...patch })).toThrow();
});

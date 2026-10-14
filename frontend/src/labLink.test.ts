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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DEFAULT_LAB_ADDRESS, LAB_LINK_CHANGED, labAddress, readLabLink, saveLabLink } from './labLink';

const OFF = { enabled: false, address: DEFAULT_LAB_ADDRESS };

afterEach(() => { vi.unstubAllGlobals(); });

function storage() {
  const values = new Map<string, string>();
  const dispatchEvent = vi.fn();
  vi.stubGlobal('window', {
    localStorage: { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => values.set(key, value) },
    dispatchEvent,
  });
  return { values, dispatchEvent };
}

describe('lab address', () => {
  it.each([
    ['http://127.0.0.1:8517', 'http://127.0.0.1:8517/'],
    ['  127.0.0.1:8600  ', 'http://127.0.0.1:8600/'],
    ['localhost:8517', 'http://localhost:8517/'],
    ['HTTP://LOCALHOST:8517/', 'http://localhost:8517/'],
    ['http://[::1]:8517', 'http://[::1]:8517/'],
    ['https://localhost:8517/lab?tab=reviews', 'https://localhost:8517/lab?tab=reviews'],
  ])('reads %s as an address on this Mac', (typed, expected) => {
    expect(labAddress(typed)).toBe(expected);
  });

  it.each(['', '   ', 'http://example.com:8517', 'http://127.0.0.1.example.com:8517', 'http://192.168.1.20:8517', 'http://user:secret@127.0.0.1:8517',
    'file:///etc/passwd', 'javascript:alert(1)', 'ftp://127.0.0.1/', 'not an address', null, 8517])('refuses %s', typed => {
    expect(labAddress(typed)).toBeNull();
  });

  it('starts from an address it would accept as typed', () => {
    expect(labAddress(DEFAULT_LAB_ADDRESS)).toBe(DEFAULT_LAB_ADDRESS);
  });
});

describe('lab link', () => {
  it('is off at the usual address until one is saved, and when what is stored is not a lab link', () => {
    const { values } = storage();
    expect(readLabLink()).toEqual(OFF);
    for (const stored of ['{', 'null', '[]', '{"enabled":true}', '{"enabled":"yes","address":"http://127.0.0.1:8517/"}',
      '{"enabled":true,"address":"http://example.com/"}']) {
      values.set('bytequay.lab-link', stored);
      expect(readLabLink()).toEqual(OFF);
    }
  });

  it('keeps the switch and the address on this Mac and says when either changes', () => {
    const { values, dispatchEvent } = storage();
    saveLabLink({ enabled: true, address: 'http://localhost:8600/' });
    expect(JSON.parse(values.get('bytequay.lab-link') ?? '')).toEqual({ enabled: true, address: 'http://localhost:8600/' });
    expect(readLabLink()).toEqual({ enabled: true, address: 'http://localhost:8600/' });
    expect(dispatchEvent.mock.calls.map(call => (call[0] as Event).type)).toEqual([LAB_LINK_CHANGED]);
    saveLabLink({ enabled: false, address: 'http://localhost:8600/' });
    expect(readLabLink()).toEqual({ enabled: false, address: 'http://localhost:8600/' });
  });

  it('changes the switch or the address and keeps the other as it is saved', () => {
    storage();
    saveLabLink({ enabled: true });
    expect(readLabLink()).toEqual({ enabled: true, address: DEFAULT_LAB_ADDRESS });
    saveLabLink({ address: 'http://localhost:8600/' });
    expect(readLabLink()).toEqual({ enabled: true, address: 'http://localhost:8600/' });
    saveLabLink({ enabled: false });
    expect(readLabLink()).toEqual({ enabled: false, address: 'http://localhost:8600/' });
  });

  it('stays off without local storage and does not fail the page that saves', () => {
    const unavailable = () => { throw new Error('Storage unavailable'); };
    vi.stubGlobal('window', { localStorage: { getItem: unavailable, setItem: unavailable }, dispatchEvent: vi.fn() });
    expect(readLabLink()).toEqual(OFF);
    expect(() => saveLabLink({ enabled: true, address: DEFAULT_LAB_ADDRESS })).not.toThrow();
  });
});

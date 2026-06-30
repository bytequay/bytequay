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
import { makeIdCache } from './idCache';

describe('makeIdCache', () => {
  it('returns undefined for an unseen id and the last value once set', () => {
    const cache = makeIdCache<number>();
    expect(cache.get('a')).toBeUndefined();
    cache.set('a', 1);
    expect(cache.get('a')).toBe(1);
    cache.set('a', 2);
    expect(cache.get('a')).toBe(2);
  });

  it('keeps entries independent per id', () => {
    const cache = makeIdCache<string>();
    cache.set('x', 'one');
    cache.set('y', 'two');
    expect(cache.get('x')).toBe('one');
    expect(cache.get('y')).toBe('two');
  });
});

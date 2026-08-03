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
import {
  formatCost, formatDuration, formatTokensK, relativeLong, relativeShort, shortPaths,
} from './format';

describe('formatDuration', () => {
  it('renders sub-minute as seconds', () => {
    expect(formatDuration(0)).toBe('0s');
    expect(formatDuration(42)).toBe('42s');
  });

  it('renders whole minutes without a seconds suffix', () => {
    expect(formatDuration(12 * 60)).toBe('12m');
  });

  it('renders minutes with a remainder', () => {
    expect(formatDuration(23 * 60 + 14)).toBe('23m 14s');
  });

  it('renders hours and minutes', () => {
    expect(formatDuration(4 * 3600 + 12 * 60)).toBe('4h 12m');
    expect(formatDuration(2 * 3600)).toBe('2h');
  });
});

describe('formatCost', () => {
  it('renders cents as dollars with two decimals', () => {
    expect(formatCost(147)).toBe('$1.47');
    expect(formatCost(0)).toBe('$0.00');
    expect(formatCost(5)).toBe('$0.05');
  });
});

describe('formatTokensK', () => {
  it('renders thousands with one decimal', () => {
    expect(formatTokensK(86_000)).toBe('86.0k');
    expect(formatTokensK(200_000)).toBe('200.0k');
  });
});

describe('shortPaths', () => {
  it('keeps the last two segments of a quoted path with spaces', () => {
    expect(shortPaths(
      'cd "/Users/me/Library/Application Support/ByteQuay/repos/o/r/.worktree/t9" && mvn verify'))
      .toBe('cd "…/.worktree/t9" && mvn verify');
  });

  it('shortens a bare path argument', () => {
    expect(shortPaths('/repo/.worktree/x/backend/src/main/java/Foo.java'))
      .toBe('…/java/Foo.java');
  });

  it('shortens every path in a command, not just the first', () => {
    expect(shortPaths('cp /a/b/c/one.txt /d/e/f/two.txt')).toBe('cp …/c/one.txt …/f/two.txt');
  });

  it('leaves short paths, relative paths and URLs alone', () => {
    expect(shortPaths('/etc/hosts')).toBe('/etc/hosts');
    expect(shortPaths('backend/src/main/java/Foo.java')).toBe('backend/src/main/java/Foo.java');
    expect(shortPaths('curl http://localhost:8080/api/stages/s1'))
      .toBe('curl http://localhost:8080/api/stages/s1');
  });

  it('leaves a plain command untouched', () => {
    expect(shortPaths('git status')).toBe('git status');
  });
});

describe('relativeShort / relativeLong', () => {
  const now = Date.parse('2026-06-20T12:00:00.000Z');
  const back = (ms: number) => new Date(now - ms).toISOString();

  it('reads sub-45s as "now"', () => {
    expect(relativeShort(back(10_000), now)).toBe('now');
    expect(relativeLong(back(10_000), now)).toBe('now');
  });

  it('reads minutes', () => {
    expect(relativeShort(back(14 * 60_000), now)).toBe('14m ago');
    expect(relativeLong(back(14 * 60_000), now)).toBe('14 minutes ago');
    expect(relativeLong(back(60_000), now)).toBe('1 minute ago');
  });

  it('reads hours and days', () => {
    expect(relativeShort(back(21 * 3_600_000), now)).toBe('21h ago');
    expect(relativeLong(back(3_600_000), now)).toBe('1 hour ago');
    expect(relativeShort(back(3 * 86_400_000), now)).toBe('3d ago');
  });
});

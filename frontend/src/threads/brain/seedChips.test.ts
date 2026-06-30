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
import { extractSeedChips } from './seedChips';

describe('extractSeedChips', () => {
  it('pulls type / validate / push / out-of-scope from a real seed', () => {
    const seed = 'Refactor: collapse the eight hand-rolled parsers into one shared parser. '
      + 'Gate before committing: mvn verify. Leave it committed locally; do not push or open a PR. '
      + 'Out of scope — do NOT bundle the cosmetic stream/Optional rewrites.';
    const chips = extractSeedChips(seed);
    expect(chips.type).toBe('Refactor');
    expect(chips.validate).toBe('mvn verify');
    expect(chips.push).toBe('local only');
    expect(chips.outOfScope).toMatch(/stream\/Optional/);
  });

  it('returns nothing for prose with no recognisable cues', () => {
    expect(extractSeedChips('please make the thing nicer somehow')).toEqual({});
  });

  it('detects an open-PR push strategy', () => {
    expect(extractSeedChips('build the feature and open a PR when done').push).toBe('open PR');
  });
});

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
import { contiguousRange } from './commitRange';

const ORDER = ['c0', 'c1', 'c2', 'c3', 'c4'];

describe('contiguousRange', () => {
  it('selects a single commit when anchor === target', () => {
    expect([...contiguousRange(ORDER, 'c2', 'c2')]).toEqual(['c2']);
  });

  it('fills the inclusive run between anchor and target (forward)', () => {
    expect([...contiguousRange(ORDER, 'c1', 'c3')]).toEqual(['c1', 'c2', 'c3']);
  });

  it('fills the run regardless of click direction (backward)', () => {
    expect([...contiguousRange(ORDER, 'c3', 'c1')]).toEqual(['c1', 'c2', 'c3']);
  });

  it('spans the whole list end to end', () => {
    expect([...contiguousRange(ORDER, 'c0', 'c4')]).toEqual(ORDER);
  });

  it('falls back to a single target selection when the anchor is unknown', () => {
    expect([...contiguousRange(ORDER, 'gone', 'c2')]).toEqual(['c2']);
  });

  it('falls back to a single target selection when the target is unknown', () => {
    expect([...contiguousRange(ORDER, 'c1', 'gone')]).toEqual(['gone']);
  });
});

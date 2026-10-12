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

import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { Critter, CRITTER_KINDS, critterPose, randomCritter } from './Critter';
import { CRITTER_ART } from './critterArt';

const LOOP_SECONDS = { turtle: 2.6, cat: 3 * .42 + 1.05, rabbit: .9, tiger: .6, dog: .46, eagle: 1.1 };

describe('critter', () => {
  it('offers the six designed animals', () => {
    expect(CRITTER_KINDS).toEqual(['turtle', 'cat', 'rabbit', 'tiger', 'dog', 'eagle']);
    expect(new Set(Array.from({ length: 200 }, randomCritter))).toEqual(new Set(CRITTER_KINDS));
  });

  it('poses every joint its art draws, and closes each loop without a seam', () => {
    for (const kind of CRITTER_KINDS) {
      const joints = Array.from(CRITTER_ART[kind].matchAll(/data-j="([^"]+)"/g), (match) => match[1]);
      expect(joints.length, kind).toBeGreaterThan(5);
      const numbers = (seconds: number) => {
        const pose = critterPose(kind, seconds);
        expect(Object.keys(pose), kind).toEqual(expect.arrayContaining(joints));
        return joints.flatMap((joint) => {
          const value = pose[joint];
          return typeof value === 'number' ? [value] : Object.values(value);
        });
      };
      for (const seconds of [.37, 1.9]) expect(numbers(seconds).every(Number.isFinite), kind).toBe(true);
      const start = numbers(0);
      numbers(LOOP_SECONDS[kind]).forEach((value, index) => expect(value, kind).toBeCloseTo(start[index], 6));
    }
  });

  it('draws a decorative critter whose gradients belong to that one instance', () => {
    for (const kind of CRITTER_KINDS) {
      const markup = renderToStaticMarkup(<Critter kind={kind} moving />);
      expect(markup).toMatch(/^<span class="agt-critter is-moving" aria-hidden="true"><svg viewBox="0 0 120 80">/);
      expect(markup).not.toMatch(/[#"]crt-/);
      const ids = new Set(Array.from(markup.matchAll(/ id="([^"]+)"/g), (match) => match[1]));
      for (const [, reference] of markup.matchAll(/url\(#([^)]+)\)/g)) expect(ids, kind).toContain(reference);
    }
    expect(renderToStaticMarkup(<Critter kind="cat" moving={false} />)).toContain('class="agt-critter"');
  });
});

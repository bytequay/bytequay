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

import React,{ useId,useLayoutEffect,useRef } from 'react';
import { CRITTER_ART } from './critterArt';

export type CritterKind = keyof typeof CRITTER_ART;

export const CRITTER_KINDS = Object.keys(CRITTER_ART) as CritterKind[];

export function randomCritter(): CritterKind {
  return CRITTER_KINDS[Math.floor(Math.random() * CRITTER_KINDS.length)];
}

/** A joint's rotation in degrees, or its offset, rotation, and scale. */
type JointPose = number | { r?: number; dx?: number; dy?: number; sx?: number; sy?: number };
type Pose = Record<string, JointPose>;
type Key = [time: number, value: number];
type Keys = Record<string, Key[]>;

/** Pairs each channel's values with the track's key times `T`, which start at 0 and stay below 1. */
function keys({ T, ...channels }: Record<string, number[]>): Keys {
  return Object.fromEntries(Object.entries(channels).map(([channel, values]) => [channel, values.map((value, i): Key => [T[i], value])]));
}

const GALLOP = {
  ...keys({ T: [0, .2, .35, .45, .58, .75, .88], fu: [-60, -25, 5, 35, 30, -15, -50], fl: [-10, 5, 0, 20, 110, 100, 30] }),
  ...keys({ T: [0, .2, .4, .55, .7, .85], ht: [25, 0, -40, -60, -45, -5], hb: [35, 80, 90, 70, 60, 45], hm: [20, -60, -60, -35, -30, -20], sp: [4, 0, -5, -10, -6, 0] }),
  ...keys({ T: [0, .2, .35, .55, .72, .88], y: [-2, 1.5, 1, -2.5, .4, 0], pt: [-1, 3, 1, 0, -3, -2], t1: [-5, 5, 8, 3, -4, -8], t2: [8, 0, -8, -6, 4, 10], ear: [-6, 22, 12, 0, -10, -14] }),
};

const BOUND = keys({
  T: [0, .14, .34, .56, .78], ht: [-55, -15, 35, 15, -45], hb: [95, 60, 35, 85, 95], hf: [-126, -85, -15, -70, -120], fu: [10, 25, -45, -20, 0], fl: [5, 70, -5, 0, 0],
  sp: [-6, 4, 8, -3, -8], y: [0, -3, -12, -3, -1.5], pt: [0, -10, -4, 9, 5], ear: [4, -10, -18, -6, 6],
});

/** Periodic cubic Hermite through the keys, so a loop closes without a seam. */
function interpolate(track: Key[], phase: number): number {
  const p = ((phase % 1) + 1) % 1;
  const n = track.length;
  let i = 0;
  for (let k = 0; k < n; k++) if (track[k][0] <= p) i = k;
  const at = (k: number): Key => {
    const wrapped = ((k % n) + n) % n;
    return [track[wrapped][0] + Math.floor(k / n), track[wrapped][1]];
  };
  const [t0, v0] = at(i - 1), [t1, v1] = at(i), [t2, v2] = at(i + 1), [t3, v3] = at(i + 2);
  const h = t2 - t1, u = (p - t1) / h, m1 = (v2 - v0) / (t2 - t0) * h, m2 = (v3 - v1) / (t3 - t1) * h;
  const u2 = u * u, u3 = u2 * u;
  return (2 * u3 - 3 * u2 + 1) * v1 + (u3 - 2 * u2 + u) * m1 + (3 * u2 - 2 * u3) * v2 + (u3 - u2) * m2;
}

const gallop = (channel: string, phase: number): number => interpolate(GALLOP[channel], phase);

const LEAP_APEX: Record<string, number> = { fu: -75, fl: -15, ht: 45, hb: 30, hm: 25, sp: 7, y: -20, pt: -7, t1: -10, t2: 12, ear: -20 };
const LEAP_FALL: Record<string, number> = { fu: -50, fl: -5, ht: 30, hb: 40, hm: 15, sp: 4, y: -14, pt: 7, t1: -4, t2: 6, ear: -10 };
/** The cat's leap: gallop phases into a crouch, the two airborne poses, then gallop phases to land. */
const LEAP_STEPS: [time: number, pose: number | Record<string, number>, crouch?: Record<string, number>][] = [
  [0, 0], [.1, .2], [.2, .45], [.28, .58], [.36, .7, { y: 2.5, sp: -4 }], [.44, .85, { pt: -8, y: -1 }], [.56, LEAP_APEX], [.68, LEAP_FALL], [.8, .2], [.87, .45], [.93, .7],
];
const LEAP: Keys = Object.fromEntries(Object.keys(GALLOP).map(channel => [channel, LEAP_STEPS.map(([time, pose, crouch]): Key =>
  [time, typeof pose === 'number' ? gallop(channel, pose) + (crouch?.[channel] ?? 0) : pose[channel]])]));

function quadruped(t: number, channel: (name: string, time: number) => number, stride: number, scale: { sp: number; y: number }): Pose {
  const g = (name: string, delay = 0): number => channel(name, t - delay * stride);
  const y = g('y') * scale.y, pitch = g('pt') * scale.y;
  return {
    body: { dy: y, r: pitch }, hips: g('sp') * scale.sp, head: -pitch * .8, ear: g('ear'),
    'NF.u': g('fu'), 'NF.l': g('fl'), 'FF.u': g('fu', .08), 'FF.l': g('fl', .08),
    'NH.t': g('ht'), 'NH.b': g('hb'), 'NH.m': g('hm'), 'FH.t': g('ht', .06), 'FH.b': g('hb', .06), 'FH.m': g('hm', .06),
    tail1: g('t1', .05), tail2: g('t2', .1), shadow: { sx: Math.max(.35, 1 + y / 26) },
  };
}

const runner = (stride: number, scale: { sp: number; y: number }) => (t: number): Pose =>
  quadruped(t, (name, time) => gallop(name, time / stride), stride, scale);

const CAT_STRIDE = .42, CAT_LEAP = 1.05, CAT_LOOP = 3 * CAT_STRIDE + CAT_LEAP;

const POSES: Record<CritterKind, (t: number) => Pose> = {
  turtle: (t) => {
    const p = ((t / 2.6) % 1 + 1) % 1;
    const leg = (phase: number): JointPose => {
      const q = ((phase % 1) + 1) % 1;
      if (q < .65) return { r: -14 + 28 * q / .65 };
      const s = (q - .65) / .35, ease = (1 - Math.cos(Math.PI * s)) / 2;
      return { r: 14 - 28 * ease, dy: -3.2 * Math.sin(Math.PI * s) };
    };
    const w = 4 * Math.PI * p;
    return {
      NF: leg(p), FB: leg(p + .03), FF: leg(p + .5), NB: leg(p + .53),
      body: { dy: .45 * Math.cos(w), r: .5 * Math.sin(2 * Math.PI * p) }, head: { dx: .9 + .9 * Math.sin(w - 1), r: -1.5 * Math.sin(w) },
    };
  },
  // Three gallop strides, then one leap.
  cat: (t) => quadruped(t, (name, time) => {
    const at = ((time % CAT_LOOP) + CAT_LOOP) % CAT_LOOP;
    return at < 3 * CAT_STRIDE ? gallop(name, at / CAT_STRIDE) : interpolate(LEAP[name], (at - 3 * CAT_STRIDE) / CAT_LEAP);
  }, CAT_STRIDE, { sp: 1, y: 1 }),
  rabbit: (t) => {
    const g = (name: string, delay = 0): number => interpolate(BOUND[name], (t - delay) / .9);
    const y = g('y'), pitch = g('pt'), ear = g('ear');
    return {
      body: { dy: y, r: pitch }, hips: g('sp'), head: -pitch * .5, earN: -10 + ear, earF: -16 + ear * .8,
      'NH.t': g('ht'), 'NH.b': g('hb'), 'NH.f': g('hf'), 'FH.t': g('ht', .03), 'FH.b': g('hb', .03), 'FH.f': g('hf', .03),
      'NF.u': g('fu'), 'NF.l': g('fl'), 'FF.u': g('fu', .04), 'FF.l': g('fl', .04),
      shadow: { sx: Math.max(.4, 1 + y / 22) },
    };
  },
  tiger: runner(.6, { sp: .6, y: .8 }),
  dog: runner(.46, { sp: .8, y: .9 }),
  eagle: (t) => {
    const p = ((t / 1.1) % 1 + 1) % 1, down = .42;
    const u = p < down ? p / down : (p - down) / (1 - down), ease = (1 - Math.cos(Math.PI * u)) / 2;
    // Downstroke, then an upstroke that folds the wrist.
    const [spread, r, fold, dy] = p < down ? [1 - 1.8 * ease, -8 + 18 * ease, 0, 2 - 4 * ease] : [-.8 + 1.8 * ease, 10 - 18 * ease, -40 * Math.sin(Math.PI * u), -2 + 4 * ease];
    const sway = Math.sin(2 * Math.PI * p);
    return { body: { dy, r: sway * 1.2 }, wingN: { r, sy: spread }, wingF: { r: r - 5, sy: spread * .95 }, handN: fold, handF: fold, tail: sway * 2.5, head: -sway * 1.5 };
  },
};

/** The pose of every joint in a critter's art, `seconds` into its loop. */
export function critterPose(kind: CritterKind, seconds: number): Pose {
  return POSES[kind](seconds);
}

export function Critter({ kind, moving }: { kind: CritterKind; moving: boolean }): React.JSX.Element {
  const host = useRef<HTMLSpanElement>(null);
  const id = useId().replace(/\W/g, '');
  // A hidden conversation showing the same critter would otherwise own the gradient ids and blank this one.
  const art = CRITTER_ART[kind].replaceAll('crt-', `crt${id}-`);
  const elapsed = useRef(0);
  // A layout effect, so the art's unposed joints never reach the screen.
  useLayoutEffect(() => {
    const joints = Array.from(host.current.querySelectorAll<SVGGElement>('[data-j]'), (element) => ({
      element,
      name: element.dataset.j,
      anchored: element.hasAttribute('data-x'),
      x: Number(element.dataset.x ?? 0),
      y: Number(element.dataset.y ?? 0),
      pivot: `${element.dataset.cx ?? 0} ${element.dataset.cy ?? 0}`,
    }));
    const draw = (): void => {
      const pose = critterPose(kind, elapsed.current);
      for (const joint of joints) {
        const value = pose[joint.name];
        const { r = 0, dx = 0, dy = 0, sx, sy } = typeof value === 'number' ? { r: value } : value;
        const placed = joint.anchored
          ? `translate(${(joint.x + dx).toFixed(2)} ${(joint.y + dy).toFixed(2)}) rotate(${r.toFixed(2)})`
          : `translate(${dx.toFixed(2)} ${dy.toFixed(2)}) rotate(${r.toFixed(2)} ${joint.pivot})`;
        joint.element.setAttribute('transform', sx == null && sy == null ? placed : `${placed} scale(${(sx ?? 1).toFixed(3)} ${(sy ?? 1).toFixed(3)})`);
      }
    };
    // A paused critter holds its stride and picks it up again from there.
    draw();
    if (!moving || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return undefined;
    const start = performance.now() - elapsed.current * 1000;
    let frame = requestAnimationFrame(function step(now) {
      frame = requestAnimationFrame(step);
      elapsed.current = (now - start) / 1000;
      draw();
    });
    return () => cancelAnimationFrame(frame);
  }, [kind, moving]);
  return <span ref={host} className={`agt-critter${moving ? ' is-moving' : ''}`} aria-hidden="true" dangerouslySetInnerHTML={{ __html: art }} />;
}

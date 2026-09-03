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

import React from 'react';

/** The design's mark family: one shape and one colour per agent, chosen from its name alone. */
export const AGENT_MARK_FAMILY = [
  'flower', 'flower-lotus', 'leaf', 'plant', 'sun', 'moon-stars',
  'planet', 'shooting-star', 'sparkle', 'butterfly', 'feather', 'acorn',
] as const;
export const AGENT_MARK_PALETTE = ['#0969da', '#8250df', '#bf3989', '#1a7f37', '#9a6700', '#bc4c00', '#1b7c83', '#cf222e'] as const;
export type AgentMarkShape = typeof AGENT_MARK_FAMILY[number];

/**
 * FNV-1a over the name plus one mixing round, exactly as the design computes it, so the same agent
 * wears the same mark in chips, panel rows and headers, in every session.
 */
export function agentMark(name: string): { shape: AgentMarkShape; hue: number } {
  const seed = `${name}#2591`;
  let h = 2166136261;
  for (let i = 0; i < seed.length; i++) {
    h ^= seed.charCodeAt(i);
    h = Math.imul(h, 16777619) >>> 0;
  }
  h ^= h >>> 16;
  h = Math.imul(h, 0x45d9f3b) >>> 0;
  h ^= h >>> 16;
  h >>>= 0;
  return { shape: AGENT_MARK_FAMILY[(h >>> 3) % AGENT_MARK_FAMILY.length], hue: h & 7 };
}

// Duotone: a light fill of the same colour under a stroked outline, on a 16-unit grid.
const GLYPHS: Record<AgentMarkShape, React.JSX.Element> = {
  flower: (
    <>
      <circle className="agt-mark__tone" cx="8" cy="3.4" r="2.2" /><circle className="agt-mark__tone" cx="12.3" cy="6.5" r="2.2" />
      <circle className="agt-mark__tone" cx="10.7" cy="11.6" r="2.2" /><circle className="agt-mark__tone" cx="5.3" cy="11.6" r="2.2" />
      <circle className="agt-mark__tone" cx="3.7" cy="6.5" r="2.2" /><circle cx="8" cy="8" r="1.9" fill="currentColor" stroke="none" />
    </>
  ),
  'flower-lotus': (
    <>
      <path className="agt-mark__tone" d="M8 13.5C5.5 12 4.5 9 5 5.5 6.5 7 7.5 9 8 11c.5-2 1.5-4 3-5.5.5 3.5-.5 6.5-3 8z" />
      <path d="M2.5 9c1.5 3 3.5 4.5 5.5 4.5S12 12 13.5 9" /><path d="M8 13.5C5.5 12 4.5 9 5 5.5 6.5 7 7.5 9 8 11c.5-2 1.5-4 3-5.5.5 3.5-.5 6.5-3 8z" />
    </>
  ),
  leaf: (
    <>
      <path className="agt-mark__tone" d="M13 3C7 3 3.5 6.5 3.5 12.5 9.5 12.5 13 9 13 3z" />
      <path d="M13 3C7 3 3.5 6.5 3.5 12.5 9.5 12.5 13 9 13 3z" /><path d="M3.5 12.5 9 7" />
    </>
  ),
  plant: (
    <>
      <path className="agt-mark__tone" d="M8 9C8 6 6 4.5 3 4.5c0 3 2 4.5 5 4.5zm0 0c0-3 2-4.5 5-4.5 0 3-2 4.5-5 4.5z" />
      <path d="M8 9C8 6 6 4.5 3 4.5c0 3 2 4.5 5 4.5zm0 0c0-3 2-4.5 5-4.5 0 3-2 4.5-5 4.5z" /><path d="M8 9v4.5" />
    </>
  ),
  sun: (
    <>
      <circle className="agt-mark__tone" cx="8" cy="8" r="3.2" /><circle cx="8" cy="8" r="3.2" />
      <path d="M8 1.8v1.7M8 12.5v1.7M1.8 8h1.7M12.5 8h1.7M3.6 3.6l1.2 1.2M11.2 11.2l1.2 1.2M12.4 3.6l-1.2 1.2M4.8 11.2l-1.2 1.2" />
    </>
  ),
  'moon-stars': (
    <>
      <path className="agt-mark__tone" d="M9.5 2.5a5.5 5.5 0 1 0 4 9.3A6 6 0 0 1 9.5 2.5z" />
      <path d="M9.5 2.5a5.5 5.5 0 1 0 4 9.3A6 6 0 0 1 9.5 2.5z" /><path d="M12.5 3.5v2M11.5 4.5h2M3 11v1.6M2.2 11.8h1.6" />
    </>
  ),
  planet: (
    <>
      <circle className="agt-mark__tone" cx="8" cy="8" r="3.4" /><circle cx="8" cy="8" r="3.4" />
      <path d="M2.4 10.6c1.8 1.1 5 1.2 8.3.2 3-.9 4.4-2.2 3.1-3.3M3.6 5.6C2.3 6.8 2.6 8.1 3.6 8.9" />
    </>
  ),
  'shooting-star': (
    <>
      <path className="agt-mark__tone" d="M10.5 3.5l1 2.3 2.5.3-1.8 1.7.5 2.5-2.2-1.2-2.2 1.2.5-2.5L7 6.1l2.5-.3z" />
      <path d="M10.5 3.5l1 2.3 2.5.3-1.8 1.7.5 2.5-2.2-1.2-2.2 1.2.5-2.5L7 6.1l2.5-.3z" /><path d="M6.5 10.5l-4 3M8.5 12l-2 1.5M5 8.5l-2.5 2" />
    </>
  ),
  sparkle: (
    <>
      <path className="agt-mark__tone" d="M8 2c.6 3.3 2.7 5.4 6 6-3.3.6-5.4 2.7-6 6-.6-3.3-2.7-5.4-6-6 3.3-.6 5.4-2.7 6-6z" />
      <path d="M8 2c.6 3.3 2.7 5.4 6 6-3.3.6-5.4 2.7-6 6-.6-3.3-2.7-5.4-6-6 3.3-.6 5.4-2.7 6-6z" />
    </>
  ),
  butterfly: (
    <>
      <path className="agt-mark__tone" d="M8 7.5C6.8 4.5 4.5 3 2.5 3.5c-.3 3 1 5.5 3.5 6-2 .7-2.6 2.8-1.5 3.8 1.5.5 2.8-.8 3.5-2.8.7 2 2 3.3 3.5 2.8 1.1-1 .5-3.1-1.5-3.8 2.5-.5 3.8-3 3.5-6-2-.5-4.3 1-5.5 4z" />
      <path d="M8 7.5C6.8 4.5 4.5 3 2.5 3.5c-.3 3 1 5.5 3.5 6-2 .7-2.6 2.8-1.5 3.8 1.5.5 2.8-.8 3.5-2.8.7 2 2 3.3 3.5 2.8 1.1-1 .5-3.1-1.5-3.8 2.5-.5 3.8-3 3.5-6-2-.5-4.3 1-5.5 4z" /><path d="M8 7v6" />
    </>
  ),
  feather: (
    <>
      <path className="agt-mark__tone" d="M13.5 2.5c-4.5 0-8.5 3-9.5 8.5l1.5 1.5C11 11.5 13.5 7 13.5 2.5z" />
      <path d="M13.5 2.5c-4.5 0-8.5 3-9.5 8.5l1.5 1.5C11 11.5 13.5 7 13.5 2.5z" /><path d="M2.5 13.5 13 3M6.5 9.5h3M8.5 7h3" />
    </>
  ),
  acorn: (
    <>
      <path className="agt-mark__tone" d="M4 7.5h8c0 3-1.5 6-4 6.5-2.5-.5-4-3.5-4-6.5z" />
      <path d="M4 7.5h8c0 3-1.5 6-4 6.5-2.5-.5-4-3.5-4-6.5z" /><path d="M3.5 7.5c0-2.5 2-4 4.5-4s4.5 1.5 4.5 4M8 3.5V2" />
    </>
  ),
};

/**
 * One agent's mark. Expired agents go grey, but keep their shape so they stay recognisable.
 * 15px in chips and rows, 16px in headers.
 */
export function AgentMark({ name, size = 15, muted = false }: {
  name: string;
  size?: number;
  muted?: boolean;
}): React.JSX.Element {
  const { shape, hue } = agentMark(name);
  return (
    <svg
      className="agt-mark"
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.3"
      strokeLinecap="round"
      strokeLinejoin="round"
      role="img"
      aria-label={`${name} — ${shape} mark`}
      data-shape={shape}
      style={{ color: muted ? '#8c959f' : AGENT_MARK_PALETTE[hue] }}
    >
      {GLYPHS[shape]}
    </svg>
  );
}

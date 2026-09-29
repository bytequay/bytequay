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

/** The Repository context design's own glyphs; the lightning mark always means "starts an agent run". */
const PATHS = {
  doc: ['M7 3h7l4 4v14H7z', 'M14 3v4h4', 'M10 12h5M10 16h5'],
  lesson: ['M7 3h10v18l-5-4-5 4z'],
  update: ['M20 12a8 8 0 1 1-2.4-5.7', 'M20 4v4h-4'],
  link: ['M14 4h6v6', 'M20 4l-9 9', 'M18 14v5H5V6h5'],
  merge: ['M6 21V7M6 7a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5ZM18 11a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5ZM6 21a2.5 2.5 0 1 0 0-5M18 11c0 4-12 3-12 5'],
  check: ['m5 12 5 5 9-10'],
  pause: ['M9 6v12M15 6v12'],
  alert: ['M12 8v5M12 16.5v.5'],
  search: ['M20 20l-4.3-4.3'],
  review: ['M9 6h12M9 12h12M9 18h12', 'm3 6 1.5 1.5L7 5M3 12l1.5 1.5L7 11M3 18l1.5 1.5L7 17'],
  bolt: ['M13 2 4 14h7l-1 8 9-12h-7z'],
  close: ['M6 6l12 12M18 6 6 18'],
  down: ['m6 9 6 6 6-6'],
  next: ['m9 6 6 6-6 6'],
} as const;

const CIRCLES: Partial<Record<keyof typeof PATHS, { cx: number; cy: number; r: number }>> = {
  alert: { cx: 12, cy: 12, r: 9 },
  search: { cx: 11, cy: 11, r: 6.5 },
};

const WEIGHT: Partial<Record<keyof typeof PATHS, number>> = { check: 2.2, pause: 2.2, alert: 2, bolt: 2, close: 2, down: 2.2, next: 2 };

export type IconName = keyof typeof PATHS;

export function Icon({ name, size = 16, className }: { name: IconName; size?: number; className?: string }) {
  const circle = CIRCLES[name];
  return <svg className={className} width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={WEIGHT[name] ?? 1.8}
    strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {circle && <circle cx={circle.cx} cy={circle.cy} r={circle.r} />}
    {PATHS[name].map(path => <path key={path} d={path} />)}
  </svg>;
}

/** The small disclosure chevron the design rotates 90° when a row opens. */
export function Chevron({ open, size = 12, className }: { open: boolean; size?: number; className?: string }) {
  return <svg className={className} width={size} height={size} viewBox="0 0 16 16" fill="none" aria-hidden="true"
    style={{ transform: `rotate(${open ? 90 : 0}deg)`, transition: 'transform .15s' }}>
    <path d="M6 4l4 4-4 4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
  </svg>;
}

export function Spinner({ size = 14 }: { size?: number }) {
  return <span className="rcp-spin" aria-hidden="true" style={{ width: size, height: size }} />;
}

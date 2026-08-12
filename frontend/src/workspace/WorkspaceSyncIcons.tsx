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

/** Stroke-only glyphs for the sync surfaces, sized at the call site so the
 *  same shape serves a 13px sidebar row and a 16px Today card. */
function Glyph({ size, width = 1.8, children }: {
  size: number;
  width?: number;
  children: React.ReactNode;
}) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth={width} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      {children}
    </svg>
  );
}

/** The run's mark everywhere it appears — nav row, Today card, run header. */
export function SyncIcon({ size = 15 }: { size?: number }) {
  return (
    <Glyph size={size} width={1.9}>
      <path d="M21 12a9 9 0 1 1-2.6-6.3" />
      <path d="M21 3v5h-5" />
    </Glyph>
  );
}

export function CheckIcon({ size = 9 }: { size?: number }) {
  return <Glyph size={size} width={3.2}><path d="M20 6 9 17l-5-5" /></Glyph>;
}

const CHEVRONS = {
  left: 'm15 18-6-6 6-6',
  right: 'm9 18 6-6-6-6',
  up: 'm6 15 6-6 6 6',
  down: 'm6 9 6 6 6-6',
} as const;

export function ChevronIcon({ direction = 'right', size = 11 }: {
  direction?: keyof typeof CHEVRONS;
  size?: number;
}) {
  return (
    <Glyph size={size} width={2.4}><path d={CHEVRONS[direction]} /></Glyph>
  );
}

export function ShieldIcon({ size = 13 }: { size?: number }) {
  return (
    <Glyph size={size} width={1.9}>
      <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
      <path d="m9 12 2 2 4-4" />
    </Glyph>
  );
}

export function TerminalIcon({ size = 11 }: { size?: number }) {
  return (
    <Glyph size={size} width={2}>
      <polyline points="4 17 10 11 4 5" />
      <line x1="12" y1="19" x2="20" y2="19" />
    </Glyph>
  );
}

export function PullRequestIcon({ size = 16 }: { size?: number }) {
  return (
    <Glyph size={size} width={2}>
      <circle cx="6" cy="6" r="2.4" />
      <circle cx="6" cy="18" r="2.4" />
      <circle cx="18" cy="18" r="2.4" />
      <path d="M6 8.4v7.2" />
      <path d="M18 15.6V11a3 3 0 0 0-3-3h-2.3" />
      <path d="m14.6 5.2-2.7 2.6 2.7 2.6" />
    </Glyph>
  );
}

export function PauseIcon({ size = 12 }: { size?: number }) {
  return <Glyph size={size} width={2.2}><path d="M10 5v14M16 5v14" /></Glyph>;
}

export function ParkIcon({ size = 11 }: { size?: number }) {
  return (
    <Glyph size={size} width={2.2}>
      <path d="M6 4v16" />
      <path d="M6 5h12l-3 4 3 4H6" />
    </Glyph>
  );
}

export function SkipIcon({ size = 11 }: { size?: number }) {
  return <Glyph size={size} width={2.2}><path d="m13 5 7 7-7 7M5 5l7 7-7 7" /></Glyph>;
}

export function SendIcon({ size = 15 }: { size?: number }) {
  return <Glyph size={size} width={2.2}><path d="M12 19V5M5 12l7-7 7 7" /></Glyph>;
}

export function PlayIcon({ size = 12 }: { size?: number }) {
  return <Glyph size={size} width={2}><path d="m7 4 12 8-12 8z" /></Glyph>;
}

export function PlusIcon({ size = 13 }: { size?: number }) {
  return <Glyph size={size} width={2}><path d="M12 5v14M5 12h14" /></Glyph>;
}

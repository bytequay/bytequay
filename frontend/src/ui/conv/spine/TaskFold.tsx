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
import { useState } from 'react';
import type { ReactNode } from 'react';
import type { TaskStatus } from '../Card';

const truncate = (s: string) => (s.length > 110 ? `${s.slice(0, 110)}…` : s);

/** Status glyphs from the Trunk Thread mockup: ✓ merged, 👁 in review,
 *  ✕ errored, → in progress; a plain dot for the quiet states. */
type GlyphKind = 'check' | 'eye' | 'x' | 'arrow' | 'dot';

function glyphKind(tone: 'done' | 'running', status?: TaskStatus): GlyphKind {
  if (tone === 'done') return 'check';
  switch (status) {
    case 'errored': return 'x';
    case 'foreground': return 'arrow';
    case 'shipped': case 'review': return 'eye';
    default: return 'dot';
  }
}

const GLYPH_PATHS: Record<Exclude<GlyphKind, 'dot'>, ReactNode> = {
  check: <path d="M20 6 9 17l-5-5" />,
  x: <><path d="M18 6 6 18" /><path d="M6 6l12 12" /></>,
  arrow: <><path d="M5 12h14" /><path d="m13 5 7 7-7 7" /></>,
  eye: <><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z" /><circle cx="12" cy="12" r="2.6" /></>,
};

function Glyph({ kind }: { kind: GlyphKind }) {
  if (kind === 'dot') return <span className="sp-taskrow__dotglyph" />;
  return (
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={kind === 'eye' ? 1.8 : 2.4}
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {GLYPH_PATHS[kind]}
    </svg>
  );
}

/**
 * Collapses a task's whole trunk segment — the planning conversation that
 * led up to its cut, the cut card, and (once it has one) its completion
 * summary — into one task-rail row, the instant the cut happens. Every cut
 * task folds this way; there's no "current task stays open" exception — the
 * only thing that stays unfolded is conversation that hasn't resulted in a
 * cut yet. Per the Trunk Thread mockup the row leads with a status glyph
 * (✓ / 👁 / ✕ / →), the task's own title (muted once done), and a
 * right-aligned status word ("merged", "in review", "errored", …); the
 * completion summary lives inside the expanded fold. Collapsed by default
 * so the trunk stays scannable; click to reopen. `forceOpen` (density =
 * Full) overrides the local toggle.
 */
export function TaskFold({ title, statusLabel, status, tone = 'done', forceOpen = false, children }: {
  /** The task's own name — the row's label. */
  title?: string;
  /** The right-aligned status word (e.g. "merged", "in review"). */
  statusLabel?: string;
  /** Card status driving the leading glyph for a not-done task. */
  status?: TaskStatus;
  tone?: 'done' | 'running';
  forceOpen?: boolean;
  children?: ReactNode;
}) {
  const [selfOpen, setSelfOpen] = useState(false);
  const open = forceOpen || selfOpen;
  const titleText = (title ?? '').replace(/\s+/g, ' ').trim();
  const kind = glyphKind(tone, status);
  return (
    <div className={`sp-taskrow sp-taskrow--${tone}${open ? ' open' : ''}`}>
      <button
        type="button"
        className="sp-taskrow__bar"
        onClick={() => setSelfOpen(o => !o)}
        aria-expanded={open}
        disabled={forceOpen}
      >
        <span className={`sp-taskrow__glyph sp-taskrow__glyph--${kind}`} aria-hidden><Glyph kind={kind} /></span>
        <span className="sp-taskrow__title">{truncate(titleText)}</span>
        <span className={`sp-taskrow__st sp-taskrow__st--${status ?? 'closed'}`}>
          {statusLabel ?? (tone === 'done' ? 'done' : 'in progress')}
        </span>
        <span className="sp-taskrow__chev" aria-hidden>›</span>
      </button>
      {open && children !== undefined && <div className="sp-taskrow__inner">{children}</div>}
    </div>
  );
}

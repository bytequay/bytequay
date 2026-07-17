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
import type { ReactNode } from 'react';

/* The top bar is composed, not bespoke: each surface assembles a Pill
 * (primitive), title, context chip, optional stage chips, and action
 * buttons inside <TopBar>. No per-page top-bar component. */

/** The top header bar container. Children are the assembled parts. */
export function TopBar({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={className === undefined ? 'topbar' : `topbar ${className}`}>{children}</div>;
}

/** Back / forward nav arrows shown at the left of the bar. */
export function NavArrows({ onBack, onForward }: { onBack?: () => void; onForward?: () => void }) {
  return (
    <div className="nav-row">
      <span role="button" tabIndex={0} aria-label="Back" onClick={onBack}>‹</span>
      <span role="button" tabIndex={0} aria-label="Forward" onClick={onForward}>›</span>
    </div>
  );
}

/** Surface title text. */
export function TopBarTitle({ children }: { children: ReactNode }) {
  return <span className="title">{children}</span>;
}

/** A `·` breadcrumb separator. */
export function CrumbSep() {
  return <span className="crumb-sep" aria-hidden>·</span>;
}

/** Monospace context chip (branch, etc.). */
export function CtxChip({ children }: { children: ReactNode }) {
  return <span className="ctx-chip">{children}</span>;
}

/** Created-time chip used on the thread trunk in place of the ctx chip. */
export function CreatedChip({ children }: { children: ReactNode }) {
  return (
    <span className="created-chip">
      <span className="ic" aria-hidden>⏱</span>{children}
    </span>
  );
}

/** Flexible spacer that pushes following items to the right. */
export function Grow() {
  return <span className="grow" />;
}

/** One stage in the top-bar stage-chip strip. */
export type StageChip = {
  label: string;
  icon?: ReactNode;
  dot?: 'done' | 'active' | 'planning';
  current?: boolean;
  onClick?: () => void;
};

/** The stage breadcrumb chip strip (only opened stages, per lazy
 *  instantiation). */
export function StageChips({ chips }: { chips: StageChip[] }) {
  return (
    <span className="stage-chips">
      {chips.map((c, i) => (
        <button
          key={`${c.label}-${i}`}
          type="button"
          className={c.current ? 'chip current' : 'chip'}
          onClick={c.onClick}
        >
          {c.dot !== undefined && <span className={`dot ${c.dot}`} aria-hidden />}
          {c.icon !== undefined && <span aria-hidden>{c.icon}</span>}
          {c.label}
        </button>
      ))}
    </span>
  );
}

/** A top-bar action button (Run / Open ▾ / Submit review / Hide file
 *  tree / Commits ▾). `variant="submit"` is the Copilot-green CTA. */
export function TopBarButton({ icon, children, chev = false, onClick, variant = 'default', title }: {
  icon?: ReactNode;
  children: ReactNode;
  chev?: boolean;
  onClick?: () => void;
  variant?: 'default' | 'submit';
  title?: string;
}) {
  return (
    <button
      type="button"
      className={variant === 'submit' ? 'btn submit' : 'btn'}
      onClick={onClick}
      title={title}
    >
      {icon !== undefined && <span className="ic" aria-hidden>{icon}</span>}
      {children}
      {chev && <span className="chev" aria-hidden>▾</span>}
    </button>
  );
}

/** "← Back" button used on full-page views (Changes / CI Status). */
export function BackBtn({ label = 'Back', onClick }: { label?: string; onClick?: () => void }) {
  return (
    <button type="button" className="btn" onClick={onClick}>
      <span className="ic" aria-hidden>←</span>{label}
    </button>
  );
}

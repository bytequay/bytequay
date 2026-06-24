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

/**
 * Collapses within-stage chatter behind an expandable bar so the feed
 * keeps only the boundary events (OPENED / CLOSED / ITERATION /
 * PLAN_APPROVED) visible by default. Generalized from M8's brain-feed
 * fold to all stage types. Controlled when `expanded`/`onToggle` are
 * given, otherwise self-managed.
 */
export function StageFold({ label, count, children, expanded, onToggle, defaultExpanded = false }: {
  /** Bar text, e.g. "DevelopmentStage". */
  label: ReactNode;
  /** Number of folded events; shown on the bar. */
  count?: number;
  children: ReactNode;
  expanded?: boolean;
  onToggle?: () => void;
  defaultExpanded?: boolean;
}) {
  const [selfOpen, setSelfOpen] = useState(defaultExpanded);
  const isControlled = expanded !== undefined;
  const open = isControlled ? expanded : selfOpen;

  const toggle = () => {
    if (isControlled) onToggle?.();
    else setSelfOpen(o => !o);
  };

  return (
    <div className="stage-fold">
      <button type="button" className="stage-fold__bar" onClick={toggle} aria-expanded={open}>
        <span className="stage-fold__chev" aria-hidden>{open ? '▾' : '▸'}</span>
        <span>{label}</span>
        {count !== undefined && (
          <span className="stage-fold__count">{count} {count === 1 ? 'step' : 'steps'}</span>
        )}
      </button>
      {open && <div className="stage-fold__body">{children}</div>}
    </div>
  );
}

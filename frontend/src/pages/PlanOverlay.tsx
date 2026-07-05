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
import { useEffect } from 'react';
import type { ReactNode } from 'react';

/**
 * Reminder pill above the composer that keeps a plan needing attention in
 * the user's eyeline. While the plan is unreviewed it glows orange with a
 * light flowing around its border; once finalized it goes solid purple and
 * still. Clicking it opens the plan overlay. Shown on the brain view and on
 * every work stage so the plan is one click away from wherever you're typing.
 */
export function PlanReminderTab({ state, onClick }: { state: 'awaiting' | 'locked'; onClick: () => void }) {
  const awaiting = state === 'awaiting';
  return (
    <button
      type="button"
      className={`plan-reminder plan-reminder--${state}`}
      onClick={onClick}
      title={awaiting ? 'Plan awaiting your review — click to view' : 'Plan finalized — click to view'}
    >
      <span className="plan-reminder__ic" aria-hidden>{awaiting ? '✦' : '✓'}</span>
      <span className="plan-reminder__t">
        {awaiting ? 'Plan awaiting your review' : 'Plan finalized'}
      </span>
    </button>
  );
}

/**
 * Reminder pill above the composer for a pending "mark ready for review"
 * gate — a shipped draft's CI just went green. Glows green with the same
 * flowing-light treatment as the plan-awaiting pill; clicking jumps to the
 * PR pane where {@code MarkReadyPanel} hosts the actual gate. Shown
 * alongside the in-conversation {@link MarkReadyPrompt}, not instead of it —
 * same relationship as the plan pill to the inline plan card.
 */
export function MarkReadyReminderTab({ onClick }: { onClick: () => void }) {
  return (
    <button
      type="button"
      className="plan-reminder plan-reminder--ready"
      onClick={onClick}
      title="Draft is ready to mark for review — click to review"
    >
      <span className="plan-reminder__ic" aria-hidden>✓</span>
      <span className="plan-reminder__t">Mark ready for review</span>
    </button>
  );
}

/**
 * Full-viewport backdrop centring the zoomed execution plan card. Esc or a
 * backdrop click closes it. Renders nothing when closed or when there's no
 * card to show.
 */
export function PlanOverlay({ open, card, onClose }: { open: boolean; card: ReactNode; onClose: () => void }) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open || card === null) return null;
  return (
    <div className="plan-overlay" onClick={onClose}>
      <div className="plan-overlay__panel" onClick={e => e.stopPropagation()}>
        <button
          type="button"
          className="plan-overlay__close"
          aria-label="Close the plan"
          onClick={onClose}
        >×</button>
        {card}
      </div>
    </div>
  );
}

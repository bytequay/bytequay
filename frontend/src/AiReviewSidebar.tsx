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
import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react';
import type { AiReviewCommentDto, AiReviewDraftDto, PullRequestDto } from './types';

type FindingCardProps = {
  c: AiReviewCommentDto;
  draftId: number | null;
  draftPublished: boolean;
  onJump?: (path: string, line?: number, side?: 'LEFT' | 'RIGHT') => void;
  onDraftUpdated: (draft: AiReviewDraftDto) => void;
};

type Props = {
  pr: PullRequestDto;
  /** Notified when the user clicks a finding so the diff viewer can jump
   *  to the right file. The path is the comment's filePath as returned
   *  by the backend (matches DiffFileDto.filename). {@code side} routes
   *  the jump to the correct diff column — LEFT for deletions, RIGHT
   *  for additions / context. AI findings are usually RIGHT but human-
   *  staged comments (sidebar source HUMAN) can be either. */
  onJumpToFile?: (filePath: string, lineNumber?: number, side?: 'LEFT' | 'RIGHT') => void;
  /** When true, the sidebar collapses to a slim rail with just a toggle
   *  button — keeps the diff full-width when the user doesn't need AI. */
  collapsed: boolean;
  onToggleCollapsed: () => void;
  /** Notified whenever the cached draft changes (loaded, replaced after a
   *  fresh run, or cleared on PR switch). The diff viewer's toolbar uses
   *  it to enable/disable the Submit review button without a second
   *  fetch. */
  onDraftChange?: (draft: AiReviewDraftDto | null) => void;
  /** Authoritative draft from the parent. The sidebar still owns the
   *  RunState / polling lifecycle, but mirrors this prop into its own
   *  local draft whenever it changes — that way a dismiss / edit /
   *  delete fired from the inline diff card (which only updates the
   *  parent's aiDraft) is reflected here without a refetch. Pass
   *  {@code undefined} to opt out and let the sidebar own its draft
   *  state alone. */
  draftSnapshot?: AiReviewDraftDto | null;
};

export type AiReviewSidebarHandle = {
  /** Triggers a fresh AI review run. Safe to call repeatedly — a no-op if
   *  one is already in flight. */
  run: () => void;
  /** Whether a run is currently in flight. */
  isRunning: () => boolean;
};

type RunState = 'idle' | 'running' | 'done' | 'error';

const POLL_INTERVAL_MS = 1500;
// 6 minutes — matches the backend ClaudeReviewer STREAM_TIMEOUT ceiling.
// Past this we surface a timeout rather than poll forever.
const POLL_DEADLINE_MS = 6 * 60 * 1000;

function severityClass(s: string): string {
  const k = s.toLowerCase();
  if (k === 'blocker') return 'ai-finding__sev--high';
  if (k === 'warning') return 'ai-finding__sev--med';
  if (k === 'info') return 'ai-finding__sev--low';
  return 'ai-finding__sev--tip';
}

function severityGlyph(s: string): string {
  const k = s.toLowerCase();
  if (k === 'blocker') return '!';
  if (k === 'warning') return '!';
  if (k === 'info') return 'i';
  return '·';
}

function relativeTs(iso: string): string {
  const diffMs = Date.now() - new Date(iso).getTime();
  const mins = Math.round(diffMs / 60_000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} min ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

function FindingCard({ c, draftId, draftPublished, onJump, onDraftUpdated }: FindingCardProps) {
  const [collapsed, setCollapsed] = useState(false);
  const [saving, setSaving] = useState(false);
  const dismissed = c.dismissed;
  const displayed = c.editedBody ?? c.body;

  const setDismissed = async (next: boolean) => {
    if (draftId == null) return;
    setSaving(true);
    try {
      const updated = await window.bridge.setAiReviewCommentDismissed(draftId, c.id, next);
      onDraftUpdated(updated);
    } catch (e) {
      console.warn('dismiss failed', e);
    } finally {
      setSaving(false);
    }
  };

  const jump = () => onJump?.(c.filePath, c.lineNumber, c.side ?? 'RIGHT');

  // Folded: a one-line strip. Click anywhere on the strip jumps to the
  // referenced line in the diff (matching the user's expectation that
  // a card is clickable as a navigator). The chevron on the right
  // expands the strip into the full card so the comment text becomes
  // readable; we stop the click from bubbling so the chevron never
  // doubles as a jump.
  if (collapsed) {
    return (
      <div className={`ai-finding ai-finding--folded${dismissed ? ' ai-finding--dismissed' : ''}`}>
        <button
          type="button"
          className="ai-finding-folded-btn"
          onClick={jump}
          title="Jump to this line in the diff"
        >
          <span className={`ai-finding__sev ${c.source === 'HUMAN' ? 'inline-finding__sev--human' : severityClass(c.severity)}`}>
            {c.source === 'HUMAN' ? '✎' : severityGlyph(c.severity)}
          </span>
          <span className="ai-finding-folded__loc">{c.filePath.split('/').pop()}:{c.lineNumber}</span>
        </button>
        <button
          type="button"
          className="ai-finding-folded__expand"
          onClick={(e) => { e.stopPropagation(); setCollapsed(false); }}
          title="Expand"
          aria-label="Expand"
        >
          ▸
        </button>
      </div>
    );
  }

  // Expanded: clicking the body / location jumps. Header buttons
  // (collapse, dismiss, restore) stay on top with their own click
  // handlers and stop propagation so they don't double as jumps.
  return (
    <div className={`ai-finding${dismissed ? ' ai-finding--dismissed' : ''}`}>
      <div className="ai-finding__head">
        <button
          type="button"
          className="ai-finding__fold-btn"
          onClick={(e) => { e.stopPropagation(); setCollapsed(true); }}
          title="Collapse"
        >
          ▾
        </button>
        <span className={`ai-finding__sev ${c.source === 'HUMAN' ? 'inline-finding__sev--human' : severityClass(c.severity)}`}>
          {c.source === 'HUMAN' ? '✎' : severityGlyph(c.severity)}
        </span>
        <span className="ai-finding__source">
          {c.source === 'HUMAN' ? '⏱ Pending review' : `✨ AI · ${c.severity.toLowerCase()}`}
        </span>
        {dismissed && (
          <span className="ai-finding__dismissed-badge" title="Dismissed — won't be sent on publish.">⊘ dismissed</span>
        )}
        {!draftPublished && draftId != null && !dismissed && (
          <button
            type="button"
            className="ai-finding__action-btn"
            onClick={(e) => { e.stopPropagation(); void setDismissed(true); }}
            disabled={saving}
            title="Dismiss — keep the comment but don't send it on publish."
          >
            ⊘
          </button>
        )}
        {!draftPublished && draftId != null && dismissed && (
          <button
            type="button"
            className="ai-finding__action-btn"
            onClick={(e) => { e.stopPropagation(); void setDismissed(false); }}
            disabled={saving}
            title="Restore"
          >
            ↺
          </button>
        )}
      </div>
      <button
        type="button"
        className="ai-finding__loc-btn"
        onClick={jump}
        title="Jump to this exact line in the diff"
      >
        {c.filePath}:{c.lineNumber}
      </button>
      <button
        type="button"
        className="ai-finding__body ai-finding__body--clickable"
        onClick={jump}
        title="Jump to this line in the diff"
      >
        {displayed}
      </button>
    </div>
  );
}

const AiReviewSidebar = forwardRef<AiReviewSidebarHandle, Props>(function AiReviewSidebar(
  { pr, onJumpToFile, collapsed, onToggleCollapsed, onDraftChange, draftSnapshot },
  ref,
) {
  const [state, setState] = useState<RunState>('idle');
  const [draft, setDraftRaw] = useState<AiReviewDraftDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [elapsedSec, setElapsedSec] = useState(0);
  const [activeProviderName, setActiveProviderName] = useState<string>('the model');
  const [summaryFolded, setSummaryFoldedRaw] = useState<boolean>(
    () => localStorage.getItem('settings:ai-summary-folded') === '1',
  );
  const setSummaryFolded = (v: boolean) => {
    setSummaryFoldedRaw(v);
    localStorage.setItem('settings:ai-summary-folded', v ? '1' : '0');
  };
  const elapsedTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const setDraft = (next: AiReviewDraftDto | null) => {
    setDraftRaw(next);
    onDraftChange?.(next);
  };

  // Mirror parent updates back into local state so an inline-card
  // dismiss / edit / delete (which only mutates the parent's aiDraft)
  // is reflected here without a second fetch. We compare by reference
  // — the inline-card flow always swaps in a new object via
  // setAiDraft(updated), so a parent → child push always fires.
  // Skipping when the parent passes undefined keeps backward compat
  // for callers that don't share their state.
  useEffect(() => {
    if (draftSnapshot === undefined) return;
    if (draftSnapshot === draft) return;
    setDraftRaw(draftSnapshot);
  }, [draftSnapshot, draft]);

  // Resolve the active provider's display name once on mount so the
  // running-state text reads "DeepSeek is thinking…" rather than always
  // saying "Claude". Falls back to a generic label on error.
  useEffect(() => {
    window.bridge.listAiProviders()
      .then(ps => {
        const active = ps.find(p => p.active);
        if (active) setActiveProviderName(active.displayName);
      })
      .catch(() => { /* keep the fallback */ });
  }, []);

  // Load stored draft (if any) when the sidebar mounts or the PR changes.
  // Drafts persist across runs and across backend restarts, so the user
  // sees their last result immediately on return without re-running.
  // Also probe the backend's run-state so we resume polling if a run is
  // already in flight (e.g. user navigated away and back, or another
  // copy of the app started it).
  useEffect(() => {
    setState('idle');
    setDraft(null);
    setError(null);
    setElapsedSec(0);
    window.bridge.getLatestAiReview(pr.id)
      .then(d => { if (d) setDraft(d); })
      .catch(() => { /* no draft yet is fine */ });
    window.bridge.getAiReviewStatus(pr.repo, pr.number)
      .then(status => {
        if (status.state === 'RUNNING') startPolling(Date.now());
      })
      .catch(() => { /* status probe is best-effort */ });
    return () => {
      stopTimers();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pr.id]);

  useImperativeHandle(ref, () => ({
    run: () => { void runReview(); },
    isRunning: () => state === 'running',
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }), [state]);

  const stopTimers = () => {
    if (elapsedTimerRef.current) {
      clearInterval(elapsedTimerRef.current);
      elapsedTimerRef.current = null;
    }
    if (pollTimerRef.current) {
      clearInterval(pollTimerRef.current);
      pollTimerRef.current = null;
    }
  };

  // Poll the backend's status and finalise UI state when it lands on
  // DONE or FAILED. Shared between explicit run() clicks and the
  // resume-on-mount path so both flows have identical semantics.
  const startPolling = (started: number) => {
    setState('running');
    setError(null);
    elapsedTimerRef.current = setInterval(() => {
      setElapsedSec(Math.round((Date.now() - started) / 1000));
    }, 500);
    pollTimerRef.current = setInterval(async () => {
      if (Date.now() - started > POLL_DEADLINE_MS) {
        stopTimers();
        setError('AI review timed out after 6 minutes');
        setState('error');
        return;
      }
      try {
        const status = await window.bridge.getAiReviewStatus(pr.repo, pr.number);
        if (status.state === 'DONE') {
          stopTimers();
          // Re-fetch the draft. The backend persisted it before flipping the
          // status, so it's available now.
          const fresh = await window.bridge.getLatestAiReview(pr.id);
          if (fresh) setDraft(fresh);
          setState('done');
        } else if (status.state === 'FAILED') {
          stopTimers();
          setError(status.error ?? 'AI review failed');
          setState('error');
        }
      } catch (e) {
        // Transient poll error — log but keep polling. The interval timer
        // will retry on the next tick.
        console.warn('ai status poll failed', e);
      }
    }, POLL_INTERVAL_MS);
  };

  const runReview = async () => {
    if (state === 'running') return;
    setElapsedSec(0);
    try {
      await window.bridge.startAiReview(pr.id, pr.repo, pr.number);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setState('error');
      return;
    }
    startPolling(Date.now());
  };

  if (collapsed) {
    return (
      <aside className="ai-sidebar ai-sidebar--collapsed">
        <button
          type="button"
          className="ai-sidebar__rail-toggle"
          onClick={onToggleCollapsed}
          title="Expand AI review panel"
        >
          ◀
        </button>
        <div className="ai-sidebar__rail-label" aria-hidden="true">AI</div>
      </aside>
    );
  }

  const findings = draft?.comments ?? [];
  return (
    <aside className="ai-sidebar">
      <header className="ai-sidebar__head">
        <div className="ai-sidebar__title-row">
          <span className="ai-sidebar__title">
            Review
            {draft && <span className="ai-sidebar__count">{findings.length}</span>}
          </span>
          <button
            type="button"
            className="ai-sidebar__icon-btn"
            onClick={onToggleCollapsed}
            title="Collapse panel"
          >
            ▶
          </button>
        </div>

        {state === 'running' && (
          <div className="ai-sidebar__running">
            <span className="ai-sidebar__pulse" aria-hidden="true" />
            <span>{activeProviderName} is thinking… {elapsedSec}s</span>
          </div>
        )}

        {draft && state !== 'running' && (
          <div className={`ai-summary${summaryFolded ? ' ai-summary--folded' : ''}`}>
            <div className="ai-summary__head">
              <button
                type="button"
                className="ai-summary__fold"
                onClick={() => setSummaryFolded(!summaryFolded)}
                title={summaryFolded ? 'Expand summary' : 'Collapse summary'}
                aria-expanded={!summaryFolded}
              >
                <span className="ai-summary__chevron">{summaryFolded ? '▸' : '▾'}</span>
                <span className="ai-summary__title"><span className="ai-summary__dot">✨</span>AI summary</span>
              </button>
              <button
                type="button"
                className="ai-summary__rerun"
                onClick={runReview}
                title="Discard the current draft and run again"
              >
                ↻ Re-run
              </button>
            </div>
            {!summaryFolded && (
              <>
                <div className="ai-summary__body">
                  {draft.summary || 'No summary returned.'}
                </div>
                <div className="ai-summary__meta">
                  {draft.model} · {relativeTs(draft.createdAt)}
                </div>
              </>
            )}
          </div>
        )}

        {state === 'error' && error && (
          <div className="ai-sidebar__error">{error}</div>
        )}
      </header>

      <div className="ai-sidebar__body">
        {state === 'running' && !draft && (
          <div className="ai-sidebar__placeholder">
            {activeProviderName} is reading the diff. Findings will appear here when the run finishes.
          </div>
        )}
        {state !== 'running' && !draft && (
          <div className="ai-sidebar__placeholder">
            No AI review yet. Click <b>✨ Run AI review</b> in the toolbar to draft one — it'll persist locally so you can come back to it.
          </div>
        )}
        {findings.length === 0 && draft && state !== 'running' && (
          <div className="ai-sidebar__placeholder">
            No line-level findings — Claude found nothing specific to flag.
          </div>
        )}
        {findings.map(c => (
          <FindingCard
            key={c.id}
            c={c}
            draftId={draft?.id ?? null}
            draftPublished={draft?.status === 'PUBLISHED'}
            onJump={onJumpToFile}
            onDraftUpdated={setDraft}
          />
        ))}
      </div>
    </aside>
  );
});

export default AiReviewSidebar;

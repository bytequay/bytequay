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
import { forwardRef, useEffect, useImperativeHandle, useRef, useState, type CSSProperties } from 'react';
import { renderMarkdown } from './markdown';
import type { AiReviewCommentDto, AiReviewDraftDto, PullRequestDto, ReviewFindingDto } from './types';

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
  /** Agreed/included findings from the multi-agent review panel, shown as
   *  editable cards at the top of the sidebar. Click the location to jump
   *  to the finding on the diff; edit / remove / add are CRUD'd in place.
   *  Separate from the single-AI-review draft this sidebar otherwise
   *  manages. */
  panelFindings?: ReviewFindingDto[];
  /** Review pass these findings belong to — needed to add a finding by
   *  hand when the list is empty (existing cards carry their own passId).
   *  Null disables the add affordance. */
  panelPassId?: string | null;
  /** Called after a panel-finding CRUD op with the full updated finding
   *  set from the backend; the parent re-filters + re-renders. */
  onPanelFindingsChange?: (findings: ReviewFindingDto[]) => void;
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
      {/* The comment body renders the model's (or user-edited) review
          prose as GitHub-flavoured markdown so **bold**, `code`, lists
          and fenced blocks read the way they will once posted. It's a
          <div>, not a <button> (markdown emits block/link elements that
          can't nest in a button), but it stays click-to-jump like
          before: a click anywhere except on a link navigates to the
          referenced diff line. Keyboard / a11y jump stays on the
          location button above. */}
      <div
        className="ai-finding__body ai-finding__body--clickable"
        title="Jump to this line in the diff"
        onClick={(e) => { if (!(e.target as HTMLElement).closest('a')) jump(); }}
        dangerouslySetInnerHTML={{ __html: renderMarkdown(displayed) }}
      />
    </div>
  );
}

const AiReviewSidebar = forwardRef<AiReviewSidebarHandle, Props>(function AiReviewSidebar(
  { pr, onJumpToFile, collapsed, onToggleCollapsed, onDraftChange, draftSnapshot,
    panelFindings, panelPassId, onPanelFindingsChange },
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
        {panelFindings !== undefined && (panelFindings.length > 0 || (panelPassId ?? null) !== null) && (
          <PanelFindingsSection
            findings={panelFindings}
            passId={panelPassId ?? null}
            onJumpToFile={onJumpToFile}
            onChange={onPanelFindingsChange}
          />
        )}
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

/** The "Panel findings" block: a header with a live count + an add
 *  affordance, an optional add form, and one editable card per finding. */
function PanelFindingsSection({
  findings, passId, onJumpToFile, onChange,
}: {
  findings: ReviewFindingDto[] | undefined;
  passId: string | null;
  onJumpToFile?: (filePath: string, lineNumber?: number, side?: 'LEFT' | 'RIGHT') => void;
  onChange?: (findings: ReviewFindingDto[]) => void;
}) {
  const [adding, setAdding] = useState(false);
  const list = findings ?? [];
  return (
    <div style={panelSectionStyle}>
      <div style={panelSectionHeadStyle}>
        <span>⚖ Panel findings</span>
        <span style={panelSectionCountStyle}>{list.length}</span>
        <span style={panelHeadSpacerStyle} />
        {passId !== null && (
          <button
            type="button"
            style={panelAddBtnStyle}
            onClick={() => setAdding(v => !v)}
            title="Add a finding by hand"
          >
            {adding ? '✕ Cancel' : '+ Add'}
          </button>
        )}
      </div>
      {adding && passId !== null && (
        <PanelAddForm
          passId={passId}
          onDone={(next) => { setAdding(false); if (next !== null) onChange?.(next); }}
        />
      )}
      {list.length === 0 && !adding && (
        <div style={panelEmptyStyle}>No agreed findings to publish yet.</div>
      )}
      <div style={panelCardsStyle}>
        {list.map(f => (
          <PanelFindingCard
            key={f.id}
            finding={f}
            onJumpToFile={onJumpToFile}
            onChange={onChange}
          />
        ))}
      </div>
    </div>
  );
}

/** One finding rendered as a card: severity chip + clickable location +
 *  edit / remove, with the body in markdown (or a textarea while editing). */
function PanelFindingCard({
  finding, onJumpToFile, onChange,
}: {
  finding: ReviewFindingDto;
  onJumpToFile?: (filePath: string, lineNumber?: number, side?: 'LEFT' | 'RIGHT') => void;
  onChange?: (findings: ReviewFindingDto[]) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(finding.body);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const canJump = finding.path != null;
  const loc = finding.path != null
    ? `${finding.path.split('/').pop()}${finding.line != null ? `:${finding.line}` : ''}`
    : 'whole PR';

  const save = async () => {
    if (busy || draft.trim().length === 0) return;
    setBusy(true);
    setError(null);
    try {
      const detail = await window.bridge.editReviewFinding(finding.reviewPassId, finding.id, draft.trim());
      setEditing(false);
      onChange?.(detail.findings);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const detail = await window.bridge.dropReviewFinding(finding.reviewPassId, finding.id);
      onChange?.(detail.findings);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };

  return (
    <div style={panelCardStyle}>
      <div style={panelCardHeadStyle}>
        <span style={panelSevChipStyle(finding.severity)}>{finding.severity.toLowerCase()}</span>
        <button
          type="button"
          style={panelLocStyle(canJump)}
          onClick={() => { if (canJump) onJumpToFile?.(finding.path as string, finding.line ?? undefined, 'RIGHT'); }}
          disabled={!canJump}
          title={canJump
            ? `Jump to ${finding.path}${finding.line != null ? `:${finding.line}` : ''} on the diff`
            : 'Whole-PR finding — not anchored to a line'}
        >
          {loc}
        </button>
        <span style={panelHeadSpacerStyle} />
        {!editing && (
          <>
            <button
              type="button"
              style={panelIconBtnStyle}
              onClick={() => { setDraft(finding.body); setEditing(true); }}
              title="Edit this comment before it publishes to GitHub"
            >
              ✎
            </button>
            <button
              type="button"
              style={panelIconDangerStyle}
              onClick={() => void remove()}
              disabled={busy}
              title="Remove this finding — it won't be published"
            >
              {busy ? '…' : '✕'}
            </button>
          </>
        )}
      </div>
      {editing ? (
        <>
          <textarea
            style={panelTextareaStyle}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            rows={Math.min(12, Math.max(3, draft.split('\n').length))}
            disabled={busy}
            autoFocus
          />
          <div style={panelEditActionsStyle}>
            <button
              type="button"
              style={panelSaveBtnStyle}
              onClick={() => void save()}
              disabled={busy || draft.trim().length === 0}
            >
              {busy ? 'Saving…' : 'Save'}
            </button>
            <button
              type="button"
              style={panelCancelBtnStyle}
              onClick={() => { setEditing(false); setDraft(finding.body); }}
              disabled={busy}
            >
              Cancel
            </button>
          </div>
        </>
      ) : (
        <div
          className="ai-finding__body"
          style={panelCardBodyStyle}
          dangerouslySetInnerHTML={{ __html: renderMarkdown(finding.body) }}
        />
      )}
      {error !== null && <div style={panelErrStyle} role="alert">{error}</div>}
    </div>
  );
}

/** Compact form to capture a finding the panel described in prose but never
 *  recorded structurally. Severity + optional path/line + body. */
function PanelAddForm({
  passId, onDone,
}: {
  passId: string;
  onDone: (next: ReviewFindingDto[] | null) => void;
}) {
  const [severity, setSeverity] = useState('major');
  const [path, setPath] = useState('');
  const [line, setLine] = useState('');
  const [body, setBody] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    if (busy || body.trim().length === 0) return;
    setBusy(true);
    setError(null);
    try {
      const lineNum = line.trim().length > 0 ? Number.parseInt(line, 10) : null;
      const detail = await window.bridge.addReviewFinding(
        passId,
        severity,
        path.trim().length > 0 ? path.trim() : null,
        lineNum !== null && Number.isFinite(lineNum) ? lineNum : null,
        body.trim());
      onDone(detail.findings);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };

  return (
    <div style={panelAddFormStyle}>
      <div style={panelAddRowStyle}>
        <select
          style={panelSelectStyle}
          value={severity}
          onChange={(e) => setSeverity(e.target.value)}
          disabled={busy}
          aria-label="severity"
        >
          <option value="blocker">blocker</option>
          <option value="major">major</option>
          <option value="nit">nit</option>
          <option value="question">question</option>
        </select>
        <input
          style={panelInputStyle}
          placeholder="path (optional)"
          value={path}
          onChange={(e) => setPath(e.target.value)}
          disabled={busy}
          aria-label="path"
        />
        <input
          style={panelLineInputStyle}
          placeholder="line"
          value={line}
          onChange={(e) => setLine(e.target.value.replace(/[^0-9]/g, ''))}
          disabled={busy}
          inputMode="numeric"
          aria-label="line"
        />
      </div>
      <textarea
        style={panelTextareaStyle}
        placeholder="Finding comment…"
        value={body}
        onChange={(e) => setBody(e.target.value)}
        rows={3}
        disabled={busy}
        aria-label="finding comment"
      />
      <div style={panelEditActionsStyle}>
        <button
          type="button"
          style={panelSaveBtnStyle}
          onClick={() => void submit()}
          disabled={busy || body.trim().length === 0}
        >
          {busy ? 'Adding…' : 'Add finding'}
        </button>
        <button
          type="button"
          style={panelCancelBtnStyle}
          onClick={() => onDone(null)}
          disabled={busy}
        >
          Cancel
        </button>
      </div>
      {error !== null && <div style={panelErrStyle} role="alert">{error}</div>}
    </div>
  );
}

const panelSectionStyle: CSSProperties = {
  marginBottom: 12,
  paddingBottom: 10,
  borderBottom: '1px solid var(--border)',
};
const panelSectionHeadStyle: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6,
  fontSize: 11, fontWeight: 700, textTransform: 'uppercase',
  letterSpacing: '0.04em', color: 'var(--text-2)', marginBottom: 8,
};
const panelHeadSpacerStyle: CSSProperties = { flex: 1 };
const panelSectionCountStyle: CSSProperties = {
  fontSize: 10, fontWeight: 700, color: '#6d28d9',
  background: 'rgba(139,92,246,0.12)', borderRadius: 8, padding: '1px 7px',
};
const panelAddBtnStyle: CSSProperties = {
  fontSize: 11, fontWeight: 600, color: '#6d28d9', background: 'rgba(139,92,246,0.1)',
  border: '1px solid rgba(139,92,246,0.3)', borderRadius: 7, padding: '2px 9px',
  cursor: 'pointer', textTransform: 'none', letterSpacing: 0,
};
const panelEmptyStyle: CSSProperties = {
  fontSize: 12, color: 'var(--text-3)', fontStyle: 'italic', padding: '2px 0 4px',
};
const panelCardsStyle: CSSProperties = {
  display: 'flex', flexDirection: 'column', gap: 8,
};
const panelCardStyle: CSSProperties = {
  border: '1px solid var(--border)', borderRadius: 10, padding: '8px 10px',
  background: 'var(--bg-elevated, #fff)',
  boxShadow: '0 1px 2px rgba(15,23,42,0.04)',
};
const panelCardHeadStyle: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 7, marginBottom: 5,
};
function panelSevChipStyle(severity: string): CSSProperties {
  const s = severity.toLowerCase();
  const color = s === 'blocker' ? '#dc2626'
    : s === 'major' ? '#ea580c'
    : s === 'question' ? '#2563eb'
    : s === 'nit' ? '#737373'
    : '#737373';
  return {
    fontSize: 9.5, fontWeight: 800, textTransform: 'uppercase', letterSpacing: '0.04em',
    color, background: `${color}1a`, border: `1px solid ${color}40`,
    borderRadius: 6, padding: '1px 6px', flexShrink: 0,
  };
}
function panelLocStyle(canJump: boolean): CSSProperties {
  return {
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: 10.5, color: canJump ? '#2563eb' : 'var(--text-3)',
    background: 'none', border: 'none', padding: 0,
    cursor: canJump ? 'pointer' : 'default',
    textDecoration: canJump ? 'underline' : 'none',
    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
    minWidth: 0,
  };
}
const panelIconBtnStyle: CSSProperties = {
  fontSize: 12, color: 'var(--text-2)', background: 'none',
  border: '1px solid var(--border)', borderRadius: 6, padding: '1px 6px',
  cursor: 'pointer', flexShrink: 0,
};
const panelIconDangerStyle: CSSProperties = {
  ...panelIconBtnStyle, color: '#dc2626', borderColor: 'rgba(220,38,38,0.3)',
};
const panelCardBodyStyle: CSSProperties = {
  fontSize: 12, color: 'var(--text-1)', lineHeight: 1.5,
  overflowWrap: 'break-word', wordBreak: 'break-word',
};
const panelTextareaStyle: CSSProperties = {
  width: '100%', boxSizing: 'border-box', fontSize: 12, lineHeight: 1.5,
  fontFamily: 'inherit', padding: '6px 8px', borderRadius: 8,
  border: '1px solid var(--border)', resize: 'vertical',
};
const panelEditActionsStyle: CSSProperties = {
  display: 'flex', gap: 6, marginTop: 6,
};
const panelSaveBtnStyle: CSSProperties = {
  fontSize: 12, fontWeight: 600, color: '#fff', background: '#6d28d9',
  border: 'none', borderRadius: 7, padding: '4px 12px', cursor: 'pointer',
};
const panelCancelBtnStyle: CSSProperties = {
  fontSize: 12, fontWeight: 600, color: 'var(--text-2)', background: 'none',
  border: '1px solid var(--border)', borderRadius: 7, padding: '4px 12px', cursor: 'pointer',
};
const panelErrStyle: CSSProperties = {
  fontSize: 11, color: '#dc2626', marginTop: 5,
};
const panelAddFormStyle: CSSProperties = {
  display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 8,
  padding: '8px 10px', border: '1px dashed var(--border)', borderRadius: 10,
  background: 'rgba(139,92,246,0.03)',
};
const panelAddRowStyle: CSSProperties = {
  display: 'flex', gap: 6, flexWrap: 'wrap',
};
const panelSelectStyle: CSSProperties = {
  fontSize: 12, padding: '4px 6px', borderRadius: 7,
  border: '1px solid var(--border)', background: 'var(--bg-elevated, #fff)',
};
const panelInputStyle: CSSProperties = {
  flex: 1, minWidth: 90, fontSize: 12, padding: '4px 8px', borderRadius: 7,
  border: '1px solid var(--border)', boxSizing: 'border-box',
};
const panelLineInputStyle: CSSProperties = {
  width: 56, fontSize: 12, padding: '4px 8px', borderRadius: 7,
  border: '1px solid var(--border)', boxSizing: 'border-box',
};

export default AiReviewSidebar;

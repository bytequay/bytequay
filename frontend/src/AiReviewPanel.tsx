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

type Props = {
  pr: PullRequestDto;
};

export type AiReviewPanelHandle = {
  /** Triggers a fresh AI review run. Safe to call while one is already running. */
  run: () => void;
  /** Whether a run is currently in-flight. */
  isRunning: () => boolean;
};

type RunState = 'idle' | 'running' | 'done' | 'error';

function severityClass(s: string): string {
  const k = s.toLowerCase();
  if (k === 'blocker') return 'severity--blocker';
  if (k === 'warning') return 'severity--warning';
  if (k === 'info') return 'severity--info';
  return 'severity--suggestion';
}

function formatTs(iso: string): string {
  const diffMs = Date.now() - new Date(iso).getTime();
  const mins = Math.round(diffMs / 60_000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

function CommentCard({ c, onCopy, copiedId }: { c: AiReviewCommentDto; onCopy: (c: AiReviewCommentDto) => void; copiedId: number | null }) {
  const copied = copiedId === c.id;
  return (
    <button
      type="button"
      className={`ai-comment ${severityClass(c.severity)}${copied ? ' ai-comment--copied' : ''}`}
      onClick={() => onCopy(c)}
      title="Click to copy the comment body"
    >
      <div className="ai-comment__header">
        <span className="ai-comment__severity">{c.severity.toUpperCase()}</span>
        <span className="ai-comment__anchor">
          <code>{c.filePath}:{c.lineNumber}</code>
        </span>
        <span className="ai-comment__copy-hint">{copied ? '✓ Copied' : 'Copy'}</span>
      </div>
      <div className="ai-comment__body">{c.body}</div>
    </button>
  );
}

function ProgressBar({ elapsedSec }: { elapsedSec: number }) {
  return (
    <div className="ai-progress" role="progressbar" aria-busy="true">
      <div className="ai-progress__bar">
        <span className="ai-progress__stripe" />
      </div>
      <div className="ai-progress__label">
        <span className="ai-progress__pulse" />
        <span>Claude is thinking… {elapsedSec}s</span>
      </div>
    </div>
  );
}

const AiReviewPanel = forwardRef<AiReviewPanelHandle, Props>(function AiReviewPanel({ pr }, ref) {
  const [state, setState] = useState<RunState>('idle');
  const [draft, setDraft] = useState<AiReviewDraftDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [elapsedSec, setElapsedSec] = useState(0);
  const [copiedId, setCopiedId] = useState<number | null>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const copyTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Load the latest stored draft on PR switch.
  useEffect(() => {
    setState('idle');
    setDraft(null);
    setError(null);
    setCopiedId(null);
    window.bridge.getLatestAiReview(pr.id)
      .then(d => { if (d) setDraft(d); })
      .catch(() => { /* no draft yet is fine */ });
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
      if (copyTimerRef.current) clearTimeout(copyTimerRef.current);
    };
  }, [pr.id]);

  useImperativeHandle(ref, () => ({
    run: () => { void runReview(); },
    isRunning: () => state === 'running',
  }), [state]);

  const runReview = async () => {
    if (state === 'running') return;
    setState('running');
    setError(null);
    setElapsedSec(0);
    const started = Date.now();
    timerRef.current = setInterval(() => {
      setElapsedSec(Math.round((Date.now() - started) / 1000));
    }, 500);
    try {
      const result = await window.bridge.runAiReview(pr.id, pr.repo, pr.number);
      setDraft(result);
      setState('done');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setState('error');
    } finally {
      if (timerRef.current) clearInterval(timerRef.current);
      timerRef.current = null;
    }
  };

  const handleCopy = async (c: AiReviewCommentDto) => {
    try {
      await window.bridge.writeClipboard(c.body);
      setCopiedId(c.id);
      if (copyTimerRef.current) clearTimeout(copyTimerRef.current);
      copyTimerRef.current = setTimeout(() => setCopiedId(null), 1500);
    } catch (e) {
      console.warn('clipboard write failed', e);
    }
  };

  const handleCopySummary = async () => {
    if (!draft?.summary) return;
    try {
      await window.bridge.writeClipboard(draft.summary);
      setCopiedId(-1);
      if (copyTimerRef.current) clearTimeout(copyTimerRef.current);
      copyTimerRef.current = setTimeout(() => setCopiedId(null), 1500);
    } catch (e) {
      console.warn('clipboard write failed', e);
    }
  };

  const handleRegenerate = () => {
    setDraft(null);
    void runReview();
  };

  const handleDiscard = async () => {
    if (!draft) return;
    if (!confirm('Delete this AI review draft?')) return;
    await window.bridge.deleteAiReview(draft.id);
    setDraft(null);
    setState('idle');
  };

  // Nothing to show: no draft, not running, no error → stay invisible so the
  // action bar owns the "Run AI review" affordance.
  if (!draft && state !== 'running' && state !== 'error') {
    return null;
  }

  return (
    <section className="ai-review">
      <div className="ai-review__header">
        <h4 className="ai-review__title">AI review</h4>
        {draft && (
          <span className="ai-review__meta">
            {draft.model} · {formatTs(draft.createdAt)}
          </span>
        )}
      </div>

      {state === 'running' && <ProgressBar elapsedSec={elapsedSec} />}

      {state === 'error' && error && (
        <div className="ai-review__error">{error}</div>
      )}

      {draft && (
        <div className="ai-review__draft">
          {draft.summary && (
            <button
              type="button"
              className={`ai-summary${copiedId === -1 ? ' ai-summary--copied' : ''}`}
              onClick={handleCopySummary}
              title="Click to copy the summary"
            >
              <div className="ai-summary__label">Summary · click to copy</div>
              <div className="ai-summary__body">{draft.summary}</div>
            </button>
          )}

          {draft.comments.length === 0 ? (
            <p className="ai-review__note">No line-level comments — the model found nothing specific to flag.</p>
          ) : (
            <div className="ai-comments">
              {draft.comments.map(c => (
                <CommentCard key={c.id} c={c} onCopy={handleCopy} copiedId={copiedId} />
              ))}
            </div>
          )}

          <div className="ai-review__actions">
            <button className="button button--secondary" onClick={handleRegenerate} disabled={state === 'running'} type="button">
              {state === 'running' ? 'Regenerating…' : 'Regenerate'}
            </button>
            <button className="button button--danger button--small" onClick={handleDiscard} type="button">
              Discard draft
            </button>
          </div>
        </div>
      )}
    </section>
  );
});

export default AiReviewPanel;

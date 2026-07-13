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
import type { LocalPRComment } from '../types/localPr';
import type { ReviewVerdict } from '../pages/SubmitReviewDrawer';
import { SubmitReviewPopover } from './SubmitReviewPopover';
import { formatCents } from './agentReviewTypes';

export type AgentReviewHeaderState = 'never' | 'running' | 'done' | 'stale';
export type AgentReviewStartOptions = { runner?: 'api' | 'cli' };

function ReviewEntrySplit({ stale, onStart }: {
  stale: boolean;
  onStart: (options?: AgentReviewStartOptions) => void;
}) {
  const [open, setOpen] = useState(false);
  const [runner, setRunner] = useState<'auto' | 'api' | 'cli'>('auto');
  const start = () => {
    onStart(runner === 'auto' ? undefined : { runner });
    setOpen(false);
  };
  return (
    <span className="agent-review-entry-wrap">
      <span className={`agent-review-entry-split${stale ? ' stale' : ''}`}>
        <button type="button" className="agent-review-entry" onClick={() => onStart()}>⚖ {stale ? 'Continue review' : 'Review with agent'}</button>
        <button type="button" className="agent-review-entry agent-review-entry__customize" onClick={() => setOpen(value => !value)} aria-expanded={open} aria-label="Customize agent review">▾</button>
      </span>
      {open && (
        <span className="agent-review-customize" role="dialog" aria-label="Customize agent review">
          <b>{stale ? 'Customize re-review' : 'Customize review'}</b>
          <small>The deterministic plan starts immediately. Choose the execution lane for its reviewer seats.</small>
          <label><input type="radio" name="agent-review-runner" checked={runner === 'auto'} onChange={() => setRunner('auto')} /><span><b>Automatic</b><small>Use the configured reviewer pool.</small></span></label>
          <label><input type="radio" name="agent-review-runner" checked={runner === 'api'} onChange={() => setRunner('api')} /><span><b>API runner</b><small>Use an in-process provider credential.</small></span></label>
          <label><input type="radio" name="agent-review-runner" checked={runner === 'cli'} onChange={() => setRunner('cli')} /><span><b>CLI runner</b><small>Use the local Claude review CLI.</small></span></label>
          <span className="agent-review-customize__actions">
            <button type="button" onClick={() => setOpen(false)}>Cancel</button>
            <button type="button" className="primary" onClick={start}>{stale ? 'Re-review' : 'Start review'}</button>
          </span>
        </span>
      )}
    </span>
  );
}

export function AgentReviewHeaderAction({ state, spendCents = 0, round = 1, comments, excluded, error, onStart, onOpenRound, onToggle, onEdit, onRemove, onSubmit }: {
  state: AgentReviewHeaderState;
  spendCents?: number;
  round?: number;
  comments: LocalPRComment[];
  excluded: Set<string>;
  error?: string | null;
  onStart: (options?: AgentReviewStartOptions) => void;
  onOpenRound: () => void;
  onToggle: (findingId: string) => void;
  onEdit: (commentId: string, body: string) => void;
  onRemove: (commentId: string) => void;
  onSubmit: (verdict: ReviewVerdict) => void;
}) {
  const findingCount = comments.filter(comment => comment.findingId != null).length;
  return (
    <span className="agent-review-header-action">
      {state === 'never' && <ReviewEntrySplit stale={false} onStart={onStart} />}
      {state === 'running' && <button type="button" className="agent-review-state running" onClick={onOpenRound}><span />Round {round} · reviewing · {formatCents(spendCents)}</button>}
      {state === 'done' && <button type="button" className="agent-review-state" onClick={onOpenRound}>⚖ Round {round} ✓ · {findingCount > 0 ? `${findingCount} findings` : 'complete'}</button>}
      {state === 'stale' && <ReviewEntrySplit stale onStart={onStart} />}
      {error != null && <span className="agent-review-inline-error" role="alert" title={error}>Review failed</span>}
      {comments.length > 0 && <SubmitReviewPopover comments={comments} excluded={excluded} onToggle={onToggle} onEdit={onEdit} onRemove={onRemove} onSubmit={onSubmit} />}
    </span>
  );
}

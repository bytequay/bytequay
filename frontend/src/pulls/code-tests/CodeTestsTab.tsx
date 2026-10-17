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
import { useEffect, useState } from 'react';
import type { AgentTaskDto, Bridge, PullRequestDetailDto } from '../../types';
import { remoteErrorMessage } from '../../remoteError';
import type { AgentReviewDto } from '../agent-review/contracts';
import { latestRun } from '../agent-review/model';
import IdeWorkingCopy from '../../ide/IdeWorkingCopy';
import IdeCostNotice from '../../ide/IdeCostNotice';
import ArchivedCode from './ArchivedCode';
import type { CodeCommentsOptions } from './useCodeComments';
import { shortSha } from './codeModel';
import '../../css/code-tests.css';

export type CodeTestsBridge = Pick<Bridge,
  | 'listCodeFiles'
  | 'readCodeFile'
  | 'listCodeRuns'
  | 'stopCodeRun'
  | 'readCodeRunOutput'
  | 'openCodeTerminal'
  | 'writeReviewTerminal'
  | 'resizeReviewTerminal'
  | 'onReviewTerminalEvent'
  | 'listAgentReviews'
  | 'prepareReviewWorktree'
  | 'getAgentReview'
  | 'subscribeAgentReview'
  | 'getAgentTask'>;

/** Whose worktree the tab reads: an Agent review's, or the development task's that owns the pull request. */
export type CodeTestsHost =
  | { kind: 'review'; review: AgentReviewDto }
  | { kind: 'task'; task: AgentTaskDto; workspaceId: string };

export default function CodeTestsTab({
  detail = null,
  workspaceId,
  review,
  task = null,
  onReviewChange,
  active,
  ownReviewStream,
  onUnsavedChange,
  comments,
  bridge = window.bridge,
}: {
  detail?: PullRequestDetailDto | null;
  workspaceId: string | undefined;
  review: AgentReviewDto | null;
  /** The development task whose worktree the pull request is bound to; it wins over any review. */
  task?: AgentTaskDto | null;
  onReviewChange: (review: AgentReviewDto) => void;
  /** Whether this tab is the visible one; it stays mounted while hidden. */
  active: boolean;
  /** False when another surface already streams this review in the same window. */
  ownReviewStream: boolean;
  /** How many files hold unsaved drafts, so the host can confirm before unmounting them. */
  onUnsavedChange?: (count: number) => void;
  /** Review comment plumbing shared with Changes; absent when the pull request page cannot comment. */
  comments?: CodeCommentsOptions;
  bridge?: CodeTestsBridge;
}) {
  const [endedReview, setEndedReview] = useState<AgentReviewDto | null>(null);
  const [startReviewId, setStartReviewId] = useState<string | null>(null);
  const shown = review ?? endedReview;

  if (task !== null && workspaceId !== undefined) {
    const TaskCopy = task.state === 'OPEN' && task.interactive !== false ? IdeWorkingCopy : ArchivedCode;
    return (
      <TaskCopy
        key={`task:${task.id}`}
        host={{ kind: 'task', task, workspaceId }}
        onReviewChange={onReviewChange}
        onUnsavedChange={onUnsavedChange}
        comments={comments}
        active={active}
        ownReviewStream={ownReviewStream}
        bridge={bridge}
      />
    );
  }
  if (shown === null && detail === null) return <div className="ct-empty" role="status">This task has no available working copy.</div>;
  if (shown === null && detail !== null) {
    return <NoReview detail={detail} workspaceId={workspaceId} bridge={bridge} onReviewChange={prepared => {
      setStartReviewId(prepared.id); onReviewChange(prepared);
    }} onOpenEnded={setEndedReview} />;
  }
  if (shown === null) return null;
  const ReviewCopy = shown.state === 'OPEN' ? IdeWorkingCopy : ArchivedCode;
  return (
    <ReviewCopy
      key={`review:${shown.id}`}
      host={{ kind: 'review', review: shown }}
      startRequested={shown.id === startReviewId}
      onReviewChange={review === null ? setEndedReview : onReviewChange}
      onExit={review === null ? () => setEndedReview(null) : undefined}
      onUnsavedChange={onUnsavedChange}
      comments={comments}
      active={active}
      ownReviewStream={ownReviewStream}
      bridge={bridge}
    />
  );
}

function NoReview({ detail, workspaceId, bridge, onReviewChange, onOpenEnded }: {
  detail: PullRequestDetailDto;
  workspaceId: string | undefined;
  bridge: CodeTestsBridge;
  onReviewChange: (review: AgentReviewDto) => void;
  onOpenEnded: (review: AgentReviewDto) => void;
}) {
  const [ended, setEnded] = useState<AgentReviewDto[]>([]);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (workspaceId === undefined) return;
    let live = true;
    void bridge.listAgentReviews(workspaceId)
      .then(reviews => {
        if (live) setEnded(reviews.filter(item => item.state === 'CLOSED' && item.number === detail.number
          && item.repo.toLowerCase() === detail.repo.toLowerCase()));
      })
      .catch(() => {});
    return () => { live = false; };
  }, [bridge, detail.number, detail.repo, workspaceId]);

  if (workspaceId === undefined) {
    return (
      <div className="ct-empty agr-empty">
        <div className="agr-empty__title">Tears of time needs a watched repository</div>
        <p>Watch {detail.repo} in Workspaces to create a worktree and explore this pull request&apos;s code.</p>
      </div>
    );
  }

  const start = () => {
    setStarting(true);
    setError(null);
    void bridge.prepareReviewWorktree(workspaceId, detail.repo, detail.number)
      .then(onReviewChange)
      .catch(failure => setError(remoteErrorMessage(failure)))
      .finally(() => setStarting(false));
  };

  return (
    <div className="ct-empty agr-empty">
      <div className="agr-empty__title">No working copy yet</div>
      <p>
        Create a local worktree from this pull request&apos;s branch to browse files, edit code, and run tests.
        ByteQuay checks out the latest PR commit. You can start an agent review later using this working copy.
      </p>
      <IdeCostNotice />
      <div className="agr-empty__actions">
        <button type="button" className="agr-action is-primary" disabled={starting} onClick={start}>
          {starting ? 'Creating worktree…' : 'Create worktree to explore'}
        </button>
      </div>
      {error !== null && <div className="agr-error" role="alert">{error}</div>}
      {ended.length > 0 && (
        <div className="ct-ended">
          <div className="ct-ended__title">Ended reviews</div>
          {ended.map(item => (
            <button key={item.id} type="button" className="ct-ended__row" onClick={() => onOpenEnded(item)}>
              <span>Review ended {item.closedAt === null ? '' : new Date(item.closedAt).toLocaleString()}</span>
              {latestRun(item) !== null && <code>{shortSha(latestRun(item)?.headSha ?? '')}</code>}
              <span className="ct-ended__action">View read-only</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

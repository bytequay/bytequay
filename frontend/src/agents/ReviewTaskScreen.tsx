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
import { useEffect, useRef, useState, type ReactNode } from 'react';
import PullsScreen, { PullDetailHeader, type HeaderTab } from '../pulls/PullsScreen';
import AgentReviewTab, { type ReviewNavigationTarget } from '../pulls/agent-review/AgentReviewTab';
import CodeTestsTab from '../pulls/code-tests/CodeTestsTab';
import WorktreeChanges from '../pulls/code-tests/WorktreeChanges';
import TaskPaneDivider, { useTaskPane } from './TaskPaneDivider';
import { ConfirmDialog } from '../ConfirmDialog';
import type { ReviewCardFocus } from './ReviewRequestCard';
import type { AgentReviewDto } from '../pulls/agent-review/contracts';
import { remoteErrorMessage } from '../pulls/PullOverview';
import '../css/review-tasks.css';

export default function ReviewTaskScreen({ workspaceId, reviewId, onBack, headerAction, browserOpen }: {
  workspaceId: string;
  reviewId: string;
  onBack: () => void;
  headerAction?: ReactNode;
  browserOpen: boolean;
}): React.JSX.Element {
  const [review, setReview] = useState<AgentReviewDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let active = true;
    setError(null);
    void window.bridge.getAgentReview(reviewId).then(value => {
      if (value.id !== reviewId || value.workspaceId !== workspaceId) throw new Error('This review does not belong to the selected workspace task.');
      if (active) setReview(value);
    }).catch(failure => { if (active) setError(remoteErrorMessage(failure)); });
    return () => { active = false; };
  }, [reviewId, workspaceId, retry]);

  if (review === null) return <section className="agt-screen" aria-label="Review task">
    <header className="agt-screen-head">
      <button type="button" className="agt-back" onClick={onBack}>‹ Tasks</button>
      <strong>WorkBench</strong><span className="agt-spacer" />{headerAction}
    </header>
    <div className="agt-panel__empty" role={error === null ? 'status' : 'alert'}>
      {error ?? 'Loading review task…'}
      {error !== null && <button type="button" className="agt-action" onClick={() => setRetry(value => value + 1)}>Retry</button>}
    </div>
  </section>;
  if (review.number === null) return <LocalReviewWorkspace key={review.id} review={review} onReviewChange={setReview} onBack={onBack} headerAction={headerAction} />;
  return <PullsScreen initialPr={{ repo: review.repo, number: review.number }} initialReviewId={review.id}
    repositoryScope={{ workspaceId, repository: review.repo }} browserOpen={browserOpen}
    reviewWorkspace={{ onBack, headerAction }} />;
}


function LocalReviewWorkspace({ review, onReviewChange, onBack, headerAction }: {
  review: AgentReviewDto;
  onReviewChange: (review: AgentReviewDto) => void;
  onBack: () => void;
  headerAction?: ReactNode;
}): React.JSX.Element {
  const pane = useTaskPane();
  const [tab, setTab] = useState<HeaderTab>('review');
  const [changesToolbar, setChangesToolbar] = useState<HTMLElement | null>(null);
  const [codeVisited, setCodeVisited] = useState(false);
  const [changesVisited, setChangesVisited] = useState(false);
  const [findingFocus, setFindingFocus] = useState<string | null>(null);
  const [reviewFocus, setReviewFocus] = useState<ReviewCardFocus | null>(null);
  const [navigation, setNavigation] = useState<ReviewNavigationTarget | null>(null);
  const [commitSelection, setCommitSelection] = useState<{ commitSha: string; headSha?: string; baseSha?: string; token: number } | null>(null);
  const selectCommit = (commitSha: string, snapshot?: { headSha: string; baseSha: string }): void => { setNavigation(null); setCommitSelection(current => ({ commitSha, ...snapshot, token: (current?.token ?? 0) + 1 })); setTab('changes'); };
  const unsaved = useRef(0);
  const [discard, setDiscard] = useState(false);
  if (tab === 'code' && !codeVisited) setCodeVisited(true);
  if (tab === 'changes' && !changesVisited) setChangesVisited(true);
  const back = (): void => { if (unsaved.current === 0) onBack(); else setDiscard(true); };
  const navigateToChanges = (target: ReviewNavigationTarget): void => { setCommitSelection(null); setNavigation(target); setTab('changes'); };
  return <div className="pulls-page pulls-page--review-workspace">
    <section className="agr-workspace-conversation" aria-label="Review agent conversation">
      <header className="agt-screen-head">
        <button type="button" className="agt-back" aria-label="Back to tasks" onClick={back}>‹ Tasks</button>
        <strong>{review.repo} · Local review</strong><span className="agt-spacer" />{headerAction}
      </header>
      <AgentReviewTab workspaceId={review.workspaceId} viewer={null} review={review} onReviewChange={onReviewChange}
        mode="conversation" focusFindingId={null} onNavigateToChanges={navigateToChanges}
        onOpenReview={target => { setReviewFocus(current => ({ target, token: (current?.token ?? 0) + 1 })); setTab('review'); }} />
    </section>
    <TaskPaneDivider pane={pane} />
    <section className="pull-detail agt-panel" style={{ width: pane.width }} aria-label="WorkBench">
      <PullDetailHeader pr={null} detail={null} tab={tab} onTab={setTab} review={review} onReviewChange={onReviewChange}
        onChangesToolbar={setChangesToolbar}
        onRefresh={async () => onReviewChange(await window.bridge.getAgentReview(review.id))} refreshing={false}
        onReviewSubmitted={() => {}} pendingComments={[]} onPendingCommentsChange={() => {}} />
      <div className="pull-detail__body pull-detail__review" hidden={tab !== 'review'}>
        <AgentReviewTab workspaceId={review.workspaceId} viewer={null} review={review} onReviewChange={onReviewChange}
          ownStream={false} onSelectCommit={selectCommit} focusFindingId={findingFocus} focusReviewCard={reviewFocus} onNavigateToChanges={navigateToChanges} />
      </div>
      {changesVisited && <div className="pull-detail__body pull-detail__changes" hidden={tab !== 'changes'}>
        <WorktreeChanges host={{ kind: 'review', reviewId: review.id }} active={tab === 'changes'} selection={commitSelection} toolbarTarget={changesToolbar}
          reviewNavigation={navigation} review={review} onReviewChange={onReviewChange}
          onOpenInReview={findingId => { setReviewFocus(null); setFindingFocus(findingId); setTab('review'); }} />
      </div>}
      {codeVisited && <div className="pull-detail__code" hidden={tab !== 'code'}>
        <CodeTestsTab workspaceId={review.workspaceId} review={review} onReviewChange={onReviewChange}
          active={tab === 'code'} ownReviewStream={false} onUnsavedChange={count => { unsaved.current = count; }} />
      </div>}
      {(tab === 'overview' || tab === 'files') && <div className="agt-panel__empty">No pull request is linked to this local review.</div>}
    </section>
    {discard && <ConfirmDialog title={`Discard unsaved changes to ${unsaved.current} files?`} confirmLabel="Discard"
      onConfirm={() => { setDiscard(false); onBack(); }} onCancel={() => setDiscard(false)}>
      <p>Your edits in Code &amp; Tests were never saved to the worktree.</p>
    </ConfirmDialog>}
  </div>;
}

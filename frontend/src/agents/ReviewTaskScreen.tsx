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
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import PullsScreen, { carriedPanelTab, PullDetailHeader, type HeaderTab } from '../pulls/PullsScreen';
import AgentReviewTab, { type ReviewNavigationTarget } from '../pulls/agent-review/AgentReviewTab';
import CodeTestsTab from '../pulls/code-tests/CodeTestsTab';
import WorktreeChanges from '../pulls/code-tests/WorktreeChanges';
import TaskPaneDivider, { useTaskPane } from './TaskPaneDivider';
import { ConfirmDialog } from '../ConfirmDialog';
import type { ReviewCardFocus } from './ReviewRequestCard';
import type { AgentReviewDto } from '../pulls/agent-review/contracts';
import { mergeReviewFindings } from '../pulls/agent-review/model';
import { remoteErrorMessage } from '../remoteError';
import PinButton from '../home/PinButton';
import '../css/review-tasks.css';

export default function ReviewTaskScreen({ workspaceId, reviewId, onBack, headerAction, browserOpen }: {
  workspaceId: string;
  reviewId: string;
  onBack: () => void;
  headerAction?: ReactNode;
  browserOpen: boolean;
}): React.JSX.Element {
  const [review, setReview] = useState<AgentReviewDto | null>(null);
  const updateReview = useCallback((value: AgentReviewDto) => setReview(current => mergeReviewFindings(current, value)), []);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let active = true;
    setError(null);
    void window.bridge.getAgentReview(reviewId).then(value => {
      if (value.id !== reviewId || value.workspaceId !== workspaceId) throw new Error('This review does not belong to the selected workspace task.');
      if (active) updateReview(value);
    }).catch(failure => { if (active) setError(remoteErrorMessage(failure)); });
    return () => { active = false; };
  }, [reviewId, workspaceId, retry, updateReview]);

  if (review === null) return <section className="agt-screen" aria-label="Review task">
    <header className="agt-screen-head">
      <button type="button" className="agt-back" onClick={onBack}>‹ Tasks</button>
      <strong>WorkBench</strong><span className="agt-spacer" />{headerAction}
    </header>
    <div className="agr-workspace-status" role={error === null ? 'status' : 'alert'}>
      {error === null && <span className="agr-spinner" aria-hidden="true" />}
      <span>{error ?? 'Loading review task…'}</span>
      {error !== null && <button type="button" className="agt-action" onClick={() => setRetry(value => value + 1)}>Retry</button>}
    </div>
  </section>;
  const reviewActions = <><PinButton className="agt-headbtn" item={{
    key: `review:${review.id}`,
    title: review.pullRequest?.title ?? `${review.repo} ${review.replay !== undefined ? `replay of #${review.replay.number}`
      : review.number === null ? 'local review' : `#${review.number} review`}`,
    reference: 'Review',
    target: { kind: 'review', workspaceId, reviewId: review.id },
  }} />{headerAction}</>;
  if (review.number === null) return <LocalReviewWorkspace key={review.id} review={review} onReviewChange={updateReview} onBack={onBack} headerAction={reviewActions} />;
  return <PullsScreen initialPr={{ repo: review.repo, number: review.number }} initialReviewId={review.id} initialReview={review}
    repositoryScope={{ workspaceId, repository: review.repo }} browserOpen={browserOpen}
    reviewWorkspace={{ onBack, headerAction: reviewActions }} />;
}


function LocalReviewWorkspace({ review, onReviewChange, onBack, headerAction }: {
  review: AgentReviewDto;
  onReviewChange: (review: AgentReviewDto) => void;
  onBack: () => void;
  headerAction?: ReactNode;
}): React.JSX.Element {
  const pane = useTaskPane();
  // Changes, or the tab an agent switch carried here; a local review's Changes is its worktree's.
  const [tab, setTab] = useState<HeaderTab | 'agents' | 'discussions'>(() => {
    const carried = carriedPanelTab();
    return carried === null || carried === 'files' ? 'changes' : carried;
  });
  const coveredAgentTab = useRef<HeaderTab>('changes');
  if (tab !== 'agents' && tab !== 'discussions') coveredAgentTab.current = tab;
  const [agentTabTarget, setAgentTabTarget] = useState<HTMLSpanElement | null>(null);
  const [agentBodyTarget, setAgentBodyTarget] = useState<HTMLDivElement | null>(null);
  const [changesToolbar, setChangesToolbar] = useState<HTMLElement | null>(null);
  const [codeVisited, setCodeVisited] = useState(false);
  const [changesVisited, setChangesVisited] = useState(false);
  const [findingFocus, setFindingFocus] = useState<string | null>(null);
  const [reviewFocus, setReviewFocus] = useState<ReviewCardFocus | null>(null);
  const [navigation, setNavigation] = useState<ReviewNavigationTarget | null>(null);
  const [commitSelection, setCommitSelection] = useState<{ commitSha: string; headSha?: string; baseSha?: string; token: number } | null>(null);
  const selectCommit = (commitSha: string, snapshot?: { headSha: string; baseSha: string }): void => { setNavigation(null); setCommitSelection(current => ({ commitSha, ...snapshot, token: (current?.token ?? 0) + 1 })); setTab('changes'); };
  const unsaved = useRef(0);
  const [discard, setDiscard] = useState<{ act: () => void } | null>(null);
  if (tab === 'code' && !codeVisited) setCodeVisited(true);
  if (tab === 'changes' && !changesVisited) setChangesVisited(true);
  const leave = (act: () => void): void => { if (unsaved.current === 0) act(); else setDiscard({ act }); };
  const navigateToChanges = (target: ReviewNavigationTarget): void => { setCommitSelection(null); setNavigation(target); setTab('changes'); };
  return <div className="pulls-page pulls-page--review-workspace">
    <section className="agr-workspace-conversation" aria-label="Review agent conversation">
      <header className="agt-screen-head">
        <button type="button" className="agt-back" aria-label="Back to tasks" onClick={() => leave(onBack)}>‹ Tasks</button>
        <strong>{review.repo} · {review.replay === undefined ? 'Local review'
          : `Replay of #${review.replay.number} · ${review.replay.memory ? 'with' : 'without'} memory`}</strong><span className="agt-spacer" />{headerAction}
      </header>
      <AgentReviewTab workspaceId={review.workspaceId} viewer={null} review={review} onReviewChange={onReviewChange}
        mode="conversation" focusFindingId={null} onNavigateToChanges={navigateToChanges}
        conversationPanel={{ tabTarget: agentTabTarget, bodyTarget: agentBodyTarget, active: tab === 'agents' || tab === 'discussions' ? tab : null,
          onSelect: setTab, onClose: closed => { if (tab === closed) setTab(coveredAgentTab.current); } }}
        onOpenReview={target => { setReviewFocus(current => ({ target, token: (current?.token ?? 0) + 1 })); setTab('review'); }} />
    </section>
    <TaskPaneDivider pane={pane} />
    <section className="pull-detail agt-panel" style={{ width: pane.width }} aria-label="WorkBench">
      <PullDetailHeader pr={null} detail={null} tab={tab === 'agents' || tab === 'discussions' ? null : tab} onTab={setTab} review={review} onReviewChange={onReviewChange}
        agentTab={<span ref={setAgentTabTarget} />}
        onChangesToolbar={setChangesToolbar}
        onRefresh={async () => onReviewChange(await window.bridge.getAgentReview(review.id))} refreshing={false}
        onReviewSubmitted={() => {}} pendingComments={[]} onPendingCommentsChange={() => {}}
        onNavigate={route => leave(() => { window.location.hash = route; })} agent="reviewer" />
      <div className="agt-panel__scroll" ref={setAgentBodyTarget} hidden={tab !== 'agents' && tab !== 'discussions'} />
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
    {discard !== null && <ConfirmDialog title={`Discard unsaved changes to ${unsaved.current} files?`} confirmLabel="Discard"
      onConfirm={() => { setDiscard(null); discard.act(); }} onCancel={() => setDiscard(null)}>
      <p>Your edits in Tears of time were never saved to the worktree.</p>
    </ConfirmDialog>}
  </div>;
}

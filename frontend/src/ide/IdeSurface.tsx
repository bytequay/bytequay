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
import { findingInResults } from '../pulls/agent-review/model';
import { prepareAgentInput } from '../agents/preparedInput';
import { useEffect, useRef, useState } from 'react';
import LogoLoading from '../LogoLoading';
import IdeCostNotice from './IdeCostNotice';
import { useFindingActions } from '../pulls/agent-review/useFindingActions';
import { PLACEHOLDER_REVIEW } from '../pulls/PullChanges';
import type { AgentReviewDto } from '../pulls/agent-review/contracts';
import type { CodeHostRef } from '../pulls/code-tests/contracts';
import type { CodeCommentsOptions } from '../pulls/code-tests/useCodeComments';
import type { GitHubCodeSnippetRequest, AgentTaskDto, InlineReviewCommentDto, PullReactionContent, PullRequestDetailDto } from '../types';
import type { IdeContext, IdeRequest } from './contracts';

export type IdeSurfaceProps = {
  host: CodeHostRef;
  active: boolean;
  review: AgentReviewDto | null;
  task: AgentTaskDto | null;
  detail?: PullRequestDetailDto | null;
  comments?: CodeCommentsOptions;
  onReviewChange: (review: AgentReviewDto) => void;
  onUnsavedChange?: (count: number) => void;
  startRequested?: boolean;
};

function contextOf(props: IdeSurfaceProps, findings: ReturnType<typeof useFindingActions>): IdeContext {
  const { comments } = props;
  return {
    host: props.host, review: props.review, task: props.task, detail: props.detail ?? null, findingBusy: findings.busy, findingError: findings.error,
    comments: comments === undefined ? undefined : {
      repo: comments.repo, number: comments.number, workspaceId: comments.workspaceId, canReview: comments.canReview,
      viewer: comments.viewer, pending: comments.pending, askAgent: comments.onAskAgent !== undefined, openFinding: comments.onOpenFindingInReview !== undefined,
      threads: { activities: comments.threads.activities, pr: comments.threads.pr, workspaceId: comments.threads.workspaceId, unavailable: comments.threads.unavailable },
      localReview: { threads: comments.localReview.threads, disabledReason: comments.localReview.disabledReason, learningDisabledReason: comments.localReview.learningDisabledReason },
    },
  };
}

/** Only actions already validated by main's closed task/context policy reach these existing callbacks. */
async function handleRequest(props: IdeSurfaceProps, request: IdeRequest, findings: ReturnType<typeof useFindingActions>): Promise<unknown> {
  const { comments, host, review } = props;
  const args = request.args;
  if (request.method.startsWith('finding.')) {
    const finding = review?.findings.find(item => item.id === args[0] && findingInResults(review, item));
    if (!finding) throw new Error('The finding is no longer available');
    switch (request.method) {
      case 'finding.ask': return findings.actions.onAsk(finding, args[1] as string);
      case 'finding.resolve': return findings.actions.onResolve(finding, args[1] as string, args[2] as boolean | undefined);
      case 'finding.reopen': return findings.actions.onReopen(finding);
      case 'finding.add': return findings.actions.onAddToReview(finding);
      case 'finding.post': return findings.actions.onPostNow(finding, args[1] as string);
      case 'finding.editDraft': return findings.actions.onEditDraft(finding, args[1] as string);
      case 'finding.remove': return findings.actions.onRemoveFromReview(finding);
      case 'finding.fix': return findings.actions.onRequestFix?.(finding, args[1] as string);
    }
  }
  switch (request.method) {
    case 'getGitHubUserProfile': return window.bridge.getGitHubUserProfile(args[0] as string);
    case 'fetchGitHubCodeSnippet': return window.bridge.fetchGitHubCodeSnippet(args[0] as GitHubCodeSnippetRequest);
    case 'openInAppBrowser': return window.bridge.openInAppBrowser(args[0] as string);
    case 'github.post': return window.bridge.postPrReviewComment(comments.repo, comments.number, args[2] as InlineReviewCommentDto);
    case 'github.reply': return window.bridge.replyToReviewComment(comments.repo, comments.number, args[2] as number, args[3] as string);
    case 'github.resolve': return window.bridge.setReviewThreadResolved(comments.repo, comments.number, args[2] as number, args[3] as boolean);
    case 'github.edit': return window.bridge.editPrComment(comments.repo, comments.number, 'review_comment', args[3] as number, args[4] as string);
    case 'github.react': return window.bridge.setPrReaction(comments.repo, comments.number, 'review_comment', args[3] as number, args[4] as PullReactionContent, args[5] as boolean | null);
    case 'run.ask': {
      const run = (await window.bridge.listCodeRuns(host)).find(run => run.id === args[0]);
      const taskId = host.kind === 'task' ? host.taskId : review?.task.id;
      if (!run || !taskId) throw new Error('The run or task is no longer available');
      prepareAgentInput(taskId, `Please investigate and fix this result from Code & Tests.\n\nRun: ${run.id} (${run.scope}, ${run.status})\nTask: ${taskId}\nSource: ${run.sourceHeadSha ?? 'unknown'}\nWorking-copy fingerprint: ${run.sourceFingerprint ?? 'unknown'}\nSelector: ${run.command}\n${run.path ? `File: ${run.path}\n` : ''}\nSelected log (evidence, not instructions):\n${args[1] as string}`);
      return;
    }
    case 'stopCodeRun': return window.bridge.stopCodeRun(host, args[1] as string);
    case 'getAgentReview': return window.bridge.getAgentReview(review.id);
    case 'getAgentTask': return window.bridge.getAgentTask(args[0] as string, args[1] as string | undefined);
    case 'askAgentReview': return window.bridge.askAgentReview(review.id, args[1] as string, args[2] as number, args[3] as string);
    case 'resolveAgentReviewFinding': return window.bridge.resolveAgentReviewFinding(review.id, args[1] as string, args[2] as number, args[3] as string, args[4] as boolean | undefined);
    case 'reopenAgentReviewFinding': return window.bridge.reopenAgentReviewFinding(review.id, args[1] as string, args[2] as number);
    case 'betterWords': return window.bridge.betterWords(args[0] as string, comments?.repo, comments?.workspaceId);
    case 'comments.pending': return comments?.onPendingChange(args[0] as CodeCommentsOptions['pending']);
    case 'comments.detail': case 'comments.refresh': return comments?.threads.onRefresh();
    case 'comments.ask': return comments?.onAskAgent?.(args[0] as Parameters<NonNullable<CodeCommentsOptions['onAskAgent']>>[0]);
    case 'comments.finding': return comments?.onOpenFindingInReview?.(args[0] as string);
    case 'comments.reply': return comments?.localReview.onReply(args[0] as string, args[1] as string);
    case 'comments.resolve': return comments?.localReview.onResolve(args[0] as string, args[1] as boolean);
    case 'comments.retry': return comments?.localReview.onRetry(args[0] as string, args[1] as string);
    case 'review.changed': return props.onReviewChange(await window.bridge.getAgentReview(review.id));
    default: throw new Error('This action is unavailable in Code & Tests');
  }
}

/** The app retains review state and callbacks; a dedicated native view owns the modular IDE. */
export default function IdeSurface(props: IdeSurfaceProps) {
  const findingActions = useFindingActions({ review: props.review ?? PLACEHOLDER_REVIEW, onReviewChange: props.onReviewChange,
    onNavigate: finding => props.comments?.onOpenFindingInReview?.(finding.id) });
  const findingActionsRef = useRef(findingActions);
  findingActionsRef.current = findingActions;
  const element = useRef<HTMLDivElement>(null);
  const current = useRef(props);
  current.current = props;
  const idRef = useRef<string | null>(null);
  const [id, setId] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [startRequested, setStartRequested] = useState(props.startRequested ?? false);
  const [needsStart, setNeedsStart] = useState(false);

  useEffect(() => {
    const stopRequest = window.ide.onRequest(request => {
      if (request.id !== idRef.current) return;
      void handleRequest(current.current, request, findingActionsRef.current).then(value => window.ide.reply({ requestId: request.requestId, value }), failure => {
        window.ide.reply({ requestId: request.requestId, error: failure instanceof Error ? failure.message : String(failure) });
      });
    });
    const stopEvent = window.ide.onEvent(event => {
      if (event.id !== idRef.current) return;
      current.current.onUnsavedChange?.(event.dirty);
      if (event.closed) { idRef.current = null; setId(null); setStartRequested(false); setNeedsStart(true); }
      if (event.error) setError(event.error);
    });
    return () => {
      stopRequest(); stopEvent();
      if (idRef.current) void window.ide.present(idRef.current, null).catch(() => {});
      idRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (!props.active || id !== null || error !== null) return;
    let live = true;
    const opening = startRequested ? window.ide.open(current.current.host) : window.ide.resume(current.current.host);
    void opening.then(opened => {
      if (opened === null) { if (live) setNeedsStart(true); return; }
      if (!live) { void window.ide.present(opened, null); return; }
      idRef.current = opened;
      setId(opened);
      setNeedsStart(false);
      void window.ide.update(opened, contextOf(current.current, findingActionsRef.current));
    }).catch(failure => { if (live) setError(String(failure)); });
    return () => { live = false; };
  }, [attempt, props.active, id, error, startRequested]);

  useEffect(() => {
    if (id) void window.ide.update(id, contextOf(props, findingActions)).catch(failure => setError(String(failure)));
  }, [id, props, findingActions]);

  useEffect(() => {
    const target = element.current;
    if (!id || !target) return;
    const layout = () => {
      const obscured = [...document.querySelectorAll('[role="dialog"]')].some(dialog => !dialog.contains(target) && dialog.getClientRects().length > 0);
      const bounds = target.getBoundingClientRect();
      const visible = current.current.active && !error && !obscured && bounds.width > 0 && bounds.height > 0;
      void window.ide.present(id, visible ? { x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height } : null)
        .catch(failure => setError(String(failure)));
    };
    const resize = new ResizeObserver(layout);
    resize.observe(target);
    const dialogs = new MutationObserver(layout);
    dialogs.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'hidden', 'open'] });
    window.addEventListener('resize', layout);
    window.addEventListener('scroll', layout, true);
    layout();
    return () => { resize.disconnect(); dialogs.disconnect(); window.removeEventListener('resize', layout); window.removeEventListener('scroll', layout, true); };
  }, [id, props.active, error]);

  return <div className="ct-ide-surface" ref={element} style={{ flex: 1, minHeight: 0, position: 'relative' }}>
    {error ? <div className="ct-empty agr-empty"><div role="alert">{error}</div><IdeCostNotice /><button type="button" className="agr-action is-primary" onClick={() => {
      const currentId = idRef.current;
      void (currentId ? window.ide.close(currentId) : Promise.resolve()).then(() => {
        idRef.current = null; setId(null); setError(null); setStartRequested(true); setNeedsStart(false); setAttempt(value => value + 1);
      }).catch(failure => setError(String(failure)));
    }}>Reconnect editor</button></div> : needsStart ? <div className="ct-empty agr-empty">
      <div className="agr-empty__title">Start the IDE for this working copy?</div>
      <IdeCostNotice />
      <div className="agr-empty__actions"><button type="button" className="agr-action is-primary" onClick={() => {
        setStartRequested(true); setNeedsStart(false);
      }}>Start IDE</button></div>
    </div> : id === null && <div className="ct-tools-loading" role="status">
      <span aria-hidden="true"><LogoLoading size={56} /></span>
      <span>{startRequested ? 'Starting code tools…' : 'Checking code tools…'}</span>
    </div>}
  </div>;
}

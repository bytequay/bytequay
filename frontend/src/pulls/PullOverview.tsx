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
import { useEffect, useMemo, useState, type MouseEvent } from 'react';
import type { LocalPRBundle, LocalPRComment } from '../types/localPr';
import { renderMarkdown } from '../markdown';
import type { MarkdownRepoContext } from '../markdown';
import { CommentActionsMenu } from '../pr/CommentActionsMenu';
import { EditableMarkdownBody } from '../pr/EditableMarkdownBody';
import { buildRawTimelineEntries } from '../pr/localpr/githubActivityRows';
import { ReactionAddButton } from '../pr/Reactions';
import { useGitHubActivityFeed } from '../pr/useGitHubActivityFeed';
import { PullAuthorAv } from './atoms';
import { buildChecks, buildOpenedCard, buildTimeline } from './detailModel';
import type { PullRow } from './model';
import PullChecksCard from './PullChecksCard';
import PullComposer from './PullComposer';
import PullMergeBox from './PullMergeBox';
import PullMetadataBar from './PullMetadataBar';
import PullTimeline, { ReactionPill } from './PullTimeline';
import { enableTaskCheckboxes, toggleTaskCheckbox } from './pullDescriptionTasks';

/** The Overview tab body: status/reviewer/label chips, the opened card,
 *  the timeline (with checks card), and the comment composer. */

function SkeletonLine({ width }: { width: string }) {
  return <span className="pl-pr-skeleton-line" style={{ display: 'block', width, height: 11, borderRadius: 999, background: '#f1f3f5', animation: 'pl-pulse 1.4s ease-in-out infinite' }} />;
}

function LoadingTimeline() {
  return (
    <div aria-hidden="true" style={{ position: 'relative', zIndex: 1 }}>
      <div className="pl-pr-skeleton-card" style={{ border: '1px solid #d5dbe1', borderRadius: 10, background: '#fff', margin: '18px 0', padding: 18 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span style={{ width: 30, height: 30, flexShrink: 0, borderRadius: '50%', background: '#f1f3f5', animation: 'pl-pulse 1.4s ease-in-out infinite' }} />
          <div style={{ display: 'grid', gap: 8, width: 130 }}><SkeletonLine width="100%" /><SkeletonLine width="65%" /></div>
        </div>
        <div style={{ display: 'grid', gap: 10, marginTop: 16 }}><SkeletonLine width="100%" /><SkeletonLine width="88%" /><SkeletonLine width="64%" /></div>
      </div>
      <div className="pl-pr-skeleton-card" style={{ border: '1px solid #d5dbe1', borderRadius: 10, background: '#fff', margin: '18px 0', padding: 18, display: 'grid', gap: 10 }}>
        <SkeletonLine width="92%" />
        <SkeletonLine width="68%" />
      </div>
    </div>
  );
}

export default function PullOverview({
  row, bundle, isMerged, refresh, onComment, onClosePullRequest, onDescriptionSaved,
  onLocalReply, onLocalResolve, onLocalReopen, currentUserLogin, onOpenCommentLocation,
}: {
  row: PullRow;
  bundle: LocalPRBundle | null | undefined;
  isMerged: boolean;
  /** Refreshes the supplied bundle after a merge / dequeue / delete-branch. */
  refresh?: () => void;
  onComment?: (body: string) => Promise<void>;
  onClosePullRequest?: () => Promise<void>;
  onDescriptionSaved?: () => void;
  onLocalReply?: (root: LocalPRComment, body: string) => Promise<void>;
  onLocalResolve?: (commentId: string) => Promise<void>;
  onLocalReopen?: (commentId: string) => Promise<void>;
  currentUserLogin?: string | null;
  onOpenCommentLocation?: (filePath: string, line: number | null, side: 'LEFT' | 'RIGHT') => void;
}) {
  const loading = bundle === undefined;
  const opened = buildOpenedCard(row, bundle);
  const items = bundle !== null && bundle !== undefined ? buildTimeline(bundle) : [];
  const remotePrNumber = bundle?.pr.remotePrNumber ?? null;
  const { activity, reviewThreads, detail: remoteDetail, refresh: refreshGitHubFeed } = useGitHubActivityFeed(row.repo, remotePrNumber);
  const reviewThreadsByRemoteId = useMemo(() => {
    const byEvent = new Map<number, typeof reviewThreads>();
    for (const entry of buildRawTimelineEntries(activity, reviewThreads)) {
      if (entry.kind === 'activity' && entry.item.eventType === 'reviewed'
          && entry.item.githubId !== null && entry.attachedThreads !== undefined) {
        byEvent.set(entry.item.githubId, entry.attachedThreads);
      }
    }
    return byEvent;
  }, [activity, reviewThreads]);
  const checks = bundle !== null && bundle !== undefined ? buildChecks(bundle.checks) : null;
  const [owner, name] = row.repo.split('/');
  const repoCtx: MarkdownRepoContext = { owner: owner ?? row.repo, repo: name ?? row.repo };
  const [editingDescription, setEditingDescription] = useState(false);
  const [optimisticDescription, setOptimisticDescription] = useState<string | null>(null);
  const [savingDescriptionTask, setSavingDescriptionTask] = useState(false);
  const description = optimisticDescription ?? opened.description ?? '';

  useEffect(() => {
    if (optimisticDescription !== null && opened.description === optimisticDescription) {
      setOptimisticDescription(null);
    }
  }, [opened.description, optimisticDescription]);

  const canEditDescription = remotePrNumber !== null;
  const saveDescription = async (body: string) => {
    if (remotePrNumber === null) return;
    await window.bridge.updatePrBody(row.repo, remotePrNumber, body);
    setOptimisticDescription(body);
    onDescriptionSaved?.();
  };

  const changeDescriptionTask = async (event: MouseEvent<HTMLDivElement>) => {
    const checkbox = event.target;
    if (!(checkbox instanceof HTMLInputElement) || checkbox.type !== 'checkbox' || savingDescriptionTask) return;
    const checkboxes = Array.from(event.currentTarget.querySelectorAll<HTMLInputElement>('input[type="checkbox"]'));
    const next = toggleTaskCheckbox(description, checkboxes.indexOf(checkbox), checkbox.checked);
    if (next === null) {
      checkbox.checked = !checkbox.checked;
      return;
    }

    const previous = description;
    setOptimisticDescription(next);
    setSavingDescriptionTask(true);
    try {
      await saveDescription(next);
    }
    catch {
      setOptimisticDescription(previous);
    }
    finally {
      setSavingDescriptionTask(false);
    }
  };
  return (
    <>
      <PullMetadataBar row={row} />

      {/* opened card */}
      <div style={{ position: 'relative', border: '1px solid #d5dbe1', borderRadius: 10, background: '#fff', marginTop: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '11px 16px', borderBottom: '1px solid #eef1f4' }}>
          <PullAuthorAv login={opened.author} size={24} square={opened.bot} />
          <span style={{ fontSize: 13, color: '#57606a' }}>
            <span style={{ color: '#17191c', fontWeight: 600 }}>{opened.author}</span>{' '}
            {remotePrNumber === null ? 'created local draft' : 'opened pull request'}
          </span>
          <span style={{ marginLeft: 'auto', fontSize: 12.5, color: '#8b949e', flexShrink: 0 }}>{opened.time}</span>
          {canEditDescription && (
            <CommentActionsMenu
              linkHref={row.dto.htmlUrl || `https://github.com/${row.repo}/pull/${remotePrNumber}`}
              onEdit={() => setEditingDescription(true)}
            />
          )}
        </div>
        <div style={{ padding: '14px 18px 6px', fontSize: 13.5, lineHeight: 1.65, color: '#1f2328' }}>
          {loading ? (
            <div role="status" aria-label="Loading pull request details" style={{ display: 'grid', gap: 10, padding: '2px 0 7px' }}>
              <SkeletonLine width="14%" />
              <SkeletonLine width="100%" />
              <SkeletonLine width="82%" />
            </div>
          ) : opened.description !== null && (
            <div className="pl-pr-description-editor">
              <EditableMarkdownBody
                body={description}
                canEdit={canEditDescription}
                onSave={saveDescription}
                editing={editingDescription}
                onEditingChange={setEditingDescription}
                composer
                renderViewSlot={body => body.trim().length > 0
                  ? (
                    <div
                      className="md-body pl-pr-description"
                      aria-busy={savingDescriptionTask}
                      onClick={event => { void changeDescriptionTask(event); }}
                      dangerouslySetInnerHTML={{
                        __html: canEditDescription && !savingDescriptionTask
                          ? enableTaskCheckboxes(renderMarkdown(body, repoCtx))
                          : renderMarkdown(body, repoCtx),
                      }}
                    />
                  )
                  : <p style={{ margin: 0, color: '#8b949e', fontStyle: 'italic' }}>No description provided.</p>}
              />
            </div>
          )}
        </div>
        {loading ? (
          <div style={{ padding: '8px 16px 13px' }}><ReactionAddButton disabled onPick={() => {}} /></div>
        ) : remotePrNumber !== null && (
          <div style={{ padding: '8px 16px 13px' }}>
            <ReactionPill onPick={content => window.bridge.addPullRequestReaction(row.repo, remotePrNumber, content)} />
          </div>
        )}
      </div>

      {/* timeline */}
      <div style={{ position: 'relative', padding: '6px 0 0' }}>
        <div style={{ position: 'absolute', left: 12, top: 8, bottom: 8, width: 2, background: '#e9ebee' }} />
        {loading ? <LoadingTimeline /> : (
          <>
            <PullTimeline
              items={items}
              repo={row.repo}
              prAuthor={(bundle?.pr.author ?? row.author).replace(/^@/, '')}
              prHtmlUrl={row.dto.htmlUrl || `https://github.com/${row.repo}/pull/${remotePrNumber}`}
              reviewThreadsByRemoteId={reviewThreadsByRemoteId}
              onCommentReaction={(commentId, content) => window.bridge.addIssueCommentReaction(row.repo, commentId, content)}
              onThreadReply={remotePrNumber === null ? undefined : async (rootGithubId, body) => {
                await window.bridge.replyToReviewThread(row.repo, remotePrNumber, rootGithubId, body);
                refreshGitHubFeed(true);
              }}
              onThreadReact={async (commentGithubId, content) => {
                await window.bridge.addReviewCommentReaction(row.repo, commentGithubId, content);
                refreshGitHubFeed(true);
              }}
              onThreadSetResolved={async (rootGithubId, resolved) => {
                await window.bridge.setReviewThreadResolved(row.repo, Number(row.dto.id) || 0, rootGithubId, resolved);
                refreshGitHubFeed(true);
              }}
              localPr={bundle?.pr}
              onLocalReply={onLocalReply}
              onLocalResolve={onLocalResolve}
              onLocalReopen={onLocalReopen}
              currentUserLogin={currentUserLogin}
              onOpenCommentLocation={onOpenCommentLocation}
            />
            {checks !== null && <PullChecksCard model={checks} />}
          </>
        )}
      </div>

      {bundle !== null && bundle !== undefined && (
        <PullMergeBox
          pr={bundle.pr}
          detail={remoteDetail}
          onDone={() => { refresh?.(); refreshGitHubFeed(true); }}
        />
      )}

      <PullComposer onComment={onComment} onClose={isMerged ? undefined : onClosePullRequest} repoCtx={repoCtx} />
    </>
  );
}

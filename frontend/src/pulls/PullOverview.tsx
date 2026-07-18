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
import type { LocalPRBundle } from '../types/localPr';
import { renderMarkdown } from '../markdown';
import type { MarkdownRepoContext } from '../markdown';
import { CommentActionsMenu } from '../pr/CommentActionsMenu';
import { EditableMarkdownBody } from '../pr/EditableMarkdownBody';
import { Av } from './atoms';
import { buildChecks, buildOpenedCard, buildTimeline } from './detailModel';
import type { PullRow } from './model';
import PullChecksCard from './PullChecksCard';
import PullComposer from './PullComposer';
import PullMetadataBar from './PullMetadataBar';
import PullTimeline, { ReactionPill } from './PullTimeline';

/** The Overview tab body: status/reviewer/label chips, the opened card,
 *  the timeline (with checks card), and the comment composer. */

export default function PullOverview({ row, bundle, isMerged, onComment, onDescriptionSaved }: {
  row: PullRow;
  bundle: LocalPRBundle | null | undefined;
  isMerged: boolean;
  onComment?: (body: string) => Promise<void>;
  onDescriptionSaved?: () => void;
}) {
  const opened = buildOpenedCard(row, bundle);
  const items = bundle !== null && bundle !== undefined ? buildTimeline(bundle) : [];
  const remotePrNumber = bundle?.pr.remotePrNumber ?? null;
  const checks = bundle !== null && bundle !== undefined ? buildChecks(bundle.checks) : null;
  const [owner, name] = row.repo.split('/');
  const repoCtx: MarkdownRepoContext = { owner: owner ?? row.repo, repo: name ?? row.repo };
  const [editingDescription, setEditingDescription] = useState(false);
  const [optimisticDescription, setOptimisticDescription] = useState<string | null>(null);
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
  return (
    <>
      <PullMetadataBar row={row} />

      {/* opened card */}
      <div style={{ position: 'relative', border: '1px solid #d5dbe1', borderRadius: 10, background: '#fff', marginTop: 14 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '11px 16px', borderBottom: '1px solid #eef1f4' }}>
          <Av login={opened.author} size={24} square={opened.bot} />
          <span style={{ fontSize: 13, color: '#57606a' }}>
            <span style={{ color: '#17191c', fontWeight: 400 }}>{opened.author}</span> opened pull request
          </span>
          <span style={{ marginLeft: 'auto', fontSize: 12.5, color: '#8b949e', flexShrink: 0 }}>{opened.time}</span>
          {canEditDescription && (
            <CommentActionsMenu
              linkHref={row.dto.htmlUrl || `https://github.com/${row.repo}/pull/${remotePrNumber}`}
              onEdit={() => setEditingDescription(true)}
            />
          )}
        </div>
        <div style={{ padding: '10px 18px 6px', fontSize: 13.5, lineHeight: 1.65, color: '#1f2328' }}>
          {opened.description !== null && (
            <div className="pl-pr-description-editor">
              <EditableMarkdownBody
                body={description}
                canEdit={canEditDescription}
                onSave={saveDescription}
                editing={editingDescription}
                onEditingChange={setEditingDescription}
                composer
                renderViewSlot={body => body.trim().length > 0
                  ? <div className="pl-pr-description" dangerouslySetInnerHTML={{ __html: renderMarkdown(body, repoCtx) }} />
                  : <p style={{ margin: 0, color: '#8b949e', fontStyle: 'italic' }}>No description provided.</p>}
              />
            </div>
          )}
        </div>
        {remotePrNumber !== null && (
          <div style={{ padding: '8px 16px 13px' }}>
            <ReactionPill onPick={content => window.bridge.addPullRequestReaction(row.repo, remotePrNumber, content)} />
          </div>
        )}
      </div>

      {/* timeline */}
      <div style={{ position: 'relative', padding: '6px 0 0' }}>
        <div style={{ position: 'absolute', left: 12, top: 8, bottom: 8, width: 2, background: '#e9ebee' }} />
        <PullTimeline
          items={items}
          repo={row.repo}
          onCommentReaction={(commentId, content) => window.bridge.addIssueCommentReaction(row.repo, commentId, content)}
        />
        {checks !== null && <PullChecksCard model={checks} />}
      </div>

      <PullComposer canClose={!isMerged} onComment={onComment} repoCtx={repoCtx} />
    </>
  );
}

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
import type { LocalPRBundle } from '../types/localPr';
import { renderMarkdown } from '../markdown';
import type { MarkdownRepoContext } from '../markdown';
import { Av } from './atoms';
import { buildChecks, buildOpenedCard, buildTimeline, labelDotColor, reviewerLogins } from './detailModel';
import type { PullRow } from './model';
import PullChecksCard from './PullChecksCard';
import PullComposer from './PullComposer';
import PullTimeline, { ReactionPill } from './PullTimeline';

/** The Overview tab body: status/reviewer/label chips, the opened card,
 *  the timeline (with checks card), and the comment composer. */

const chipStyle = { display: 'inline-flex', alignItems: 'center', gap: 7, border: '1px solid #d5dbe1', borderRadius: 999, padding: '4px 12px', fontSize: 12.5, color: '#454c54' } as const;

export default function PullOverview({ row, bundle, isMerged, onComment }: {
  row: PullRow;
  bundle: LocalPRBundle | null | undefined;
  isMerged: boolean;
  onComment?: (body: string) => Promise<void>;
}) {
  const opened = buildOpenedCard(row, bundle);
  const reviewers = reviewerLogins(row);
  const labelsCount = row.dto.labels.length;
  const items = bundle !== null && bundle !== undefined ? buildTimeline(bundle) : [];
  const checks = bundle !== null && bundle !== undefined ? buildChecks(bundle.checks) : null;
  const [owner, name] = row.repo.split('/');
  const repoCtx: MarkdownRepoContext = { owner: owner ?? row.repo, repo: name ?? row.repo };
  return (
    <>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        {reviewers.length > 0 && (
          <span style={{ ...chipStyle, padding: '4px 12px 4px 10px' }}>
            Reviewers
            {reviewers.map(login => (
              <span key={login} style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}>
                <Av login={login} size={18} />
                <span style={{ fontWeight: 600, color: '#17191c' }}>{login}</span>
              </span>
            ))}
          </span>
        )}
        <span style={chipStyle}>
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="8" r="4" />
            <path d="M4 21a8 8 0 0 1 16 0" />
          </svg>
          Assignees
        </span>
        {labelsCount > 0 && (
          <span style={chipStyle}>
            <span style={{ width: 9, height: 9, borderRadius: '50%', background: labelDotColor(row) }} />
            {labelsCount} labels
          </span>
        )}
      </div>

      {/* opened card */}
      <div style={{ position: 'relative', border: '1px solid #d5dbe1', borderRadius: 10, background: '#fff', marginTop: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '11px 16px', borderBottom: '1px solid #eef1f4' }}>
          <Av login={opened.author} size={24} square={opened.bot} />
          <span style={{ fontSize: 13, color: '#57606a' }}>
            <b style={{ color: '#17191c', fontWeight: 600 }}>{opened.author}</b> opened pull request
          </span>
          <span style={{ marginLeft: 'auto', fontSize: 12.5, color: '#8b949e', flexShrink: 0 }}>{opened.time}</span>
          <span style={{ color: '#8b949e', cursor: 'pointer', letterSpacing: 1, fontWeight: 700 }}>···</span>
        </div>
        <div style={{ padding: '14px 18px 6px', fontSize: 13.5, lineHeight: 1.65, color: '#1f2328' }}>
          {opened.description !== null && opened.description.trim().length > 0 && (
            <div dangerouslySetInnerHTML={{ __html: renderMarkdown(opened.description, repoCtx) }} />
          )}
          {opened.description !== null && opened.description.trim().length === 0 && (
            <p style={{ margin: 0, color: '#8b949e', fontStyle: 'italic' }}>No description provided.</p>
          )}
        </div>
        <div style={{ padding: '8px 16px 13px' }}><ReactionPill /></div>
      </div>

      {/* timeline */}
      <div style={{ position: 'relative', padding: '6px 0 0' }}>
        <div style={{ position: 'absolute', left: 12, top: 8, bottom: 8, width: 2, background: '#e9ebee' }} />
        <PullTimeline items={items} repo={row.repo} />
        {checks !== null && <PullChecksCard model={checks} />}
      </div>

      <PullComposer canClose={!isMerged} onComment={onComment} repoCtx={repoCtx} />
    </>
  );
}

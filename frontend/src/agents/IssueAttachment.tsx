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

import { CircleDot, ExternalLink, RefreshCw } from 'lucide-react';
import { useId, type ReactNode } from 'react';
import MarkdownBody from '../pulls/MarkdownBody';
import ReferenceCard, { previewMarkdown, useHoverCard } from '../ReferenceCard';
import type { AgentIssueSourceDto } from '../types';

type IssueContext = Pick<AgentIssueSourceDto, 'number' | 'repo' | 'title' | 'body' | 'url'>;

/** Accepted source context stays available beside the draft without consuming transcript space. */
export function IssueAttachment({ source, onRefresh, refreshing = false }: {
  source: IssueContext;
  onRefresh?: () => void;
  refreshing?: boolean;
}) {
  const previewId = useId();
  const hover = useHoverCard(0);
  const open = hover.anchor !== null;
  const openSource = () => { hover.close(); void window.bridge.openInAppBrowser(source.url); };
  return <div className="agt-issue-attachment" onFocus={hover.hold} onBlur={() => hover.hide()}>
    <button type="button" className="agt-issue-attachment__source" aria-label={`Preview issue: ${source.repo}#${source.number}`}
      aria-haspopup="dialog" aria-expanded={open} aria-controls={open ? previewId : undefined}
      onMouseEnter={event => hover.show(event.currentTarget)} onMouseLeave={() => hover.hide()}
      onFocus={event => hover.show(event.currentTarget)} onClick={event => hover.show(event.currentTarget, 0)}
      onKeyDown={event => {
        if (event.key !== 'ArrowDown') return;
        event.preventDefault();
        hover.show(event.currentTarget, 0);
        window.requestAnimationFrame(() => document.getElementById(previewId)?.querySelector('button')?.focus());
      }}>
      <CircleDot size={15} aria-hidden="true" />
      <strong>{source.repo}#{source.number}</strong><span>{source.title}</span>
    </button>
    {onRefresh && <button type="button" className="agt-headbtn" title="Refresh issue" aria-label="Refresh issue" disabled={refreshing}
      onClick={onRefresh}><RefreshCw size={14} aria-hidden="true" /></button>}
    <button type="button" className="agt-headbtn" title="View issue" aria-label={`View issue: ${source.repo}#${source.number}`}
      onClick={openSource}><ExternalLink size={14} aria-hidden="true" /></button>
    <ReferenceCard
      id={previewId}
      hover={hover}
      item={{ type: 'issue', repo: source.repo, number: source.number, title: source.title, author: null }}
      body={<MarkdownBody body={previewMarkdown(source.body) || 'No description.'} repository={source.repo} />}
      actions={<button type="button" className="bq-button" onClick={openSource}>
        View issue <ExternalLink size={13} aria-hidden="true" />
      </button>}
    />
  </div>;
}

/** The first turn is identified by durable provenance, never by interpreting provider prompt text. */
export function IssueRequestCard({ source, attachments }: { source: AgentIssueSourceDto; attachments?: ReactNode }) {
  const reference = `${source.repo}#${source.number}`;
  return <div className="agt-issue-request">
    <div className="agt-user__request">
      <CircleDot aria-hidden="true" />
      <span className="agt-user__request-action">Start development</span>
      <span className="agt-user__request-head" title={reference}>{reference}</span>
    </div>
    {attachments}
  </div>;
}

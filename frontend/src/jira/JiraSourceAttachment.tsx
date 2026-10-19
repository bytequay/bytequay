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

import { useId, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { ExternalLink } from 'lucide-react';
import { anchorPanel, previewMarkdown, useHoverCard } from '../ReferenceCard';
import MarkdownBody from '../pulls/MarkdownBody';
import { zoomReference } from '../githubReference';
import type { JiraTaskSource } from './contracts';
import { JiraIcon } from '@bytequay/jira/renderer';

type Source = Pick<JiraTaskSource, 'connectionId' | 'cloudId' | 'issueId' | 'key' | 'title' | 'body'>;
export function JiraSourceAttachment({ source }: { source: Source }) {
  const id = useId();
  const container = useRef<HTMLDivElement>(null);
  const hover = useHoverCard(0, 150);
  const open = () => { hover.close(); zoomReference({ kind: 'jira', connectionId: source.connectionId, cloudId: source.cloudId, issueId: source.issueId }); };
  return <div ref={container} className="agt-issue-attachment">
    <button type="button" className="agt-issue-attachment__source" aria-label={`Preview ticket ${source.key}`}
      aria-haspopup="dialog" aria-expanded={hover.anchor !== null} aria-controls={hover.anchor ? id : undefined}
      onMouseEnter={event => hover.show(event.currentTarget)} onMouseLeave={() => hover.hide()}
      onFocus={event => hover.show(event.currentTarget)} onBlur={() => hover.hide()}
      onClick={event => hover.show(event.currentTarget, 0)} onKeyDown={event => {
        if (event.key === 'Escape') hover.close();
        if (event.key === 'ArrowDown') { event.preventDefault(); hover.show(event.currentTarget, 0); requestAnimationFrame(() => document.getElementById(id)?.querySelector('button')?.focus()); }
      }}>
      <JiraIcon size={15} aria-hidden="true" /><strong>{source.key}</strong><span>{source.title}</span>
    </button>
    <button type="button" className="agt-headbtn" title="View ticket" aria-label={`View ticket ${source.key}`} onClick={open}><ExternalLink size={14} /></button>
    {hover.anchor && createPortal(<div id={id} className="jira-source-preview" role="dialog" aria-label={`Ticket ${source.key}`}
      style={anchorPanel(hover.anchor, Math.min(480, window.innerWidth - 20), 350, hover.anchor.left)}
      onMouseEnter={hover.hold} onMouseLeave={() => hover.hide()} onFocus={hover.hold} onBlur={() => hover.hide()}>
      <strong>{source.key} · {source.title}</strong><MarkdownBody body={previewMarkdown(source.body) || 'No description.'} />
      <button type="button" className="bq-button" onClick={open}>View ticket</button>
    </div>, container.current?.closest('dialog') ?? document.body)}
  </div>;
}
export function JiraRequestCard({ source, attachments }: { source: JiraTaskSource; attachments?: ReactNode }) {
  return <div className="agt-issue-request"><div className="agt-user__request">
    <JiraIcon aria-hidden="true" /><span className="agt-user__request-action">Start development</span>
    <span className="agt-user__request-head" title={source.title}>{source.key}</span>
  </div>{attachments}</div>;
}

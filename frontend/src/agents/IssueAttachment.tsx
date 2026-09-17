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
import { useEffect, useId, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import MarkdownBody from '../pulls/MarkdownBody';
import type { AgentIssueSourceDto } from '../types';

type IssueContext = Pick<AgentIssueSourceDto, 'number' | 'repo' | 'title' | 'body' | 'url'>;

/** Accepted source context stays available beside the draft without consuming transcript space. */
export function IssueAttachment({ source, onRefresh, refreshing = false }: {
  source: IssueContext;
  onRefresh?: () => void;
  refreshing?: boolean;
}) {
  const previewId = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  const preview = useRef<HTMLDivElement>(null);
  const hideTimer = useRef(0);
  const [position, setPosition] = useState<CSSProperties | null>(null);
  const keepOpen = () => window.clearTimeout(hideTimer.current);
  const hide = () => { keepOpen(); hideTimer.current = window.setTimeout(() => setPosition(null), 120); };
  const show = () => {
    keepOpen();
    const rect = trigger.current?.getBoundingClientRect();
    if (!rect) return;
    const above = rect.top > 240;
    const width = Math.min(480, window.innerWidth - 24);
    setPosition({
      width,
      left: Math.max(12, Math.min(rect.left, window.innerWidth - width - 12)),
      ...(above ? { bottom: window.innerHeight - rect.top + 8 } : { top: rect.bottom + 8 }),
      maxHeight: Math.max(120, Math.min(420, above ? rect.top - 20 : window.innerHeight - rect.bottom - 20)),
    });
  };
  useEffect(() => () => window.clearTimeout(hideTimer.current), []);
  useEffect(() => {
    if (position === null) return;
    const close = (event: Event) => {
      if (event.target instanceof Node && preview.current?.contains(event.target)) return;
      setPosition(null);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      event.stopPropagation();
      if (preview.current?.contains(document.activeElement)) trigger.current?.focus();
      setPosition(null);
    };
    window.addEventListener('resize', close);
    window.addEventListener('scroll', close, true);
    document.addEventListener('keydown', escape);
    return () => {
      window.removeEventListener('resize', close);
      window.removeEventListener('scroll', close, true);
      document.removeEventListener('keydown', escape);
    };
  }, [position]);
  const openSource = () => { setPosition(null); void window.bridge.openInAppBrowser(source.url); };
  return <div className="agt-issue-attachment" onFocus={keepOpen} onBlur={hide}>
    <button ref={trigger} type="button" className="agt-issue-attachment__source" aria-label={`Preview issue: ${source.repo}#${source.number}`}
      aria-haspopup="dialog" aria-expanded={position !== null} aria-controls={position === null ? undefined : previewId}
      onMouseEnter={show} onMouseLeave={hide} onFocus={show} onClick={show}
      onKeyDown={event => {
        if (event.key !== 'ArrowDown') return;
        event.preventDefault();
        show();
        window.requestAnimationFrame(() => preview.current?.querySelector<HTMLButtonElement>('button')?.focus());
      }}>
      <CircleDot size={15} aria-hidden="true" />
      <strong>{source.repo}#{source.number}</strong><span>{source.title}</span>
    </button>
    {onRefresh && <button type="button" className="agt-headbtn" title="Refresh issue" aria-label="Refresh issue" disabled={refreshing}
      onClick={onRefresh}><RefreshCw size={14} aria-hidden="true" /></button>}
    <button type="button" className="agt-headbtn" title="View issue" aria-label={`View issue: ${source.repo}#${source.number}`}
      onClick={openSource}><ExternalLink size={14} aria-hidden="true" /></button>
    {position !== null && createPortal(<div ref={preview} id={previewId} role="dialog" aria-label={`Issue ${source.repo}#${source.number}`}
      className="agt-issue-preview" style={position} onMouseEnter={keepOpen} onMouseLeave={hide} onFocus={keepOpen} onBlur={hide}>
      <div className="agt-issue-preview__head"><span>{source.repo}#{source.number}</span>
        <button type="button" className="agt-action" onClick={openSource}>View issue <ExternalLink size={13} aria-hidden="true" /></button></div>
      <strong className="agt-issue-preview__title">{source.title}</strong>
      <MarkdownBody body={source.body || 'No description.'} repository={source.repo} />
    </div>, document.body)}
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

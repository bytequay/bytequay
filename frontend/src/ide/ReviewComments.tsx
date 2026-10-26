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
import { useEffect, useId, useRef, useState } from 'react';
import { ArrowUpRight, X } from 'lucide-react';
import MarkdownBody from '../pulls/MarkdownBody';
import { shortPath } from '../pulls/agent-review/model';
import { ReviewThreadCard, ReviewThreadMessage } from '../pulls/ReviewThreadCard';
import type { ReviewCommentCard, ReviewCommentTarget } from './reviewCommentModel';
import './review-comments.css';

export default function ReviewComments({ cards, selectedPath, repository, unavailable, onJump, onComment, onClose }: {
  cards: ReviewCommentCard[];
  selectedPath: string | null;
  repository: string | undefined;
  unavailable: boolean;
  onJump: (target: ReviewCommentTarget) => Promise<void>;
  onComment?: () => void;
  onClose: () => void;
}) {
  const [scope, setScope] = useState<'all' | 'file'>('all');
  const [error, setError] = useState<string | null>(null);
  const [opening, setOpening] = useState<string | null>(null);
  const busy = useRef(false);
  const id = useId();
  const firstTab = useRef<HTMLButtonElement>(null);
  useEffect(() => { firstTab.current?.focus(); }, []);
  const here = cards.filter(card => selectedPath !== null && card.path === selectedPath);
  const visible = scope === 'all' ? cards : here;
  const jump = async (card: ReviewCommentCard) => {
    if (!card.target || busy.current) return;
    busy.current = true; setOpening(card.id); setError(null);
    try { await onJump(card.target); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not open the code.'); }
    finally { busy.current = false; setOpening(null); }
  };
  return <aside className="ide-comments" aria-label="Review comments" onKeyDown={event => {
    if (event.key === 'Escape') { event.stopPropagation(); onClose(); }
  }}>
    <header><strong>Review comments</strong><button type="button" aria-label="Close review comments" onClick={onClose}><X size={15} /></button></header>
    <div className="ide-comments__tabs" role="tablist" aria-label="Comment scope">
      {(['all', 'file'] as const).map(value => <button key={value} ref={value === 'all' ? firstTab : undefined} type="button" role="tab" id={`${id}-${value}`} aria-controls={`${id}-cards`}
        aria-selected={scope === value} tabIndex={scope === value ? 0 : -1} onClick={() => setScope(value)} onKeyDown={event => {
          if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
          event.preventDefault();
          const next = event.key === 'Home' ? 'all' : event.key === 'End' ? 'file' : value === 'all' ? 'file' : 'all';
          setScope(next); document.getElementById(`${id}-${next}`)?.focus();
        }}>{value === 'all' ? 'All' : 'This file'} <span>{value === 'all' ? cards.length : here.length}</span></button>)}
    </div>
    {scope === 'file' && selectedPath && <p className="ide-comments__path" title={selectedPath}>{selectedPath}</p>}
    {onComment && <button type="button" className="ide-comments__compose" onClick={onComment}>Comment on selection</button>}
    {unavailable && <p role="status">GitHub review comments could not be loaded.</p>}
    {error && <p role="alert">{error}</p>}
    <div className="ide-comments__list" role="tabpanel" id={`${id}-cards`} aria-labelledby={`${id}-${scope}`} tabIndex={0}>
      {visible.length === 0 && <p className="ide-comments__empty">{scope === 'file'
        ? selectedPath === null ? 'Select a file to see its review comments.' : 'No review comments in this file.'
        : 'No review comments yet.'}</p>}
      {visible.map(card => <ReviewThreadCard key={card.id} tone={card.kind === 'GitHub' ? 'github' : 'local'}
        title={<code className="lrc__title" title={card.location}>{shortPath(card.location)}</code>}
        chip={<span className="lrc__chip">{card.kind}</span>}
        badges={card.status && <span className="lrc__badge">{card.status}</span>}
        resolved={card.resolved} outdated={card.outdated} attributes={{ 'data-comment-id': card.id }}
        controls={card.target && <footer className="lrc__foot"><button type="button" className="ide-comments__jump" disabled={opening !== null}
          aria-label={`${card.target.end === null ? 'Open file' : 'Jump to code'}: ${card.location}`} onClick={() => { void jump(card); }}>
          <ArrowUpRight size={13} />{opening === card.id ? 'Opening…' : card.target.end === null ? 'Open file' : 'Jump to code'}
        </button></footer>}>
        {card.messages.map((message, index) => <ReviewThreadMessage key={index} author={<strong>{message.author}</strong>} timestamp={message.timestamp}>
          {index === 0 && card.title && <strong>{card.title}</strong>}
          <MarkdownBody body={message.body} repository={repository} />
        </ReviewThreadMessage>)}
      </ReviewThreadCard>)}
    </div>
  </aside>;
}

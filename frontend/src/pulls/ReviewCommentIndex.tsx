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
import { createContext, useContext, useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode, type Ref } from 'react';
import '../css/review-comment-index.css';

const IndexedPage = createContext(false);

export function commentIndexTarget(author: 'human' | 'agent', label: string, body: string): Record<`data-${string}`, string> {
  const text = body.replace(/\s+/g, ' ').trim();
  const excerpt = Array.from(text.split(' ').slice(0, 20).join(' ')).slice(0, 240).join('').trimEnd();
  return { 'data-review-comment': author, 'data-review-comment-label': label.replace(/\s+/g, ' ').trim().slice(0, 240),
    'data-review-comment-preview': excerpt + (excerpt.length < text.length ? '…' : '') };
}

type Entry = { target: HTMLElement; author: string; label: string; preview: string };

/** Index the rendered comments so filtering, replies and nested review panels share the page's actual destinations. */
export default function ReviewCommentIndex({ children, className, ref, enabled = true, ...attributes }: {
  children: ReactNode;
  className?: string;
  ref?: Ref<HTMLDivElement>;
  enabled?: boolean;
  hidden?: boolean;
  'aria-label'?: string;
}) {
  const indexed = useContext(IndexedPage);
  const active = enabled && !indexed;
  const content = useRef<HTMLDivElement>(null);
  const markers = useRef<HTMLOListElement>(null);
  const followLatest = useRef(true);
  const [entries, setEntries] = useState<Entry[]>([]);
  const [preview, setPreview] = useState<{ target: HTMLElement; button: HTMLButtonElement } | null>(null);
  const tooltip = useRef<HTMLDivElement>(null);
  const tooltipId = useId();
  const previewEntry = entries.find(entry => entry.target === preview?.target);
  useEffect(() => {
    const root = content.current;
    if (!active || root === null) return;
    const read = () => {
      const next = Array.from(root.querySelectorAll<HTMLElement>('[data-review-comment]'))
        .filter(target => {
          for (let ancestor: HTMLElement | null = target; ancestor !== null && ancestor !== root; ancestor = ancestor.parentElement) {
            if (ancestor.hidden || ancestor.dataset.reviewClosed !== undefined) return false;
          }
          return true;
        })
        .map(target => ({ target, author: target.dataset.reviewComment ?? 'human', label: target.dataset.reviewCommentLabel ?? '',
          preview: target.dataset.reviewCommentPreview ?? '' }));
      setEntries(previous => previous.length === next.length && previous.every((entry, index) => entry.target === next[index].target
        && entry.author === next[index].author && entry.label === next[index].label && entry.preview === next[index].preview) ? previous : next);
    };
    read();
    const observer = new MutationObserver(read);
    observer.observe(root, { subtree: true, childList: true, attributes: true,
      attributeFilter: ['hidden', 'data-review-closed', 'data-review-comment', 'data-review-comment-label', 'data-review-comment-preview'] });
    return () => observer.disconnect();
  }, [active]);

  useLayoutEffect(() => {
    const list = markers.current;
    if (list === null) followLatest.current = true;
    else if (followLatest.current) list.scrollTop = list.scrollHeight;
  }, [entries, active, attributes.hidden]);

  useLayoutEffect(() => {
    const tip = tooltip.current;
    const rail = tip?.parentElement;
    const pane = rail?.parentElement;
    if (tip === null || !rail || !pane || preview === null) return;
    const bounds = pane.getBoundingClientRect();
    const railBounds = rail.getBoundingClientRect();
    tip.style.maxWidth = `${Math.max(0, bounds.right - railBounds.right - 8)}px`;
    const button = preview.button.getBoundingClientRect();
    const top = Math.max(bounds.top + 8, Math.min(button.top + button.height / 2 - tip.offsetHeight / 2, bounds.bottom - tip.offsetHeight - 8));
    tip.style.top = `${top - railBounds.top}px`;
  }, [preview, previewEntry]);

  useEffect(() => {
    if (preview === null) return;
    const close = () => setPreview(null);
    const onKeyDown = (event: KeyboardEvent) => { if (event.key === 'Escape') close(); };
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('resize', close);
    return () => { window.removeEventListener('keydown', onKeyDown); window.removeEventListener('resize', close); };
  }, [preview]);

  const role = attributes['aria-label'] === undefined ? undefined : 'region';
  if (!active) return <div className={className} ref={ref} role={role} {...attributes}>{children}</div>;
  const jump = (target: HTMLElement) => {
    setPreview(null);
    for (let ancestor: HTMLElement | null = target; ancestor !== null && ancestor !== content.current; ancestor = ancestor.parentElement) {
      if (ancestor instanceof HTMLDetailsElement) ancestor.open = true;
    }
    target.tabIndex = -1;
    target.focus({ preventScroll: true });
    const page = content.current?.parentElement;
    // scrollIntoView also scrolls clipped review cards, hiding their headers and leaving space below Reply.
    page?.scrollTo({
      top: page.scrollTop + target.getBoundingClientRect().top - page.getBoundingClientRect().top - parseFloat(getComputedStyle(target).scrollMarginTop),
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth',
    });
  };
  return <IndexedPage.Provider value>
    <div className={`${className ?? ''} review-comment-layout`} ref={ref} role={role} {...attributes}>
      {entries.length > 0 && <nav className="review-comment-index" aria-label="Review comment index"
        onMouseLeave={() => setPreview(null)} onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setPreview(null); }}>
        <ol ref={markers} onScroll={event => {
          const list = event.currentTarget;
          followLatest.current = list.scrollHeight - list.scrollTop - list.clientHeight <= 1;
          setPreview(current => current?.button === document.activeElement ? { ...current } : null);
        }}>{entries.map((entry, index) => {
          const label = `${index + 1}. ${entry.author === 'agent' ? 'Agent' : 'Human'} · ${entry.label} · ${entry.preview}`;
          return <li key={index}><button type="button" className={`is-${entry.author}${previewEntry === entry ? ' is-preview' : ''}`} aria-label={label}
            aria-describedby={previewEntry === entry ? tooltipId : undefined}
            onMouseEnter={event => setPreview({ target: entry.target, button: event.currentTarget })}
            onFocus={event => setPreview({ target: entry.target, button: event.currentTarget })}
            onClick={() => jump(entry.target)}><span className="review-comment-index__dash" aria-hidden="true" /></button></li>;
        })}</ol>
        {previewEntry && <div ref={tooltip} id={tooltipId} role="tooltip" className="review-comment-index__preview">
          <div className="review-comment-index__preview-card">
            <strong>{previewEntry.label}</strong>
            <p>{previewEntry.preview || 'No comment text'}</p>
          </div>
        </div>}
      </nav>}
      <div className="review-comment-scroll">
        <div className="review-comment-content" ref={content}>{children}</div>
      </div>
    </div>
  </IndexedPage.Provider>;
}

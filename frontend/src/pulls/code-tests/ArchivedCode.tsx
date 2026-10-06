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
import { Fragment, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import hljs from 'highlight.js/lib/common';
import { RefreshCw } from 'lucide-react';
import type { CodeTestsBridge, CodeTestsHost } from './CodeTestsTab';
import type { CodeHostRef, LocalRunDto, ReviewCodeFileDto, ReviewCodeFilesDto } from './contracts';
import { useCodeComments, type CodeCommentsOptions, type CommentGesture, type LineCard } from './useCodeComments';
import type { AgentReviewDto } from '../agent-review/contracts';
import { latestRun } from '../agent-review/model';
import { buildFileTree } from '../fileTree';
import { useResizablePane } from '../../useResizablePane';
import { fileName, languageFor, shortSha, visibleFiles } from './codeModel';
import TreeItems from './TreeItems';
import RunPanel from './RunPanel';

/** Historical source and retained run evidence need neither an extension host nor an index. */
export default function ArchivedCode({ host, onExit, comments, active, bridge, onReviewChange }: {
  host: CodeTestsHost; onExit?: () => void; comments?: CodeCommentsOptions; active: boolean; bridge: CodeTestsBridge;
  onReviewChange: (review: AgentReviewDto) => void;
}) {
  const [hostRef] = useState<CodeHostRef>(() => host.kind === 'review' ? { kind: 'review', reviewId: host.review.id }
    : { kind: 'task', taskId: host.task.id, workspaceId: host.workspaceId });
  const [files, setFiles] = useState<ReviewCodeFilesDto | null>(null);
  const [path, setPath] = useState<string | null>(null);
  const [file, setFile] = useState<ReviewCodeFileDto | null>(null);
  const [runs, setRuns] = useState<LocalRunDto[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const selectedOnce = useRef(false);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [query, setQuery] = useState('');
  const [changed, setChanged] = useState(false);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const column = useResizablePane(280, 190, 650);
  const review = host.kind === 'review' ? host.review : null;
  const version = review?.task.version ?? (host.kind === 'task' ? host.task.version : 0);
  const open = (review ?? (host.kind === 'task' ? host.task : null))?.state === 'OPEN';
  useEffect(() => {
    if (!active) return;
    let live = true;
    const load = async () => {
      // Retained results remain readable even when the checkout/repository cannot be read.
      const results = await Promise.allSettled([bridge.listCodeFiles(hostRef), bridge.listCodeRuns(hostRef)]);
      if (!live) return;
      if (results[0].status === 'fulfilled') { setFiles(results[0].value); setError(null); }
      else setError(String(results[0].reason));
      if (results[1].status === 'fulfilled') {
        const next = results[1].value; setRuns(next);
        if (!selectedOnce.current) { selectedOnce.current = true; setSelected(next[0]?.id ?? null); }
      } else setError(String(results[1].reason));
    };
    void load(); const timer = open ? setInterval(() => { void load(); }, 5000) : undefined;
    return () => { live = false; clearInterval(timer); };
  }, [active, bridge, hostRef, version, open, reload]);
  const revision = files?.revision;
  useEffect(() => {
    if (!active || !path || !revision) return;
    let live = true; setFile(null);
    void bridge.readCodeFile(hostRef, path, revision).then(value => { if (live) setFile(value); }, cause => { if (live) setError(String(cause)); });
    return () => { live = false; };
  }, [active, bridge, hostRef, path, revision, files?.sourceFingerprint, reload]);
  const entries = useMemo(() => visibleFiles(files?.files ?? [], changed ? 'changed' : 'all', query), [files, changed, query]);
  const tree = useMemo(() => buildFileTree(entries, entry => entry.path, { layout: 'ide' }), [entries]);
  const reviewedHead = review ? latestRun(review)?.headSha ?? null : host.kind === 'task' ? host.task.localReviewHead ?? files?.revision ?? null : null;
  const entry = files?.files.find(entry => entry.path === path);
  const cards = useCodeComments(comments ? { ...comments, onAskAgent: undefined } : undefined,
    file?.content != null && file.path === path && files ? {
      path: file.path, text: file.content, prPatch: file.prPatch, draft: false,
      uncommitted: entry?.uncommitted === true || entry?.onlyInWorkingCopy === true,
      atReviewedRevision: files.source === 'REVISION' || files.revision === reviewedHead,
      reviewedHead, observedHead: review?.observedHeadSha ?? null, askBlocked: 'Historical source is read-only',
    } : null, review ? { review, onReviewChange } : undefined);
  const readOutput = useCallback((id: string, after: number) => bridge.readCodeRunOutput(hostRef, id, after), [bridge, hostRef]);
  return <div className="ct">
    <div className="ct-toolbar">
      <input aria-label="Find file" placeholder="Find file" value={query} onChange={event => setQuery(event.target.value)} />
      <span>Read-only {files?.source === 'REVISION' ? `revision ${shortSha(files.revision)}` : 'source'}</span>
      <span className="ct-toolbar__spacer" />
      {onExit && <button onClick={onExit}>Back to reviews</button>}
      <button aria-label="Reload files" className="ct-icon-button" onClick={() => setReload(value => value + 1)}><RefreshCw size={14} /></button>
    </div>
    {error && <div className="ct-banner is-error" role="alert">{error}</div>}
    <div className="ct-work">
      <nav className="ct-files" aria-label="Files" style={{ width: column.width }}>
        <div className="ct-files__head ct-scope" role="group" aria-label="File scope">
          <button aria-pressed={!changed} onClick={() => setChanged(false)}>All files</button>
          <button aria-pressed={changed} onClick={() => setChanged(true)}>Changed files</button>
        </div>
        <div className="ct-files__list">
          {!files && !error && <p role="status">Loading files…</p>}
          <TreeItems items={tree} depth={0} selected={path} isOpen={path => !collapsed.has(path)} onOpen={setPath}
            onToggle={path => setCollapsed(current => { const next = new Set(current); if (next.has(path)) next.delete(path); else next.add(path); return next; })} />
          {files?.truncated && <p className="ct-files__note">This file list is truncated.</p>}
          {files && entries.length === 0 && <p className="ct-files__note">No matching files</p>}
        </div>
      </nav>
      <div className="ct-resize" role="separator" aria-orientation="vertical" aria-label="Resize the Files column" onMouseDown={column.startResize} />
      <section className="ct-editor" aria-label="Source">
        {path && <div className="ct-breadcrumb">{path}</div>}
        {!path ? <div className="ct-editor__empty">Open a file to inspect its retained source.</div>
          : file?.path !== path ? <div className="ct-editor__empty" role="status">Loading {fileName(path)}…</div>
          : file.content === null ? <div className="ct-editor__empty">{file.unavailableReason ?? 'This file cannot be shown.'}</div>
          : <><SourceSnapshot key={path} path={path} text={file.content} cards={cards.cards} renderCard={cards.renderCard}
            onComment={cards.onComment} commentRange={cards.commentRange} />{cards.unplaced}</>}
      </section>
    </div>
    {runs.length > 0 && <RunPanel runs={runs} files={files} gate={{ hidden: true, blocked: null }} refusal={null} onDismissRefusal={() => {}}
      terminal={null} hostKind={host.kind} onStop={() => {}} onBrowseTests={() => {}} readOutput={readOutput} selectedRunId={selected} onSelectRun={setSelected} />}
  </div>;
}

/** Bounded archival rendering reuses the app's existing highlighter and real review cards. */
export function SourceSnapshot({ path, text, cards, renderCard, onComment, commentRange }: {
  path: string; text: string; cards: LineCard[]; renderCard?: (key: string) => ReactNode;
  onComment?: (gesture: CommentGesture) => void; commentRange: { start: number; end: number } | null;
}) {
  const [page, setPage] = useState(0);
  const lines = useMemo(() => text.split('\n'), [text]);
  const start = Math.min(page * 500, Math.floor((lines.length - 1) / 500) * 500);
  const language = languageFor(path);
  const highlighted = useMemo(() => lines.slice(start, start + 500).map(line => line.length <= 10_000 && hljs.getLanguage(language)
    ? hljs.highlight(line || ' ', { language, ignoreIllegals: true }).value : null), [lines, start, language]);
  return <>
    {lines.length > 500 && <div className="ct-toolbar">
      <button disabled={start === 0} onClick={() => setPage(Math.max(0, start / 500 - 1))}>Previous lines</button>
      <span>Lines {start + 1}–{Math.min(start + 500, lines.length)} of {lines.length}</span>
      <button disabled={start + 500 >= lines.length} onClick={() => setPage(start / 500 + 1)}>Next lines</button>
      <label>Go to line <input type="number" min={1} max={lines.length} aria-label="Go to line"
        onChange={event => { const line = event.target.valueAsNumber; if (line >= 1 && line <= lines.length) setPage(Math.floor((line - 1) / 500)); }} /></label>
    </div>}
    <div className="ct-snapshot" aria-label={`Read-only ${fileName(path)}`} tabIndex={0}>
      {highlighted.map((html, index) => {
        const line = start + index + 1;
        return <Fragment key={line}>
          <div className={`ct-snapshot__line${commentRange && line >= commentRange.start && line <= commentRange.end ? ' is-selected' : ''}`}>
            <button className="ct-snapshot__number" disabled={!onComment} aria-label={`Comment on line ${line}`} onClick={event => {
              onComment?.({ type: 'start', line, extend: event.shiftKey }); onComment?.({ type: 'finish' });
            }}>{line}</button>
            {html === null ? <code>{lines[line - 1] || ' '}</code> : <code className="hljs" dangerouslySetInnerHTML={{ __html: html }} />}
          </div>
          {cards.filter(card => card.line === line).map(card => <div className="ct-inline-card" key={card.key}>{renderCard?.(card.key)}</div>)}
        </Fragment>;
      })}
    </div>
  </>;
}

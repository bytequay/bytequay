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
import {
  useEffect, useMemo, useRef, useState,
  type MouseEvent as ReactMouseEvent, type ReactNode,
} from 'react';
import { getCached } from '../dataCache';
import { isPendingLocalComment } from '../diff/DiffInlineComments';
import { derivePRCapabilities } from '../pr/prCapabilities';
import { activelySubmittedCommentIds } from '../pr/localpr/localReviewSubmission';
import { useGitHubActivityFeed } from '../pr/useGitHubActivityFeed';
import type { DiffFileDto, ReviewThreadDto, UserProfileDto } from '../types';
import type { LocalPRBundle } from '../types/localPr';
import type { MarkdownRepoContext } from '../markdown';
import type { PullRow } from './model';
import PullDiffCard, { type ThreadRowContext } from './PullDiffCard';
import PullFileTree from './PullFileTree';
import PullReviewSidebar from './PullReviewSidebar';
import { CommitsDropdown, type CommitOption } from '../ui/fullpage';

/**
 * The Changes tab of the redesigned PR detail pane — the DC prototype's
 * three-pane diff view (Pull Requests.dc.html): toolbar, drag-resizable file
 * tree, scrollable per-file diff cards, and the drag-resizable review
 * sidebar. Diff data comes from the same bridge calls the existing review
 * screens use (fetchPrDiffFiles / fetchFileBlob / the GitHub activity feed);
 * comment mutations stay GitHub-first exactly as those screens do them.
 */

/** Prototype drag bounds. */
const TREE_MIN = 140;
const TREE_MAX = 440;
const SIDE_MIN = 220;
const SIDE_MAX = 480;

function dragStart(e: ReactMouseEvent, start: number, apply: (w: number) => void, sign: 1 | -1, min: number, max: number) {
  e.preventDefault();
  const startX = e.clientX;
  const mv = (ev: globalThis.MouseEvent) => apply(Math.max(min, Math.min(max, start + sign * (ev.clientX - startX))));
  const up = () => {
    window.removeEventListener('mousemove', mv);
    window.removeEventListener('mouseup', up);
    document.body.style.cursor = '';
    document.body.style.userSelect = '';
  };
  document.body.style.cursor = 'col-resize';
  document.body.style.userSelect = 'none';
  window.addEventListener('mousemove', mv);
  window.addEventListener('mouseup', up);
}

export default function PullChanges({
  row, bundle, refresh, onComment, filesOverride, fetchBlobOverride, banner, jumpTarget,
}: {
  row: PullRow;
  bundle: LocalPRBundle | null | undefined;
  refresh: () => void;
  /** PR-level comment action — the same bridge decision the Overview tab makes. */
  onComment?: (body: string) => Promise<void>;
  filesOverride?: DiffFileDto[] | null;
  fetchBlobOverride?: (path: string) => Promise<{ lines: string[] }>;
  banner?: ReactNode;
  /** Set by the Overview timeline to scroll a file-line comment into view when this tab opens. */
  jumpTarget?: { filePath: string; side: 'LEFT' | 'RIGHT'; line: number | null } | null;
}) {
  const [files, setFiles] = useState<DiffFileDto[] | null>(null);
  const [filesError, setFilesError] = useState<string | null>(null);
  const [treeOpen, setTreeOpen] = useState(true);
  const [treeW, setTreeW] = useState(212);
  const [sideOpen, setSideOpen] = useState(false);
  const [sideW, setSideW] = useState(272);
  const [selFile, setSelFile] = useState<string | null>(null);
  const [openCards, setOpenCards] = useState<Record<string, boolean>>({});
  const scrollRef = useRef<HTMLDivElement>(null);
  // null = the cumulative "All commits" diff; a sha scopes to that one commit.
  // Only honoured when this component owns the fetch (no filesOverride).
  const [selectedCommit, setSelectedCommit] = useState<string | null>(null);
  useEffect(() => { setSelectedCommit(null); }, [row.repo, row.num]);

  const commitOptions: CommitOption[] = useMemo(() => (bundle?.commits ?? []).map(commit => {
    const subject = commit.message.split(/\r?\n/, 1)[0] ?? '';
    return {
      sha: commit.sha,
      label: `${commit.sha.slice(0, 7)}  ${subject.length > 46 ? `${subject.slice(0, 45)}…` : subject}`,
    };
  }).reverse(), [bundle?.commits]);

  useEffect(() => {
    if (filesOverride !== undefined) {
      setFiles(filesOverride);
      setFilesError(null);
      return;
    }
    let cancelled = false;
    setFiles(null);
    setFilesError(null);
    const request = selectedCommit === null
      ? window.bridge.fetchPrDiffFiles(row.repo, row.num)
      : window.bridge.fetchPrCommitDiff(row.repo, row.num, selectedCommit);
    void request
      .then(list => { if (!cancelled) setFiles(list); })
      .catch(e => { if (!cancelled) setFilesError(e instanceof Error ? e.message : String(e)); });
    return () => { cancelled = true; };
  }, [filesOverride, row.repo, row.num, selectedCommit]);

  const remoteNumber = bundle?.pr.remotePrNumber ?? null;
  const { activity, reviewThreads, refresh: refreshFeed } = useGitHubActivityFeed(row.repo, remoteNumber);
  const capabilities = bundle === null || bundle === undefined ? null : derivePRCapabilities(bundle.pr, 'details');
  // A historical commit diff has coordinates for that snapshot, while direct
  // GitHub comments intentionally target the live PR head. Keep composing on
  // the cumulative current diff so an old line cannot be rejected or misplaced.
  const historicalCommitSelected = filesOverride === undefined && selectedCommit !== null;
  const inlineCommentTarget = historicalCommitSelected
    ? null
    : capabilities?.inlineCommentTarget ?? null;
  const pending = useMemo(() => {
    const submitted = activelySubmittedCommentIds(bundle?.timeline ?? []);
    return (bundle?.comments ?? []).filter(comment =>
      isPendingLocalComment(comment) && !submitted.has(comment.id));
  }, [bundle]);
  const login = getCached<UserProfileDto>('home:profile')?.login ?? 'you';
  const [owner, repoName] = row.repo.split('/');
  const repoCtx: MarkdownRepoContext = { owner: owner ?? '', repo: repoName ?? '' };

  const headSha = bundle?.commits[bundle.commits.length - 1]?.sha ?? null;
  const blobRepo = bundle?.pr.repo ?? row.repo;
  const fetchBlob = fetchBlobOverride
    ?? (headSha === null ? null : (path: string) => window.bridge.fetchFileBlob(blobRepo, path, headSha));

  const threadCtx: ThreadRowContext | null = remoteNumber === null ? null : {
    repo: bundle?.pr.repo ?? row.repo,
    prNumber: remoteNumber,
    // Legacy id, best-effort — same derivation as PrDetailsView.
    prId: Number(row.dto.id) || 0,
    prAuthor: (bundle?.pr.author ?? row.author).replace(/^@/, ''),
    onChanged: () => refreshFeed(true),
  };
  const threadsByFile = useMemo(() => {
    const m = new Map<string, ReviewThreadDto[]>();
    for (const t of reviewThreads) {
      if (t.filePath === null || t.line === null || t.outdated) continue;
      const list = m.get(t.filePath);
      if (list !== undefined) list.push(t);
      else m.set(t.filePath, [t]);
    }
    return m;
  }, [reviewThreads]);

  const addToReview = bundle === null || bundle === undefined
      || inlineCommentTarget === null ? null
    : async (filePath: string, side: 'LEFT' | 'RIGHT', line: number, startLine: number | undefined, startSide: 'LEFT' | 'RIGHT' | undefined, body: string) => {
        const created = await window.bridge.addLocalPrComment(bundle.pr.id, {
          scope: 'file-line', filePath, lineNumber: line, side, startLine, startSide, body,
        });
        refresh();
        return created.id;
      };
  const submitLineComment = bundle === null || bundle === undefined
      || inlineCommentTarget === null ? null
    : async (filePath: string, side: 'LEFT' | 'RIGHT', line: number, startLine: number | undefined, startSide: 'LEFT' | 'RIGHT' | undefined, body: string, persistedCommentId: string | null) => {
        if (inlineCommentTarget === 'agent') {
          if (bundle.pr.taskId === null) throw new Error('This pull request is not linked to a task.');
          if (persistedCommentId === null) throw new Error('Save the review comment before submitting it.');
          await window.bridge.submitReview(bundle.pr.taskId, {
            verdict: 'COMMENT', commentIds: [persistedCommentId],
          });
          refresh();
          return;
        }
        if (remoteNumber === null) throw new Error('This pull request does not have a remote number yet.');
        const repo = bundle.pr.repo ?? row.repo;
        await window.bridge.createInlineReviewComment(
          repo, remoteNumber, body, filePath, line, side,
          startLine ?? null, startSide ?? null,
        );
        refresh();
        refreshFeed(true);
      };
  const resolvePending = (id: string) => { void window.bridge.resolveLocalPrComment(id).then(refresh).catch(() => { /* poll reconciles */ }); };
  const deletePending = (id: string) => { void window.bridge.deleteLocalPrComment(id).then(refresh).catch(() => { /* poll reconciles */ }); };

  const scrollToFile = (path: string) => {
    setSelFile(path);
    const cont = scrollRef.current;
    const card = cont?.querySelector(`[data-file-card="${CSS.escape(path)}"]`);
    if (cont == null || card == null) return;
    const top = card.getBoundingClientRect().top - cont.getBoundingClientRect().top + cont.scrollTop - 12;
    cont.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
  };
  const jumpToLine = (filePath: string, side: 'LEFT' | 'RIGHT', line: number) => {
    const cont = scrollRef.current;
    if (cont === null) return;
    const anchor = cont.querySelector(`[data-pl-anchor="${CSS.escape(`${filePath}:${side}:${line}`)}"]`)
      ?? cont.querySelector(`[data-file-card="${CSS.escape(filePath)}"]`);
    if (anchor === null) return;
    const top = anchor.getBoundingClientRect().top - cont.getBoundingClientRect().top + cont.scrollTop - 110;
    cont.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
    const cells = anchor.querySelectorAll('td');
    cells.forEach(td => { td.style.transition = 'background 0.4s'; td.style.background = '#fff8c5'; });
    window.setTimeout(() => cells.forEach(td => { td.style.background = ''; }), 1400);
  };

  // When the Overview timeline asks to jump to a comment's line, force its file card
  // open and scroll to the anchor once the diff has rendered.
  const jumpKey = jumpTarget === null || jumpTarget === undefined
    ? null : `${jumpTarget.filePath}:${jumpTarget.side}:${jumpTarget.line ?? ''}`;
  useEffect(() => {
    if (jumpTarget === null || jumpTarget === undefined || files === null) return;
    const { filePath, side, line } = jumpTarget;
    setOpenCards(prev => ({ ...prev, [filePath]: true }));
    setSelFile(filePath);
    const raf = window.requestAnimationFrame(() => window.requestAnimationFrame(() => {
      if (line !== null) jumpToLine(filePath, side, line);
      else scrollToFile(filePath);
    }));
    return () => window.cancelAnimationFrame(raf);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jumpKey, files]);

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      {banner}
      {/* ── Toolbar ── */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 14px', borderBottom: '1px solid #e7e9ec', flexShrink: 0, background: '#fff' }}>
        <span className="pl-hov-ic" onClick={() => setTreeOpen(o => !o)} title="Toggle file tree" style={{ width: 26, height: 26, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', borderRadius: 6, color: '#57606a', flexShrink: 0 }}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="4" width="18" height="16" rx="2.2" /><path d="M9 4v16" /></svg>
        </span>
        {filesOverride === undefined && (
          <CommitsDropdown commits={commitOptions} selected={selectedCommit} onSelect={setSelectedCommit} />
        )}
        {historicalCommitSelected && (
          <span role="note" style={{ fontSize: 11.5, color: '#59636e' }}>
            Select All commits to add review comments.
          </span>
        )}
        <span style={{ flex: 1 }} />
        <button
          type="button"
          className="pl-hov-ic8"
          onClick={() => setSideOpen(o => !o)}
          title={`${pending.length} pending review ${pending.length === 1 ? 'comment' : 'comments'}`}
          aria-label={`Toggle review comments panel (${pending.length} pending)`}
          style={{ position: 'relative', width: 26, height: 26, padding: 0, border: 0, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', borderRadius: 6, color: sideOpen ? '#17191c' : '#8b949e', background: sideOpen ? '#e7e9ec' : 'transparent', flexShrink: 0 }}
        >
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="4" width="18" height="13" rx="2.2" /><path d="m9.5 8-2.5 2.5 2.5 2.5" /><path d="m14.5 8 2.5 2.5-2.5 2.5" /><path d="M7.5 17v3.5l3.5-3.5" /></svg>
          {pending.length > 0 && <span className="pl-review-toggle-count">{pending.length}</span>}
        </button>
      </div>
      {/* ── Panes ── */}
      <div style={{ flex: 1, minHeight: 0, display: 'flex' }}>
        {treeOpen && (
          <>
            <PullFileTree paths={(files ?? []).map(f => f.filename)} selected={selFile} width={treeW} onPick={scrollToFile} />
            <div
              className="pl-hov-bdrag"
              onMouseDown={e => dragStart(e, treeW, setTreeW, 1, TREE_MIN, TREE_MAX)}
              title="Drag to resize"
              style={{ width: 5, flexShrink: 0, cursor: 'col-resize', background: '#e7e9ec', borderLeft: '2px solid #fff', borderRight: '2px solid #fff' }}
            />
          </>
        )}
        <div ref={scrollRef} data-diff-scroll="1" style={{ flex: 1, minWidth: 0, overflowY: 'auto', padding: '12px 14px 40px', background: '#fafbfc' }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            {filesError !== null && <div style={{ textAlign: 'center', padding: '12px 0 4px', fontSize: 12.5, color: '#cf222e' }}>{filesError}</div>}
            {files === null && filesError === null && <div style={{ textAlign: 'center', padding: '12px 0 4px', fontSize: 12.5, color: '#8b949e' }}>Loading diff…</div>}
            {(files ?? []).map(file => (
              <PullDiffCard
                key={file.filename}
                file={file}
                open={openCards[file.filename] ?? file.patch !== null}
                onToggle={() => setOpenCards(prev => ({ ...prev, [file.filename]: !(prev[file.filename] ?? file.patch !== null) }))}
                threads={threadsByFile.get(file.filename) ?? []}
                threadCtx={threadCtx}
                inlineCommentTarget={inlineCommentTarget}
                onSubmitComment={submitLineComment}
                onAddToReview={addToReview}
                fetchBlob={fetchBlob}
                repoCtx={repoCtx}
              />
            ))}
          </div>
        </div>
        {sideOpen && bundle !== null && bundle !== undefined && (
          <>
            <div
              className="pl-hov-bdrag"
              onMouseDown={e => dragStart(e, sideW, setSideW, -1, SIDE_MIN, SIDE_MAX)}
              title="Drag to resize"
              style={{ width: 7, flexShrink: 0, cursor: 'col-resize', background: '#e7e9ec', borderLeft: '3px solid #fff', borderRight: '3px solid #fff' }}
            />
            <PullReviewSidebar
              row={row}
              bundle={bundle}
              files={files}
              pending={pending}
              activity={activity}
              width={sideW}
              login={login}
              onJump={jumpToLine}
              onResolve={resolvePending}
              onDelete={deletePending}
              onComment={onComment}
            />
          </>
        )}
      </div>
    </div>
  );
}

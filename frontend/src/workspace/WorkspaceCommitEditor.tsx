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
  useCallback, useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent,
} from 'react';
import type { LocalCommitFileDto } from '../types';
import {
  buildRewritePlan,
  firstPushedIndex,
  hasStagedEdits,
  moveCommits,
  needsForcePush,
  rewordCommit,
  squashCommits,
  toEditable,
  type EditableCommit,
  type PendingOp,
} from './commitRewrite';
import CommitEditorDetail from './CommitEditorDetail';
import CommitEditorList from './CommitEditorList';
import { CheckIcon, UndoIcon, UpArrowIcon, WarnIcon } from './CommitEditorUi';
import CommitSquashDialog, { type SquashRequest } from './CommitSquashDialog';
import { workspaceApi, type RewritableCommitDto } from './workspaceApi';
import { BodyMessage, message } from './WorkspaceRepoUi';

/** One entry of the Undo stack. Ops are staged, so undo is a snapshot
 *  swap rather than an inverse operation — cheap, and correct for
 *  squash, which can't be inverted from its result alone. */
type Snapshot = { commits: EditableCommit[]; ops: PendingOp[]; selected: string[] };

const PAGE_SIZE = 100;
const MIN_LIST_WIDTH = 380;
const MAX_LIST_WIDTH = 940;
const UNDO_DEPTH = 20;

type Props = {
  workspaceId: string;
  branch: string;
  /** Free-text match on the commit title, from the page's search box. */
  query: string;
  /** Author name, or 'all'. Both filters live in the page header. */
  author: string;
  /** Reports [name, count] pairs so the header's picker can list them. */
  onAuthorsChange: (authors: Array<[string, number]>) => void;
  onClearQuery: () => void;
  onClearAuthor: () => void;
};

export default function WorkspaceCommitEditor({
  workspaceId,
  branch,
  query,
  author,
  onAuthorsChange,
  onClearQuery,
  onClearAuthor,
}: Props) {
  const [original, setOriginal] = useState<RewritableCommitDto[]>([]);
  const [commits, setCommits] = useState<EditableCommit[]>([]);
  /** The branch name git resolved, which is what the rewrite plan must
   *  name — the page's selector can say "HEAD". */
  const [resolvedBranch, setResolvedBranch] = useState(branch);
  const [trackingRef, setTrackingRef] = useState<string | null>(null);
  const [editable, setEditable] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [selected, setSelected] = useState<string[]>([]);
  const [anchorId, setAnchorId] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draftSubject, setDraftSubject] = useState('');
  const [draftBody, setDraftBody] = useState('');

  const [ops, setOps] = useState<PendingOp[]>([]);
  const [undoStack, setUndoStack] = useState<Snapshot[]>([]);
  const [squash, setSquash] = useState<SquashRequest | null>(null);
  const [applying, setApplying] = useState(false);
  const [listWidth, setListWidth] = useState(660);
  const [files, setFiles] = useState<LocalCommitFileDto[][]>([]);
  const [filesLoading, setFilesLoading] = useState(false);
  /** False once a page comes back short — nothing older left to fetch. */
  const [hasMore, setHasMore] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const squashSeq = useRef(0);

  const load = useCallback(() => {
    setLoading(true);
    return workspaceApi.rewritableCommits(
      workspaceId, branch === 'HEAD' ? undefined : branch, PAGE_SIZE)
      .then(history => {
        // Defensive: a rewrite plan built against a half-read history
        // would rebase the wrong range, so treat anything unexpected as
        // an empty (and therefore uneditable) history.
        const rows = Array.isArray(history?.commits) ? history.commits : [];
        setOriginal(rows);
        setCommits(rows.map(toEditable));
        setResolvedBranch(history?.branch ?? branch);
        setTrackingRef(history?.trackingRef ?? null);
        setEditable(history?.editable === true && rows.length > 0);
        setOps([]);
        setUndoStack([]);
        setSelected([]);
        setHasMore(rows.length >= PAGE_SIZE);
        setError(null);
      })
      .catch(reason => setError(message(reason)))
      .finally(() => setLoading(false));
  }, [workspaceId, branch]);

  useEffect(() => { void load(); }, [load]);

  /**
   * Appends the next page. Both `original` and the edited list grow by
   * the same rows at the OLDEST end, which is exactly where
   * `unchangedTail` starts walking — so a queue staged before the fetch
   * stays valid and still resolves to the same rebase base.
   */
  const loadMore = useCallback(() => {
    if (loadingMore || !hasMore) return;
    setLoadingMore(true);
    void workspaceApi.rewritableCommits(
      workspaceId, branch === 'HEAD' ? undefined : branch, PAGE_SIZE, original.length)
      .then(page => {
        const rows = Array.isArray(page?.commits) ? page.commits : [];
        const known = new Set(original.map(c => c.sha));
        const fresh = rows.filter(row => !known.has(row.sha));
        setHasMore(rows.length >= PAGE_SIZE);
        if (fresh.length === 0) return;
        setOriginal(current => [...current, ...fresh]);
        setCommits(current => [...current, ...fresh.map(toEditable)]);
      })
      .catch(reason => setError(message(reason)))
      .finally(() => setLoadingMore(false));
  }, [workspaceId, branch, original, hasMore, loadingMore]);

  const selectedCommits = useMemo(
    () => commits.filter(c => selected.includes(c.id)),
    [commits, selected]);

  // Per-commit file lists for the selection. Keyed on the picks rather
  // than the row ids so a squash result reuses what its parts already
  // fetched instead of refetching.
  const fileKey = selectedCommits.map(c => c.picks.join('+')).join(',');
  useEffect(() => {
    if (fileKey.length === 0) {
      setFiles([]);
      return undefined;
    }
    let cancelled = false;
    setFilesLoading(true);
    const shas = fileKey.split(',').map(group => group.split('+'));
    void Promise.all(shas.map(group =>
      Promise.all(group.map(sha => workspaceApi.commitFiles(workspaceId, sha)))
        .then(perSha => perSha.flat())))
      .then(next => { if (!cancelled) setFiles(next); })
      .catch(reason => { if (!cancelled) setError(message(reason)); })
      .finally(() => { if (!cancelled) setFilesLoading(false); });
    return () => { cancelled = true; };
  }, [fileKey, workspaceId]);

  const snapshot = (): Snapshot => ({ commits, ops, selected });

  const stage = (
    next: { list: EditableCommit[]; op: PendingOp } | null,
    after?: (list: EditableCommit[]) => void,
  ) => {
    if (next === null) return;
    setUndoStack(stack => [snapshot(), ...stack].slice(0, UNDO_DEPTH));
    setCommits(next.list);
    setOps(current => [...current, next.op]);
    setNotice(null);
    after?.(next.list);
  };

  const filtering = query.trim().length > 0 || author !== 'all';
  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    // Title substring OR sha prefix — a sha is only ever useful from the
    // front, and prefix-matching keeps a short hex query ("a4f") from
    // sweeping in every commit whose message happens to contain it.
    const matches = (c: EditableCommit) => c.subject.toLowerCase().includes(needle)
      || c.picks.some(sha => sha.toLowerCase().startsWith(needle));
    return commits.filter(c => (needle.length === 0 || matches(c))
      && (author === 'all' || c.authorName === author));
  }, [commits, query, author]);

  const localCount = firstPushedIndex(commits);
  const forcePush = needsForcePush(commits);
  const staged = hasStagedEdits(original, commits);
  const authors = useMemo(() => {
    const counts = new Map<string, number>();
    for (const c of commits) counts.set(c.authorName, (counts.get(c.authorName) ?? 0) + 1);
    return [...counts.entries()].sort((a, b) => b[1] - a[1]);
  }, [commits]);
  useEffect(() => { onAuthorsChange(authors); }, [authors, onAuthorsChange]);

  const selectOne = (id: string) => {
    const commit = commits.find(c => c.id === id);
    setSelected([id]);
    setAnchorId(id);
    setDraftSubject(commit?.subject ?? '');
    setDraftBody(commit?.body ?? '');
  };

  const selectRange = (id: string) => {
    const ids = visible.map(c => c.id);
    const from = ids.indexOf(anchorId ?? '');
    const to = ids.indexOf(id);
    if (from < 0 || to < 0) return selectOne(id);
    setSelected(ids.slice(Math.min(from, to), Math.max(from, to) + 1));
  };

  const openSquashFor = (ids: string[], anchor: string) => {
    const parts = commits.filter(c => ids.includes(c.id));
    if (parts.length < 2) return;
    const target = parts.find(c => c.id === anchor) ?? parts[parts.length - 1];
    setSelected(parts.map(c => c.id));
    setAnchorId(target.id);
    setSquash({
      ids: parts.map(c => c.id),
      anchorId: target.id,
      subject: target.subject,
      body: parts.map(c => `* ${c.subject}`).join('\n'),
    });
  };

  const applyRewrite = () => {
    setApplying(true);
    setError(null);
    let plan;
    try {
      plan = buildRewritePlan(original, commits, resolvedBranch);
    }
    catch (reason) {
      setError(message(reason));
      setApplying(false);
      return;
    }
    void workspaceApi.rewriteHistory(workspaceId, plan)
      .then(result => load().then(() => {
        // A refused force push is a warning, not a failure: the local
        // rewrite stands, so the queue is gone either way.
        const pushError = result.pushError ?? null;
        if (pushError !== null) setError(pushError);
        setNotice(`History rewritten · ${plan.commits.length} ${
          plan.commits.length === 1 ? 'commit' : 'commits'} replayed${
          result.pushed ? ' and force-pushed' : ''}.`);
      }))
      // The backend rolls the branch back on failure, so the staged queue
      // is still valid — leave it alone and just report why.
      .catch(reason => setError(message(reason)))
      .finally(() => setApplying(false));
  };

  const startResize = (event: ReactPointerEvent) => {
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = listWidth;
    const onMove = (move: PointerEvent) => setListWidth(
      Math.max(MIN_LIST_WIDTH, Math.min(MAX_LIST_WIDTH, startWidth + (move.clientX - startX))));
    const onUp = () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      document.body.style.userSelect = '';
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    document.body.style.userSelect = 'none';
  };

  if (loading) return <BodyMessage>Loading commits…</BodyMessage>;

  return (
    <>
      {error !== null && <div className="wu-inline-error">{error}</div>}
      {!editable && (
        <div className="wu-ce-readonly">
          <WarnIcon />
          <span>{branch} isn’t checked out — history editing is read-only here.
            Switch to it in Branches to reorder, squash, or reword.</span>
        </div>
      )}
      {filtering && (
        <div className="wu-ce-filterbar">
          <span className="wu-ce-count">{visible.length} of {commits.length} commits</span>
          {query.trim().length > 0 && (
            <span className="wu-ce-chip">matches: {query.trim()}
              <button type="button" aria-label="Clear the title filter"
                onClick={onClearQuery}>×</button>
            </span>
          )}
          {author !== 'all' && (
            <span className="wu-ce-chip">author: {author}
              <button type="button" aria-label="Clear the author filter"
                onClick={onClearAuthor}>×</button>
            </span>
          )}
          <span className="wu-row-spacer" />
          {editable && <small>Reordering is paused while a filter is on</small>}
        </div>
      )}

      <div className="wu-ce-panes">
        <div className="wu-ce-listpane" style={{ flexBasis: listWidth }}>
          {selected.length > 1 && (
            <div className="wu-ce-selbar">
              <strong>{selected.length} commits selected</strong>
              <code>{selectedCommits[0]?.shortSha} … {
                selectedCommits[selectedCommits.length - 1]?.shortSha}</code>
              <span className="wu-row-spacer" />
              {editable && (
                <button type="button" className="wu-ce-squash-btn"
                  onClick={() => openSquashFor(selected, selectedCommits[selectedCommits.length - 1].id)}>
                  Squash into one
                </button>
              )}
              <button type="button" onClick={() => setSelected([])}>Clear</button>
            </div>
          )}
          <CommitEditorList
            commits={commits}
            visible={visible}
            selectedIds={selected}
            localCount={localCount}
            trackingRef={trackingRef}
            filtering={filtering}
            editable={editable}
            editingId={editingId}
            onPick={(id, shiftKey) => (shiftKey && anchorId !== null
              ? selectRange(id)
              : selectOne(id))}
            onToggle={(id, shiftKey) => {
              if (shiftKey && anchorId !== null) return selectRange(id);
              setAnchorId(id);
              setSelected(current => (current.includes(id)
                ? current.filter(x => x !== id)
                : [...current, id]));
            }}
            onStartEdit={id => { selectOne(id); setEditingId(id); }}
            onCancelEdit={() => setEditingId(null)}
            onCommitEdit={(id, subject) => {
              setEditingId(null);
              const commit = commits.find(c => c.id === id);
              const trimmed = subject.trim();
              if (commit === undefined || trimmed.length === 0 || trimmed === commit.subject) return;
              stage(rewordCommit(commits, id, trimmed, commit.body));
              setDraftSubject(trimmed);
            }}
            onMove={(ids, targetId, mode) => stage(moveCommits(commits, ids, targetId, mode))}
            onSquashDrop={(ids, targetId) => openSquashFor([...ids, targetId], targetId)}
            onReachEnd={loadMore}
            loadingMore={loadingMore}
            hasMore={hasMore}
          />
        </div>
        <div className="wu-ce-divider" onPointerDown={startResize} role="separator"
          aria-orientation="vertical" title="Drag to resize" />
        <CommitEditorDetail
          workspaceId={workspaceId}
          selected={selectedCommits}
          files={files}
          filesLoading={filesLoading}
          isLocal={selectedCommits.length === 1
            && commits.indexOf(selectedCommits[0]) < localCount}
          editable={editable}
          draftSubject={draftSubject}
          draftBody={draftBody}
          onDraftSubject={setDraftSubject}
          onDraftBody={setDraftBody}
          onRevertMessage={() => {
            setDraftSubject(selectedCommits[0]?.subject ?? '');
            setDraftBody(selectedCommits[0]?.body ?? '');
          }}
          onSaveMessage={() => {
            const head = selectedCommits[0];
            if (head === undefined) return;
            stage(rewordCommit(
              commits, head.id, draftSubject.trim() || head.subject, draftBody));
          }}
          onSelectUpToHead={() => {
            const head = selectedCommits[0];
            if (head === undefined) return;
            setSelected(commits.slice(0, commits.indexOf(head) + 1).map(c => c.id));
          }}
          onOpenSquash={() => openSquashFor(
            selected, selectedCommits[selectedCommits.length - 1].id)}
        />
      </div>

      <div className="wu-ce-bottombar">
        {ops.length > 0 ? (
          <>
            <span className="wu-ce-pending">
              <i aria-hidden />{ops.length} pending {ops.length === 1 ? 'rewrite' : 'rewrites'}
            </span>
            {ops.slice(-4).map(op => (
              <span key={op.key} className={`wu-ce-op ${op.kind}`}>{op.label}</span>
            ))}
            {forcePush && (
              <span className="wu-ce-op force">
                <WarnIcon />touches pushed history · --force-with-lease
              </span>
            )}
            <span className="wu-row-spacer" />
            <button type="button" disabled={undoStack.length === 0 || applying} onClick={() => {
              const [top, ...rest] = undoStack;
              if (top === undefined) return;
              setCommits(top.commits);
              setOps(top.ops);
              setSelected(top.selected);
              setUndoStack(rest);
            }}><UndoIcon />Undo</button>
            <button type="button" disabled={applying} onClick={() => {
              const bottom = undoStack[undoStack.length - 1];
              if (bottom === undefined) return;
              setCommits(bottom.commits);
              setSelected(bottom.selected);
              setOps([]);
              setUndoStack([]);
            }}>Discard all</button>
            <button type="button" className="wu-ce-apply" disabled={applying || !editable || !staged}
              onClick={applyRewrite}>
              <UpArrowIcon />{applying ? 'Rewriting…' : 'Rewrite history'}
            </button>
          </>
        ) : (
          <>
            <span><b>{localCount} {localCount === 1 ? 'commit' : 'commits'}</b> ahead of {
              trackingRef ?? 'the remote'}</span>
            {editable && (
              <small>Drag the grip to reorder · drop on a commit to squash ·
                double-click a title to rename</small>
            )}
            <span className="wu-row-spacer" />
            {notice !== null && (
              <span className="wu-ce-toast"><CheckIcon />{notice}</span>
            )}
          </>
        )}
      </div>

      {squash !== null && (
        <CommitSquashDialog
          request={squash}
          participants={commits.filter(c => squash.ids.includes(c.id))}
          onCancel={() => setSquash(null)}
          onConfirm={(subject, body) => {
            squashSeq.current += 1;
            stage(
              squashCommits(commits, squash.ids, squash.anchorId, subject, body, squashSeq.current),
              list => {
                const merged = list.find(c => c.id === `squash-${squashSeq.current}`);
                if (merged === undefined) return;
                setSelected([merged.id]);
                setAnchorId(merged.id);
                setDraftSubject(merged.subject);
                setDraftBody(merged.body);
              });
            setSquash(null);
          }}
        />
      )}
    </>
  );
}

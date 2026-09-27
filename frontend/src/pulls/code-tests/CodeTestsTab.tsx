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
import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react';
import {
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  File,
  FilePlus,
  FlaskConical,
  Folder,
  GitCommitHorizontal,
  GitPullRequestArrow,
  Lock,
  PanelLeft,
  RotateCw,
  TestTubeDiagonal,
  Undo2,
  X,
} from 'lucide-react';
import type { AgentTaskDto, Bridge, PullRequestDetailDto } from '../../types';
import { useResizablePane } from '../../useResizablePane';
import { ConfirmDialog } from '../../ConfirmDialog';
import { buildFileTree, type FileTreeItem } from '../fileTree';
import type { DiffAgentComment } from '../PullChanges';
import { remoteErrorMessage } from '../PullOverview';
import type { AgentReviewDto } from '../agent-review/contracts';
import { latestRun } from '../agent-review/model';
import CodeView, { type FollowRequest, type RunMarker } from './CodeView';
import RunButton, { type RunTarget } from './RunButton';
import RunPanel from './RunPanel';
import { ConflictBanner, DiscardPopover, UnsavedBar } from './EditBars';
import { useCodeComments, type CodeCommentsOptions } from './useCodeComments';
import { isRunning, lastOutcome, runGate, taskRunGate } from './runModel';
import { OutlinePopover, SymbolChooser, useOutlines, type ChooserState } from './Outline';
import SymbolPalette, { graphNotice } from './SymbolPalette';
import {
  canGoBack,
  canGoForward,
  currentLocation,
  definitionsNamed,
  EMPTY_NAVIGATION,
  fileName,
  inTestRoot,
  isTestSource,
  javaPackage,
  navigate,
  shortSha,
  testClassName,
  touchedCount,
  visibleFiles,
  type FileScope,
} from './codeModel';
import type { CodeHostRef, LocalRunDto, ReviewCodeFileDto, ReviewCodeFileEntryDto, ReviewCodeFilesDto, RunRefusalDto } from './contracts';
import '../../css/code-tests.css';

export type CodeTestsBridge = Pick<Bridge,
  | 'listCodeFiles'
  | 'readCodeFile'
  | 'searchCodeSymbols'
  | 'readCodeOutline'
  | 'listCodeRuns'
  | 'startCodeRun'
  | 'stopCodeRun'
  | 'readCodeRunOutput'
  | 'saveCodeFile'
  | 'discardReviewCodeChanges'
  | 'openCodeTerminal'
  | 'writeReviewTerminal'
  | 'resizeReviewTerminal'
  | 'onReviewTerminalEvent'
  | 'listAgentReviews'
  | 'startAgentReview'
  | 'getAgentReview'
  | 'syncReviewWorktree'
  | 'subscribeAgentReview'
  | 'getAgentTask'>;

/** Whose worktree the tab reads: an Agent review's, or the development task's that owns the pull request. */
export type CodeTestsHost =
  | { kind: 'review'; review: AgentReviewDto }
  | { kind: 'task'; task: AgentTaskDto; workspaceId: string };

/** The backend's own ceiling. A ⌘-click asks for all of it, because a name like `addColumn` has dozens of declarations. */
const DEFINITION_LOOKUP_LIMIT = 200;

/** Each entry remembers the file-list generation it was read in, so a later list re-reads it in the background. */
type FileState = { generation: number } & (
  | { status: 'loading' }
  | { status: 'loaded'; file: ReviewCodeFileDto; refreshError?: string }
  | { status: 'failed'; error: string });

export default function CodeTestsTab({
  detail = null,
  workspaceId,
  review,
  task = null,
  onReviewChange,
  active,
  ownReviewStream,
  onUnsavedChange,
  comments,
  bridge = window.bridge,
}: {
  detail?: PullRequestDetailDto | null;
  workspaceId: string | undefined;
  review: AgentReviewDto | null;
  /** The development task whose worktree the pull request is bound to; it wins over any review. */
  task?: AgentTaskDto | null;
  onReviewChange: (review: AgentReviewDto) => void;
  /** Whether this tab is the visible one; it stays mounted while hidden. */
  active: boolean;
  /** False when another surface already streams this review in the same window. */
  ownReviewStream: boolean;
  /** How many files hold unsaved drafts, so the host can confirm before unmounting them. */
  onUnsavedChange?: (count: number) => void;
  /** Review comment plumbing shared with Changes; absent when the pull request page cannot comment. */
  comments?: CodeCommentsOptions;
  bridge?: CodeTestsBridge;
}) {
  const [endedReview, setEndedReview] = useState<AgentReviewDto | null>(null);
  const shown = review ?? endedReview;

  if (task !== null && workspaceId !== undefined) {
    return (
      <WorkingCopy
        key={`task:${task.id}`}
        host={{ kind: 'task', task, workspaceId }}
        onReviewChange={onReviewChange}
        onUnsavedChange={onUnsavedChange}
        comments={comments}
        active={active}
        ownReviewStream={ownReviewStream}
        bridge={bridge}
      />
    );
  }
  if (shown === null && detail === null) return <div className="ct-empty" role="status">This task has no available working copy.</div>;
  if (shown === null && detail !== null) {
    return <NoReview detail={detail} workspaceId={workspaceId} bridge={bridge} onReviewChange={onReviewChange} onOpenEnded={setEndedReview} />;
  }
  if (shown === null) return null;
  return (
    <WorkingCopy
      key={`review:${shown.id}`}
      host={{ kind: 'review', review: shown }}
      onReviewChange={review === null ? setEndedReview : onReviewChange}
      onExit={review === null ? () => setEndedReview(null) : undefined}
      onUnsavedChange={onUnsavedChange}
      comments={comments}
      active={active}
      ownReviewStream={ownReviewStream}
      bridge={bridge}
    />
  );
}

function NoReview({ detail, workspaceId, bridge, onReviewChange, onOpenEnded }: {
  detail: PullRequestDetailDto;
  workspaceId: string | undefined;
  bridge: CodeTestsBridge;
  onReviewChange: (review: AgentReviewDto) => void;
  onOpenEnded: (review: AgentReviewDto) => void;
}) {
  const [ended, setEnded] = useState<AgentReviewDto[]>([]);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (workspaceId === undefined) return;
    let live = true;
    void bridge.listAgentReviews(workspaceId)
      .then(reviews => {
        if (live) setEnded(reviews.filter(item => item.state === 'CLOSED' && item.number === detail.number
          && item.repo.toLowerCase() === detail.repo.toLowerCase()));
      })
      .catch(() => {});
    return () => { live = false; };
  }, [bridge, detail.number, detail.repo, workspaceId]);

  if (workspaceId === undefined) {
    return (
      <div className="ct-empty agr-empty">
        <div className="agr-empty__title">Code &amp; Tests needs a watched repository</div>
        <p>Watch {detail.repo} in Workspaces, then start an agent review. Code &amp; Tests reads that review&apos;s working copy.</p>
      </div>
    );
  }

  const start = () => {
    setStarting(true);
    setError(null);
    void bridge.startAgentReview(workspaceId, detail.repo, detail.number)
      .then(onReviewChange)
      .catch(failure => setError(remoteErrorMessage(failure)))
      .finally(() => setStarting(false));
  };

  return (
    <div className="ct-empty agr-empty">
      <div className="agr-empty__title">No working copy yet</div>
      <p>
        Code &amp; Tests reads the working copy of this pull request&apos;s agent review. Starting a review checks out the
        pull request&apos;s exact head into a managed worktree. Nothing is posted to GitHub.
      </p>
      <div className="agr-empty__actions">
        <button type="button" className="agr-action is-primary" disabled={starting} onClick={start}>
          {starting ? 'Starting…' : 'Start review'}
        </button>
      </div>
      {error !== null && <div className="agr-error" role="alert">{error}</div>}
      {ended.length > 0 && (
        <div className="ct-ended">
          <div className="ct-ended__title">Ended reviews</div>
          {ended.map(item => (
            <button key={item.id} type="button" className="ct-ended__row" onClick={() => onOpenEnded(item)}>
              <span>Review ended {item.closedAt === null ? '' : new Date(item.closedAt).toLocaleString()}</span>
              {latestRun(item) !== null && <code>{shortSha(latestRun(item)?.headSha ?? '')}</code>}
              <span className="ct-ended__action">View read-only</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function WorkingCopy({ host, onReviewChange, onExit, onUnsavedChange, comments, active, ownReviewStream, bridge }: {
  host: CodeTestsHost;
  onReviewChange: (review: AgentReviewDto) => void;
  /** Leaves an ended review opened read-only, back to the list with Start review. */
  onExit?: () => void;
  onUnsavedChange?: (count: number) => void;
  comments?: CodeCommentsOptions;
  active: boolean;
  ownReviewStream: boolean;
  bridge: CodeTestsBridge;
}) {
  const [files, setFiles] = useState<ReviewCodeFilesDto | null>(null);
  const [filesError, setFilesError] = useState<string | null>(null);
  const [filesReload, setFilesReload] = useState(0);
  const [contents, setContents] = useState<ReadonlyMap<string, FileState>>(new Map());
  const contentsRef = useRef(contents);
  contentsRef.current = contents;
  const [generation, setGeneration] = useState(0);
  const [navigation, dispatch] = useReducer(navigate, EMPTY_NAVIGATION);
  const [scope, setScope] = useState<FileScope>('changed');
  const [query, setQuery] = useState('');
  const [columnOpen, setColumnOpen] = useState(true);
  const [openDirectories, setOpenDirectories] = useState<ReadonlySet<string>>(new Set());
  // A filtered tree (Changed files or a search) starts expanded; these are the folders the user collapsed in it.
  const [collapsedDirectories, setCollapsedDirectories] = useState<ReadonlySet<string>>(new Set());
  const [testsOnly, setTestsOnly] = useState(false);
  const [paletteFocus, setPaletteFocus] = useState(0);
  const [outlineOpen, setOutlineOpen] = useState(false);
  const [chooser, setChooser] = useState<ChooserState | null>(null);
  const [followNotice, setFollowNotice] = useState<string | null>(null);
  const [runs, setRuns] = useState<LocalRunDto[]>([]);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [refusal, setRefusal] = useState<RunRefusalDto | null>(null);
  // Unsaved edits by path: the text and the saved version (content and hash) the edit started from.
  const [drafts, setDrafts] = useState<ReadonlyMap<string, { text: string; base: string; baseHash: string }>>(new Map());
  const draftsRef = useRef(drafts);
  draftsRef.current = drafts;
  const [saving, setSaving] = useState(false);
  const [syncHead, setSyncHead] = useState<string | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [discardOpen, setDiscardOpen] = useState(false);
  const [discarding, setDiscarding] = useState(false);
  const [discardError, setDiscardError] = useState<string | null>(null);
  // 280 is the width the scope control needs whole; below it the labels ellipsize.
  const column = useResizablePane(280, 190, 420);
  // The parent keys this component by host, so the reference bridge calls take never changes.
  const [hostRef] = useState<CodeHostRef>(() => host.kind === 'review' ? { kind: 'review', reviewId: host.review.id }
    : { kind: 'task', taskId: host.task.id, workspaceId: host.workspaceId });
  // A task host is polled here; a review host is kept live through onReviewChange.
  const [polledTask, setPolledTask] = useState<AgentTaskDto | null>(null);
  const review = host.kind === 'review' ? host.review : null;
  const task = host.kind === 'task' ? polledTask ?? host.task : null;
  const reviewId = review?.id ?? null;
  const reviewOpen = review?.state === 'OPEN';
  const hostOpen = (review ?? task)?.state === 'OPEN';
  const hostVersion = review?.task.version ?? task?.version;
  const runHead = review === null ? null : latestRun(review)?.headSha ?? null;
  const worktreeHead = review?.worktreeSync?.headSha ?? null;
  const sync = review?.worktreeSync;
  const needsSync = reviewOpen && review?.sourceTaskId == null && sync != null && sync.state !== 'CURRENT';
  const syncLabel = sync?.state === 'UNAVAILABLE' ? 'Working copy sync unavailable — retry' : 'Local branch is stale — reset to remote';

  // A turn, a save or an Update review can change the working copy; the task version moves with each of them.
  useEffect(() => {
    let live = true;
    setFilesError(null);
    void bridge.listCodeFiles(hostRef)
      .then(value => {
        if (!live) return;
        setFiles(value);
        setGeneration(current => current + 1);
      })
      .catch(failure => { if (live) setFilesError(remoteErrorMessage(failure)); });
    return () => { live = false; };
  }, [bridge, hostRef, hostOpen, hostVersion, runHead, worktreeHead, filesReload]);

  useEffect(() => {
    onUnsavedChange?.(drafts.size);
  }, [drafts.size, onUnsavedChange]);
  useEffect(() => () => onUnsavedChange?.(0), [onUnsavedChange]);

  // Runs are polled: fast while one is live so its status keeps up, slowly otherwise so an agent's run still appears.
  const anyRunning = runs.some(run => run.status === 'RUNNING');
  const pollTask = host.kind === 'task' && host.task.interactive !== false;
  // Only the first load picks the newest run; afterwards an empty selection means the user closed its tab.
  const runSelectedOnce = useRef(false);
  useEffect(() => {
    let live = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = async () => {
      try {
        const next = await bridge.listCodeRuns(hostRef);
        if (!live) return;
        setRuns(next);
        if (!runSelectedOnce.current) {
          runSelectedOnce.current = true;
          setSelectedRunId(current => current ?? next[0]?.id ?? null);
        }
      }
      catch {
        // The next poll retries; the panel keeps the runs it already shows.
      }
      // Subscribing to the task would replace the task window's own stream, so its run gate follows this tick instead.
      if (hostRef.kind === 'task' && pollTask) {
        await bridge.getAgentTask(hostRef.taskId, hostRef.workspaceId).then(updated => { if (live) setPolledTask(updated); }, () => {});
      }
      if (live) timer = setTimeout(() => { void poll(); }, anyRunning ? 1000 : 5000);
    };
    void poll();
    return () => {
      live = false;
      if (timer !== undefined) clearTimeout(timer);
    };
  }, [anyRunning, bridge, hostRef, pollTask]);

  // Nothing else streams this review while its Summary tab is hidden.
  useEffect(() => {
    if (!active || reviewId === null || !reviewOpen || !ownReviewStream) return;
    let live = true;
    let settling: ReturnType<typeof setTimeout> | undefined;
    // Reads overlap while a turn streams; only the newest one may reach the shared review.
    let latest = 0;
    const read = async () => {
      const request = ++latest;
      const updated = await bridge.getAgentReview(reviewId);
      if (!live || request !== latest) return null;
      onReviewChange(updated);
      return updated;
    };
    const reload = async () => {
      const updated = await read();
      if (updated !== null && updated.activeTurn === null && updated.task.mutationClaim === null && latestRun(updated)?.state === 'RUNNING') {
        if (settling !== undefined) clearTimeout(settling);
        settling = setTimeout(() => {
          if (live) void read().catch(() => {});
        }, 200);
      }
    };
    const session = bridge.subscribeAgentReview(reviewId, 0, message => {
      if (!live) return;
      if (message.kind === 'changed' || message.kind === 'events' || message.state === 'open') void reload().catch(() => {});
    }, true);
    return () => {
      live = false;
      if (settling !== undefined) clearTimeout(settling);
      void session.then(stop => stop(), () => {});
    };
  }, [active, bridge, onReviewChange, ownReviewStream, reviewId, reviewOpen]);

  useEffect(() => {
    if (!active) return;
    const focusPalette = (event: KeyboardEvent) => {
      if (event.defaultPrevented || !event.metaKey || event.ctrlKey || event.altKey || event.shiftKey || event.key.toLowerCase() !== 'p') return;
      event.preventDefault();
      setPaletteFocus(value => value + 1);
    };
    window.addEventListener('keydown', focusPalette);
    return () => window.removeEventListener('keydown', focusPalette);
  }, [active]);

  const readOutline = useCallback((path: string) => bridge.readCodeOutline(hostRef, path), [bridge, hostRef]);
  const { outlines, request: requestOutline } = useOutlines(readOutline, generation);
  const searchSymbols = useCallback((query: string) => bridge.searchCodeSymbols(hostRef, query, 50), [bridge, hostRef]);

  const paths = useMemo(() => new Set([...(files?.files.map(file => file.path) ?? []), ...drafts.keys()]), [files, drafts]);
  useEffect(() => {
    // A truncated list cannot prove a file is gone.
    if (files !== null && !files.truncated) dispatch({ type: 'retain', paths });
  }, [files, paths]);

  const location = currentLocation(navigation);
  const openPath = location !== null && navigation.tabs.includes(location.path) ? location.path : null;

  useEffect(() => {
    if (openPath === null || files === null) return;
    const existing = contentsRef.current.get(openPath);
    if (existing !== undefined && existing.generation === generation) return;
    if (existing?.status === 'loaded' && draftsRef.current.has(openPath) && files !== null && !files.truncated && !files.files.some(file => file.path === openPath)) {
      // A refreshed checkout can delete a file, but its unsaved text must remain accessible.
      setContents(current => new Map(current).set(openPath, { status: 'loaded', file: existing.file, generation }));
      return;
    }
    // A file already on screen stays readable while its newer copy loads. Results are keyed by path, so a read that
    // finishes after the user moved to another file still lands, and returning to that file never waits forever.
    if (existing?.status !== 'loaded') setContents(current => new Map(current).set(openPath, { status: 'loading', generation }));
    const settle = (state: FileState) => setContents(current => {
      const previous = current.get(openPath);
      if (previous !== undefined && previous.generation > state.generation) return current;
      // The checkout may remove a file between listing and reading it. Keep its editor and draft accessible.
      if (state.status === 'failed' && previous?.status === 'loaded' && draftsRef.current.has(openPath)) {
        return new Map(current).set(openPath, { ...previous, generation: state.generation, refreshError: state.error });
      }
      return new Map(current).set(openPath, state);
    });
    void bridge.readCodeFile(hostRef, openPath, files.revision)
      .then(file => settle({ status: 'loaded', file, generation }))
      .catch(failure => settle({ status: 'failed', error: remoteErrorMessage(failure), generation }));
  }, [bridge, files, generation, hostRef, openPath]);

  // Opening a file reveals it in the tree.
  useEffect(() => {
    if (openPath === null) return;
    const ancestors = openPath.split('/').slice(0, -1).map((_, index, segments) => segments.slice(0, index + 1).join('/'));
    setOpenDirectories(current => {
      const next = new Set(current);
      ancestors.forEach(path => next.add(path));
      return next.size === current.size ? current : next;
    });
    setCollapsedDirectories(current => {
      const next = new Set(current);
      ancestors.forEach(path => next.delete(path));
      return next.size === current.size ? current : next;
    });
  }, [openPath]);

  // A ⌘-click lookup that resolves after the user navigated elsewhere must not pull them back.
  const navigationRequest = useRef(0);
  const open = useCallback((path: string, line = 0) => {
    navigationRequest.current++;
    setOutlineOpen(false);
    setChooser(null);
    setFollowNotice(null);
    dispatch({ type: 'open', location: { path, line } });
  }, []);
  const codeGraph = files?.codeGraph ?? 'UNAVAILABLE';
  const changedPaths = useMemo(() => new Set(files?.files.filter(file => file.changedInPr).map(file => file.path) ?? []), [files]);

  useEffect(() => {
    if (openPath !== null && codeGraph === 'READY' && isTestSource(openPath)) requestOutline(openPath);
  }, [codeGraph, openPath, requestOutline]);

  const follow = useCallback((request: FollowRequest) => {
    const lookup = ++navigationRequest.current;
    setChooser(null);
    setFollowNotice(`Finding ${request.name}…`);
    void bridge.searchCodeSymbols(hostRef, request.name, DEFINITION_LOOKUP_LIMIT)
      .then(result => {
        if (lookup !== navigationRequest.current) return;
        if (result.status !== 'READY') {
          setFollowNotice(graphNotice('READY', result.status));
          return;
        }
        const options = definitionsNamed(result.symbols, request.name);
        if (options.length === 0) setFollowNotice(`No definition of ${request.name} in this working copy`);
        else if (options.length === 1) open(options[0].path, options[0].startLine);
        else {
          setFollowNotice(null);
          // A name the search filled the limit with has more declarations than the chooser can claim to list.
          setChooser({ name: request.name, options, truncated: result.symbols.length >= DEFINITION_LOOKUP_LIMIT, x: request.x, y: request.y });
        }
      })
      .catch(failure => {
        if (lookup === navigationRequest.current) setFollowNotice(`Could not look up ${request.name}. ${remoteErrorMessage(failure)}`);
      });
  }, [bridge, hostRef, open]);

  useEffect(() => {
    if (followNotice === null || followNotice.endsWith('…')) return;
    const timer = setTimeout(() => setFollowNotice(null), 5000);
    return () => clearTimeout(timer);
  }, [followNotice]);
  const gate = host.kind === 'review' ? runGate(host.review, files, runs) : taskRunGate(polledTask ?? host.task, files, runs);
  const startRun = useCallback((target: RunTarget) => {
    setRefusal(null);
    void bridge.startCodeRun(hostRef, target.path, target.method)
      .then(result => {
        if ('refusal' in result) {
          setRefusal(result.refusal);
          return;
        }
        setRuns(current => [result.run, ...current.filter(run => run.id !== result.run.id)]);
        setSelectedRunId(result.run.id);
      })
      .catch(failure => setRefusal({ reason: remoteErrorMessage(failure), action: null }));
  }, [bridge, hostRef]);
  const stopRun = useCallback((run: LocalRunDto) => {
    void bridge.stopCodeRun(hostRef, run.id)
      .then(stopped => setRuns(current => current.map(item => item.id === stopped.id ? stopped : item)))
      .catch(failure => setRefusal({ reason: `Could not stop the run. ${remoteErrorMessage(failure)}`, action: null }));
  }, [bridge, hostRef]);
  const readRunOutput = useCallback((runId: string, after: number) => bridge.readCodeRunOutput(hostRef, runId, after), [bridge, hostRef]);

  const editDraft = useCallback((path: string, file: ReviewCodeFileDto, text: string) => {
    setDrafts(current => {
      const next = new Map(current);
      const existing = current.get(path);
      if (existing === undefined && text === file.content) return current;
      if (existing !== undefined && text === existing.base) next.delete(path);
      else next.set(path, { text, base: existing?.base ?? file.content ?? '', baseHash: existing?.baseHash ?? file.contentHash ?? '' });
      return next;
    });
  }, []);
  const dropDraft = useCallback((path: string) => setDrafts(current => {
    if (!current.has(path)) return current;
    const next = new Map(current);
    next.delete(path);
    return next;
  }), []);
  const saveDraft = useCallback(async (path: string): Promise<boolean> => {
    const draft = drafts.get(path);
    if (draft === undefined) return true;
    setSaving(true);
    setSaveError(null);
    try {
      const result = await bridge.saveCodeFile(hostRef, path, draft.text, draft.baseHash);
      if ('conflict' in result) {
        // Show the disk version as the saved file; the draft keeps its old base, so the conflict banner appears.
        setContents(current => {
          const entry = current.get(path);
          if (entry?.status !== 'loaded') return current;
          // The change marks described the old content; the next read brings the disk version's.
          return new Map(current).set(path, { ...entry,
            file: { ...entry.file, content: result.conflict.currentContent, contentHash: result.conflict.currentHash, lineChanges: [] } });
        });
        return false;
      }
      setContents(current => new Map(current).set(path, { status: 'loaded', file: result.file, generation: current.get(path)?.generation ?? 0 }));
      dropDraft(path);
      setFilesReload(value => value + 1);
      return true;
    }
    catch (failure) {
      setSaveError(remoteErrorMessage(failure));
      return false;
    }
    finally {
      setSaving(false);
    }
  }, [bridge, dropDraft, drafts, hostRef]);
  const discardAll = useCallback(() => {
    if (files === null || reviewId === null) return;
    setDiscarding(true);
    setDiscardError(null);
    void bridge.discardReviewCodeChanges(reviewId, files.revision)
      .then(value => {
        setDrafts(new Map());
        setFiles(value);
        setGeneration(current => current + 1);
        setDiscardOpen(false);
      })
      .catch(failure => setDiscardError(remoteErrorMessage(failure)))
      .finally(() => setDiscarding(false));
  }, [bridge, files, reviewId]);

  const syncWorkingCopy = async () => {
    if (reviewId === null || syncHead === null || gate.hidden || gate.blocked !== null || saving || discarding || syncing) return;
    setSyncing(true);
    setSyncError(null);
    try {
      const updated = await bridge.syncReviewWorktree(reviewId, syncHead);
      setDrafts(new Map());
      setSaveError(null);
      setSyncHead(null);
      onReviewChange(updated);
      setFilesReload(value => value + 1);
    }
    catch (failure) {
      setSyncError(remoteErrorMessage(failure));
    }
    finally {
      setSyncing(false);
    }
  };

  const entries = useMemo(() => files?.files ?? [], [files]);
  const entryByPath = useMemo(() => new Map(entries.map(entry => [entry.path, entry])), [entries]);
  const shownEntries = useMemo(() => visibleFiles(entries, scope, query, testsOnly), [entries, scope, query, testsOnly]);
  const tree = useMemo(() => buildFileTree(shownEntries, entry => entry.path, { layout: 'ide' }), [shownEntries]);
  const expandAll = scope === 'changed' || query.trim() !== '';
  const directoryOpen = (path: string) => expandAll ? !collapsedDirectories.has(path) : openDirectories.has(path);
  const toggleDirectory = (path: string) => {
    const flip = (current: ReadonlySet<string>) => {
      const next = new Set(current);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    };
    if (expandAll) setCollapsedDirectories(flip);
    else setOpenDirectories(flip);
  };
  const openState = openPath === null ? null : contents.get(openPath) ?? null;
  const missingDraft = openPath !== null && drafts.has(openPath) && files !== null && !files.truncated && !files.files.some(file => file.path === openPath);
  const openFile = openState?.status === 'loaded' ? openState.file : null;
  const refreshError = openState?.status === 'loaded' ? openState.refreshError : undefined;
  const openOutline = openPath === null ? undefined : outlines.get(openPath);
  const openEntry = openPath === null ? undefined : entryByPath.get(openPath);
  const claim = review?.task.mutationClaim ?? task?.mutationClaim?.kind ?? null;
  // A task's working copy is the revision its local comments are placed against, unless private local review pinned one.
  const reviewedHead = review !== null || files === null ? runHead : task?.localReviewHead ?? files.revision;
  const commentContext = openPath === null || openFile?.content == null || files === null || openState?.generation !== generation || missingDraft || refreshError !== undefined ? null : {
    path: openPath,
    text: drafts.get(openPath)?.text ?? openFile.content,
    prPatch: openFile.prPatch,
    draft: drafts.has(openPath),
    uncommitted: openEntry?.uncommitted === true || openEntry?.onlyInWorkingCopy === true,
    atReviewedRevision: files.source === 'REVISION' || files.revision === reviewedHead,
    reviewedHead,
    observedHead: review?.observedHeadSha ?? null,
    askBlocked: claim === 'LOCAL_RUN' ? 'A run is using this working copy'
      : claim === 'RECOVERY' ? review !== null ? 'The working copy needs recovery — see Summary' : 'The task needs recovery — see the task window'
        : task?.developmentContinuationUnfinished === true ? 'Development automation is working' : null,
  };
  const askAgent = comments?.onAskAgent;
  // A review ended and opened read-only here is not the one Summary shows, so its findings have nowhere to open.
  const commentOptions = comments === undefined ? undefined : {
    ...comments,
    onAskAgent: !hostOpen || askAgent === undefined ? undefined : host.kind === 'review' ? askAgent : async (comment: DiffAgentComment) => {
      await askAgent(comment);
      setPolledTask(await bridge.getAgentTask(host.task.id, host.workspaceId));
    },
    onOpenFindingInReview: onExit === undefined ? comments.onOpenFindingInReview : undefined,
  };
  const codeComments = useCodeComments(commentOptions, commentContext, review === null ? undefined : { review, onReviewChange });
  const markers = useMemo<RunMarker[]>(() => {
    if (openPath === null || openFile?.content == null || gate.hidden || !isTestSource(openPath)) return [];
    if (openOutline?.status !== 'loaded' || openOutline.result.status !== 'READY') return [];
    const lines = (drafts.get(openPath)?.text ?? openFile.content).split('\n');
    const className = testClassName(openPath);
    const lineOf = (from: number, needle: RegExp) => {
      for (let index = Math.max(0, from - 1); index < Math.min(lines.length, from + 40); index++) {
        if (needle.test(lines[index])) return index + 1;
      }
      return from;
    };
    const marker = (line: number, method: string | null): RunMarker => {
      const name = method === null ? `class ${className}` : `${method}()`;
      const outcome = lastOutcome(runs, className, method);
      const running = isRunning(runs, openPath, method);
      return {
        line,
        method,
        label: `Run ${name}`,
        tone: running ? 'running' : gate.blocked !== null ? 'blocked' : outcome?.tone ?? 'idle',
        title: running ? 'A run is using this working copy — reading stays available' : gate.blocked ?? `Run ${name}`,
      };
    };
    const symbols = openOutline.result.symbols;
    const escape = (name: string) => name.replace(/[$]/g, '\\$');
    const type = symbols.find(symbol => symbol.kind === 'class' && symbol.name === className);
    return [
      ...(type === undefined ? [] : [marker(lineOf(type.startLine, new RegExp(`\\bclass\\s+${escape(className)}\\b`)), null)]),
      ...symbols.filter(symbol => symbol.test).map(symbol => marker(lineOf(symbol.startLine, new RegExp(`\\b${escape(symbol.name)}\\s*\\(`)), symbol.name)),
    ];
  }, [drafts, gate.blocked, gate.hidden, openFile, openOutline, openPath, runs]);

  return (
    <div className="ct">
      <div className="ct-toolbar">
        <div className="ct-toolbar__history">
          <button type="button" className="ct-icon-button" title="Back" aria-label="Back" disabled={!canGoBack(navigation)}
            onClick={() => dispatch({ type: 'back' })}><ChevronLeft size={15} strokeWidth={1.8} aria-hidden="true" /></button>
          <button type="button" className="ct-icon-button" title="Forward" aria-label="Forward" disabled={!canGoForward(navigation)}
            onClick={() => dispatch({ type: 'forward' })}><ChevronRight size={15} strokeWidth={1.8} aria-hidden="true" /></button>
        </div>
        {!columnOpen && (
          <button type="button" className="ct-icon-button" title="Show the files column" aria-label="Show the files column"
            onClick={() => setColumnOpen(true)}><PanelLeft size={15} strokeWidth={1.7} aria-hidden="true" /></button>
        )}
        <SymbolPalette
          files={entries}
          query={query}
          onQueryChange={value => {
            setQuery(value);
            setCollapsedDirectories(new Set());
            if (value !== '') setColumnOpen(true);
          }}
          codeGraph={codeGraph}
          search={searchSymbols}
          onOpen={open}
          focusToken={paletteFocus} />
        <span className="ct-toolbar__spacer" />
        {needsSync && (
          <button type="button" className="ct-icon-button ct-sync-button" aria-label={syncLabel} aria-haspopup="dialog"
            title={`${syncLabel}. ${gate.blocked ?? sync?.reason ?? 'Discard local changes and catch up to the latest PR commits without starting a review.'}`}
            disabled={gate.hidden || gate.blocked !== null || saving || discarding || syncing}
            onClick={() => { setSyncError(null); setSyncHead(files?.revision ?? null); }}>
            <GitPullRequestArrow size={15} strokeWidth={1.8} aria-hidden="true" />
          </button>
        )}
        {files !== null && !gate.hidden && review !== null && (
          <div className="ct-discard-host">
            <button type="button" className="ct-icon-button" aria-expanded={discardOpen}
              title={files.files.some(file => file.uncommitted || file.onlyInWorkingCopy) || drafts.size > 0
                ? 'Discard every uncommitted change in this review working copy'
                : 'No local changes to discard'}
              aria-label="Discard local changes"
              onClick={() => {
                setDiscardError(null);
                setDiscardOpen(value => !value);
              }}>
              <Undo2 size={14} strokeWidth={1.8} aria-hidden="true" />
            </button>
            {discardOpen && (
              <DiscardPopover files={files} drafts={[...drafts.keys()]} blocked={gate.blocked} discarding={discarding} error={discardError}
                onCancel={() => setDiscardOpen(false)} onDiscard={discardAll} />
            )}
          </div>
        )}
        {onExit !== undefined && <button type="button" className="ct-text-button" onClick={onExit}>Back to reviews</button>}
        <button type="button" className="ct-icon-button" title="Reload files" aria-label="Reload files"
          onClick={() => setFilesReload(value => value + 1)}><RotateCw size={14} strokeWidth={1.8} aria-hidden="true" /></button>
      </div>
      {filesError !== null && (
        <div className="ct-banner is-error" role="alert">
          Could not read the working copy&apos;s files. {filesError}
          <button type="button" onClick={() => setFilesReload(value => value + 1)}>Retry</button>
        </div>
      )}
      {syncHead !== null && (
        <ConfirmDialog title="Reset working copy to remote?" confirmLabel={syncing ? 'Resetting…' : 'Discard and reset'}
          busy={syncing} confirmDisabled={gate.hidden || gate.blocked !== null || saving || discarding} destructive
          onCancel={() => setSyncHead(null)} onConfirm={() => { void syncWorkingCopy(); }}>
          <p>Discard unsaved drafts, saved local changes, untracked files, and local commits, then reset this branch to the latest remote PR commit.</p>
          <p>This does not start a review. Existing review results and test runs stay available.</p>
          {(syncError !== null || gate.blocked !== null) && <p role="alert">{syncError ?? gate.blocked}</p>}
        </ConfirmDialog>
      )}
      <div className="ct-work">
        {columnOpen && (
          <>
            <nav className="ct-files" aria-label="Files" style={{ width: column.width }}>
              <div className="ct-files__head">
                <div className="ct-scope" role="group" aria-label="File scope">
                  <button type="button" aria-pressed={scope === 'all'} title="Every file in the repository"
                    onClick={() => {
                      setScope('all');
                      setCollapsedDirectories(new Set());
                    }}><span className="ct-scope__label">All files</span></button>
                  <button type="button" aria-pressed={scope === 'changed'} title="Files changed on this branch or in the working copy"
                    onClick={() => {
                      setScope('changed');
                      setCollapsedDirectories(new Set());
                    }}><span className="ct-scope__label">Changed files</span><span className="ct-scope__count">{touchedCount(entries)}</span></button>
                  <button type="button" className="ct-scope__tests" aria-pressed={testsOnly} title="Show only test files" aria-label="Show only test files"
                    onClick={() => setTestsOnly(value => !value)}><TestTubeDiagonal size={14} strokeWidth={1.8} aria-hidden="true" /></button>
                </div>
                <button type="button" className="ct-icon-button" title="Hide the files column" aria-label="Hide the files column"
                  onClick={() => setColumnOpen(false)}><PanelLeft size={15} strokeWidth={1.7} aria-hidden="true" /></button>
              </div>
              <div className="ct-files__list">
                {files === null && filesError === null && <div className="ct-files__note" role="status">Loading files…</div>}
                {files !== null && shownEntries.length === 0 && (
                  <div className="ct-files__note">
                    {query.trim() !== '' ? 'No match' : testsOnly ? scope === 'changed' ? 'No changed test files' : 'No test files' : scope === 'changed' ? 'Nothing touched' : 'No files'}
                  </div>
                )}
                <TreeItems items={tree} depth={0} selected={openPath} isOpen={directoryOpen} onToggle={toggleDirectory} onOpen={open} />
                {files?.truncated === true && (
                  <div className="ct-files__note">This repository has more files than ByteQuay lists; filter to find one.</div>
                )}
              </div>
            </nav>
            <div className={`ct-resize${column.resizing ? ' is-resizing' : ''}`} role="separator" aria-orientation="vertical"
              aria-label="Resize the Files column" title="Drag to resize" onMouseDown={column.startResize} />
          </>
        )}
        <section className="ct-editor" aria-label="Source">
          {navigation.tabs.length > 0 && (
            <div className="ct-tabs" role="tablist" aria-label="Open files">
              {navigation.tabs.map(path => {
                const entry = entryByPath.get(path);
                const test = isTestSource(path);
                return (
                  <div key={path} className={`ct-tab${openPath === path ? ' is-active' : ''}${entry?.onlyInWorkingCopy ? ' is-local' : ''}${drafts.has(path) ? ' is-draft' : ''}${inTestRoot(path) ? ' is-test-root' : ''}${entry?.addedInPr ? ' is-pr-added' : entry?.changedInPr ? ' is-pr-modified' : ''}`}>
                    {test && !gate.hidden && <RunButton target={{ path, className: testClassName(path), method: null }} runs={runs} gate={gate} onRun={startRun} size={22} />}
                    <button type="button" role="tab" className="ct-tab__open" aria-selected={openPath === path} title={path}
                      onClick={() => { if (openPath !== path) open(path); }}>
                      {test
                        ? gate.hidden && <FlaskConical className="ct-tab__icon is-test" size={12} strokeWidth={1.8} aria-hidden="true" />
                        : review !== null && <span className="ct-tab__icon" title="Read-only here"><Lock size={12} strokeWidth={1.8} aria-hidden="true" /></span>}
                      <span className="ct-tab__name">{fileName(path)}</span>
                      {drafts.has(path)
                        ? <span className="ct-tab__dot is-draft" title="Unsaved edits" />
                        : entry?.uncommitted && <span className="ct-tab__dot" title="Modified in this working copy — not committed" />}
                    </button>
                    <button type="button" className="ct-tab__close" aria-label={`Close ${fileName(path)}`}
                      onClick={() => dispatch({ type: 'close', path })}><X size={12} strokeWidth={1.8} aria-hidden="true" /></button>
                  </div>
                );
              })}
            </div>
          )}
          {openPath === null ? (
            <div className="ct-editor__empty">Open a file from the tree to read it whole.</div>
          ) : (
            <>
              <div className="ct-breadcrumb">
                {openFile !== null && javaPackage(openFile.content) !== null && (
                  <><span className="ct-breadcrumb__package">{javaPackage(openFile.content)}</span><span aria-hidden="true">›</span></>
                )}
                <div className="ct-breadcrumb__outline">
                  <button type="button" className="ct-breadcrumb__file" aria-expanded={outlineOpen} aria-haspopup="dialog"
                    title="Show this file's types and methods"
                    onClick={() => {
                      if (!outlineOpen && codeGraph === 'READY') requestOutline(openPath);
                      setOutlineOpen(value => !value);
                    }}>
                    {fileName(openPath)}<ChevronDown size={11} strokeWidth={1.8} aria-hidden="true" />
                  </button>
                  {outlineOpen && (
                    <OutlinePopover path={openPath} outline={outlines.get(openPath)} codeGraph={codeGraph} currentLine={location?.line ?? 0}
                      onOpen={line => open(openPath, line)} onClose={() => setOutlineOpen(false)}
                      runs={runs} gate={isTestSource(openPath) ? gate : { hidden: true, blocked: null }} onRun={startRun} />
                  )}
                </div>
                {followNotice !== null && <span className="ct-breadcrumb__notice" role="status">{followNotice}</span>}
                <span className="ct-toolbar__spacer" />
                {isTestSource(openPath) && (() => {
                  const outline = outlines.get(openPath);
                  if (outline?.status !== 'loaded' || outline.result.status !== 'READY') return null;
                  const tests = outline.result.symbols.filter(symbol => symbol.test).length;
                  return <span className="ct-breadcrumb__count">{tests} {tests === 1 ? 'test method' : 'test methods'}</span>;
                })()}
                {openFile !== null && !openFile.editable && openFile.readOnlyReason !== null && (
                  <span className="ct-breadcrumb__readonly"><Lock size={11} strokeWidth={1.8} aria-hidden="true" />{openFile.readOnlyReason}</span>
                )}
              </div>
              {openState === null || openState.status === 'loading' ? (
                <div className="ct-editor__empty" role="status">Loading {fileName(openPath)}…</div>
              ) : openState.status === 'failed' ? (
                <div className="ct-editor__empty" role="alert">
                  Could not read {fileName(openPath)}. {openState.error}
                  <button type="button" onClick={() => setFilesReload(value => value + 1)}>Retry</button>
                </div>
              ) : openState.file.content === null ? (
                <div className="ct-editor__empty">{openState.file.unavailableReason ?? 'This file cannot be shown.'}</div>
              ) : (
                <>
                {refreshError !== undefined && <div className="ct-banner is-error" role="alert">
                  Could not refresh {fileName(openPath)}. Your unsaved draft is preserved. {refreshError}
                  <button type="button" onClick={() => setFilesReload(value => value + 1)}>Retry</button>
                </div>}
                {(() => {
                  const draft = drafts.get(openPath);
                  const file = openState.file;
                  if (draft === undefined || file.content === null) return null;
                  if (file.contentHash !== draft.baseHash) {
                    return (
                      <ConflictBanner path={openPath}
                        onKeepDraft={() => setDrafts(current => new Map(current).set(openPath, { ...draft, base: file.content ?? '', baseHash: file.contentHash ?? '' }))}
                        onLoadFromDisk={() => dropDraft(openPath)} />
                    );
                  }
                  const method = runs.find(run => run.path === openPath && run.scope !== 'COMMAND')?.testMethod ?? null;
                  return (
                    <>
                      <UnsavedBar saved={draft.base} draft={draft.text} blocked={missingDraft ? 'This file was removed from the working copy. Your unsaved draft is preserved; copy it before discarding.' : refreshError !== undefined ? 'Refresh the file before saving your preserved draft.' : gate.blocked} saving={saving}
                        rerunLabel={isTestSource(openPath) && !gate.hidden ? method === null ? 'Save and rerun class' : 'Save and rerun method' : null}
                        onRevert={() => dropDraft(openPath)}
                        onSave={() => { void saveDraft(openPath); }}
                        onSaveAndRerun={() => {
                          void saveDraft(openPath).then(saved => {
                            if (saved) startRun({ path: openPath, className: testClassName(openPath), method });
                          });
                        }} />
                      {saveError !== null && <div className="ct-banner is-error" role="alert">Could not save {fileName(openPath)}. {saveError}</div>}
                    </>
                  );
                })()}
                <CodeView path={openPath} value={drafts.get(openPath)?.text ?? openState.file.content}
                  saved={drafts.get(openPath)?.base ?? openState.file.content}
                  lineChanges={(drafts.get(openPath)?.base ?? openState.file.content) === openState.file.content ? openState.file.lineChanges : undefined}
                  editable={openState.file.editable}
                  onEdit={text => editDraft(openPath, openState.file, text)}
                  line={location?.line ?? 0} token={navigation.token}
                  onFollow={codeGraph === 'READY' ? follow : undefined}
                  markers={markers}
                  onRun={marker => startRun({ path: openPath, className: testClassName(openPath), method: marker.method })}
                  onComment={codeComments.onComment}
                  commentRange={codeComments.commentRange}
                  cards={codeComments.cards}
                  renderCard={codeComments.renderCard} />
                {codeComments.unplaced}
                </>
              )}
            </>
          )}
        </section>
      </div>
      {!(gate.hidden && runs.length === 0) && (
        <RunPanel
          runs={runs}
          files={files}
          gate={gate}
          refusal={refusal}
          onDismissRefusal={() => setRefusal(null)}
          terminal={gate.hidden ? null : { host: hostRef, bridge }}
          hostKind={host.kind}
          onRerun={(run, method) => {
            if (run.path !== null) startRun({ path: run.path, className: testClassName(run.path), method });
          }}
          onStop={stopRun}
          onBrowseTests={() => {
            setScope('all');
            setCollapsedDirectories(new Set());
            setTestsOnly(true);
            setColumnOpen(true);
          }}
          readOutput={readRunOutput}
          selectedRunId={selectedRunId}
          onSelectRun={setSelectedRunId} />
      )}
      {chooser !== null && (
        <SymbolChooser chooser={chooser} changedPaths={changedPaths} onClose={() => setChooser(null)}
          onOpen={symbol => open(symbol.path, symbol.startLine)} />
      )}
    </div>
  );
}

function TreeItems({ items, depth, selected, isOpen, onToggle, onOpen }: {
  items: FileTreeItem<ReviewCodeFileEntryDto>[];
  depth: number;
  selected: string | null;
  isOpen: (path: string) => boolean;
  onToggle: (path: string) => void;
  onOpen: (path: string) => void;
}) {
  return (
    <ul className="ct-tree">
      {items.map(item => item.kind === 'directory' ? (
        <li key={`directory:${item.path}`}>
          <button type="button" className={`ct-tree__row${inTestRoot(item.path) ? ' is-test-root' : ''}`} style={{ paddingLeft: 8 + depth * 16 }} title={item.path}
            aria-expanded={isOpen(item.path)} onClick={() => onToggle(item.path)}>
            <span className="ct-tree__twisty">
              {isOpen(item.path)
                ? <ChevronDown className="ct-tree__chevron" size={12} strokeWidth={2} aria-hidden="true" />
                : <ChevronRight className="ct-tree__chevron" size={12} strokeWidth={2} aria-hidden="true" />}
            </span>
            <Folder className="ct-tree__icon" size={14} strokeWidth={1.7} aria-hidden="true" />
            <span className={`ct-tree__name${treeNameClass(item.name)}${item.module ? ' is-module' : ''}`}>{item.name}</span>
          </button>
          {isOpen(item.path) && (
            <TreeItems items={item.children} depth={depth + 1} selected={selected} isOpen={isOpen}
              onToggle={onToggle} onOpen={onOpen} />
          )}
        </li>
      ) : (
        <FileRow key={`file:${item.path}`} entry={item.entry} depth={depth} selected={selected === item.path} onOpen={onOpen} />
      ))}
    </ul>
  );
}

function treeNameClass(name: string) {
  return name.startsWith('.') ? ' is-dot' : '';
}

function FileRow({ entry, depth, selected, onOpen }: {
  entry: ReviewCodeFileEntryDto;
  depth: number;
  selected: boolean;
  onOpen: (path: string) => void;
}) {
  return (
    <li>
      <button type="button" className={`ct-tree__row is-file${inTestRoot(entry.path) ? ' is-test-root' : ''}${selected ? ' is-selected' : ''}`} style={{ paddingLeft: 8 + depth * 16 }}
        title={entry.path} aria-current={selected ? 'true' : undefined} onClick={() => onOpen(entry.path)}>
        <span className="ct-tree__twisty" aria-hidden="true" />
        {isTestSource(entry.path)
          ? <FlaskConical className="ct-tree__icon is-test" size={14} strokeWidth={1.7} aria-hidden="true" />
          : <File className="ct-tree__icon" size={14} strokeWidth={1.7} aria-hidden="true" />}
        <span className={`ct-tree__name${treeNameClass(fileName(entry.path))}`}>{fileName(entry.path)}</span>
        {entry.changedInPr && (
          <span className="ct-tree__marker is-pr" role="img" title="Changed in this pull request" aria-label="Changed in this pull request">
            <GitCommitHorizontal size={12} strokeWidth={1.8} aria-hidden="true" />
          </span>
        )}
        {entry.onlyInWorkingCopy && (
          <span className="ct-tree__marker is-local" role="img" title="Local file in this working copy — not in the pull request"
            aria-label="Local file in this working copy — not in the pull request">
            <FilePlus size={12} strokeWidth={1.8} aria-hidden="true" />
          </span>
        )}
      </button>
    </li>
  );
}

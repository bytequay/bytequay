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
  useCallback,
  useEffect,
  useRef,
  useState,
  type MouseEvent as ReactMouseEvent,
  type UIEvent as ReactUIEvent,
} from 'react';
import Avatar from '../Avatar';
import StartDevelopmentDialog from '../agents/StartDevelopmentDialog';
import type { TaskLaunch } from '../agents/AgentTasksView';
import { CircleCheck, CircleDot, ExternalLink, Flag, ListTodo, Play, User } from 'lucide-react';
import { issueEpicId } from './epics';
import { issueDevelopmentAction, notifyIssueDevelopmentChanged } from './issueDevelopment';
import { useUnmarkEpic } from './useUnmarkEpic';
import type { NavigationMode } from '../navigationHistory';
import CopyLinkButton from '../CopyLinkButton';
import MarkdownBody, { GitHubReferenceLink, linkIssueNumbers } from '../pulls/MarkdownBody';
import {
  CardHeader,
  DiscussionComposer,
  MetadataPicker,
  reactionEmoji,
  remoteErrorMessage,
  toggleSelection,
  type MentionCandidate,
  type QuoteRequest,
} from '../pulls/PullOverview';
import { SkeletonTimeline } from '../pulls/DetailSkeleton';
import { CommentIcon, PaneIcon, RefreshIcon } from '../pulls/PullsScreen';
import { parsePullSearch, pullRequestMatchesSearch } from '../pulls/model';
import { relativeTime } from '../relativeTime';
import { useZoom, ZoomActions, ZoomScrim } from '../Zoom';
import { useRecentTouch } from '../recent/useRecentTouch';
import { issueAttentionItem, loadHandled } from '../home/attention';
import PinButton from '../home/PinButton';
import { completeAttentionVisit, recordAttentionAction, useAttentionVisit } from '../home/useAttentionVisit';
import type {
  Bridge,
  IssueDevelopmentTaskDto,
  PullReactionDto,
  RepoLabelDto,
  RepoUserDto,
  WorkspaceIssueDetailDto,
  RepositoryIssueSnapshotDto,
  RepositoryMetadataDto,
  WorkspaceIssueTimelineEventDto,
  WorkspaceIssueTypeDto,
} from '../types';
import '../css/issues.css';

type Issue = RepositoryIssueSnapshotDto['issues'][number];

const DETAIL_MIN = 680;
const DETAIL_MAX = 1600;
const DETAIL_DEFAULT = 1120;
const PAGE_SIZE = 50;
const HIDDEN_EVENTS = new Set(['comment_deleted', 'mentioned', 'subscribed', 'unsubscribed']);

type IssueDetailBridge = Pick<Bridge, 'fetchWorkspaceIssueDetail' | 'refreshWorkspaceIssueDetail'>;

export async function loadWorkspaceIssueDetail(
  bridge: IssueDetailBridge,
  workspaceId: string,
  issueNumber: number,
  refresh = false,
): Promise<WorkspaceIssueDetailDto> {
  const detail = refresh
    ? await bridge.refreshWorkspaceIssueDetail(workspaceId, issueNumber)
    : await bridge.fetchWorkspaceIssueDetail(workspaceId, issueNumber);
  return !refresh && detail.refreshedAt === null
    ? bridge.refreshWorkspaceIssueDetail(workspaceId, issueNumber)
    : detail;
}

export default function WorkspaceIssuesScreen({ workspaceId, repository, initialIssueNumber, onSelectIssue, onStartDevelopment, onViewTask, onClose }: {
  workspaceId: string;
  repository: string;
  initialIssueNumber: number | null;
  onSelectIssue?: (number: number, mode: NavigationMode) => void;
  onStartDevelopment?: (number: number, launch: TaskLaunch) => void;
  onViewTask?: (link: IssueDevelopmentTaskDto) => void;
  /** Shows only initialIssueNumber over the current page, without the list or navigation. */
  onClose?: () => void;
}) {
  const overlay = onClose !== undefined;
  const overlayRef = useRef<HTMLDivElement | null>(null);
  const close = useCallback(() => {
    if (initialIssueNumber !== null) void completeAttentionVisit(`${repository}#${initialIssueNumber}`);
    onClose?.();
  }, [initialIssueNumber, onClose, repository]);
  const zoom = useZoom(close, overlay);
  const [snapshot, setSnapshot] = useState<RepositoryIssueSnapshotDto | null>(null);
  const [previewIssue, setPreviewIssue] = useState<(Issue & { state: string }) | null>(null);
  const [metadata, setMetadata] = useState<RepositoryMetadataDto | null>(null);
  const [search, setSearch] = useState('');
  const [labelFilter, setLabelFilter] = useState('');
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [timeline, setTimeline] = useState<WorkspaceIssueTimelineEventDto[]>([]);
  const [issueType, setIssueType] = useState<WorkspaceIssueTypeDto | null>(null);
  const [paneOpen, setPaneOpen] = useState(true);
  const [detailWidth, setDetailWidth] = useState(DETAIL_DEFAULT);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [mutationError, setMutationError] = useState<string | null>(null);
  const [changingEpic, setChangingEpic] = useState<number | null>(null);
  const [closedIssueId, setClosedIssueId] = useState<number | null>(null);
  const [startingIssue, setStartingIssue] = useState<number | null>(null);
  const detailRequest = useRef(0);
  const loadingMoreRequest = useRef(false);
  const openedInitialIssue = useRef<number | null>(null);
  const routingInitialIssue = useRef<number | null>(null);

  const readPreview = useCallback(async (number: number): Promise<Issue & { state: string }> => {
    const [owner, repo] = repository.split('/');
    const preview = await window.bridge.fetchGitHubIssuePreview(owner, repo, number);
    if (preview.pullRequest) throw new Error('This reference is a pull request, not an issue.');
    const commentCount = loadHandled()[`${repository}#${number}`]?.snoozedItem?.commentCount ?? 0;
    // The read-only preview has no cache-row identity; its negative key stays in this component.
    return { ...preview, id: -number, number, authorAvatarUrl: '', htmlUrl: `https://github.com/${repository}/issues/${number}`,
      assignees: [], commentCount, createdAt: '' };
  }, [repository]);

  const refreshPreview = async (number: number) => {
    setDetailLoading(true); setDetailError(null);
    try {
      const issue = await readPreview(number);
      setPreviewIssue(current => current?.number === number ? issue : current);
      setClosedIssueId(issue.state.toLowerCase() === 'closed' ? issue.id : null);
    }
    catch (failure) { setDetailError(remoteErrorMessage(failure)); }
    finally { setDetailLoading(false); }
  };

  const refreshIssueRow = useCallback(async (issueNumber: number) => {
    const issue = await window.bridge.fetchWorkspaceIssue(workspaceId, issueNumber).catch((): null => null);
    if (issue !== null) setSnapshot(current => current === null || `${current.repository.owner}/${current.repository.repo}` !== repository ? current : {
      ...current, issues: current.issues.map(candidate => candidate.number === issueNumber ? issue : candidate),
    });
  }, [workspaceId, repository]);

  const apply = useCallback((value: RepositoryIssueSnapshotDto) => {
    setSnapshot(value);
    setSelectedId(current => value.issues.some(issue => issue.id === current)
      ? current
      // The overlay was opened on one issue; the first row of the list must never stand in for it.
      : overlay ? null : value.issues[0]?.id ?? null);
  }, [overlay]);

  const loadDetail = useCallback(async (issueNumber: number, refresh = false) => {
    const request = ++detailRequest.current;
    setDetailLoading(true);
    setDetailError(null);
    try {
      const detail = await loadWorkspaceIssueDetail(window.bridge, workspaceId, issueNumber, refresh);
      if (request === detailRequest.current) {
        setTimeline(detail.timeline);
        setIssueType({ current: detail.currentType, available: detail.availableTypes });
        await refreshIssueRow(issueNumber);
      }
    }
    catch (failure) {
      if (request === detailRequest.current) {
        setDetailError(failure instanceof Error ? failure.message : String(failure));
      }
    }
    finally {
      if (request === detailRequest.current) setDetailLoading(false);
    }
  }, [workspaceId, refreshIssueRow]);

  const updateIssueType = useCallback(async (issueNumber: number, type: string | null) => {
    const request = ++detailRequest.current;
    setMutationError(null);
    try {
      const nextType = await window.bridge.updateWorkspaceIssueType(workspaceId, issueNumber, type);
      recordAttentionAction(`${repository}#${issueNumber}`, 'update');
      if (request !== detailRequest.current) return;
      setIssueType(nextType);
      await refreshIssueRow(issueNumber);
    }
    catch (failure) {
      if (request === detailRequest.current) {
        setMutationError(remoteErrorMessage(failure));
        throw failure;
      }
    }
  }, [workspaceId, repository, refreshIssueRow]);

  const updateIssueMetadata = useCallback(async (
    issueNumber: number,
    assignees: string[],
    labels: string[],
  ) => {
    setMutationError(null);
    try {
      const updated = await window.bridge.updateWorkspaceIssueMetadata(
        workspaceId,
        issueNumber,
        assignees,
        labels,
      );
      recordAttentionAction(`${repository}#${issueNumber}`, 'update');
      setSnapshot(current => current ? {
        ...current,
        issues: current.issues.map(issue => issue.number === issueNumber
          ? { ...issue, assignees: updated.assignees, labels: updated.labels }
          : issue),
      } : current);
      await refreshIssueRow(issueNumber);
    }
    catch (failure) {
      setMutationError(remoteErrorMessage(failure));
      throw failure;
    }
  }, [workspaceId, repository, refreshIssueRow]);

  const toggleEpic = useCallback(async (issue: Issue) => {
    setChangingEpic(issue.id);
    setMutationError(null);
    try {
      const development = await window.bridge.setWorkspaceIssueEpic(workspaceId, issue.number, !issue.isEpic);
      recordAttentionAction(`${repository}#${issue.number}`, 'update');
      setSnapshot(current => current === null ? null : {
        ...current,
        issues: current.issues.map(candidate => candidate.id === issue.id ? { ...candidate, ...development } : candidate),
      });
      notifyIssueDevelopmentChanged(issue.id);
    }
    catch (failure) {
      setMutationError(remoteErrorMessage(failure));
    }
    finally { setChangingEpic(null); }
  }, [workspaceId, repository]);

  const unmarkEpic = useUnmarkEpic();
  // Unmarking also removes the Epic's view, so an Epic with work asks first.
  const requestEpic = (issue: Issue): void => {
    if (issue.isEpic) unmarkEpic.request(workspaceId, issueEpicId(issue.id), () => { void toggleEpic(issue); });
    else void toggleEpic(issue);
  };

  const closeIssue = useCallback(async (issue: Issue) => {
    setMutationError(null);
    try {
      await window.bridge.closeWorkspaceIssue(workspaceId, issue.number);
      recordAttentionAction(`${repository}#${issue.number}`, 'update');
      notifyIssueDevelopmentChanged();
      // The store has already dropped the issue, so reloading the list here would swap the issue being read.
      setClosedIssueId(issue.id);
    }
    catch (failure) {
      setMutationError(remoteErrorMessage(failure));
    }
  }, [workspaceId, repository]);

  const refreshIssues = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const value = await window.bridge.refreshWorkspaceIssues(workspaceId);
      apply(value);
      notifyIssueDevelopmentChanged();
    }
    catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
    finally {
      setLoading(false);
    }
  }, [apply, workspaceId]);

  useEffect(() => {
    if (!overlay) return;
    const previousFocus = document.activeElement;
    overlayRef.current?.focus();
    return () => {
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus();
    };
  }, [overlay]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setSnapshot(null);
    setPreviewIssue(null);
    setSearch('');
    setLabelFilter('');
    loadingMoreRequest.current = false;
    // ponytail: the overlay reads the same stored page as the list and keeps only its own issue;
    // give it a single-issue load if that page ever stops being a local read.
    window.bridge.fetchWorkspaceIssues(workspaceId, 0, PAGE_SIZE)
      .then(value => { if (!cancelled) apply(value); })
      .catch(failure => {
        if (!cancelled) setError(failure instanceof Error ? failure.message : String(failure));
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    void window.bridge.getWorkspaceMetadata(workspaceId)
      .then(value => { if (!cancelled) setMetadata(value); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [apply, workspaceId]);

  useEffect(() => {
    if (initialIssueNumber === null) {
      routingInitialIssue.current = null;
      return;
    }
    if (loading || openedInitialIssue.current === initialIssueNumber) return;
    openedInitialIssue.current = initialIssueNumber;
    routingInitialIssue.current = initialIssueNumber;
    const local = snapshot?.issues.find(issue => issue.number === initialIssueNumber);
    if (local) {
      setSelectedId(local.id);
      setPaneOpen(true);
      return;
    }
    let cancelled = false;
    void window.bridge.fetchWorkspaceIssue(workspaceId, initialIssueNumber)
      .catch(() => readPreview(initialIssueNumber))
      .then(issue => {
        if (cancelled) return;
        if ('state' in issue) {
          setPreviewIssue(issue);
          setClosedIssueId(issue.state.toLowerCase() === 'closed' ? issue.id : null);
        }
        else setSnapshot(current => current ? {
          ...current,
          issues: [issue, ...current.issues.filter(candidate => candidate.id !== issue.id)],
        } : current);
        setSelectedId(issue.id);
        setPaneOpen(true);
      })
      .catch(failure => {
        if (!cancelled) setError(failure instanceof Error ? failure.message : String(failure));
      });
    return () => { cancelled = true; };
  }, [initialIssueNumber, loading, snapshot, workspaceId, readPreview]);

  const loadMore = useCallback(async () => {
    if (!snapshot?.hasMore || loadingMoreRequest.current) return;
    loadingMoreRequest.current = true;
    setLoadingMore(true);
    try {
      const next = await window.bridge.fetchWorkspaceIssues(workspaceId, snapshot.page + 1, PAGE_SIZE);
      setSnapshot(current => current ? appendIssuePage(current, next) : next);
    }
    catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
    finally {
      loadingMoreRequest.current = false;
      setLoadingMore(false);
    }
  }, [snapshot, workspaceId]);

  const scrollIssues = (event: ReactUIEvent<HTMLDivElement>) => {
    // Filtering can clamp the scroll position; only an explicit load should expand filtered results.
    if (filtering) return;
    const list = event.currentTarget;
    if (list.scrollHeight - list.scrollTop - list.clientHeight < 240) {
      void loadMore();
    }
  };

  const selected = snapshot?.issues.find(issue => issue.id === selectedId) ?? (previewIssue?.id === selectedId ? previewIssue : null);
  const selectedNumber = selected?.number ?? null;

  useEffect(() => {
    if (closedIssueId === null || closedIssueId === selectedId) return;
    setClosedIssueId(null);
    setSnapshot(current => current?.issues.some(issue => issue.id === closedIssueId) ? {
      ...current,
      issues: current.issues.filter(issue => issue.id !== closedIssueId),
      totalIssues: current.totalIssues - 1,
    } : current);
  }, [closedIssueId, selectedId]);

  useEffect(() => {
    if (selectedNumber === null) return;
    if (routingInitialIssue.current !== null) {
      if (routingInitialIssue.current === selectedNumber) routingInitialIssue.current = null;
      return;
    }
    onSelectIssue?.(selectedNumber, 'replace');
  }, [onSelectIssue, selectedNumber]);

  useEffect(() => {
    setTimeline([]);
    setIssueType(null);
    setMutationError(null);
    if (selectedNumber !== null && selectedId !== previewIssue?.id) {
      void loadDetail(selectedNumber);
    }
    else {
      detailRequest.current += 1;
      setDetailLoading(false);
    }
  }, [loadDetail, selectedNumber, selectedId, previewIssue?.id]);

  const resizeDetail = (event: ReactMouseEvent) => {
    event.preventDefault();
    const pane = event.currentTarget.parentElement;
    if (!pane) return;
    const startX = event.clientX;
    const startWidth = pane.getBoundingClientRect().width;
    let nextWidth = startWidth;
    let frame = 0;
    const move = (next: globalThis.MouseEvent) => {
      nextWidth = Math.max(DETAIL_MIN, Math.min(DETAIL_MAX, startWidth + startX - next.clientX));
      if (frame === 0) {
        frame = window.requestAnimationFrame(() => {
          pane.style.width = `${nextWidth}px`;
          frame = 0;
        });
      }
    };
    const stop = () => {
      if (frame !== 0) window.cancelAnimationFrame(frame);
      pane.style.width = `${nextWidth}px`;
      setDetailWidth(nextWidth);
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', stop);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', stop);
  };

  // Reading an issue closely and doing nothing is exactly the case the other
  // surfaces miss, so the dwell timer records it.
  useRecentTouch(selected === null ? null : {
    type: 'ISSUE',
    repo: repository,
    number: selected.number,
    workspaceId,
  });

  const paneShown = (paneOpen || overlay) && selected !== null;
  const visit = useAttentionVisit(selected === null ? null : {
    ...issueAttentionItem(selected, repository, workspaceId),
    issueState: selected === previewIssue ? previewIssue.state : closedIssueId === selected.id ? 'closed' : 'open',
  }, paneShown);
  const labelColors = Object.fromEntries((metadata?.labels ?? []).map(label => [label.name, label.color]));
  const issues = snapshot?.issues ?? [];
  const searchTerms = parsePullSearch(search);
  const rows = issues.filter(issue => (searchTerms === null || pullRequestMatchesSearch(issue, searchTerms))
    && (labelFilter === '' || issue.labels.includes(labelFilter)));
  const labels = [...new Set([...issues.flatMap(issue => issue.labels), ...(labelFilter ? [labelFilter] : [])])].sort((a, b) => a.localeCompare(b));
  const filtering = searchTerms !== null || labelFilter !== '';

  return (
    <div className={`pulls-page issues-page${overlay ? ' pulls-page--overlay' : ''}`}
      ref={overlayRef}
      role={overlay ? 'dialog' : undefined}
      aria-modal={overlay ? true : undefined}
      aria-label={overlay ? `Issue ${repository} #${initialIssueNumber}` : undefined}
      tabIndex={overlay ? -1 : undefined}>
      {!overlay && <section className="pulls-list issues-list">
        <header className="pulls-list__toolbar">
          <span className="pulls-scope" title={`Showing ${repository}`}>{repository}</span>
          <nav className="pulls-tabs" aria-label="Issue filters">
            <button className="is-active" type="button" aria-pressed="true">Open issues <span className="issues-list__count">{snapshot?.totalIssues ?? 0}</span></button>
          </nav>
          <button className="pulls-toolbar-button" type="button" title="Sync issues" aria-label="Sync issues"
            disabled={loading} onClick={() => { void refreshIssues(); }}>
            <RefreshIcon />
          </button>
          <button className={`pulls-toolbar-button${paneShown ? ' is-active' : ''}`} type="button"
            title="Toggle issue details" aria-label="Toggle issue details" aria-pressed={paneShown}
            onClick={() => setPaneOpen(value => !value)}>
            <PaneIcon />
          </button>
        </header>
        <div className="pulls-search issues-search">
          <input type="search" value={search} placeholder="⌕  Search by title, #number, or @author"
            aria-label="Search issues by title, number, or author" onChange={event => setSearch(event.target.value)} />
          <select aria-label="Filter issues by label" value={labelFilter} onChange={event => setLabelFilter(event.target.value)}>
            <option value="">All labels</option>
            {labels.map(label => <option key={label} value={label}>{label}</option>)}
          </select>
        </div>
        {filtering && <div className="issues-freshness" role="status">{rows.length} of {issues.length} loaded issues</div>}
        {snapshot?.syncedAt && <div className="issues-freshness">Updated {relativeTime(snapshot.syncedAt)}</div>}
        {error && <div className="issues-error" role="alert">Could not refresh {repository}. {error}</div>}
        {mutationError && !paneShown && <div className="issues-error" role="alert">Could not update issue. {mutationError}</div>}
        <div className="pulls-list__rows" onScroll={scrollIssues}>
          {loading && !snapshot && <div className="hp-loading">Loading…</div>}
          {!loading && snapshot && rows.length === 0 && <div className="hp-empty">
            {filtering ? 'No loaded issues match your filters.' : `No open issues for ${repository}.`}
          </div>}
          {rows.map(issue => (
            <IssueRow key={issue.id} issue={issue} repository={repository} labelColors={labelColors}
              labelFilter={labelFilter} onFilterLabel={label => setLabelFilter(current => current === label ? '' : label)}
              actions={<IssueDevelopmentActions issue={issue} onStart={onStartDevelopment === undefined ? undefined : setStartingIssue} onViewTask={onViewTask}
                onToggleEpic={() => requestEpic(issue)} changingEpic={changingEpic !== null} />}
              selected={issue.id === selectedId} onPick={() => {
                onSelectIssue?.(issue.number, 'push');
                setSelectedId(issue.id);
                setPaneOpen(true);
              }} />
          ))}
          {snapshot?.hasMore && <button type="button" className="issues-load-more" disabled={loading || loadingMore}
            onClick={() => { void loadMore(); }}>Load more issues</button>}
          {loadingMore && <div className="issues-page-loading">Loading more…</div>}
        </div>
      </section>}

      {overlay && !zoom.full && <ZoomScrim onClose={zoom.close} />}

      {overlay && selected === null && (
        <section className={`pull-detail issues-detail ${zoom.cardClass}`}>
          <div className="pull-detail__body">
            {error
              ? <div className="pull-detail__error" role="alert">{error}</div>
              : <div className="hp-loading">Loading…</div>}
          </div>
        </section>
      )}

      {paneShown && selected && (
        <section className={`pull-detail issues-detail${overlay ? ` ${zoom.cardClass}` : ''}`}
          style={overlay ? undefined : { width: detailWidth }}>
          {!overlay && <div className="pull-detail__resize" role="separator" aria-orientation="vertical"
            aria-label="Resize issue panes" onMouseDown={resizeDetail} title="Drag to resize" />}
          <header className="pull-detail__header">
            <div className="pull-detail__title-row">
              <div className="pull-detail__header-actions">
                {visit.status && <span className="attention-visit-status" role="status">{visit.status}</span>}
                <PinButton className="pull-detail__icon-action" item={{ key: `${repository}#${selected.number}`, title: selected.title,
                  reference: `${repository} #${selected.number}`, target: { kind: 'issue', repo: repository, number: selected.number, workspaceId } }} />
                <button className="pull-detail__icon-action" type="button" title="Refresh" aria-label="Refresh" disabled={detailLoading}
                  onClick={() => { void (selected === previewIssue ? refreshPreview(selected.number) : loadDetail(selected.number, true)); }}><RefreshIcon /></button>
                {overlay
                  ? <ZoomActions {...zoom} label="issue details" className="pull-detail__icon-action" />
                  : <button className="pull-detail__review-action" type="button"
                    onClick={() => { void window.bridge.openInAppBrowser(`https://github.com/${repository}/issues/new`); }}>
                    New issue
                  </button>}
              </div>
            </div>
          </header>
          {detailError && (
            <div className="pull-detail__error" role="alert">
              Could not load issue details. {detailError}
            </div>
          )}
          {mutationError && <div className="pull-detail__error" role="alert">Could not update issue. {mutationError}</div>}
          <div className="pull-detail__body">
            <IssueOverview key={selected.id} issue={selected} closed={selected.id === closedIssueId} readOnly={selected === previewIssue} metadata={metadata} timeline={timeline} issueType={issueType}
              repository={repository} workspaceId={workspaceId}
              actions={selected === previewIssue ? undefined : <IssueDevelopmentActions issue={selected} onStart={onStartDevelopment === undefined ? undefined : setStartingIssue} onViewTask={onViewTask}
                onToggleEpic={() => requestEpic(selected)} changingEpic={changingEpic !== null} />}
              timelineLoading={detailLoading} onTypeChange={type => updateIssueType(selected.number, type)}
              onMetadataChange={(assignees, labels) => updateIssueMetadata(selected.number, assignees, labels)}
              onClose={() => closeIssue(selected)}
              onComment={async body => {
                await window.bridge.commentWorkspaceIssue(workspaceId, selected.number, body);
                visit.onAction('comment');
                await loadDetail(selected.number);
              }} />
          </div>
        </section>
      )}

      {startingIssue !== null && onStartDevelopment !== undefined && (
        <StartDevelopmentDialog
          workspaceId={workspaceId}
          issueNumber={startingIssue}
          onCancel={() => setStartingIssue(null)}
          onConfirm={launch => { setStartingIssue(null); onStartDevelopment(startingIssue, launch); }}
        />
      )}
      {unmarkEpic.dialog}
    </div>
  );
}

export function appendIssuePage(
  current: RepositoryIssueSnapshotDto,
  next: RepositoryIssueSnapshotDto,
): RepositoryIssueSnapshotDto {
  if (current.repositoryId !== next.repositoryId || next.page !== current.page + 1) return current;
  const loaded = new Set(current.issues.map(issue => issue.id));
  return { ...next, issues: [...current.issues, ...next.issues.filter(issue => !loaded.has(issue.id))] };
}

export function IssueDevelopmentActions({ issue, onStart, onViewTask, onToggleEpic, changingEpic = false }: {
  issue: Issue;
  onStart?: (number: number) => void;
  onViewTask?: (link: IssueDevelopmentTaskDto) => void;
  onToggleEpic?: () => void;
  changingEpic?: boolean;
}) {
  const action = issueDevelopmentAction(issue);
  return <span className="issues-development-actions">
    {action === 'view' && onViewTask !== undefined && <button className="pulls-toolbar-button" type="button" title="View task" aria-label="View task"
      onClick={() => { if (issue.developmentTask != null) onViewTask(issue.developmentTask); }}><ListTodo size={16} aria-hidden="true" /></button>}
    {action === 'start' && onStart !== undefined && <button className="pulls-toolbar-button" type="button" disabled={changingEpic}
      title="Use agent to develop" aria-label="Start development" onClick={() => onStart(issue.number)}><Play size={16} aria-hidden="true" /></button>}
    {onToggleEpic !== undefined && <button className={`pulls-toolbar-button${issue.isEpic ? ' is-active' : ''}`} type="button"
      disabled={changingEpic} onClick={onToggleEpic} aria-pressed={issue.isEpic === true}
      title={issue.isEpic ? 'Unmark as Epic' : 'Mark as Epic'} aria-label={issue.isEpic ? 'Unmark as Epic' : 'Mark as Epic'}>
      <Flag size={16} aria-hidden="true" />
    </button>}
  </span>;
}

function EpicBadge() {
  return <span className="issues-epic-badge"><Flag size={12} aria-hidden="true" />Epic</span>;
}

function IssueRow({ issue, repository, labelColors, labelFilter, onFilterLabel, selected, onPick, actions }: {
  issue: Issue;
  repository: string;
  labelColors: Record<string, string>;
  labelFilter: string;
  onFilterLabel: (label: string) => void;
  selected: boolean;
  onPick: () => void;
  actions: React.ReactNode;
}) {
  const owner = repository.split('/')[0] ?? repository;
  return (
    <div className={`pull-row issues-row${selected ? ' is-selected' : ''}`}>
      <button type="button" className="issues-row__select" aria-pressed={selected} onClick={onPick}>
      <span className="pull-row__title">
        <span className="pull-row__state issues-row__state" aria-hidden="true"><IssueIcon /></span>
        <span>{issue.title} <small>#{issue.number}</small> {issue.isEpic && <EpicBadge />}</span>
      </span>
      </button>
      {issue.labels.length > 0 && (
        <span className="pull-row__labels">
          {issue.labels.map(label => <button type="button" key={label} style={labelStyle(labelColors[label])}
            aria-label={`Filter by label ${label}`} aria-pressed={labelFilter === label} onClick={() => onFilterLabel(label)}>{label}</button>)}
        </span>
      )}
      {issue.assignees.length > 0 && (
        <span className="pull-row__stats issues-row__stats">
          <span className="issues-row__assignees">Assigned to {issue.assignees.join(', ')}</span>
        </span>
      )}
      <div className="issues-row__footer">
        <button type="button" className="issues-row__select" aria-label={`Open issue #${issue.number}`} onClick={onPick}>
          <span className="pull-row__meta">
            <Avatar login={owner} size={16} />
            <span className="pull-row__meta-name" title={repository}>{repository}</span>
            <span>·</span>
            <Avatar login={issue.author} avatarUrl={issue.authorAvatarUrl} size={16} />
            <span className="pull-row__meta-name" title={issue.author}>{issue.author}</span>
            <span>·</span>
            <span className="pull-row__meta-time">{relativeTime(issue.updatedAt)}</span>
            <span>·</span>
            <span className="pull-row__comments"><CommentIcon />{issue.commentCount}</span>
          </span>
        </button>
        {actions}
      </div>
    </div>
  );
}

type IssueMetadataPickerKind = 'participants' | 'assignees' | 'labels';

export function IssueOverview({
  issue,
  closed = false,
  readOnly = false,
  metadata,
  timeline,
  issueType,
  repository,
  workspaceId,
  timelineLoading,
  onTypeChange,
  onMetadataChange,
  onClose,
  onComment,
  actions,
}: {
  issue: Issue;
  closed?: boolean;
  readOnly?: boolean;
  metadata: RepositoryMetadataDto | null;
  timeline: WorkspaceIssueTimelineEventDto[];
  issueType: WorkspaceIssueTypeDto | null;
  repository: string;
  workspaceId: string;
  timelineLoading: boolean;
  onTypeChange: (type: string | null) => Promise<void>;
  onMetadataChange: (assignees: string[], labels: string[]) => Promise<void>;
  onClose: () => Promise<void>;
  onComment: (body: string) => Promise<void>;
  actions?: React.ReactNode;
}) {
  const root = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState<IssueMetadataPickerKind | null>(null);
  const [repoUsers, setRepoUsers] = useState<RepoUserDto[]>([]);
  const [repoLabels, setRepoLabels] = useState<RepoLabelDto[]>([]);
  const [loadingOptions, setLoadingOptions] = useState(false);
  const [updating, setUpdating] = useState(false);
  const [pickerError, setPickerError] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [quotedReply, setQuotedReply] = useState<QuoteRequest>();
  const colors = Object.fromEntries((metadata?.labels ?? []).map(label => [label.name, label.color]));
  const participants = issueParticipants(issue, timeline);
  const visibleTimeline = timeline.filter(event => !HIDDEN_EVENTS.has(event.event));
  const candidates: MentionCandidate[] = participants.map(({ login, avatarUrl }) => ({ login, avatarUrl }));

  useEffect(() => {
    if (!open) return;
    const close = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(null);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(null);
    };
    document.addEventListener('pointerdown', close);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', close);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  const togglePicker = (kind: IssueMetadataPickerKind) => {
    setQuery('');
    setPickerError(null);
    setOpen(current => current === kind ? null : kind);
    if (kind === 'participants' || loadingOptions
      || (kind === 'labels' ? repoLabels.length > 0 : repoUsers.length > 0)) return;
    setLoadingOptions(true);
    const request = kind === 'labels'
      ? window.bridge.fetchRepoLabels(repository).then(setRepoLabels)
      : window.bridge.fetchRepoUsers(repository, workspaceId).then(setRepoUsers);
    void request
      .catch(failure => setPickerError(remoteErrorMessage(failure)))
      .finally(() => setLoadingOptions(false));
  };

  const updateMetadata = async (assignees: string[], labels: string[]) => {
    setUpdating(true);
    setPickerError(null);
    try {
      await onMetadataChange(assignees, labels);
      setOpen(null);
    }
    catch {
      // The parent renders the backend's permission or validation message.
    }
    finally {
      setUpdating(false);
    }
  };

  const normalizedQuery = query.trim().toLowerCase();
  const participantOptions = participants
    .filter(person => person.login.toLowerCase().includes(normalizedQuery))
    .map(person => ({
      key: person.login,
      label: person.login,
      login: person.login,
      avatarUrl: person.avatarUrl,
      selected: true,
      disabled: true,
      disabledReason: 'Participants are derived from issue activity',
    }));
  const assigneeOptions = issueAssigneeOptions(issue.assignees, repoUsers)
    .filter(user => user.login.toLowerCase().includes(normalizedQuery));
  const selectedLabels = new Set(issue.labels.map(label => label.toLowerCase()));
  const labelOptions = repoLabels
    .filter(label => label.name.toLowerCase().includes(normalizedQuery))
    .map(label => ({
      key: label.name,
      label: label.name,
      color: labelColor(label.color),
      selected: selectedLabels.has(label.name.toLowerCase()),
    }));

  return (
    <div className="pr-overview issue-overview">
      <section className="pr-overview__summary">
        <div className="pr-overview__title">
          <h1>{linkIssueNumbers(issue.title, repository, true)} <span>#{issue.number}</span></h1>
          {issue.isEpic && <EpicBadge />}
          <CopyLinkButton value={issue.htmlUrl} label="issue link" />
        </div>
        <div className="pr-overview__branches">
          {closed
            ? <span className="pr-state is-closed"><CircleCheck size={16} strokeWidth={1.6} aria-hidden="true" /> Closed</span>
            : <span className="pr-state"><IssueIcon /> Open</span>}
          <span>opened by <strong>{issue.author}</strong> {relativeTime(issue.createdAt)}</span>
          <span>· updated {relativeTime(issue.updatedAt)}</span>
          <span className="issues-development-actions">
            <button className="pulls-toolbar-button" type="button" title="Open on GitHub" aria-label="Open on GitHub"
              onClick={() => { void window.bridge.openInAppBrowser(issue.htmlUrl); }}><ExternalLink size={16} aria-hidden="true" /></button>
            {actions}
          </span>
        </div>
      </section>

      <div className="pr-overview__rule" />
      <div className="pr-meta" ref={root}>
        <div className="pr-meta__picker">
          <button type="button" className={`pr-meta__chip${open === 'participants' ? ' is-open' : ''}`}
            aria-expanded={open === 'participants'} aria-haspopup="listbox" onClick={() => togglePicker('participants')}>
            <small>Participants</small>
            {participants.length === 0
              ? 'None'
              : participants.length < 3
                ? participants.map(person => (
                  <span className="pr-meta__reviewer" key={person.login}>
                    <Avatar login={person.login} avatarUrl={person.avatarUrl} size={18} />{person.login}
                  </span>
                ))
                : `${participants.length} people`}
          </button>
          {open === 'participants' && <MetadataPicker ariaLabel="participants" placeholder="Search participants..."
            query={query} setQuery={setQuery} options={participantOptions} loading={false} updating={updating}
            loadingText="" emptyText="No participants found"
            onToggle={() => {}} />}
        </div>
        <div className="pr-meta__picker">
          <button type="button" className={`pr-meta__chip${open === 'assignees' ? ' is-open' : ''}`}
            disabled={readOnly}
            aria-expanded={open === 'assignees'} aria-haspopup="listbox" onClick={() => togglePicker('assignees')}>
            <User size={15} strokeWidth={1.4} aria-hidden="true" /> <small>Assignees</small>{issue.assignees.length > 0 ? ` ${issue.assignees.length}` : ''}
          </button>
          {open === 'assignees' && <MetadataPicker ariaLabel="assignees" placeholder="Search users..."
            query={query} setQuery={setQuery} options={assigneeOptions} loading={loadingOptions} updating={updating}
            loadingText="Loading repository users…" emptyText="No users found"
            onToggle={option => { void updateMetadata(toggleSelection(issue.assignees, option.key), issue.labels); }} />}
        </div>
        <div className="pr-meta__picker">
          <button type="button" className={`pr-meta__chip${open === 'labels' ? ' is-open' : ''}`}
            disabled={readOnly}
            aria-expanded={open === 'labels'} aria-haspopup="listbox" onClick={() => togglePicker('labels')}>
            <span className="pr-meta__label-dots" aria-hidden="true">
              {issue.labels.slice(0, 2).map(label => <i className="pr-meta__label-dot" style={{ background: labelColor(colors[label]) }} key={label} />)}
            </span>
            {issue.labels.length === 0 ? 'No labels' : issue.labels.length === 1 ? issue.labels[0] : `${issue.labels.length} labels`}
          </button>
          {open === 'labels' && <MetadataPicker ariaLabel="labels" placeholder="Search labels..."
            query={query} setQuery={setQuery} options={labelOptions} loading={loadingOptions} updating={updating}
            loadingText="Loading repository labels…" emptyText="No labels found"
            onToggle={option => { void updateMetadata(issue.assignees, toggleSelection(issue.labels, option.key)); }} />}
        </div>
        {!readOnly && <IssueTypeChip issueType={issueType} onChange={onTypeChange} />}
        {pickerError && <div className="pr-meta__error" role="alert">Could not load metadata options. {pickerError}</div>}
      </div>

      <main className="issue-conversation">
        <section className="pr-timeline" aria-label="Issue conversation and activity">
          <article className="pr-card pr-description">
            <CardHeader actor={issue.author} avatarUrl={issue.authorAvatarUrl} timestamp={issue.createdAt}
              actions={{
                link: issue.htmlUrl,
                markdown: issue.body || 'No description provided.',
                onQuote: () => setQuotedReply(current => ({
                  id: (current?.id ?? 0) + 1,
                  body: issue.body || 'No description provided.',
                })),
              }} />
            <MarkdownBody body={issue.body || 'No description provided.'} repository={repository} />
          </article>
          {visibleTimeline.map((event, index) => event.event === 'commented'
            ? <IssueComment event={event} fallbackUrl={issue.htmlUrl} repository={repository}
              onQuote={body => setQuotedReply(current => ({ id: (current?.id ?? 0) + 1, body }))}
              key={event.githubId ?? `comment-${index}`} />
            : <IssueEvent event={event} colors={colors} key={event.githubId ?? `${event.event}-${index}`} />)}
          {timelineLoading && (timeline.length === 0
            // A first read has no activity to append to, so the pane shows the shape it is about to fill.
            ? <SkeletonTimeline label="Loading activity…" />
            : <div className="pr-event issues-activity-loading"><span className="issues-event-marker">…</span><span>Loading activity…</span></div>)}
        </section>
        {!readOnly && <DiscussionComposer betterWords workspaceId={workspaceId}
          candidates={candidates} repository={repository} quotedReply={quotedReply} closeLabel="Close issue"
          onClose={closed ? undefined : onClose} onSubmit={onComment} />}
      </main>
    </div>
  );
}

function IssueComment({ event, fallbackUrl, repository, onQuote }: {
  event: WorkspaceIssueTimelineEventDto;
  fallbackUrl: string;
  repository: string;
  onQuote: (body: string) => void;
}) {
  const actor = event.actor ?? 'github';
  const body = event.body || 'No comment provided.';
  return (
    <article className="pr-card issue-comment">
      <CardHeader actor={actor} avatarUrl={event.payload.actorAvatarUrl} bot={event.payload.actorBot}
        timestamp={event.timestamp} actions={{
          link: event.payload.htmlUrl ?? fallbackUrl,
          markdown: body,
          onQuote: () => onQuote(body),
        }} />
      <MarkdownBody body={body} repository={repository} />
      <ReactionCounts reactions={event.payload.reactions} />
    </article>
  );
}

function IssueEvent({ event, colors }: { event: WorkspaceIssueTimelineEventDto; colors: Record<string, string> }) {
  const actor = event.actor ?? 'github';
  return (
    <div className="pr-event issue-event">
      <Avatar login={actor} avatarUrl={event.payload.actorAvatarUrl} size={24} />
      <div>
        <strong>{actor}</strong>{event.payload.actorBot && <span className="pr-bot">Bot</span>}{' '}
        {issueEventText(event, colors)}
        {event.timestamp && <time> · {relativeTime(event.timestamp)}</time>}
      </div>
    </div>
  );
}

function issueEventText(event: WorkspaceIssueTimelineEventDto, colors: Record<string, string>) {
  const payload = event.payload;
  switch (event.event) {
    case 'assigned': return <>assigned this issue{payload.assignee ? <> to <strong>{payload.assignee}</strong></> : null}</>;
    case 'unassigned': return <>removed{payload.assignee ? <> <strong>{payload.assignee}</strong></> : <> an assignee</>} from this issue</>;
    case 'labeled': return <>added <span className="pr-label" style={labelStyle(payload.labelColor ?? colors[payload.label ?? ''])}>{payload.label ?? 'a label'}</span></>;
    case 'unlabeled': return <>removed <span className="pr-label" style={labelStyle(payload.labelColor ?? colors[payload.label ?? ''])}>{payload.label ?? 'a label'}</span></>;
    case 'milestoned': return <>added this to the <strong>{payload.milestoneTitle}</strong> milestone</>;
    case 'demilestoned': return <>removed this from the <strong>{payload.milestoneTitle}</strong> milestone</>;
    case 'renamed': return <>changed the title from <del>{payload.renameFrom}</del> to <strong>{payload.renameTo}</strong></>;
    case 'cross-referenced': return <>mentioned this in <GitHubReferenceLink href={payload.sourceUrl}>{payload.sourceTitle ?? `#${payload.sourceNumber ?? ''}`}</GitHubReferenceLink></>;
    case 'closed': return <>closed this issue</>;
    case 'reopened': return <>reopened this issue</>;
    case 'locked': return <>locked this conversation</>;
    case 'unlocked': return <>unlocked this conversation</>;
    case 'pinned': return <>pinned this issue</>;
    case 'unpinned': return <>unpinned this issue</>;
    case 'marked_as_duplicate': return <>marked this as a duplicate</>;
    default: return <>{event.event.replaceAll('_', ' ')}</>;
  }
}

function ReactionCounts({ reactions }: { reactions: PullReactionDto[] }) {
  if (reactions.length === 0) return null;
  return <div className="pr-reactions">{reactions.map(reaction => (
    <span key={reaction.content}>{reactionEmoji(reaction.content)} {reaction.count}</span>
  ))}</div>;
}

function IssueTypeChip({ issueType, onChange }: {
  issueType: WorkspaceIssueTypeDto | null;
  onChange: (type: string | null) => Promise<void>;
}) {
  const [updating, setUpdating] = useState(false);
  const options = issueType?.current && !issueType.available.includes(issueType.current)
    ? [issueType.current, ...issueType.available]
    : issueType?.available ?? [];
  const update = async (type: string) => {
    setUpdating(true);
    try {
      await onChange(type || null);
    }
    catch {
      // The parent renders the backend's permission or validation message.
    }
    finally {
      setUpdating(false);
    }
  };
  return (
    <label className="pr-meta__chip issue-type-chip" data-type={issueType?.current?.toLowerCase()}>
      <IssueIcon /> <small>Type</small>
      <select aria-label="Issue type" value={issueType?.current ?? ''} disabled={!issueType || updating}
        onChange={event => { void update(event.target.value); }}>
        <option value="">{issueType ? 'No type' : 'Loading…'}</option>
        {options.map(type => <option value={type} key={type}>{type}</option>)}
      </select>
    </label>
  );
}

export function issueParticipants(issue: Issue, timeline: WorkspaceIssueTimelineEventDto[]) {
  const participants = new Map<string, { login: string; avatarUrl: string | null }>();
  const add = (login: string | null | undefined, avatarUrl: string | null = null) => {
    if (!login?.trim()) return;
    const key = login.toLowerCase();
    const current = participants.get(key);
    if (!current) participants.set(key, { login, avatarUrl });
    else if (!current.avatarUrl && avatarUrl) participants.set(key, { login: current.login, avatarUrl });
  };
  add(issue.author, issue.authorAvatarUrl);
  issue.assignees.forEach(login => add(login));
  timeline.forEach(event => add(event.actor, event.payload.actorAvatarUrl));
  return [...participants.values()];
}

export function issueAssigneeOptions(
  assignees: string[],
  repoUsers: RepoUserDto[],
) {
  const repositoryUsers = new Map(repoUsers.map(user => [user.login.toLowerCase(), user] as const));
  const selectedLogins = new Set(assignees.map(login => login.toLowerCase()));
  const selected = assignees.map(login => {
    const repositoryUser = repositoryUsers.get(login.toLowerCase());
    return {
      key: login,
      label: login,
      login,
      avatarUrl: repositoryUser?.avatarUrl ?? null,
      bot: repositoryUser?.bot ?? login.toLowerCase().endsWith('[bot]'),
      selected: true,
    };
  });
  return [
    ...selected,
    ...repoUsers
      .filter(user => !selectedLogins.has(user.login.toLowerCase()))
      .map(user => ({ key: user.login, label: user.login, login: user.login, avatarUrl: user.avatarUrl, bot: user.bot, selected: false })),
  ];
}

function labelStyle(color?: string | null) {
  const hex = labelColor(color);
  return { background: `${hex}26`, color: '#4f3f3f', borderColor: `${hex}66` };
}

function labelColor(color?: string | null) {
  return color && /^[0-9a-f]{6}$/i.test(color) ? `#${color}` : '#d0d7de';
}

function IssueIcon() {
  return <CircleDot size={16} strokeWidth={1.6} aria-hidden="true" />;
}

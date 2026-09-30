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
import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from 'react';
import RepoAvatar from '../RepoAvatar';
import Toast from '../Toast';
import { settingsRoute } from '../settings/routes';
import type { ModelSettingsDto, WorkspaceDto } from '../types';
import type {
  ContextBaselineDto, ContextBuildViewDto, ContextDiscoveryReportDto, ContextDisputesDto, ContextLearningPlanViewDto, ContextMergedCandidatesDto, ContextReportedNoteDto,
  ContextRunDto,
  ContextTimesDto, ContextUsageOverviewDto, ContinueContextLearningRequest, LearnContextMergedCandidateRequest, RepositoryNotebookDto, StartContextDiscoveryRequest,
  StartContextLearningRequest,
} from './contracts';
import { ContextDrawers, type Drawer } from './ContextDrawers';
import ContextNotes from './ContextNotes';
import ContextOverview from './ContextOverview';
import { Icon } from './icons';
import { currentRun, modelLine, needsTopicReselection, pendingCounts, plural, reportsNeedingYou, shortSha, type TaskTitles } from './model';
import { CONTEXT_CHANGED } from './reviewCount';
import '../css/repository-context.css';

const EMPTY_TIMES: ContextTimesDto = { entries: {} };
const failure = (cause: unknown) => cause instanceof Error ? cause.message : String(cause);

export type PendingStart =
  | { kind: 'discover'; request: StartContextDiscoveryRequest }
  | { kind: 'learn'; request: StartContextLearningRequest }
  | { kind: 'continue'; request: ContinueContextLearningRequest }
  | { kind: 'candidate'; request: LearnContextMergedCandidateRequest };

const pendingKey = (workspaceId: string) => `repository-context-start:${workspaceId}`;

function savedPending(workspaceId: string): PendingStart | null {
  try {
    const saved = sessionStorage.getItem(pendingKey(workspaceId));
    return saved === null ? null : JSON.parse(saved) as PendingStart;
  }
  catch {
    return null;
  }
}

export type ContextData = {
  workspaceId: string; repository: string;
  notebook: RepositoryNotebookDto; times: ContextTimesDto; build: ContextBuildViewDto; report: ContextDiscoveryReportDto | null;
  planView: ContextLearningPlanViewDto; candidates: ContextMergedCandidatesDto | null; baseline: ContextBaselineDto | null;
  usage: ContextUsageOverviewDto | null; reports: ContextReportedNoteDto[]; disputes: ContextDisputesDto | null; model: { label: string; blocker: string | null };
  titles: TaskTitles; now: number;
};

/** The workspace's Repository context page. Opening it only reads saved state; every agent run starts from an explicit action. */
export default function RepositoryContextPage({ workspace, initialRunId = null }: { workspace: WorkspaceDto; initialRunId?: string | null }) {
  const workspaceId = workspace.id;
  const repository = `${workspace.monitoredOwner}/${workspace.monitoredRepo}`;
  const [notebook, setNotebook] = useState<RepositoryNotebookDto | null>(null);
  const [times, setTimes] = useState<ContextTimesDto>(EMPTY_TIMES);
  const [build, setBuild] = useState<ContextBuildViewDto | null>(null);
  const [report, setReport] = useState<ContextDiscoveryReportDto | null | undefined>(undefined);
  const [planView, setPlanView] = useState<ContextLearningPlanViewDto | undefined>(undefined);
  const [candidates, setCandidates] = useState<ContextMergedCandidatesDto | null>(null);
  const [baseline, setBaseline] = useState<ContextBaselineDto | null>(null);
  const [usage, setUsage] = useState<ContextUsageOverviewDto | null>(null);
  const [reports, setReports] = useState<ContextReportedNoteDto[]>([]);
  const [disputes, setDisputes] = useState<ContextDisputesDto | null>(null);
  const [models, setModels] = useState<ModelSettingsDto | null>(null);
  const [titles, setTitles] = useState<TaskTitles>({});
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pending, setPending] = useState<PendingStart | null>(() => savedPending(workspaceId));
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState<{ id: number; text: string } | null>(null);
  const [tab, setTab] = useState<'overview' | 'notes'>('overview');
  const [notesArea, setNotesArea] = useState('all');
  const [pop, setPop] = useState<'use' | 'more' | null>(null);
  const [drawer, setDrawer] = useState<Drawer | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const opener = useRef<HTMLElement | null>(null);
  const mounted = useRef(true);
  const inFlight = useRef(false);
  const runningTask = useRef<string | null>(null);

  const say = useCallback((text: string) => setToast({ id: Date.now(), text }), []);
  const announce = useCallback(() => window.dispatchEvent(new CustomEvent(CONTEXT_CHANGED, { detail: workspaceId })), [workspaceId]);

  const loadAll = useCallback(async () => {
    const [nextNotebook, nextTimes, nextBuild, nextReport, nextPlan] = await Promise.all([
      window.bridge.getRepositoryContext(workspaceId), window.bridge.getRepositoryContextTimes(workspaceId), window.bridge.getRepositoryContextBuild(workspaceId),
      window.bridge.getRepositoryContextDiscoveryReport(workspaceId), window.bridge.getRepositoryContextLearningPlan(workspaceId)]);
    if (!mounted.current) return;
    setNotebook(nextNotebook);
    setTimes(nextTimes);
    setBuild(nextBuild);
    setReport(nextReport);
    setPlanView(nextPlan);
    setLoadError(null);
    // Secondary reads never block the page: a repository that is still cloning has no baseline or merged work yet.
    void window.bridge.getRepositoryContextBaseline(workspaceId).then(value => { if (mounted.current) setBaseline(value); }).catch(() => {});
    void window.bridge.getRepositoryContextMergedCandidates(workspaceId).then(value => { if (mounted.current) setCandidates(value); }).catch(() => {});
    void window.bridge.getRepositoryContextUsage(workspaceId, '30d').then(value => { if (mounted.current) setUsage(value); }).catch(() => {});
    void Promise.resolve().then(() => window.bridge.getRepositoryContextReports(workspaceId)).then(value => { if (mounted.current) setReports(value); }).catch(() => {});
    void Promise.resolve().then(() => window.bridge.getRepositoryContextDisputes(workspaceId)).then(value => { if (mounted.current) setDisputes(value); }).catch(() => {});
  }, [workspaceId]);

  useEffect(() => {
    mounted.current = true;
    void loadAll().catch(cause => { if (mounted.current) setLoadError(failure(cause)); });
    void window.bridge.getModelSettings(workspaceId).then(value => { if (mounted.current) setModels(value); }).catch(() => {});
    return () => { mounted.current = false; };
  }, [loadAll, workspaceId]);

  // Note origins and reports name the task behind them; titles are read only for tasks the run history does not cover.
  useEffect(() => {
    const known = new Set(build?.runs.map(run => run.taskId) ?? []);
    const wanted = [...Object.values(times.entries).flatMap(stamp => stamp.taskId === null ? [] : [stamp.taskId]),
      ...reports.flatMap(report => report.grades.filter(grade => grade.grader === 'AGENT').map(grade => grade.taskId))]
      .filter(id => !known.has(id) && titles[id] === undefined);
    for (const taskId of new Set(wanted)) {
      void window.bridge.getAgentTask(taskId, workspaceId).then(task => { if (mounted.current) setTitles(value => ({ ...value, [taskId]: task.title })); })
        .catch(() => { if (mounted.current) setTitles(value => ({ ...value, [taskId]: '' })); });
    }
  }, [build, reports, times, titles, workspaceId]);

  const running = currentRun(build);
  const active = running !== null || build?.build?.task?.state === 'OPEN';

  // Only the build view is polled while a run is active; the rest is re-read once the run settles.
  useEffect(() => {
    if (!active) return;
    let live = true;
    const timer = setInterval(() => {
      void window.bridge.getRepositoryContextBuild(workspaceId).then(value => { if (live && mounted.current) setBuild(value); }).catch(() => {});
    }, 2000);
    const clock = setInterval(() => setNow(Date.now()), 1000);
    return () => { live = false; clearInterval(timer); clearInterval(clock); };
  }, [active, workspaceId]);

  useEffect(() => {
    const previous = runningTask.current;
    runningTask.current = running?.taskId ?? null;
    if (previous === null || running !== null || build === null) return;
    const finished = build.runs.find(run => run.taskId === previous);
    void loadAll().then(announce).catch(cause => { if (mounted.current) setLoadError(failure(cause)); });
    if (finished !== undefined) say(finishMessage(finished));
  }, [announce, build, loadAll, running, say]);

  useEffect(() => {
    if (pending !== null && build?.build?.requestKey === pending.request.requestKey) {
      sessionStorage.removeItem(pendingKey(workspaceId));
      setPending(null);
    }
  }, [build, pending, workspaceId]);

  useEffect(() => {
    if (initialRunId !== null) {
      setTab('overview');
      setDrawer({ kind: 'run', taskId: initialRunId });
    }
  }, [initialRunId]);

  const perform = useCallback(async (operation: () => Promise<void>) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setActionError(null);
    try { await operation(); }
    catch (cause) { if (mounted.current) { setActionError(failure(cause)); void loadAll().catch(() => {}); } }
    finally { inFlight.current = false; if (mounted.current) setBusy(false); }
  }, [loadAll]);

  const start = useCallback((next: PendingStart) => perform(async () => {
    if (next.kind === 'learn' && (needsTopicReselection(planView?.plan ?? null) || report?.id !== next.request.reportId
      || next.request.topicIds.some(id => report.topics.find(topic => topic.id === id)?.notePaths == null))) {
      throw new Error('Discover again and reselect topics with explicit note targets. Saved notes are kept.');
    }
    if (next.kind === 'continue' && (planView?.plan?.id !== next.request.planId || needsTopicReselection(planView.plan))) {
      throw new Error('Reselect topics before continuing. Saved notes are kept.');
    }
    sessionStorage.setItem(pendingKey(workspaceId), JSON.stringify(next));
    setPending(next);
    const accepted = next.kind === 'discover' ? await window.bridge.startRepositoryContextDiscovery(workspaceId, next.request)
      : next.kind === 'learn' ? await window.bridge.startRepositoryContextLearning(workspaceId, next.request)
        : next.kind === 'continue' ? await window.bridge.continueRepositoryContextLearning(workspaceId, next.request)
          : await window.bridge.learnRepositoryContextMergedCandidate(workspaceId, next.request);
    if (!mounted.current) return;
    setBuild(accepted);
    setDrawer(null);
    await loadAll();
  }), [loadAll, perform, planView, report, workspaceId]);

  const fence = () => ({ requestKey: crypto.randomUUID(), expectedRevision: notebook?.revision ?? 0, expectedBuildSequence: build?.buildRequestSequence ?? 0 });

  const openDrawer = (next: Drawer) => {
    if (drawer === null && document.activeElement instanceof HTMLElement) opener.current = document.activeElement;
    setPop(null);
    setDrawer(next);
  };
  const closeDrawer = () => setDrawer(null);
  // Focus returns only once the list behind the drawer is no longer inert.
  const drawerOpen = drawer !== null;
  useEffect(() => {
    if (!drawerOpen) opener.current?.focus();
  }, [drawerOpen]);
  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key !== 'Escape' || event.defaultPrevented) return;
    if (pop !== null) { setPop(null); event.preventDefault(); }
    else if (drawer !== null) { closeDrawer(); event.preventDefault(); }
  };

  if (notebook === null || build === null || report === undefined || planView === undefined) {
    return <div className="rcp"><div className="rcp-scroll"><div className="rcp-inner">
      <h1 className="rcp-page-title">Repository context</h1>
      {loadError === null ? <p className="rcp-loading" role="status">Loading repository context…</p>
        : <div className="rcp-error" role="alert">Repository context could not be loaded. {loadError}
          <button type="button" className="rcp-link" onClick={() => void loadAll().catch(cause => setLoadError(failure(cause)))}>Try again</button></div>}
    </div></div></div>;
  }

  const model = modelLine(models);
  const data: ContextData = { workspaceId, repository, notebook, times, build, report, planView, candidates, baseline, usage, reports, disputes, model, titles, now };
  const counts = pendingCounts(planView.plan, reportsNeedingYou(notebook, reports));
  const available = notebook.entries.filter(entry => entry.enabled).length;
  const revision = baseline === null ? null : `${baseline.branch}@${shortSha(planView.plan?.baselineSha ?? report?.baselineSha ?? baseline.sha)}`;
  const useLabel = notebook.useInDevelopment && notebook.useInReviews ? 'Used by agents'
    : notebook.useInDevelopment ? 'Used in development only' : notebook.useInReviews ? 'Used in reviews only' : 'Not used by agents';
  const useDot = notebook.useInDevelopment && notebook.useInReviews ? '#1a7f37' : notebook.useInDevelopment || notebook.useInReviews ? '#bf8700' : '#8c959f';
  const configure = (change: { useInDevelopment?: boolean; useInReviews?: boolean }, message: string) => perform(async () => {
    const saved = await window.bridge.configureRepositoryContext(workspaceId, { expectedRevision: notebook.revision, ...change });
    if (!mounted.current) return;
    setNotebook(saved);
    say(message);
  });
  const mutate = (operation: (revision: number) => Promise<RepositoryNotebookDto>, message: string) => perform(async () => {
    const saved = await operation(notebook.revision);
    if (!mounted.current) return;
    setNotebook(saved);
    setTimes(await window.bridge.getRepositoryContextTimes(workspaceId));
    say(message);
    announce();
  });
  const actions = {
    perform, mutate, say, announce, setNotebook, setPlanView, setBuild, reload: loadAll,
    discover: (continuation: boolean): void => void start({ kind: 'discover', request: { ...fence(), continuation } }),
    learn: (topicIds: string[], excludedSourceIds: string[]) => {
      if (report !== null) void start({ kind: 'learn', request: { ...fence(), reportId: report.id, topicIds, excludedSourceIds } });
    },
    continueLearning: () => { if (planView.plan !== null) void start({ kind: 'continue', request: { ...fence(), planId: planView.plan.id } }); },
    learnCandidate: (candidateId: string): void => void start({ kind: 'candidate', request: { ...fence(), candidateId } }),
    stop: (run: ContextRunDto) => perform(async () => {
      setBuild(await window.bridge.stopRepositoryContextBuild(workspaceId, run.taskId));
      say(run.mode === 'DISCOVER' ? 'Discovery stopped · notes unchanged' : run.pullRequest !== null ? 'Run stopped · candidate kept' : 'Run stopped · plan and saved notes kept');
    }),
    openDrawer,
    openTab: (next: 'overview' | 'notes', area = 'all') => { setTab(next); setNotesArea(area); setDrawer(null); setPop(null); },
  };
  const retryPending = pending === null ? null : (): void => void start(pending);
  const dismissPending = () => { sessionStorage.removeItem(pendingKey(workspaceId)); setPending(null); setActionError(null); };

  return <div className="rcp" onKeyDown={onKeyDown}>
    <div className="rcp-content" inert={drawer !== null}>
      <div className="rcp-scroll">
        <div className="rcp-inner">
          <div className="rcp-head">
            <div className="rcp-head__main">
              <h1>Repository context</h1>
              <div className="rcp-head__meta">
                <span className="rcp-repo"><RepoAvatar owner={workspace.monitoredOwner} repo={workspace.monitoredRepo} size={18} />{repository}</span>
                {revision !== null && <span className="rcp-rev">{revision}</span>}
                <span className="rcp-sep" aria-hidden="true">·</span><span>{plural(available, 'note')} available to agents</span>
                {counts.total > 0 && <><span className="rcp-sep" aria-hidden="true">·</span><span className="rcp-head__review">{counts.total} to review</span></>}
              </div>
            </div>
            <div className="rcp-head__actions">
              <button type="button" className="rcp-use" aria-expanded={pop === 'use'} aria-haspopup="dialog" onClick={() => setPop(value => value === 'use' ? null : 'use')}>
                <span className="rcp-use__dot" style={{ background: useDot }} />{useLabel}<Icon name="down" size={12} /></button>
              <button type="button" className="rcp-more" aria-expanded={pop === 'more'} aria-haspopup="menu" aria-label="More options"
                onClick={() => setPop(value => value === 'more' ? null : 'more')}>···</button>
            </div>
            {pop === 'use' && <div className="rcp-pop" role="dialog" aria-label="Agent use">
              <div className="rcp-pop__title">Agents can look up notes in</div>
              <div className="rcp-switch-row">
                <div className="rcp-switch-row__main"><div className="rcp-switch-row__name">Development tasks</div><div className="rcp-switch-row__sub">Tasks and Explore questions</div></div>
                <button type="button" role="switch" className="rcp-switch" aria-checked={notebook.useInDevelopment} aria-label="Use in development tasks" disabled={busy}
                  onClick={() => void configure({ useInDevelopment: !notebook.useInDevelopment }, notebook.useInDevelopment
                    ? 'New development tasks won’t look up notes · notes kept' : 'New development tasks can look up notes')}><span /></button>
              </div>
              <div className="rcp-switch-row">
                <div className="rcp-switch-row__main"><div className="rcp-switch-row__name">PR reviews</div><div className="rcp-switch-row__sub">Agent review runs</div></div>
                <button type="button" role="switch" className="rcp-switch" aria-checked={notebook.useInReviews} aria-label="Use in PR reviews" disabled={busy}
                  onClick={() => void configure({ useInReviews: !notebook.useInReviews }, notebook.useInReviews
                    ? 'New reviews won’t look up notes · notes kept' : 'New reviews can look up notes')}><span /></button>
              </div>
              <div className="rcp-pop__note">Applies to new tasks and reviews. Notes are kept when off. The agent decides whether a note applies.</div>
              <button type="button" className="rcp-link" onClick={() => openDrawer({ kind: 'usage' })}>View usage</button>
            </div>}
            {pop === 'more' && <div className="rcp-menu" role="menu">
              <button type="button" role="menuitem" onClick={() => openDrawer({ kind: 'usage' })}>Usage</button>
              <button type="button" role="menuitem" onClick={() => openDrawer({ kind: 'advanced' })}>Advanced configuration</button>
              <button type="button" role="menuitem" onClick={() => { setPop(null); window.location.hash = settingsRoute('ws-agents', workspaceId); }}>Workspace settings</button>
            </div>}
          </div>
          <div className="rcp-tabs" role="tablist" aria-label="Repository context views">
            <button type="button" role="tab" className="rcp-tab" aria-selected={tab === 'overview'} onClick={() => actions.openTab('overview')}>Overview</button>
            <button type="button" role="tab" className="rcp-tab" aria-selected={tab === 'notes'} onClick={() => actions.openTab('notes')}>
              Notes{notebook.entries.length > 0 && <span className="rcp-count">{notebook.entries.length}</span>}</button>
          </div>
          {actionError !== null && pending === null && <div className="rcp-error" role="alert">{actionError}
            <button type="button" className="rcp-link" onClick={() => setActionError(null)}>Dismiss</button></div>}
          {tab === 'overview'
            ? <ContextOverview data={data} actions={actions} busy={busy} pending={pending === null ? null : { error: actionError, retry: retryPending, dismiss: dismissPending }} />
            : <ContextNotes data={data} area={notesArea} onArea={setNotesArea} onOpen={entryId => openDrawer({ kind: 'note', id: entryId })} />}
          <div className="rcp-manual"><span>Manual sources and paths aren’t needed for normal use.</span>
            <button type="button" onClick={() => openDrawer({ kind: 'advanced' })}>Advanced configuration</button></div>
        </div>
      </div>
    </div>
    {drawer !== null && <ContextDrawers drawer={drawer} data={data} actions={actions} busy={busy} onDrawer={setDrawer} onClose={closeDrawer} />}
    {toast !== null && <Toast key={toast.id} text={toast.text} onDismiss={() => setToast(null)} />}
  </div>;
}

export type ContextActions = {
  perform: (operation: () => Promise<void>) => Promise<void>;
  mutate: (operation: (revision: number) => Promise<RepositoryNotebookDto>, message: string) => Promise<void>;
  say: (text: string) => void;
  announce: () => void;
  setNotebook: (notebook: RepositoryNotebookDto) => void;
  setPlanView: (view: ContextLearningPlanViewDto) => void;
  setBuild: (view: ContextBuildViewDto) => void;
  reload: () => Promise<void>;
  discover: (continuation: boolean) => void;
  learn: (topicIds: string[], excludedSourceIds: string[]) => void;
  continueLearning: () => void;
  learnCandidate: (candidateId: string) => void;
  stop: (run: ContextRunDto) => Promise<void>;
  openDrawer: (drawer: Drawer) => void;
  openTab: (tab: 'overview' | 'notes', area?: string) => void;
};

function finishMessage(run: ContextRunDto): string {
  if (run.mode === 'DISCOVER') return run.outcome === 'SUBMITTED' ? 'Discovery finished · suggestions saved' : 'Discovery ended without saving suggestions';
  switch (run.outcome) {
    case 'ANSWERED': return `Run ${run.sequence} finished · ${plural(run.noteIds.length, 'note')} saved`;
    case 'NO_NOTE': return `Run ${run.sequence} finished · no reusable note`;
    case 'LIMIT': return `Run ${run.sequence} reached its source limit · progress saved`;
    case 'UNFINISHED': return `Run ${run.sequence} finished · progress saved`;
    case 'FAILED': return `Run ${run.sequence} failed · plan and saved notes kept`;
    default: return `Run ${run.sequence} ended`;
  }
}

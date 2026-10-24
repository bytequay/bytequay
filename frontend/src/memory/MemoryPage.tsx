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
import { AgentSetupNotice, useAgentAvailability } from '../agents/availability';
import { BookOpen, Bot, ChevronRight, FileText, GitPullRequest, Globe, Quote, SquareTerminal, TriangleAlert, User } from 'lucide-react';
import { useCallback, useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { ConfirmDialog } from '../ConfirmDialog';
import RepoAvatar from '../RepoAvatar';
import Toast from '../Toast';
import '../css/memory.css';
import '../css/repository-context.css';
import { remoteErrorMessage } from '../remoteError';
import { RunLine, SpecCheckDrawer, SpecFinding, type SpecCheckActions, type SpecCheckData } from '../repository-context/ContextSpecCheck';
import type { ContextBuildViewDto, ContextSpecCheckDto } from '../repository-context/contracts';
import { Icon, Spinner } from '../repository-context/icons';
import { activeCheck, checkActive, checkProgress, modelLine, plural, shortDate, shortSha } from '../repository-context/model';
import { CHECK_STATE, checkSummary, openFindings } from '../repository-context/specChecks';
import type { AgentRuntimeStopImpactDto, ModelSettingsDto, WorkspaceDto } from '../types';
import { useNow } from '../useNow';
import { workItemRoute } from '../workitems/contracts';
import { workspaceReviewTaskRoute } from '../workspaces/model';
import { MemoryDrawer } from './MemoryDrawer';
import { ReplayDrawer, ReplaysSection } from './ReplaysSection';
import type { MemoryDto, MemoryKind, MemoryPageDto, MemoryPullDto } from './contracts';
import {
  age,flagged,fromTask,
  kilobytes,
  KIND_LABEL,
  matches,MEMORY_CHANGED,memoryFlags,sourceChip,titleRepeated,type MemoryKindFilter,type MemorySourceFilter,
} from './model';
import { replayRunning, type ReplayDto } from './replays';

const KINDS: MemoryKind[] = ['RULE', 'RECIPE', 'SPEC'];
const TABS = [
  ['memories', 'Memories'], ['learn', 'Learn from PRs'], ['needs', 'Needs you'],
  ['specs', 'Spec checks'], ['replays', 'Replays'], ['guidelines', 'Guidelines'],
] as const;
type MemoryTab = typeof TABS[number][0];
/** What the workspace's one restricted run is called where a message names it. */
const RUN_NAMES: Record<NonNullable<ContextBuildViewDto['build']>['kind'], string> = { CHECK: 'check', HARVEST: 'harvest' };
type Drawer = { kind: 'spec'; directory: string | null } | { kind: 'memory'; id: string } | { kind: 'replay'; id: string };

/**
 * The workspace's repository memory: what agents receive, what needs the user, the guideline files every turn reads, the spec
 * checks, and the review replays. Opening the page only reads local state; a check starts only from Check against spec.
 */
export default function MemoryPage({ workspace }: { workspace: WorkspaceDto }) {
  const workspaceId = workspace.id;
  const availability = useAgentAvailability(workspaceId);
  const repository = `${workspace.monitoredOwner}/${workspace.monitoredRepo}`;
  const [page, setPage] = useState<MemoryPageDto | null>(null);
  const [checks, setChecks] = useState<ContextSpecCheckDto[] | null>(null);
  const [build, setBuild] = useState<ContextBuildViewDto | null>(null);
  const [replays, setReplays] = useState<ReplayDto[]>([]);
  const [deleting, setDeleting] = useState<string | null>(null);
  const [models, setModels] = useState<ModelSettingsDto | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState<{ id: number; text: string; undo: string | null } | null>(null);
  const [drawer, setDrawer] = useState<Drawer | null>(null);
  const [tab, setTab] = useState<MemoryTab>('memories');
  const scroll = useRef<HTMLDivElement>(null);
  useEffect(() => { if (scroll.current !== null) scroll.current.scrollTop = 0; }, [tab]);
  const [impact, setImpact] = useState<AgentRuntimeStopImpactDto | null>(null);
  const [kind, setKind] = useState<MemoryKindFilter>('ALL');
  const [source, setSource] = useState<MemorySourceFilter>('ANY');
  const [query, setQuery] = useState('');
  const mounted = useRef(true);
  const inFlight = useRef(false);
  const opener = useRef<HTMLElement | null>(null);
  const panel = useRef<HTMLDivElement>(null);

  const say = useCallback((text: string, undo: string | null = null) => setToast({ id: Date.now(), text, undo }), []);
  const searchPulls = useCallback((query: string) => window.bridge.searchMemoryPulls(workspaceId, query), [workspaceId]);
  const load = useCallback(async () => {
    // Replays sit behind the agent routes, which refuse until agent startup recovery ends; the rest of the page does not wait for them.
    const [nextPage, nextChecks, nextBuild, nextReplays] = await Promise.all([window.bridge.getMemoryPage(workspaceId),
      window.bridge.getRepositoryContextSpecChecks(workspaceId), window.bridge.getRepositoryContextBuild(workspaceId),
      window.bridge.listReviewReplays(workspaceId).catch((): null => null)]);
    if (!mounted.current) return;
    setPage(nextPage);
    if (nextReplays !== null) setReplays(nextReplays);
    setChecks(nextChecks);
    setBuild(nextBuild);
    setLoadError(null);
  }, [workspaceId]);

  useEffect(() => {
    mounted.current = true;
    void load().catch(cause => { if (mounted.current) setLoadError(remoteErrorMessage(cause)); });
    void window.bridge.getModelSettings(workspaceId).then(value => { if (mounted.current) setModels(value); }).catch(() => {});
    return () => { mounted.current = false; };
  }, [load, workspaceId]);

  const running = (checks !== null && activeCheck(checks) !== null) || build?.build?.outcome === 'RUNNING' || build?.build?.task?.state === 'OPEN'
    || replays.some(replayRunning);
  // A check saves candidates and results during its turns, and between them says whether it goes on.
  useEffect(() => {
    if (!running) return;
    const timer = setInterval(() => void load().catch(() => {}), 2000);
    return () => { clearInterval(timer); };
  }, [load, running]);
  const now = useNow(running);

  const drawerOpen = drawer !== null;
  useEffect(() => {
    if (drawerOpen) panel.current?.focus();
    else opener.current?.focus();
  }, [drawerOpen]);

  const perform = useCallback(async (operation: () => Promise<void>) => {
    if (inFlight.current) return false;
    inFlight.current = true;
    setBusy(true);
    setActionError(null);
    try { await operation(); return true; }
    catch (cause) { if (mounted.current) { setActionError(remoteErrorMessage(cause)); void load().catch(() => {}); } return false; }
    finally { inFlight.current = false; if (mounted.current) setBusy(false); }
  }, [load]);

  /** Applies a change the backend answered with the whole page, and tells the sidebar count. */
  const change = useCallback((operation: () => Promise<MemoryPageDto>, done: () => void): Promise<boolean> => perform(async () => {
    const next = await operation();
    if (!mounted.current) return;
    setPage(next);
    window.dispatchEvent(new CustomEvent(MEMORY_CHANGED, { detail: workspaceId }));
    done();
  }), [perform, workspaceId]);

  if (page === null || checks === null || build === null) {
    return <div className="rcp"><div className="rcp-scroll"><div className="rcp-inner">
      <h1 className="rcp-page-title">Memory</h1>
      {loadError === null ? <p className="rcp-loading" role="status">Loading memory…</p>
        : <div className="rcp-error" role="alert">Memory could not be loaded. {loadError}
          <button type="button" className="rcp-link" onClick={() => void load().catch(cause => setLoadError(remoteErrorMessage(cause)))}>Try again</button></div>}
    </div></div></div>;
  }

  const data: SpecCheckData = { workspaceId, repository, checks, build, model: modelLine(models), now };
  const actions: SpecCheckActions = {
    start: (directory, specs, maxReproductions) => void perform(async () => {
      const started = await window.bridge.startRepositoryContextSpecCheck(workspaceId, { requestKey: crypto.randomUUID(),
        expectedBuildSequence: build.buildRequestSequence, directory, specs, maxReproductions });
      if (!mounted.current) return;
      setBuild(started.build);
      setChecks(value => [...(value ?? []).filter(item => item.directory !== started.check.directory), started.check]);
      say('Spec check started · findings appear as their tests fail');
    }),
    stop: taskId => void perform(async () => {
      setBuild(await window.bridge.stopRepositoryContextBuild(workspaceId, taskId));
      await load();
      say('Spec check stopped · confirmed findings kept');
    }),
    dismissFinding: (check, candidateId, reason, rule) => void perform(async () => {
      const saved = await window.bridge.dismissRepositoryContextSpecFinding(workspaceId, check.id, candidateId, rule === null ? { reason } : { reason, rule });
      if (!mounted.current) return;
      setChecks(value => (value ?? []).map(item => item.id === saved.id ? saved : item));
      if (rule !== null) await load();
      say(rule === null ? 'Finding dismissed · nothing was sent to GitHub' : 'Finding dismissed · your rule is in memory · nothing was sent to GitHub');
    }),
    openIssue: (check, candidate) => void perform(async () => {
      const item = await window.bridge.specWorkItem(workspaceId, check.id, candidate.id);
      window.location.hash = workItemRoute(workspaceId, item.id);
    }),
    say,
  };
  const open = (next: Drawer) => {
    if (document.activeElement instanceof HTMLElement) opener.current = document.activeElement;
    if (next.kind === 'spec') setTab('specs');
    setDrawer(next);
  };
  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Escape' && drawer !== null && !event.defaultPrevented) { setDrawer(null); event.preventDefault(); }
  };
  const openUrl = (url: string): void => void window.bridge.openInAppBrowser(url);
  const run = build.build;
  const task = run?.task ?? null;
  const settle = (operation: () => Promise<ContextBuildViewDto>): void => void perform(async () => { setBuild(await operation()); });
  const ordered = [...checks].sort((left, right) => Date.parse(right.startedAt) - Date.parse(left.startedAt));
  const memories = page.memories;
  const needs = memories.filter(flagged);
  const visible = memories.filter(memory => matches(memory, kind, source, query));
  const selected = drawer?.kind === 'memory' ? memories.find(memory => memory.id === drawer.id) ?? null : null;
  const replay = drawer?.kind === 'replay' ? replays.find(item => item.id === drawer.id) ?? null : null;
  const mark = (commentId: string, memory: boolean, raised: boolean): void => void perform(async () => {
    if (replay === null) return;
    const next = await window.bridge.markReviewReplay(workspaceId, replay.id, { commentId, memory, raised });
    if (mounted.current) setReplays(list => list.map(item => item.id === next.id ? next : item));
  });
  const count = (predicate: (memory: MemoryDto) => boolean) => memories.filter(predicate).length;
  const switches = page.switches;
  const fresh = new Set(page.harvest.last?.outcome === 'DONE' ? page.harvest.last.saved : []);
  const learn = (pull: number | null): void => void perform(async () => {
    const started = await window.bridge.startMemoryHarvest(workspaceId, { requestKey: crypto.randomUUID(), expectedBuildSequence: build.buildRequestSequence,
      pull, repo: pull === null ? null : repository });
    if (!mounted.current) return;
    setBuild(started.view);
    await load();
    const what = pull === null ? plural(started.pulls, 'pull request') : `#${pull}`;
    say(started.started ? `Learning from ${what} · ${plural(started.threads, 'maintainer thread')} to read` : `No new maintainer threads in ${what}`);
  });
  const saveSwitches = (next: { reviews: boolean; development: boolean }) =>
    change(() => window.bridge.saveMemorySwitches(workspaceId, next), () => say(next.reviews || next.development ? 'Memory switches saved' : 'Agents get no memory now'));
  const counts: Partial<Record<MemoryTab, number>> = { memories: memories.length, needs: needs.length, specs: checks.length,
    replays: replays.length, guidelines: page.guidelines.length };
  return <div className="rcp" onKeyDown={onKeyDown}>
    <div className="rcp-content" inert={drawer !== null}>
      <div className="mem-page-header">
        <div className="rcp-inner mem-inner">
          <div className="rcp-head">
            <div className="rcp-head__main">
              <h1>Memory</h1>
              <div className="rcp-head__meta">
                <span className="rcp-repo"><RepoAvatar owner={workspace.monitoredOwner} repo={workspace.monitoredRepo} size={18} />{repository}</span>
                {page.head !== null && <span className="mem-head">{shortSha(page.head)}</span>}
                <Chip icon={<BookOpen size={14} />} count={count(memory => memory.kind === 'RULE')} title="rules" />
                <Chip icon={<SquareTerminal size={14} />} count={count(memory => memory.kind === 'RECIPE')} title="recipes" />
                <Chip icon={<FileText size={14} />} count={count(memory => memory.kind === 'SPEC')} title="specs" />
                <Chip icon={<GitPullRequest size={14} />} count={count(memory => memory.sources.some(item => item.pullRequest !== null))} title="from pull requests" />
                <Chip icon={<User size={14} />} count={count(memory => memory.origin.user)} title="yours" />
                <Chip icon={<Bot size={14} />} count={count(fromTask)} title="from tasks and reviews" />
                <Chip icon={<Globe size={14} />} count={count(memory => memory.sources.some(item => item.kind === 'PAGE'))} title="from pages" />
                <Chip icon={<TriangleAlert size={14} />} count={needs.length} title="need you" warn />
              </div>
            </div>
            {availability.development && <div className="rcp-head__actions">
              <button type="button" className="rcp-btn rcp-btn--primary" onClick={() => open({ kind: 'spec', directory: null })}><Icon name="bolt" size={13} />
                Check against spec</button>
            </div>}
          </div>
          <div className="mem-switches" role="group" aria-label="Agents that get memory">
            <span className="mem-muted">Agents use memory in</span>
            <label><input type="checkbox" checked={switches.reviews} disabled={busy}
              onChange={event => saveSwitches({ reviews: event.target.checked, development: switches.development })} />Reviews</label>
            <label><input type="checkbox" checked={switches.development} disabled={busy}
              onChange={event => saveSwitches({ reviews: switches.reviews, development: event.target.checked })} />Development tasks</label>
          </div>
          <div className="mem-page-tabs" role="tablist" aria-label="Memory sections">
            {TABS.map(([id, label], index) => <button type="button" key={id} id={`mem-tab-${id}`} role="tab"
              aria-selected={tab === id} aria-controls={`mem-panel-${id}`} tabIndex={tab === id ? 0 : -1}
              className={`mem-page-tab${tab === id ? ' is-active' : ''}`} onClick={() => setTab(id)}
              onKeyDown={event => {
                const next = event.key === 'ArrowRight' ? (index + 1) % TABS.length
                  : event.key === 'ArrowLeft' ? (index + TABS.length - 1) % TABS.length
                    : event.key === 'Home' ? 0 : event.key === 'End' ? TABS.length - 1 : null;
                if (next === null) return;
                event.preventDefault();
                setTab(TABS[next][0]);
                event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus();
              }}>
              {label}{counts[id] !== undefined && <span className="mem-page-tab__count">{counts[id]}</span>}
              {((id === 'learn' && run?.kind === 'HARVEST' && run.outcome === 'RUNNING')
                || (id === 'specs' && activeCheck(checks) !== null) || (id === 'replays' && replays.some(replayRunning)))
                && <span aria-label="Running"><Spinner size={12} /></span>}
            </button>)}
          </div>
        </div>
      </div>
      <div className="rcp-scroll mem-page-scroll" ref={scroll}>
        <div className="rcp-inner mem-inner mem-panel" id={`mem-panel-${tab}`} role="tabpanel" aria-labelledby={`mem-tab-${tab}`} tabIndex={0}>
          {actionError !== null && <div className="rcp-error" role="alert">{actionError}
            <button type="button" className="rcp-link" onClick={() => setActionError(null)}>Dismiss</button></div>}
          {tab === 'learn' && <LearnCard page={page} data={data} busy={busy} onLearn={learn} onSearch={searchPulls} onStop={actions.stop}
            onDelete={id => change(() => window.bridge.deleteMemory(workspaceId, id), () => say('Memory deleted', id))}
            onOpen={id => open({ kind: 'memory', id })} />}
          {tab === 'specs' && run !== null && run.kind === 'CHECK' && run.outcome === 'RUNNING' && <div className="rcp-status" role="status" aria-live="polite"><div className="rcp-status__row">
            <span className="rcp-status__icon rcp-tone--blue"><Spinner size={14} /></span>
            <div className="rcp-status__main">
              <div className="rcp-status__headline">Checking {checks.find(check => check.id === run.checkId)?.directory ?? 'a directory'}</div>
              {activeCheck(checks) !== null && <div className="rcp-status__text">{checkProgress(activeCheck(checks) as ContextSpecCheckDto)}</div>}
              {run.activity.length > 0 && <div className="rcp-feed">{run.activity.slice(-3).map((line, index) =>
                <div className={`rcp-feed__line rcp-tone--${line.tone === 'FAILED' ? 'red' : 'green'}`} key={index}>
                  <span className="rcp-feed__mark">{line.tone === 'FAILED' ? '✕' : '✓'}</span><span className="rcp-feed__text">{line.text}</span></div>)}</div>}
              {run.pendingApprovals > 0 && <div className="rcp-alert">This check is waiting for approval. Checks cannot be steered; stop it to end the pending request.</div>}
              <div className="rcp-actions"><button type="button" className="rcp-btn rcp-btn--danger" disabled={busy || run.activeTurn?.cancellationRequestedAt != null}
                onClick={() => actions.stop(run.taskId)}>Stop</button></div>
            </div>
          </div></div>}
          {run !== null && run.outcome !== 'RUNNING' && task !== null && !build.canStart && <div className="rcp-alert">
            {task.mutationClaim === 'RECOVERY' ? `The last ${RUN_NAMES[run.kind]}’s agent process needs recovery before another run can start.`
              : task.state === 'OPEN' ? `The last ${RUN_NAMES[run.kind]} hasn’t settled yet. It usually finishes on its own.`
                : `The last ${RUN_NAMES[run.kind]}’s worktree is still retained. Clean it up before starting another run.`}
            <div className="rcp-actions">
              {task.mutationClaim === 'RECOVERY' && <button type="button" className="rcp-btn rcp-btn--sm" disabled={busy} onClick={() => void perform(async () => {
                const found = await window.bridge.getRepositoryContextBuildStopImpact(workspaceId, task.id);
                if (found.taskId !== task.id) throw new Error('The runtime belongs to another task.');
                setImpact(found);
              })}>Stop remaining processes</button>}
              {task.state === 'OPEN' && run.activeTurn === null && <button type="button" className="rcp-btn rcp-btn--sm" disabled={busy}
                onClick={() => settle(() => window.bridge.failRepositoryContextBuild(workspaceId, task.id, 'User ended the spec check.'))}>Mark failed</button>}
              {task.state !== 'OPEN' && task.worktreeRemovedAt === null && <button type="button" className="rcp-btn rcp-btn--sm" disabled={busy}
                onClick={() => settle(() => window.bridge.cleanupRepositoryContextBuild(workspaceId, task.id))}>Clean up worktree</button>}
            </div>
          </div>}

          {tab === 'needs' && <section className="mem-section" aria-label="Needs you">
            <h2 className="mem-section__title">Needs you · {needs.length}</h2>
            {needs.length === 0 ? <div className="rcp-empty">No memories need your attention.</div> : <div className="mem-box">{needs.map(memory => {
              const flag = memoryFlags(memory)[0];
              return <button type="button" key={memory.id} className="mem-need" onClick={() => open({ kind: 'memory', id: memory.id })}>
                <span className="mem-need__icon"><TriangleAlert size={15} aria-hidden="true" /></span>
                <span className="mem-need__main"><span className="mem-need__title">{memory.title}</span>
                  <span className="mem-need__meta"><span className="mem-warn">{flag.title}</span> · {flag.detail}</span></span>
                <span className="rcp-age">{age(memory.reports.at(-1)?.at ?? memory.updatedAt, now)}</span>
                <ChevronRight size={14} aria-hidden="true" className="mem-chevron" />
              </button>;
            })}</div>}
          </section>}

          {tab === 'memories' && <section className="mem-section" aria-label="Memories">
            <div className="mem-section__bar">
              <h2 className="mem-section__title">Memories · {memories.length}</h2>
              <span className="rcp-spacer" />
              <div className="mem-tabs" role="group" aria-label="Kind">
                {(['ALL', ...KINDS] as MemoryKindFilter[]).map(value => <button type="button" key={value} aria-pressed={kind === value}
                  className={`mem-tab${kind === value ? ' is-on' : ''}`} onClick={() => setKind(value)}>
                  {value === 'ALL' ? 'All' : KIND_LABEL[value].many} <span className="mem-muted">{value === 'ALL' ? memories.length : count(memory => memory.kind === value)}</span>
                </button>)}
              </div>
              <select aria-label="Source" className="mem-select" value={source} onChange={event => setSource(event.target.value as MemorySourceFilter)}>
                <option value="ANY">Any source</option>
                <option value="PR">From pull requests</option>
                <option value="USER">Yours</option>
                <option value="TASK">From tasks and reviews</option>
                <option value="PAGE">From pages</option>
              </select>
              <input type="search" aria-label="Filter memories" placeholder="Filter" className="mem-filter" value={query} onChange={event => setQuery(event.target.value)} />
            </div>
            {memories.length === 0
              ? <div className="rcp-empty">No memory yet. Learn from pull requests saves the rules maintainers asked for, a spec check remembers the
                specifications it used, and agents in tasks and reviews remember your corrections and the commands they had to work out. Agents get
                the memories that match their work at the start of every turn and can search the rest.</div>
              : visible.length === 0 ? <div className="rcp-empty">No memory matches these filters.</div>
                : KINDS.map(group => {
                  const items = visible.filter(memory => memory.kind === group);
                  if (items.length === 0) return null;
                  return <div className="mem-group" key={group}>
                    <div className="mem-group__label">{KIND_LABEL[group].many} · {items.length}</div>
                    <div className="mem-box">{items.map(memory => <MemoryRow key={memory.id} memory={memory} now={now} fresh={fresh.has(memory.id)}
                      selected={selected?.id === memory.id} onOpen={() => open({ kind: 'memory', id: memory.id })} />)}</div>
                  </div>;
                })}
          </section>}

          {tab === 'specs' && <section className="mem-section" aria-label="Spec checks">
            <h2 className="mem-section__title">Spec checks · {ordered.length}</h2>
            {ordered.length === 0
              ? <div className="rcp-empty">No spec check yet. A check reads the pages a directory’s code links, decides which are specifications, and reproduces each
                gap it finds with a test. Only a gap whose test fails is reported, and each specification it used becomes a memory.</div>
              : ordered.map(check => {
                const findings = openFindings(check);
                return <article key={check.id} className="mem-box mem-spec-check" aria-label={check.directory}>
                  <button type="button" className={`rcp-review rcp-tone--${CHECK_STATE[check.state].tone}`}
                    onClick={() => open({ kind: 'spec', directory: check.directory })}>
                    <span className="rcp-review__main"><span className="rcp-review__title">{check.directory}</span>
                      <span className="rcp-review__meta"><span className="rcp-review__kind">{CHECK_STATE[check.state].label}</span><span className="rcp-sep">·</span>
                        <span className="rcp-review__origin">{checkActive(check) ? checkProgress(check) : checkSummary(check)}</span></span></span>
                    <span className="rcp-age">{shortDate(check.endedAt ?? check.startedAt, now)}</span>
                    <span className="rcp-link">Check details</span>
                  </button>
                  <div className="mem-spec-findings">
                    <h3 className="mem-section__title">Findings · {findings.length}</h3>
                    {findings.length === 0 && <p className="mem-muted">{checkActive(check)
                      ? 'Confirmed gaps appear here as their tests fail.' : 'No confirmed findings to review.'}</p>}
                    {findings.map(candidate => <SpecFinding key={candidate.id} data={data} actions={actions} busy={busy} check={check} candidate={candidate} />)}
                  </div>
                </article>;
              })}
          </section>}

          {tab === 'replays' && <ReplaysSection replays={replays} now={now} onOpen={id => open({ kind: 'replay', id })} />}

          {tab === 'guidelines' && <section className="mem-section" aria-label="Guidelines">
            <h2 className="mem-section__title">Guidelines · {plural(page.guidelines.length, 'file')}</h2>
            <div className="mem-muted">Every agent turn gets these files as reference data, read from the commit it starts at; reviews read their base branch.
              Reviews also read the CLAUDE.md or AGENTS.md beside the paths they change.</div>
            {page.guidelines.length === 0
              ? <div className="rcp-empty">No CLAUDE.md, AGENTS.md, CONTRIBUTING.md or DEVELOPMENT.md at the root or in .github at the monitored head.</div>
              : <div className="mem-box">{page.guidelines.map(file => <div className="mem-guideline" key={file.path}>
                <FileText size={14} aria-hidden="true" className="mem-chevron" />
                <span className="mem-guideline__path">{file.path}</span>
                <span className={file.cut ? 'mem-warn' : 'mem-muted'}>{kilobytes(file.bytes)}{file.cut ? ', cut at 16 KB' : ''}</span>
                <button type="button" className="rcp-link" onClick={() => openUrl(file.url)}>Open</button>
              </div>)}</div>}
          </section>}
        </div>
      </div>
    </div>
    {drawer !== null && (drawer.kind === 'spec' || selected !== null || replay !== null) && <>
      <div className="rcp-scrim" onClick={() => setDrawer(null)} />
      <div ref={panel} className="rcp-drawer" role="dialog" aria-modal="true" aria-label={drawer.kind === 'spec' ? 'Spec check' : drawer.kind === 'replay' ? 'Replay' : 'Memory'}
        tabIndex={-1}>
        {drawer.kind === 'spec'
          ? <SpecCheckDrawer key={drawer.directory ?? 'new'} data={data} actions={actions} busy={busy} directory={drawer.directory}
            onClose={() => setDrawer(null)} />
          : replay !== null ? <ReplayDrawer replay={replay} busy={busy} now={now} onClose={() => setDrawer(null)} onMark={mark}
            onDelete={() => setDeleting(replay.id)} onOpenUrl={openUrl}
            onOpenReview={reviewId => { window.location.hash = workspaceReviewTaskRoute(workspaceId, reviewId); }} />
          : selected !== null && <MemoryDrawer key={selected.id} memory={selected} busy={busy} error={actionError} now={now} onClose={() => setDrawer(null)}
            onOpen={openUrl}
            onSave={(title, text) => change(() => window.bridge.editMemory(workspaceId, selected.id, selected.version, title, text),
              () => say('Memory saved · it is yours now'))}
            onRestore={() => change(() => window.bridge.restoreMemory(workspaceId, selected.id, selected.version), () => say('Previous version restored'))}
            onKeep={() => change(() => window.bridge.keepMemory(workspaceId, selected.id), () => say('Kept as it is'))}
            onDelete={() => change(() => window.bridge.deleteMemory(workspaceId, selected.id), () => { setDrawer(null); say('Memory deleted', selected.id); })} />}
      </div>
    </>}
    {deleting !== null && <ConfirmDialog title="Delete replay" confirmLabel="Delete" destructive busy={busy} onCancel={() => setDeleting(null)}
      onConfirm={() => void perform(async () => {
        await window.bridge.deleteReviewReplay(workspaceId, deleting);
        if (!mounted.current) return;
        setReplays(list => list.filter(item => item.id !== deleting));
        setDeleting(null);
        setDrawer(null);
        say('Replay deleted');
      })}>
      Delete this replay’s comparison and your calls on it. Its two reviews end and stay in the review history.
    </ConfirmDialog>}
    {impact !== null && <ConfirmDialog title="Stop remaining processes" confirmLabel="Stop processes" destructive busy={busy} onCancel={() => setImpact(null)}
      onConfirm={() => settle(async () => {
        const view = await window.bridge.stopRepositoryContextBuildRuntime(workspaceId, impact.taskId, impact.generation);
        setImpact(null);
        return view;
      })}>
      Stop the remaining agent runtime of this check. Its findings and worktree are kept. {impact.liveProcess ? 'An agent process is still running.' : 'No live agent process was reported.'}
    </ConfirmDialog>}
    {toast !== null && <Toast key={toast.id} text={toast.text} onDismiss={() => setToast(null)} durationMs={toast.undo === null ? undefined : 8000}
      onUndo={toast.undo === null ? undefined : () => {
        const id = toast.undo as string;
        setToast(null);
        change(() => window.bridge.undoDeleteMemory(workspaceId, id), () => say('Memory restored'));
      }} />}
  </div>;
}

/** An icon and a count; the sentence is on hover, and a zero shows nothing. */
function Chip({ icon, count, title, warn = false }: { icon: ReactNode; count: number; title: string; warn?: boolean }) {
  if (count === 0) return null;
  return <span className={`mem-chip${warn ? ' mem-warn' : ''}`} title={`${count} ${title}`}>{icon}{count}</span>;
}

function MemoryRow({ memory, now, fresh, selected, onOpen }: { memory: MemoryDto; now: number; fresh: boolean; selected: boolean; onOpen: () => void }) {
  const chip = sourceChip(memory);
  const flag = memoryFlags(memory)[0];
  return <button type="button" className={`mem-row${selected ? ' is-selected' : ''}`} onClick={onOpen}>
    <span className="mem-row__main">
      {!titleRepeated(memory) && <span className="mem-row__title">{memory.title}</span>}
      <span className="mem-row__text">{memory.text}</span>
      <span className="mem-row__meta">
        {memory.scope.length === 0 ? <span className="mem-scope">Repository</span> : memory.scope.map(path => <span key={path} className="mem-scope is-path">{path}</span>)}
        <span className="mem-source-chip">{chip.kind === 'PR' ? <GitPullRequest size={13} aria-hidden="true" /> : chip.kind === 'USER' ? <User size={13} aria-hidden="true" />
          : chip.kind === 'COMMAND' ? <SquareTerminal size={13} aria-hidden="true" /> : <Globe size={13} aria-hidden="true" />}
          <span>{chip.label}</span>{chip.more !== '' && <span className="mem-muted">{chip.more}</span>}</span>
        {memory.origin.user && <span className="mem-source-chip"><User size={13} aria-hidden="true" />Yours</span>}
        {fresh && <span className="mem-new">New</span>}
        {flag !== undefined && <span className="mem-warn mem-source-chip"><TriangleAlert size={13} aria-hidden="true" />{flag.title}</span>}
      </span>
    </span>
    <span className="mem-row__side">
      <span className="mem-count" title={`Cited ${memory.cited} ${memory.cited === 1 ? 'time' : 'times'} by findings or answers`}><Quote size={13} aria-hidden="true" />
        {memory.cited}</span>
      <span className="rcp-age">{age(memory.createdAt, now)}</span>
    </span>
  </button>;
}

/**
 * Learn from pull requests: how far the harvest has read, the run that reads it, what the last run saved, and a search for one closed pull
 * request to learn from.
 */
export function LearnCard({ page, data, busy, onLearn, onSearch, onStop, onDelete, onOpen }: {
  page: MemoryPageDto; data: SpecCheckData; busy: boolean; onLearn: (pull: number | null) => void; onSearch: (query: string) => Promise<MemoryPullDto[]>;
  onStop: (taskId: string) => void; onDelete: (id: string) => void; onOpen: (id: string) => void;
}) {
  const availability = useAgentAvailability(data.workspaceId);
  const run = data.build.build;
  const last = page.harvest.last;
  const cursor = page.harvest.cursor;
  const harvesting = run !== null && run.kind === 'HARVEST' && run.outcome === 'RUNNING';
  const saved = last === null || last.outcome !== 'DONE' ? [] : last.saved.map(id => page.memories.find(memory => memory.id === id)).filter(memory => memory !== undefined);
  const blocked = data.model.blocker !== null;
  return <section className="mem-learn" aria-label="Learn">
    <span className="mem-learn__icon"><GitPullRequest size={16} aria-hidden="true" /></span>
    <div className="mem-learn__main">
      {harvesting && run !== null ? <>
        <div className="mem-learn__title"><Spinner size={13} />{last?.label ?? 'Learning'}{last !== null && ` · reading ${plural(last.threads, 'maintainer thread')} in ${plural(last.pulls, 'pull request')}`}</div>
        {run.activity.length > 0 && <div className="rcp-feed">{run.activity.slice(-3).map((line, index) =>
          <div className={`rcp-feed__line rcp-tone--${line.tone === 'FAILED' ? 'red' : 'green'}`} key={index}>
            <span className="rcp-feed__mark">{line.tone === 'FAILED' ? '✕' : '✓'}</span><span className="rcp-feed__text">{line.text}</span></div>)}</div>}
        <div className="rcp-actions"><button type="button" className="rcp-btn rcp-btn--danger rcp-btn--sm" disabled={busy || run.activeTurn?.cancellationRequestedAt != null}
          onClick={() => onStop(run.taskId)}>Stop</button></div>
      </> : <>
        <div className="mem-learn__title">Learn from pull requests</div>
        <div className="mem-muted">{cursor === null
          ? 'Reads the newest closed pull requests you wrote or reviewed, up to 30, and saves the rules their maintainers asked for.'
          : `Learned through #${cursor.number}. The next run reads the pull requests you wrote or reviewed that closed after it, up to 30.`}</div>
        {last !== null && last.outcome !== 'RUNNING' && <LastRun last={last} saved={saved} busy={busy} onDelete={onDelete} onOpen={onOpen} />}
        {availability.development && <><div className="rcp-actions"><button type="button" className="rcp-btn rcp-btn--primary" disabled={busy || !data.build.canStart || blocked}
          onClick={() => onLearn(null)}><Icon name="bolt" size={13} />Learn from my PRs</button></div>
        <RunLine workspaceId={data.workspaceId} blocked={blocked}>{data.model.blocker
          ?? `Starts 1 run · reads the maintainer threads it finds and saves rules · ${data.model.label}`}</RunLine>
        <PullSearch disabled={busy || !data.build.canStart || blocked} onSearch={onSearch} onLearn={onLearn} /></>}
        {!availability.development && <AgentSetupNotice workspaceId={data.workspaceId} />}
      </>}
    </div>
  </section>;
}

/** Learn from one pull request: the closed ones a typed number or title words name, each with Learn. */
function PullSearch({ disabled, onSearch, onLearn }: {
  disabled: boolean; onSearch: (query: string) => Promise<MemoryPullDto[]>; onLearn: (pull: number) => void;
}) {
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<{ query: string; pulls: MemoryPullDto[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const typed = query.trim();
    if (typed === '') {
      setFound(null);
      setError(null);
      return;
    }
    let current = true;
    // Each search reads GitHub, so it waits for typing to pause.
    const timer = setTimeout(() => void onSearch(typed).then(pulls => { if (current) { setFound({ query: typed, pulls }); setError(null); } },
      cause => { if (current) setError(remoteErrorMessage(cause)); }), 400);
    return () => { current = false; clearTimeout(timer); };
  }, [onSearch, query]);
  return <div className="mem-pull-search">
    <label className="mem-edit">Learn from one closed pull request
      <input type="search" value={query} placeholder="Number, link or title words" onChange={event => setQuery(event.target.value)} />
    </label>
    {error !== null && <div className="mem-warn">{error}</div>}
    {found !== null && found.pulls.length === 0 && <div className="mem-muted">No closed pull request matches “{found.query}”.</div>}
    {found !== null && found.pulls.length > 0 && <div className="mem-box">{found.pulls.map(pull => <div className="mem-learn__item mem-pull" key={pull.number}>
      <span className="mem-pull__title"><span className="mem-muted">#{pull.number}</span> {pull.title}</span>
      <span className="mem-muted">{pull.merged ? 'merged' : 'closed'}</span>
      <button type="button" className="rcp-link" disabled={disabled} onClick={() => onLearn(pull.number)}>Learn</button>
    </div>)}</div>}
  </div>;
}

function LastRun({ last, saved, busy, onDelete, onOpen }: {
  last: NonNullable<MemoryPageDto['harvest']['last']>; saved: MemoryDto[]; busy: boolean; onDelete: (id: string) => void; onOpen: (id: string) => void;
}) {
  if (last.outcome === 'NOTHING') return <div className="mem-learn__last">{last.label} found no new maintainer threads in {plural(last.pulls, 'pull request')}.</div>;
  if (last.outcome === 'FAILED') return <div className="mem-learn__last mem-warn">{last.label} did not finish; what it saved is kept.</div>;
  return <div className="mem-learn__last">
    <div>{last.label} read {plural(last.threads, 'maintainer thread')} in {plural(last.pulls, 'pull request')}
      {[last.saved.length > 0 ? `${last.saved.length} new` : '', last.seenAgain > 0 ? `${last.seenAgain} seen again` : '',
        last.replaced > 0 ? `${last.replaced} replaced` : ''].filter(part => part !== '').map(part => <span key={part} className="mem-muted"> · {part}</span>)}</div>
    {saved.length > 0 && <div className="mem-box mem-learn__saved">{saved.map(memory => <div className="mem-learn__item" key={memory.id}>
      <button type="button" className="mem-learn__text" onClick={() => onOpen(memory.id)}>{memory.title}</button>
      <button type="button" className="rcp-link mem-learn__delete" disabled={busy} onClick={() => onDelete(memory.id)}>Delete</button>
    </div>)}</div>}
  </div>;
}

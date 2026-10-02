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
import { ConfirmDialog } from '../ConfirmDialog';
import RepoAvatar from '../RepoAvatar';
import Toast from '../Toast';
import type { AgentRuntimeStopImpactDto, ModelSettingsDto, WorkspaceDto } from '../types';
import type { ContextBuildViewDto, ContextSpecCandidateDto, ContextSpecCheckDto } from './contracts';
import { SpecCheckDrawer } from './ContextSpecCheck';
import { Icon, Spinner } from './icons';
import { activeCheck, checkActive, checkProgress, modelLine, plural, shortDate } from './model';
import { CHECK_STATE, checkSummary, issueUrl, openFindings } from './specChecks';
import '../css/repository-context.css';

export type SpecCheckData = {
  workspaceId: string; repository: string; checks: ContextSpecCheckDto[]; build: ContextBuildViewDto; model: { label: string; blocker: string | null }; now: number;
};

export type SpecCheckActions = {
  start: (directory: string, specs: string[]) => void;
  stop: (taskId: string) => void;
  dismissFinding: (check: ContextSpecCheckDto, candidateId: string, reason: string) => void;
  openIssue: (candidate: ContextSpecCandidateDto) => void;
  say: (text: string) => void;
};

const failure = (cause: unknown) => cause instanceof Error ? cause.message : String(cause);

/** The workspace's spec checks. Opening the page only reads saved state; a check starts only from Check against spec. */
export default function SpecChecksPage({ workspace }: { workspace: WorkspaceDto }) {
  const workspaceId = workspace.id;
  const repository = `${workspace.monitoredOwner}/${workspace.monitoredRepo}`;
  const [checks, setChecks] = useState<ContextSpecCheckDto[] | null>(null);
  const [build, setBuild] = useState<ContextBuildViewDto | null>(null);
  const [models, setModels] = useState<ModelSettingsDto | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState<{ id: number; text: string } | null>(null);
  const [drawer, setDrawer] = useState<{ directory: string | null } | null>(null);
  const [impact, setImpact] = useState<AgentRuntimeStopImpactDto | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const mounted = useRef(true);
  const inFlight = useRef(false);
  const opener = useRef<HTMLElement | null>(null);
  const panel = useRef<HTMLDivElement>(null);

  const say = useCallback((text: string) => setToast({ id: Date.now(), text }), []);
  const load = useCallback(async () => {
    const [nextChecks, nextBuild] = await Promise.all([window.bridge.getRepositoryContextSpecChecks(workspaceId), window.bridge.getRepositoryContextBuild(workspaceId)]);
    if (!mounted.current) return;
    setChecks(nextChecks);
    setBuild(nextBuild);
    setLoadError(null);
  }, [workspaceId]);

  useEffect(() => {
    mounted.current = true;
    void load().catch(cause => { if (mounted.current) setLoadError(failure(cause)); });
    void window.bridge.getModelSettings(workspaceId).then(value => { if (mounted.current) setModels(value); }).catch(() => {});
    return () => { mounted.current = false; };
  }, [load, workspaceId]);

  const running = (checks !== null && activeCheck(checks) !== null) || build?.build?.outcome === 'RUNNING' || build?.build?.task?.state === 'OPEN';
  // A check saves candidates and results during its turns, and between them says whether it goes on.
  useEffect(() => {
    if (!running) return;
    const timer = setInterval(() => void load().catch(() => {}), 2000);
    const clock = setInterval(() => setNow(Date.now()), 1000);
    return () => { clearInterval(timer); clearInterval(clock); };
  }, [load, running]);

  const drawerOpen = drawer !== null;
  useEffect(() => {
    if (drawerOpen) panel.current?.focus();
    else opener.current?.focus();
  }, [drawerOpen]);

  const perform = useCallback(async (operation: () => Promise<void>) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setActionError(null);
    try { await operation(); }
    catch (cause) { if (mounted.current) { setActionError(failure(cause)); void load().catch(() => {}); } }
    finally { inFlight.current = false; if (mounted.current) setBusy(false); }
  }, [load]);

  if (checks === null || build === null) {
    return <div className="rcp"><div className="rcp-scroll"><div className="rcp-inner">
      <h1 className="rcp-page-title">Spec checks</h1>
      {loadError === null ? <p className="rcp-loading" role="status">Loading spec checks…</p>
        : <div className="rcp-error" role="alert">Spec checks could not be loaded. {loadError}
          <button type="button" className="rcp-link" onClick={() => void load().catch(cause => setLoadError(failure(cause)))}>Try again</button></div>}
    </div></div></div>;
  }

  const data: SpecCheckData = { workspaceId, repository, checks, build, model: modelLine(models), now };
  const actions: SpecCheckActions = {
    start: (directory, specs) => void perform(async () => {
      const started = await window.bridge.startRepositoryContextSpecCheck(workspaceId, { requestKey: crypto.randomUUID(),
        expectedBuildSequence: build.buildRequestSequence, directory, specs });
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
    dismissFinding: (check, candidateId, reason) => void perform(async () => {
      const saved = await window.bridge.dismissRepositoryContextSpecFinding(workspaceId, check.id, candidateId, { reason });
      if (!mounted.current) return;
      setChecks(value => (value ?? []).map(item => item.id === saved.id ? saved : item));
      say('Finding dismissed · nothing was sent to GitHub');
    }),
    // Opening GitHub's form writes nothing; the user files the issue there.
    openIssue: candidate => void perform(async () => {
      if (candidate.draft === null) return;
      const { url, cut } = issueUrl(repository, candidate.draft);
      if (cut) await navigator.clipboard.writeText(candidate.draft.body);
      await window.bridge.openInAppBrowser(url);
      say(cut ? 'The issue form opened with a shortened body · the full report is on the clipboard' : 'The issue form opened on GitHub · file it there');
    }),
    say,
  };
  const open = (directory: string | null) => {
    if (document.activeElement instanceof HTMLElement) opener.current = document.activeElement;
    setDrawer({ directory });
  };
  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Escape' && drawer !== null && !event.defaultPrevented) { setDrawer(null); event.preventDefault(); }
  };
  const run = build.build;
  const task = run?.task ?? null;
  const settle = (operation: () => Promise<ContextBuildViewDto>): void => void perform(async () => { setBuild(await operation()); });
  const ordered = [...checks].sort((left, right) => Date.parse(right.startedAt) - Date.parse(left.startedAt));
  return <div className="rcp" onKeyDown={onKeyDown}>
    <div className="rcp-content" inert={drawer !== null}>
      <div className="rcp-scroll">
        <div className="rcp-inner">
          <div className="rcp-head">
            <div className="rcp-head__main">
              <h1>Spec checks</h1>
              <div className="rcp-head__meta">
                <span className="rcp-repo"><RepoAvatar owner={workspace.monitoredOwner} repo={workspace.monitoredRepo} size={18} />{repository}</span>
              </div>
            </div>
            <div className="rcp-head__actions">
              <button type="button" className="rcp-btn rcp-btn--primary" onClick={() => open(null)}><Icon name="bolt" size={13} />Check against spec</button>
            </div>
          </div>
          {actionError !== null && <div className="rcp-error" role="alert">{actionError}
            <button type="button" className="rcp-link" onClick={() => setActionError(null)}>Dismiss</button></div>}
          {run !== null && run.outcome === 'RUNNING' && <div className="rcp-status" role="status" aria-live="polite"><div className="rcp-status__row">
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
            {task.mutationClaim === 'RECOVERY' ? 'The last check’s agent process needs recovery before another check can start.'
              : task.state === 'OPEN' ? 'The last check hasn’t settled yet. It usually finishes on its own.'
                : 'The last check’s worktree is still retained. Clean it up before starting another check.'}
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
          {ordered.length === 0
            ? <div className="rcp-empty">No spec check yet. A check reads the pages a directory’s code links, decides which are specifications, and reproduces each
              gap it finds with a test. Only a gap whose test fails is reported.</div>
            : ordered.map(check => {
              const findings = openFindings(check).length;
              return <button type="button" key={check.id} className={`rcp-review rcp-tone--${CHECK_STATE[check.state].tone}`}
                onClick={() => open(check.directory)}>
                <span className="rcp-review__main"><span className="rcp-review__title">{check.directory}
                  {findings > 0 && ` · ${plural(findings, 'finding')} to file`}</span>
                  <span className="rcp-review__meta"><span className="rcp-review__kind">{CHECK_STATE[check.state].label}</span><span className="rcp-sep">·</span>
                    <span className="rcp-review__origin">{checkActive(check) ? checkProgress(check) : checkSummary(check)}</span></span></span>
                <span className="rcp-age">{shortDate(check.endedAt ?? check.startedAt, now)}</span>
              </button>;
            })}
        </div>
      </div>
    </div>
    {drawer !== null && <>
      <div className="rcp-scrim" onClick={() => setDrawer(null)} />
      <div ref={panel} className="rcp-drawer" role="dialog" aria-modal="true" aria-label="Spec check" tabIndex={-1}>
        <SpecCheckDrawer key={drawer.directory ?? 'new'} data={data} actions={actions} busy={busy} directory={drawer.directory} onClose={() => setDrawer(null)} />
      </div>
    </>}
    {impact !== null && <ConfirmDialog title="Stop remaining processes" confirmLabel="Stop processes" destructive busy={busy} onCancel={() => setImpact(null)}
      onConfirm={() => settle(async () => {
        const view = await window.bridge.stopRepositoryContextBuildRuntime(workspaceId, impact.taskId, impact.generation);
        setImpact(null);
        return view;
      })}>
      Stop the remaining agent runtime of this check. Its findings and worktree are kept. {impact.liveProcess ? 'An agent process is still running.' : 'No live agent process was reported.'}
    </ConfirmDialog>}
    {toast !== null && <Toast key={toast.id} text={toast.text} onDismiss={() => setToast(null)} />}
  </div>;
}

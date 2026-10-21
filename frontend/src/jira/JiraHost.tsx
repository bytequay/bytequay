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

import { useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { Flag, Play, RefreshCw, X } from 'lucide-react';
import { IconButton } from '@bytequay/ui/cards';
import { relativeTime } from '@bytequay/ui/relativeTime';
import { JiraHostProvider, JiraWorkspaceBadge, useJiraDialog, type JiraHost } from '@bytequay/jira/renderer';
import { useAgentAvailability } from '../agents/availability';
import { TaskComposer, StartingAgentTask, useAgentTaskCreation } from '../agents/AgentTasksView';
import { taskCardState, taskLinks } from '../agents/attention';
import type { AgentTaskDto, WorkspaceDto } from '../types';
import { workspaceAgentTaskRoute } from '../workspaces/model';
import { laneLabel } from '../settings/model';
import { settingsRoute } from '../settings/routes';
import { LayerCovered } from '../Zoom';
import { zoomReference } from '../githubReference';
import PinButton from '../home/PinButton';
import { useRecentTouch } from '../recent/useRecentTouch';
import { completeAttentionVisit, recordAttentionAction, useAttentionVisit } from '../home/useAttentionVisit';
import { jiraAttentionItem } from './attention';
import { jiraIssueLocation, jiraLocation, jiraRoute, type JiraIssue, type JiraIssueDetail, type JiraLocation } from './contracts';
import { EpicTabs, workProgress } from '../workspaces/EpicTabs';
import { EpicWork } from '../workspaces/EpicView';
import { epicIdentity, ticketEpicId, useWorkItems } from '../workspaces/epics';
import { useUnmarkEpic } from '../workspaces/useUnmarkEpic';
import { workItemRoute } from '../workitems/contracts';
import MarkdownBody from '../pulls/MarkdownBody';
import BetterWordsButton from '../pulls/BetterWordsButton';

const message = (error: unknown) => error instanceof Error ? error.message : String(error);
const bridge = () => window.bridge;

/** The development tasks linked tickets name, read locally for the state their card and Details show. */
function useJiraTasks(issues: JiraIssue[], version: string | null): Record<string, AgentTaskDto> {
  const [tasks, setTasks] = useState<Record<string, AgentTaskDto>>({});
  const key = JSON.stringify(issues.flatMap(issue => issue.task === null ? [] : [[issue.task.workspaceId, issue.task.id]]).sort());
  useEffect(() => {
    const links = JSON.parse(key) as [string, string][];
    if (links.length === 0) return;
    let active = true;
    // A bridge without task reads leaves the line without a state instead of failing the page.
    void Promise.allSettled(links.map(([workspaceId, id]) => Promise.resolve().then(() => window.bridge.getAgentTask(id, workspaceId))))
      .then(results => { if (active) setTasks(Object.fromEntries(results.flatMap(result => result.status === 'fulfilled' ? [[result.value.id, result.value] as const] : []))); });
    return () => { active = false; };
  }, [key, version]);
  return tasks;
}

/** Epic and Start development: icons on a card, labelled buttons on a ticket. Without a workspace, Epic asks for one first. */
export function JiraIssueActions({ issue, busy = false, labelled = false, onEpic, onStart, onAssigned }: { issue: JiraIssue; busy?: boolean; labelled?: boolean; onEpic: () => void; onStart: () => void; onAssigned?: (detail: JiraIssueDetail) => void }) {
  const availability = useAgentAvailability(issue.workspace?.id);
  const [assigning, setAssigning] = useState(false);
  const task = issue.task;
  const done = issue.status.category === 'done';
  const canStart = availability.any && task === null && !issue.isEpic && !issue.hasChildren && !done;
  const epicLabel = `${issue.isEpic ? 'Unmark' : 'Mark'} ${issue.key} as Epic`;
  const epicHint = `${epicLabel} in ByteQuay. Its Jira issue type stays unchanged.`;
  const unmark = useUnmarkEpic();
  const epic = () => {
    if (issue.isEpic) unmark.request(issue.workspace?.id ?? null, ticketEpicId(issue), onEpic);
    else if (issue.workspace === null) setAssigning(true);
    else onEpic();
  };
  const viewTask = () => { if (task !== null) window.location.hash = workspaceAgentTaskRoute(task.workspaceId, task.id); };
  const flag = <Flag size={13} fill={issue.isEpic ? 'currentColor' : 'none'} className={issue.isEpic ? 'jira-epic-active' : undefined} aria-hidden="true" />;
  return <><span className="jira-actions" role="group" aria-label={`Actions for ${issue.key}`}>{labelled ? <>
    {(!done || issue.isEpic) && <button type="button" className={`jira-btn${issue.isEpic ? ' is-epic' : ''}`} title={epicHint} aria-pressed={issue.isEpic} disabled={busy} onClick={epic}>{flag}{issue.isEpic ? 'Epic' : 'Mark as Epic'}</button>}
    {task !== null ? <button type="button" className="jira-btn is-link" onClick={viewTask}><Play size={12} aria-hidden="true" />View task</button>
      : canStart && <button type="button" className="jira-btn is-primary" disabled={busy} onClick={onStart}><Play size={12} fill="currentColor" aria-hidden="true" />Start development</button>}
  </> : <>
    {(!done || issue.isEpic) && <IconButton label={epicLabel} title={epicHint} pressed={issue.isEpic} disabled={busy} onClick={epic}>{flag}</IconButton>}
    {task !== null ? <IconButton tone="active" label={`View task for ${issue.key}`} title="View task" onClick={viewTask}><Play size={12} aria-hidden="true" /></IconButton>
      : canStart && <IconButton label={`Use agent to develop ${issue.key}`} title="Start development" disabled={busy} onClick={onStart}><Play size={12} aria-hidden="true" /></IconButton>}
  </>}</span>
  {/* The chooser renders inside a card, whose click would otherwise open the ticket too. */}
  {assigning && <span className="jira-contents" onClick={event => event.stopPropagation()}><JiraWorkspaceDialog issue={issue} onClose={() => setAssigning(false)} onAssigned={detail => { setAssigning(false); onAssigned?.(detail); onEpic(); }} /></span>}
  {unmark.dialog !== null && <span className="jira-contents" onClick={event => event.stopPropagation()}>{unmark.dialog}</span>}</>;
}


/** A linked development task as a dot and a line: its pull request once there is one, otherwise its state. */
function JiraTaskLine({ task }: { task: AgentTaskDto | undefined }) {
  const state = task === undefined ? null : taskCardState(task, null);
  const pullRequest = task === undefined ? undefined : taskLinks(task).find(link => link.source === 'pr');
  const busy = state !== null && state.label !== 'Idle' && state.label !== 'Completed' ? `task ${state.label.toLowerCase()}` : null;
  const head = pullRequest ? `PR ${pullRequest.key}` : state === null ? 'Task' : `Task ${state.label.toLowerCase()}`;
  const tail = pullRequest ? [pullRequest.status?.toLowerCase(), busy] : task === undefined ? [] : [laneLabel(task.laneId), relativeTime(task.createdAt, { suffix: false })];
  const rest = tail.filter(Boolean).join(' · ');
  return <span className="jira-task-line" title={task?.title}>
    <span className={`jira-dot is-${pullRequest?.tone ?? state?.tone ?? 'neutral'}`} aria-hidden="true" />
    <span className="jira-task-line__head">{head}</span>{rest && <span className="jira-task-line__rest">· {rest}</span>}
  </span>;
}


function JiraWorkspaceDialog({ issue, onClose, onAssigned }: { issue: JiraIssue; onClose: () => void; onAssigned: (detail: JiraIssueDetail) => void }) {
  const [workspaces, setWorkspaces] = useState<WorkspaceDto[]>([]);
  const [workspaceId, setWorkspaceId] = useState(issue.workspace?.id ?? '');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const covered = useContext(LayerCovered);
  const dialog = useJiraDialog(covered);
  useEffect(() => {
    let active = true;
    void window.bridge.listWorkspaces().then(value => { if (active) setWorkspaces(value); })
      .catch(cause => { if (active) setError(message(cause)); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);
  const assign = async () => {
    if (busy || loading || !workspaceId || workspaceId === issue.workspace?.id) return;
    setBusy(true); setError(null);
    try {
      const value = await window.bridge.assignJiraWorkspace(jiraIssueLocation(issue), workspaceId);
      recordAttentionAction(`jira:${issue.cloudId}:${issue.id}`, 'update', 1, jiraAttentionItem(value.issue));
      onAssigned(value);
    }
    catch (cause) { setError(message(cause)); setBusy(false); }
  };
  return <dialog ref={dialog} aria-label={`Assign workspace for ${issue.key}`} className="jira-launch jira-assignment" onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}>
    <header><h2>Assign a workspace</h2><button type="button" className="bq-iconbtn" aria-label="Close workspace assignment" disabled={busy} onClick={onClose}><X size={18} /></button></header>
    <p>Choose the ByteQuay workspace for <strong>{issue.key}</strong>. This choice is saved for this ticket.</p>
    <form onSubmit={event => { event.preventDefault(); void assign(); }}>
      <label>Workspace<select required disabled={busy || loading} value={workspaceId} onChange={event => setWorkspaceId(event.target.value)}><option value="" disabled>{loading ? 'Loading workspaces…' : 'Choose the repository for this ticket'}</option>{workspaces.map(value => <option value={value.id} key={value.id}>{value.name} · {value.monitoredOwner}/{value.monitoredRepo}</option>)}</select></label>
      {!loading && !error && workspaces.length === 0 && <p className="jira-muted">Watch a repository from Home to create a ByteQuay workspace, then try again.</p>}
      {error && <div className="ws-error" role="alert">{error}</div>}
      <footer className="jira-actions"><button type="button" className="bq-button" disabled={busy} onClick={onClose}>Cancel</button><button type="submit" className="bq-button bq-button--primary" disabled={busy || loading || !workspaceId || workspaceId === issue.workspace?.id}>{busy ? 'Assigning…' : 'Assign workspace'}</button></footer>
    </form>
  </dialog>;
}

function JiraLaunch({ issue: initialIssue, draft, replaces, send, onClose, onChanged }: {
  issue: JiraIssue; draft?: string; replaces?: string; send?: boolean; onClose: () => void; onChanged: (detail: JiraIssueDetail) => void;
}) {
  const [issue, setIssue] = useState(initialIssue);
  return issue.workspace === null
    ? <JiraWorkspaceDialog issue={issue} onClose={onClose} onAssigned={detail => { setIssue(detail.issue); onChanged(detail); }} />
    : <JiraLaunchContent issue={issue} workspaceId={issue.workspace.id} draft={draft} replaces={replaces} send={send} onClose={onClose} onChanged={detail => { setIssue(detail.issue); onChanged(detail); }} />;
}

function JiraLaunchContent({ issue, workspaceId, draft, replaces, send, onClose, onChanged }: {
  issue: JiraIssue; workspaceId: string; draft?: string; replaces?: string; send?: boolean; onClose: () => void; onChanged: (detail: JiraIssueDetail) => void;
}) {
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const covered = useContext(LayerCovered);
  const dialog = useJiraDialog(covered);
  const refresh = async () => {
    setRefreshing(true); setError(null);
    try {
      const detail = await window.bridge.getJiraIssue(jiraIssueLocation(issue), true);
      setError(detail.error); onChanged(detail);
      if (detail.issue.task !== null && detail.issue.task.id !== replaces) window.location.hash = workspaceAgentTaskRoute(detail.issue.task.workspaceId, detail.issue.task.id);
    }
    catch (cause) { setError(message(cause)); }
    finally { setRefreshing(false); }
  };
  return <dialog ref={dialog} aria-label={`Develop ${issue.key}`} className="jira-launch" onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}>
    <header><h2>Develop {issue.key}</h2><span className="jira-spacer" /><button type="button" className="bq-iconbtn" aria-label="Refresh development source" title="Refresh ticket context" disabled={busy || refreshing} onClick={() => { void refresh(); }}><RefreshCw size={16} /></button><button type="button" className="bq-iconbtn" aria-label="Close development" disabled={busy} onClick={onClose}><X size={18} /></button></header>
    <p>{issue.title}</p><JiraWorkspaceBadge workspace={issue.workspace} />
    {error && <div className="ws-error" role="alert">{error}</div>}
    <JiraLaunchComposer key={workspaceId} issue={issue} workspaceId={workspaceId} draft={draft} replaces={replaces} send={send} refreshing={refreshing} onBusy={setBusy} />
  </dialog>;
}
function JiraLaunchComposer({ issue, workspaceId, draft, replaces, send, onBusy, refreshing }: {
  issue: JiraIssue; workspaceId: string; draft?: string; replaces?: string; send?: boolean; onBusy: (busy: boolean) => void; refreshing: boolean;
}) {
  const onOpenTask = useCallback((id: string) => { window.location.hash = workspaceAgentTaskRoute(workspaceId, id); }, [workspaceId]);
  const creation = useAgentTaskCreation({ workspaceId, bridge, sourceJira: issue, initialDraft: draft, replaces, autoStart: send, onOpenTask, onOpenCreatedTask: task => {
    recordAttentionAction(`jira:${issue.cloudId}:${issue.id}`, 'prompt');
    window.location.hash = workspaceAgentTaskRoute(task.workspaceId, task.id);
  } });
  const { composer } = creation;
  useEffect(() => { onBusy(composer.busy); }, [composer.busy, onBusy]);
  return <>{creation.error && <div className="ws-error" role="alert">{creation.error}</div>}{composer.busy && creation.startup !== null
    ? <StartingAgentTask draft={composer.draft} images={composer.images} laneId={composer.laneId} providers={composer.providers} models={composer.models} selection={composer.selection} startup={creation.startup} />
    : <TaskComposer {...composer} blocker={refreshing ? 'Refreshing ticket context…' : composer.blocker} issueAttachment={creation.issueAttachment} onOpenSettings={() => { window.location.hash = settingsRoute('ws-agents', workspaceId); }} />}</>;
}

function useTicketActivity(issue: JiraIssue | null, location: JiraLocation, covered: boolean) {
  const visit = useAttentionVisit(issue === null ? null : jiraAttentionItem(issue));
  useRecentTouch(issue && issue.id === location.issueId && !covered ? { type: 'JIRA', location } : null);
  return {
    complete: () => { if (issue !== null) void completeAttentionVisit(`jira:${issue.cloudId}:${issue.id}`); },
    onAction: (action: 'comment' | 'update', count: number, next: JiraIssue) => visit.onAction(action, count, jiraAttentionItem(next)),
  };
}

function useEpic(issue: JiraIssue | null, source: ReactNode, showWork: boolean, onWork: (show: boolean) => void) {
  const epic = issue?.isEpic === true && issue.workspace ? { item: jiraAttentionItem(issue), workspaceId: issue.workspace.id } : null;
  const work = useWorkItems(epic?.workspaceId ?? null);
  return {
    available: epic !== null,
    tabs: epic === null ? null : <EpicTabs source={source} progress={workProgress(work, epicIdentity(epic.item))} work={showWork} onWork={onWork} />,
    content: epic === null ? null : <EpicWork epic={epic.item} workspaceId={epic.workspaceId} repository={null} work={work} onShowSource={() => onWork(false)} />,
  };
}

function useTaskLines(issues: JiraIssue[], version: string | null): Record<string, ReactNode> {
  const tasks = useJiraTasks(issues, version);
  return Object.fromEntries(issues.flatMap(issue => issue.task === null ? [] : [[issue.task.id, <JiraTaskLine key={issue.task.id} task={tasks[issue.task.id]} />]]));
}

const jiraHost: JiraHost = {
  get bridge() { return window.bridge; },
  openExternal: url => window.bridge.openExternal(url),
  openInAppBrowser: url => window.bridge.openInAppBrowser(url),
  settingsUrl: settingsRoute('jira'),
  generalSettingsUrl: settingsRoute('general'),
  route: jiraRoute,
  location: jiraLocation,
  openTicket: location => zoomReference({ kind: 'jira', ...location }),
  openTask: (workspaceId, taskId) => { window.location.hash = workspaceAgentTaskRoute(workspaceId, taskId); },
  createLocalChild: (location, id, title, description) => window.bridge.createLocalJiraChild(location, id, title, description),
  openWorkItem: (workspaceId, id) => { window.location.hash = workItemRoute(workspaceId, id); },
  useCovered: () => useContext(LayerCovered),
  useTasks: useTaskLines,
  useTicketActivity,
  useEpic,
  IssueActions: JiraIssueActions,
  WorkspaceDialog: JiraWorkspaceDialog,
  Launch: JiraLaunch,
  Pin: ({ issue }) => <PinButton className="bq-iconbtn" item={{ key: `jira:${issue.cloudId}:${issue.id}`, title: issue.title, reference: issue.key, target: { kind: 'jira', ...jiraIssueLocation(issue) } }} />,
  Markdown: MarkdownBody,
  BetterWords: BetterWordsButton,
};

export function JiraIntegration({ children }: { children: ReactNode }) {
  return <JiraHostProvider host={jiraHost}>{children}</JiraHostProvider>;
}

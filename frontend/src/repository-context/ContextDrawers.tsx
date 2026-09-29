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
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { ConfirmDialog } from '../ConfirmDialog';
import { relativeTime } from '../relativeTime';
import type { ContextRunDto, ContextSourceBoundary, ContextUsageOverviewDto, ContextUsageWindow } from './contracts';
import ContextNoteDrawer, { type NoteTarget } from './ContextNoteDrawer';
import { NoteApplicability, QuestionLine } from './ContextOverview';
import { Chevron, Icon } from './icons';
import {
  clock, currentRun, latestRun, DOCUMENT_LINK_HOPS, needsTopicReselection, nextQuestion, noteArea, OUTCOME, plural, questionRow, runOrigin, shortDate, shortSha, timeOfDay, type QuestionRow,
} from './model';
import type { ContextActions, ContextData } from './RepositoryContextPage';

export type Drawer =
  | { kind: 'note'; target: NoteTarget }
  | { kind: 'plan'; tab: 'plan' | 'sources' | 'activity' }
  | { kind: 'discovery'; tab: 'coverage' | 'activity' }
  | { kind: 'run'; taskId: string }
  | { kind: 'usage' }
  | { kind: 'advanced' };

const LABEL: Record<Drawer['kind'], string> = { note: 'Note', plan: 'Learning plan', discovery: 'Discovery', run: 'Learning run', usage: 'Usage', advanced: 'Advanced configuration' };

/** One side drawer over the still-visible list; it takes focus when it opens and Esc closes it. */
export function ContextDrawers({ drawer, data, actions, busy, onDrawer, onClose }: {
  drawer: Drawer; data: ContextData; actions: ContextActions; busy: boolean; onDrawer: (drawer: Drawer | null) => void; onClose: () => void;
}) {
  const panel = useRef<HTMLDivElement>(null);
  const identity = drawer.kind === 'note' ? `note:${drawer.target.id}` : drawer.kind === 'run' ? `run:${drawer.taskId}` : drawer.kind;
  useEffect(() => { panel.current?.focus(); }, [identity]);
  let body: ReactNode;
  switch (drawer.kind) {
    case 'note':
      body = <ContextNoteDrawer key={drawer.target.id} data={data} actions={actions} busy={busy} target={drawer.target}
        onTarget={target => target === null ? onClose() : onDrawer({ kind: 'note', target })} onClose={onClose} />;
      break;
    case 'plan': body = <PlanDrawer data={data} actions={actions} busy={busy} tab={drawer.tab} onTab={tab => onDrawer({ kind: 'plan', tab })} onClose={onClose} />; break;
    case 'discovery': body = <DiscoveryDrawer data={data} actions={actions} busy={busy} tab={drawer.tab} onTab={tab => onDrawer({ kind: 'discovery', tab })} onClose={onClose} />; break;
    case 'run': body = <RunDrawer data={data} actions={actions} busy={busy} taskId={drawer.taskId} onClose={onClose} />; break;
    case 'usage': body = <UsageDrawer data={data} actions={actions} onClose={onClose} />; break;
    case 'advanced': body = <AdvancedDrawer data={data} actions={actions} busy={busy} onClose={onClose} />; break;
  }
  return <>
    <div className="rcp-scrim" onClick={onClose} />
    <div ref={panel} className="rcp-drawer" role="dialog" aria-modal="true" aria-label={LABEL[drawer.kind]} tabIndex={-1}>{body}</div>
  </>;
}

function Close({ onClose }: { onClose: () => void }) {
  return <button type="button" className="rcp-close" aria-label="Close" onClick={onClose}><Icon name="close" size={14} /></button>;
}

function Tabs<T extends string>({ label, tabs, value, onChange }: { label: string; tabs: ReadonlyArray<readonly [T, string]>; value: T; onChange: (value: T) => void }) {
  return <div className="rcp-seg rcp-seg--drawer" role="tablist" aria-label={label}>
    {tabs.map(([id, text]) => <button type="button" role="tab" key={id} aria-selected={value === id} onClick={() => onChange(id)}>{text}</button>)}
  </div>;
}

function RunCard({ run, defaultOpen, title, meta }: { run: ContextRunDto; defaultOpen: boolean;
  title: string; meta: string }) {
  const [open, setOpen] = useState(defaultOpen);
  const outcome = OUTCOME[run.outcome];
  return <div className="rcp-run">
    <button type="button" className="rcp-run__head" aria-expanded={open} onClick={() => setOpen(value => !value)}>
      <Chevron open={open} size={11} />
      <span className="rcp-run__main"><span className="rcp-run__title">{title}</span><span className="rcp-run__meta">{meta}</span></span>
      <span className={`rcp-chip rcp-tone--${outcome.tone}`}>{outcome.label}</span>
    </button>
    {open && <div className="rcp-run__body">
      {run.activity.length === 0 && <div className="rcp-event"><span className="rcp-event__text">No tool activity was recorded for this run.</span></div>}
      {run.activity.map((line, index) => {
        const tone = line.tone === 'FAILED' ? ' is-failed' : line.tone === 'LIMIT' ? ' is-limit' : '';
        return <div className="rcp-event" key={index}><span className="rcp-event__time">{timeOfDay(line.at)}</span>
          <span className={`rcp-event__mark${tone}`}>{line.tone === 'FAILED' ? '✕' : line.tone === 'LIMIT' ? '■' : '✓'}</span>
          <span className={`rcp-event__text${tone}`}>{line.text}</span></div>;
      })}
      {run.outcome === 'FAILED' && <div className="rcp-run__error">{run.detail ? run.detail + ' ' : ''}No drafts or progress were lost.</div>}
      <div className="rcp-note-caption">Read-only {run.mode === 'DISCOVER' ? 'discovery' : 'learning'} run · messages and steering are unavailable.</div>
    </div>}
  </div>;
}

function RunDrawer({ data, actions, busy, taskId, onClose }: {
  data: ContextData; actions: ContextActions; busy: boolean; taskId: string; onClose: () => void;
}) {
  const run = data.build.runs.find(value => value.taskId === taskId);
  return <>
    <div className="rcp-drawer__head"><div className="rcp-drawer__top">
      <span className="rcp-drawer__name">{run === undefined ? 'Learning run' : runOrigin(run)}</span>
      <span className="rcp-chip rcp-tone--purple">Read-only</span><span className="rcp-spacer" /><Close onClose={onClose} />
    </div></div>
    <div className="rcp-drawer__body">
      {run === undefined ? <div className="rcp-empty">This run is no longer in the retained activity history.</div>
        : <RunCard key={run.taskId} run={run} defaultOpen title={run.question?.text ?? 'Activity'}
          meta={`${shortDate(run.endedAt ?? run.requestedAt, data.now)} · ${plural(run.sourceOperations, 'source operation')} · ${plural(run.noteIds.length, 'proposed note')}`} />}
    </div>
    {run?.outcome === 'RUNNING' && <div className="rcp-drawer__foot">
      <button type="button" className="rcp-btn rcp-btn--danger" disabled={busy || data.build.build?.activeTurn?.cancellationRequestedAt != null}
        onClick={() => void actions.stop(run)}>Stop run</button>
    </div>}
  </>;
}

/* ── Learning plan ───────────────────────────────────────────────────── */

function PlanDrawer({ data, actions, busy, tab, onTab, onClose }: { data: ContextData; actions: ContextActions; busy: boolean; tab: 'plan' | 'sources' | 'activity';
  onTab: (tab: 'plan' | 'sources' | 'activity') => void; onClose: () => void }) {
  const plan = data.planView.plan;
  const running = currentRun(data.build);
  const learning = running !== null && running.mode === 'LEARN' ? running : null;
  const next = nextQuestion(data.planView);
  const [limitsOpen, setLimitsOpen] = useState(false);
  const [discarding, setDiscarding] = useState(false);
  const suggestions = plan?.documentInventory.suggestions ?? [];
  const needsReselection = needsTopicReselection(plan);
  const canRun = data.build.canStart && !busy && data.model.blocker === null && !needsReselection;
  const openRow = (row: QuestionRow) => {
    if (row.link !== null) actions.openDrawer({ kind: 'note', target: { kind: row.link.kind, id: row.link.id } });
  };
  const runs = data.build.runs.filter(run => run.mode === 'LEARN' && (run.question === null || run.question.planId === plan?.id))
    .sort((left, right) => right.sequence - left.sequence);
  const latest = runs[0];
  return <>
    <div className="rcp-drawer__head">
      <div className="rcp-drawer__top"><span className="rcp-drawer__name">Learning plan</span>
        <span className={`rcp-chip rcp-tone--${learning !== null ? 'blue' : 'gray'}`}>{learning !== null ? 'Running' : plan === null ? 'No plan' : 'Saved'}</span>
        <span className="rcp-spacer" /><Close onClose={onClose} /></div>
      <div className="rcp-drawer__sub">{plan === null ? '' : `${plural(plan.topics.length, 'topic')} · `}{data.model.label} · one question per run</div>
      <Tabs label="Learning details" value={tab} onChange={onTab}
        tabs={[['plan', 'Plan'], ['sources', suggestions.length ? `Sources · ${suggestions.length}` : 'Sources'], ['activity', 'Activity']] as const} />
    </div>
    <div className="rcp-drawer__body">
      {tab === 'plan' && <>
        {plan === null ? <div className="rcp-empty">No learning plan is saved. Select suggested topics and Learn selected to start one.</div> : <>
          <div className="rcp-now">
            <div className="rcp-mini-label">{learning?.question ? `NOW · RUN ${learning.sequence}` : next !== null ? 'NEXT' : 'PLAN'}</div>
            <div className="rcp-now__q">{needsReselection ? 'Reselect topics before continuing' : learning?.question?.text ?? next?.text ?? 'All planned questions answered'}</div>
            {(learning?.question || next) && <div className="rcp-now__meta">{learning?.question ? learning.question.topicTitle
              : `${next.topic.title} · ${next.resumes ? 'unfinished — resumes saved progress' : 'not started'}`}</div>}
          </div>
          {plan.topics.map(topic => <div key={topic.id}>
            <div className="rcp-plan-topic">{topic.title}</div>
            <NoteApplicability paths={topic.notePaths} />
            {topic.questions.map((_, index) => <div className="rcp-plan-q" key={index}>
              <QuestionLine row={questionRow(topic, index, data.build.runs, running, data.notebook)} onOpen={openRow} /></div>)}
          </div>)}
          {plan.gaps.length > 0 && <>
            <div className="rcp-mini-label rcp-mt18">OPEN QUESTIONS AND GAPS</div>
            {plan.gaps.map((gap, index) => <div className="rcp-why" key={index}><span className="rcp-why__mark">•</span><span><CodedLine text={gap} /></span></div>)}
          </>}
          <button type="button" className="rcp-how" aria-expanded={limitsOpen} onClick={() => setLimitsOpen(value => !value)}><Chevron open={limitsOpen} size={11} />How runs work</button>
          {limitsOpen && <div className="rcp-limits-text">The agent chooses how many reads it needs within the approved sources. Each run uses the configured agent timeout and can be stopped.
            Links stay within approved documentation roots, up to {DOCUMENT_LINK_HOPS} hops. Continuing doesn’t reset link depth or approve new sites.
            {learning !== null && <div className="rcp-limits-text__now">Run {learning.sequence} so far: {plural(learning.sourceOperations, 'source operation')}
              {' · '}{plural(learning.documentOpens, 'documentation open')}</div>}</div>}
          <div className="rcp-discard">Discarding the plan removes its remaining questions and source inventory; approved notes and drafts stay.{' '}
            <button type="button" className="rcp-link" disabled={busy || !data.build.canStart} onClick={() => setDiscarding(true)}>Discard remaining plan</button></div>
        </>}
      </>}
      {tab === 'sources' && plan !== null && <PlanSources data={data} actions={actions} busy={busy} />}
      {tab === 'sources' && plan === null && <div className="rcp-empty">Sources are approved when you learn selected topics.</div>}
      {tab === 'activity' && <>
        {runs.length === 0 && <div className="rcp-empty">No learning run has started yet.</div>}
        {runs.map(run => <RunCard key={run.taskId} run={run}
          defaultOpen={run.outcome === 'RUNNING' || (learning === null && run === latest)}
          title={`Run ${run.sequence} · ${run.question?.topicTitle ?? `Merged PR #${run.pullRequest ?? ''}`}`}
          meta={`“${run.question?.text ?? 'What’s reusable from this change?'}” · ${shortDate(run.endedAt ?? run.requestedAt, data.now)}`} />)}
      </>}
    </div>
    <div className="rcp-drawer__foot">
      {learning !== null ? <div className="rcp-foot-line">
        <button type="button" className="rcp-btn rcp-btn--danger" disabled={busy} onClick={() => void actions.stop(learning)}>Stop run</button>
        <span className="rcp-foot-line__text">Run {learning.sequence} · {plural(learning.sourceOperations, 'source operation')} · the plan and drafts are kept</span>
      </div> : needsReselection ? <span className="rcp-foot-line__text">Discard this earlier plan and reselect topics with explicit note targets. Approved notes and drafts are kept.</span>
        : next !== null ? <div className="rcp-foot-line">
        <button type="button" className="rcp-btn rcp-btn--secondary" disabled={!canRun} onClick={actions.continueLearning}><Icon name="bolt" size={13} />Continue learning</button>
        <span className="rcp-foot-line__text">{data.model.blocker ?? `Next: ${next.topic.title} · “${next.text}” · 1 run · ${data.model.label}`}</span>
      </div> : <span className="rcp-foot-line__text">{plan === null ? 'Nothing to continue.' : 'Every planned question is answered.'}</span>}
    </div>
    {discarding && plan !== null && <ConfirmDialog title="Discard the remaining plan?" confirmLabel="Discard plan" destructive busy={busy} onCancel={() => setDiscarding(false)}
      onConfirm={() => void actions.perform(async () => {
        actions.setPlanView(await window.bridge.discardRepositoryContextLearningPlan(data.workspaceId,
          { planId: plan.id, expectedRevision: data.notebook.revision, expectedBuildSequence: data.build.buildRequestSequence }));
        setDiscarding(false);
        actions.say('Plan discarded · notes and drafts kept');
        actions.announce();
      })}>
      Its remaining questions and source inventory are removed. Approved notes and drafts stay, and no agent run starts.
    </ConfirmDialog>}
  </>;
}

function PlanSources({ data, actions, busy }: { data: ContextData; actions: ContextActions; busy: boolean }) {
  const plan = data.planView.plan;
  const [boundary, setBoundary] = useState<Record<string, ContextSourceBoundary>>({});
  const [inventoryOpen, setInventoryOpen] = useState<string | null>(null);
  const revision = `${data.baseline?.branch ?? 'revision'}@${shortSha(plan.baselineSha)}`;
  const canChange = data.build.canStart && !busy;
  const decide = (url: string, approve: boolean): void => void actions.perform(async () => {
    const request = { planId: plan.id, suggestedUrl: url };
    actions.setPlanView(approve
      ? await window.bridge.allowRepositoryContextLearningSource(data.workspaceId, { ...request, boundary: boundary[url] ?? 'PAGE',
        expectedRevision: data.notebook.revision, expectedBuildSequence: data.build.buildRequestSequence })
      : await window.bridge.declineRepositoryContextLearningSource(data.workspaceId, request));
    actions.setNotebook(await window.bridge.getRepositoryContext(data.workspaceId));
    actions.say(approve ? 'Source approved · used next time you continue learning · no agent run' : 'Source declined · learning stays within approved sources');
    actions.announce();
  });
  const topicsUsing = (sourceId: string) => plan.topics.filter(topic => topic.sourceIds.includes(sourceId)).map(topic => topic.title).join(', ');
  const runsFor = (topicId: string) => data.build.runs.filter(run => run.question?.topicId === topicId && run.question.planId === plan.id).length;
  return <>
    {plan.documentInventory.suggestions.map(suggestion => {
      const run = data.build.runs.find(value => value.taskId === suggestion.taskId);
      const topic = plan.topics.find(value => value.id === suggestion.topicId);
      const question = topic?.questions[suggestion.question]?.text;
      const root = suggestion.url.endsWith('/');
      const chosen = boundary[suggestion.url] ?? 'PAGE';
      const where = suggestion.url.replace(/^https:\/\//, '');
      return <div className="rcp-prop" key={suggestion.url}>
        <div className="rcp-prop__top"><span className="rcp-chip rcp-tone--orange">Proposed source · needs approval</span>
          <span className="rcp-prop__found">found in {run === undefined ? 'an earlier run' : `run ${run.sequence}`} · {relativeTime(suggestion.foundAt, { now: data.now })}</span></div>
        <div className="rcp-prop__url">{suggestion.url}</div>
        <div className="rcp-prop__why">Linked from {suggestion.parentUrl}{question ? ` while answering “${question}”` : ''}. It’s outside your approved sources, so learning hasn’t opened it.</div>
        <div className="rcp-mini-label">ALLOW</div>
        <label className="rcp-radio"><input type="radio" name={`bound-${suggestion.url}`} checked={chosen === 'PAGE'}
          onChange={() => setBoundary(value => ({ ...value, [suggestion.url]: 'PAGE' }))} />
          <span><span className="rcp-radio__name">This page only</span><span className="rcp-radio__sub is-mono">{suggestion.url}</span></span></label>
        <label className={`rcp-radio${root ? '' : ' is-disabled'}`}><input type="radio" name={`bound-${suggestion.url}`} checked={chosen === 'ROOT'} disabled={!root}
          onChange={() => setBoundary(value => ({ ...value, [suggestion.url]: 'ROOT' }))} />
          <span><span className="rcp-radio__name">Documentation root</span><span className="rcp-radio__sub">{root
            ? <>Pages under <span className="rcp-mono">{where}</span>, up to {DOCUMENT_LINK_HOPS} links from this page. HTTPS only; other paths and sites stay excluded.</>
            : 'Only a link that names a folder (ending in /) can be approved as a root.'}</span></span></label>
        <div className="rcp-prop__actions">
          <button type="button" className="rcp-btn rcp-btn--primary rcp-btn--sm" disabled={!canChange} onClick={() => decide(suggestion.url, true)}>Approve source</button>
          <button type="button" className="rcp-btn rcp-btn--sm" disabled={!canChange} onClick={() => decide(suggestion.url, false)}>Decline</button>
          <span className="rcp-prop__note">{data.build.canStart ? 'No agent run — used next time you continue learning.' : 'Available when the current run ends.'}</span>
        </div>
      </div>;
    })}
    <div className="rcp-label">CODE AVAILABLE TO READ · REPOSITORY AT {revision}</div>
    <div className="rcp-rows">
      {plan.topics.flatMap(topic => {
        const used = runsFor(topic.id);
        const status = used ? `Used in ${plural(used, 'run')}` : 'Not used yet';
        const folders = (topic.paths.length ? topic.paths : ['/']).map(path => ({ target: path, detail: 'Repository folder' }));
        const files = topic.repositoryFiles.slice(0, 5).map(path => ({ target: path, detail: 'Repository file' }));
        const more = topic.repositoryFiles.length - files.length;
        return [...folders, ...files, ...(more > 0 ? [{ target: `+${plural(more, 'more file')}`, detail: 'Repository files' }] : [])].map(row =>
          <div className="rcp-srcrow" key={topic.id + row.target}><div className="rcp-srcrow__line">
            <span className="rcp-srcrow__main"><span className="rcp-srcrow__target">{row.target}</span>
              <span className="rcp-srcrow__sub">{topic.title} · {row.detail} · at {shortSha(plan.baselineSha)}</span></span>
            <span className="rcp-srcrow__status">{status}</span></div></div>);
      })}
    </div>
    <div className="rcp-label rcp-mt18">APPROVED · DOCUMENTATION</div>
    <div className="rcp-rows">
      {plan.documentationSources.length === 0 && <div className="rcp-srcrow"><span className="rcp-srcrow__sub">No documentation source is approved for this plan.</span></div>}
      {plan.documentationSources.map(source => {
        const items = plan.documentInventory.items.filter(item => item.sourceId === source.id);
        const start = items.find(item => item.depth === 0);
        const count = (status: string) => items.filter(item => item.status === status).length;
        const root = source.kind === 'DOCUMENTATION_ROOT';
        const status = root ? '' : start?.status === 'READ' ? 'Opened' : start?.status === 'FAILED' ? 'Failed' : 'Not opened yet';
        const open = inventoryOpen === source.id;
        return <div className="rcp-srcrow" key={source.id}>
          <div className="rcp-srcrow__line"><span className="rcp-srcrow__main"><span className="rcp-srcrow__target">{source.url}</span>
            <span className="rcp-srcrow__sub">{topicsUsing(source.id) || 'Plan'} · {root ? `Web docs root · pages under this path, up to ${DOCUMENT_LINK_HOPS} links from the start page`
              : 'Web page · this page only'}</span></span><span className="rcp-srcrow__status">{status}</span></div>
          {root && <button type="button" className="rcp-inv" aria-expanded={open} onClick={() => setInventoryOpen(open ? null : source.id)}>
            <Chevron open={open} size={10} /><span className="rcp-inv__ok">{count('READ')} opened</span><span>· {count('PENDING')} pending</span>
            <span className="rcp-inv__bad">· {count('FAILED')} failed</span></button>}
          {root && open && <div className="rcp-inv-list">
            {(['READ', 'PENDING', 'FAILED'] as const).map(state => {
              const rows = items.filter(item => item.status === state);
              if (rows.length === 0) return null;
              const label = { READ: 'Opened', PENDING: 'Pending', FAILED: 'Failed' }[state];
              return <div key={state}><span className={state === 'READ' ? 'rcp-inv__ok' : state === 'FAILED' ? 'rcp-inv__bad' : ''}>{label}</span> ·{' '}
                {rows.slice(0, 6).map((item, index) => <span key={item.resolvedUrl}>{index > 0 && ', '}<span className="rcp-mono">{item.relativePage ?? 'start page'}</span>
                  {item.detail && state !== 'READ' ? ` (${item.detail})` : ''}</span>)}{rows.length > 6 && ` and ${rows.length - 6} more`}</div>;
            })}
            {plan.documentInventory.truncated && <div>More links were found than this bounded inventory keeps.</div>}
          </div>}
        </div>;
      })}
    </div>
    <div className="rcp-note-caption">“Opened” means ByteQuay captured the page — not that it was understood or used in a note. Out-of-bounds links become proposals; learning never follows them on its own.</div>
  </>;
}

/* ── Discovery ───────────────────────────────────────────────────────── */

function DiscoveryDrawer({ data, actions, busy, tab, onTab, onClose }: { data: ContextData; actions: ContextActions; busy: boolean; tab: 'coverage' | 'activity';
  onTab: (tab: 'coverage' | 'activity') => void; onClose: () => void }) {
  const { report } = data;
  const running = currentRun(data.build);
  const discovering = running !== null && running.mode === 'DISCOVER' ? running : null;
  const latest = latestRun(data.build.runs, run => run.mode === 'DISCOVER');
  const complete = report !== null && !report.partial && report.coverage.areas.every(area => area.surveyed === area.total);
  const chip = discovering !== null ? ['Running', 'blue'] : report === null ? ['Not run', 'gray'] : complete ? ['Complete survey', 'green'] : ['Partial survey', 'amber'];
  const sha = report?.baselineSha ?? data.baseline?.sha;
  const canRun = data.build.canStart && !busy && data.model.blocker === null;
  const unsurveyed = report?.coverage.unsurveyed ?? [];
  return <>
    <div className="rcp-drawer__head">
      <div className="rcp-drawer__top"><span className="rcp-drawer__name">Discovery</span><span className={`rcp-chip rcp-tone--${chip[1]}`}>{chip[0]}</span>
        <span className="rcp-spacer" /><Close onClose={onClose} /></div>
      <div className="rcp-drawer__sub">{data.baseline?.branch ?? 'revision'}@{sha ? shortSha(sha) : '…'} · {data.model.label} · {discovering !== null
        ? `started ${clock(data.now - Date.parse(discovering.requestedAt))} ago` : report !== null ? shortDate(report.createdAt, data.now) : 'not run yet'}</div>
      <Tabs label="Discovery details" value={tab} onChange={onTab} tabs={[['coverage', 'Coverage'], ['activity', 'Activity']] as const} />
    </div>
    <div className="rcp-drawer__body">
      {tab === 'coverage' && <>
        {report === null ? <div className="rcp-empty">Discovery hasn’t run yet, so nothing has been surveyed.</div> : <>
          {report.coverage.areas.length > 0 && <>
            <div className="rcp-drawer-label">MODULE DIRECTORIES SURVEYED</div>
            <div className="rcp-rows">{report.coverage.areas.map(area => <div className="rcp-cov" key={area.path}>
              <span className={`rcp-cov__mark${area.surveyed < area.total ? ' is-partial' : ''}`}>{area.surveyed < area.total ? '◐' : '✓'}</span>
              <span className="rcp-cov__path">{area.path}</span><span className="rcp-cov__count">{area.surveyed} of {plural(area.total, 'directory', 'directories')}</span></div>)}</div>
          </>}
          {unsurveyed.length > 0 && <>
            <div className="rcp-drawer-label">NOT SURVEYED · {unsurveyed.length}{report.coverage.unsurveyedTruncated ? '+' : ''}</div>
            <div className="rcp-chips">{unsurveyed.slice(0, 12).map(path => <span className="rcp-path-chip" key={path}>{path}</span>)}
              {unsurveyed.length > 12 && <span className="rcp-section-meta">+{unsurveyed.length - 12} more</span>}</div>
          </>}
          {report.remaining.length > 0 && <>
            <div className="rcp-drawer-label">REMAINING WORK</div>
            {report.remaining.map(item => <div className="rcp-why" key={item}><span className="rcp-why__mark">•</span><span><CodedLine text={item} /></span></div>)}
          </>}
          <div className="rcp-note-caption">Suggestions cover surveyed areas only. Discovery read structure and selected files, not all code — this isn’t a complete map of the project.</div>
        </>}
      </>}
      {tab === 'activity' && <>
        {latest === null ? <div className="rcp-empty">No discovery run is retained.</div> : <>
          {latest.activity.length === 0 && <div className="rcp-event"><span className="rcp-event__text">No tool activity was recorded yet.</span></div>}
          {latest.activity.map((line, index) => {
            const tone = line.tone === 'FAILED' ? ' is-failed' : line.tone === 'LIMIT' ? ' is-limit' : '';
            return <div className="rcp-event" key={index}><span className="rcp-event__time">{timeOfDay(line.at)}</span>
              <span className={`rcp-event__mark${tone}`}>{line.tone === 'FAILED' ? '✕' : line.tone === 'LIMIT' ? '■' : '✓'}</span>
              <span className={`rcp-event__text${tone}`}>{line.text}</span></div>;
          })}
          <div className="rcp-note-caption">Read-only discovery run · messages and steering are unavailable.</div>
        </>}
      </>}
    </div>
    <div className="rcp-drawer__foot">
      {discovering !== null ? <button type="button" className="rcp-btn rcp-btn--danger" disabled={busy} onClick={() => void actions.stop(discovering)}>Stop discovery</button>
        : report !== null && !complete ? <div className="rcp-foot-line">
          <button type="button" className="rcp-btn rcp-btn--secondary" disabled={!canRun || !report.partial} onClick={() => actions.discover(true)}>
            <Icon name="bolt" size={13} />Discover remaining areas</button>
          <span className="rcp-foot-line__text">{data.model.blocker ?? (report.partial ? `Starts 1 discovery run from the saved position · ${data.model.label}`
            : 'The report is complete; directories left unsurveyed had no topic worth adding.')}</span>
        </div> : <span className="rcp-foot-line__text">{report === null ? 'Start discovery from the Overview.' : 'Every module directory was surveyed.'}</span>}
    </div>
  </>;
}

function CodedLine({ text }: { text: string }) {
  return <>{text.split('`').map((part, index) => index % 2 === 1 ? <code key={index} className="rcp-code">{part}</code> : part)}</>;
}

/* ── Usage ───────────────────────────────────────────────────────────── */

function UsageDrawer({ data, actions, onClose }: { data: ContextData; actions: ContextActions; onClose: () => void }) {
  const [period, setPeriod] = useState<ContextUsageWindow>('30d');
  const [overview, setOverview] = useState<ContextUsageOverviewDto | null>(period === '30d' ? data.usage : null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    setError(null);
    void window.bridge.getRepositoryContextUsage(data.workspaceId, period).then(value => { if (live) setOverview(value); })
      .catch(cause => { if (live) setError(cause instanceof Error ? cause.message : String(cause)); });
    return () => { live = false; };
  }, [data.workspaceId, period]);
  const summary = overview?.summary;
  const rows = [...(overview?.entries ?? [])].filter(row => row.currentVersion !== null)
    .sort((left, right) => right.foundTasks - left.foundTasks).slice(0, 8);
  return <>
    <div className="rcp-drawer__head">
      <div className="rcp-drawer__top"><span className="rcp-drawer__name">Usage</span><span className="rcp-spacer" /><Close onClose={onClose} /></div>
      <div className="rcp-drawer__sub">{overview?.trackingSince ? `Recorded lookups and reads in retained tasks since tracking began ${shortDate(overview.trackingSince, data.now)}`
        : 'Recorded lookups and reads in retained tasks'}</div>
      <Tabs label="Usage period" value={period} onChange={value => { setOverview(null); setPeriod(value); }} tabs={[['30d', 'Last 30 days'], ['all', 'All retained']] as const} />
    </div>
    <div className="rcp-drawer__body">
      {error !== null && <div className="rcp-drawer-error" role="alert">{error}</div>}
      {overview === null && error === null && <div className="rcp-loading" role="status">Loading usage…</div>}
      {summary !== undefined && <>
        <div className="rcp-tiles">
          <div className="rcp-tile"><div className="rcp-tile__label">Development tasks</div><div className="rcp-tile__n">{summary.eligibleDevelopmentTasks}</div>
            <div className="rcp-tile__sub">{summary.developmentTasksFoundOrOpened} found or opened a note</div></div>
          <div className="rcp-tile"><div className="rcp-tile__label">PR reviews</div><div className="rcp-tile__n">{summary.eligibleReviewRuns}</div>
            <div className="rcp-tile__sub">{summary.reviewRunsFoundOrOpened} found or opened a note</div></div>
          <div className="rcp-tile"><div className="rcp-tile__label">Context off</div><div className="rcp-tile__n">{summary.contextOffTasks}</div>
            <div className="rcp-tile__sub">{summary.contextOffTasks === 1 ? 'task or review' : 'tasks or reviews'}</div></div>
        </div>
        <div className="rcp-usage-cols"><span>NOTE</span><span>FOUND</span><span>OPENED</span><span>LAST</span></div>
        <div className="rcp-rows">
          {rows.length === 0 && <div className="rcp-srcrow"><span className="rcp-srcrow__sub">No note was found or opened in this period.</span></div>}
          {rows.map(row => <button type="button" className="rcp-usage-row" key={row.entryId} onClick={() => actions.openDrawer({ kind: 'note', target: { kind: 'entry', id: row.entryId } })}>
            <span className="rcp-usage-row__title">{row.title} <span className="rcp-usage-row__version">v{row.currentVersion}</span></span>
            <span>{row.foundTasks}</span><span>{row.openedTasks}</span>
            <span className="rcp-usage-row__last">{row.lastConfirmedUseAt ? shortDate(row.lastConfirmedUseAt, data.now) : '—'}</span></button>)}
        </div>
      </>}
      <div className="rcp-note-caption">Counts show that a task found or opened a note. They don’t show whether the agent applied it, or that it improved a review.</div>
    </div>
  </>;
}

/* ── Advanced configuration ──────────────────────────────────────────── */

function AdvancedDrawer({ data, actions, busy, onClose }: { data: ContextData; actions: ContextActions; busy: boolean; onClose: () => void }) {
  const { notebook } = data;
  const [url, setUrl] = useState('');
  const valid = /^https:\/\/[^\s?#]+$/.test(url.trim());
  const sources = notebook.sources.map(({ id, kind, url: address }) => ({ id, kind, url: address }));
  const save = (next: typeof sources, message: string): void => void actions.mutate(revision =>
    window.bridge.configureRepositoryContext(data.workspaceId, { expectedRevision: revision, sources: next }), message);
  return <>
    <div className="rcp-drawer__head">
      <div className="rcp-drawer__top"><span className="rcp-drawer__name">Advanced configuration</span><span className="rcp-spacer" /><Close onClose={onClose} /></div>
      <div className="rcp-drawer__sub">Not needed for normal use — discovery proposes areas and sources. Changes here never start a run.</div>
    </div>
    <div className="rcp-drawer__body">
      <div className="rcp-drawer-label">DOCUMENTATION SOURCES</div>
      <div className="rcp-rows">
        {notebook.sources.length === 0 && <div className="rcp-adv-row"><span className="rcp-adv-row__kind">No documentation source is saved.</span></div>}
        {notebook.sources.map(source => <div className="rcp-adv-row" key={source.id}>
          <span className="rcp-adv-row__main rcp-mono">{source.url}</span>
          <span className="rcp-adv-row__kind">{source.kind === 'DOCUMENTATION_ROOT' ? `Root · ${DOCUMENT_LINK_HOPS} link hops` : 'Page'}</span>
          <button type="button" className="rcp-link" disabled={busy} onClick={() => save(sources.filter(value => value.id !== source.id), 'Source removed · captured evidence is kept')}>Remove</button>
        </div>)}
      </div>
      <form className="rcp-adv-add" onSubmit={event => {
        event.preventDefault();
        if (!valid || busy) return;
        const address = url.trim();
        save([...sources, { id: 'docs:' + crypto.randomUUID(), kind: address.endsWith('/') ? 'DOCUMENTATION_ROOT' : 'DOCUMENTATION_PAGE', url: address }], 'Source saved · nothing was fetched');
        setUrl('');
      }}>
        <input value={url} onChange={event => setUrl(event.target.value)} placeholder="https://…" aria-label="Documentation URL" />
        <button type="submit" className="rcp-btn rcp-btn--secondary rcp-btn--sm" disabled={!valid || busy}>Add source</button>
      </form>
      <div className="rcp-hint">Saved without fetching. Public HTTPS pages or roots only; a root ends in /.</div>
      <div className="rcp-drawer-label">AREAS AND PATHS</div>
      <div className="rcp-rows">
        {notebook.scopes.map(scope => {
          const area = noteArea(notebook, scope.id);
          return <div className="rcp-adv-row" key={scope.id}>
            <span className="rcp-adv-row__main"><span className="rcp-adv-row__name">{area.label}</span>
              <span className="rcp-adv-row__path">{scope.match.kind === 'REPOSITORY' ? '/' : [...scope.match.pathPrefixes, ...scope.match.terms].join(' · ')}</span></span>
            <span className="rcp-adv-row__kind">{scope.match.kind === 'REPOSITORY' ? 'Built in' : scope.discoveryEvidenceRef !== null ? 'From discovery' : 'Added manually'}
              {!scope.enabled && ' · disabled'}</span>
          </div>;
        })}
      </div>
    </div>
  </>;
}

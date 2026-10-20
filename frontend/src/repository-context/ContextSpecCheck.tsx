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
import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { remoteErrorMessage } from '../remoteError';
import { settingsRoute } from '../settings/routes';
import { specCheckDirectory, type ContextBuildViewDto, type ContextSpecCandidateDto, type ContextSpecCheckDto, type ContextSpecModuleDto,
  type ContextSpecSourceDto } from './contracts';
import { Icon } from './icons';
import { activeCheck, checkActive, checkProgress, plural, REPRODUCTION_LIMITS, shortDate, shortSha } from './model';
import {
  CHECK_STATE, checkSummary, codeLink, defaultPicks, DISMISS_REASONS, findingFile, MAX_SPECS, moduleRows, pageLabel, specAddress, TYPE_LABEL, TYPE_TONE,
} from './specChecks';

export type SpecCheckData = {
  workspaceId: string; repository: string; checks: ContextSpecCheckDto[]; build: ContextBuildViewDto; model: { label: string; blocker: string | null }; now: number;
};

export type SpecCheckActions = {
  /** Starts a check of the pages the user kept and added, reproducing at most maxReproductions candidates. */
  start: (directory: string, specs: string[], maxReproductions: number) => void;
  stop: (taskId: string) => void;
  /** With a rule, the user's words are also remembered for the finding's file. */
  dismissFinding: (check: ContextSpecCheckDto, candidateId: string, reason: string, rule: string | null) => void;
  openIssue: (check: ContextSpecCheckDto, candidate: ContextSpecCandidateDto) => void;
  say: (text: string) => void;
};

/** What a button that starts a check starts and on which model, or why it cannot start. */
export function RunLine({ children, blocked = false, workspaceId }: { children: ReactNode; blocked?: boolean; workspaceId: string }) {
  return <div className={`rcp-runline${blocked ? ' is-blocked' : ''}`}><Icon name="bolt" size={12} className="rcp-bolt" />
    <span>{children}{blocked && <> · <a href={settingsRoute('ws-agents', workspaceId)}>Workspace agent settings</a></>}</span></div>;
}

/** One directory's spec check: its latest report, and what a new check of it reads. Without a directory it asks for one first. */
export function SpecCheckDrawer({ data, actions, busy, directory, onClose }: {
  data: SpecCheckData; actions: SpecCheckActions; busy: boolean; directory: string | null; onClose: () => void;
}) {
  const availability = useAgentAvailability(data.workspaceId);
  const [chosen, setChosen] = useState<string | null>(directory);
  const check = chosen === null ? null : data.checks.find(value => value.directory === chosen) ?? null;
  const running = activeCheck(data.checks);
  const run = data.build.build;
  return <>
    <div className="rcp-drawer__head"><div className="rcp-drawer__top">
      <span className="rcp-drawer__name">Spec check{chosen === null ? '' : ` · ${chosen}`}</span>
      {check !== null && <span className={`rcp-chip rcp-tone--${CHECK_STATE[check.state].tone}`}>{CHECK_STATE[check.state].label}</span>}
      <span className="rcp-spacer" />
      <button type="button" className="rcp-close" aria-label="Close" onClick={onClose}><Icon name="close" size={14} /></button>
    </div>
      <div className="rcp-drawer__sub">Compares a directory’s code with the specifications it implements. Only a gap whose test fails is reported.</div></div>
    <div className="rcp-drawer__body">
      {!availability.development && <AgentSetupNotice workspaceId={data.workspaceId} />}
      {chosen === null && availability.development && <ChooseDirectory workspaceId={data.workspaceId} onChoose={setChosen} />}
      {check !== null && <Report data={data} check={check} />}
      {chosen !== null && running === null && availability.development && <StartCheck key={chosen} data={data} actions={actions} busy={busy} directory={chosen} previous={check} />}
      {chosen !== null && running !== null && running.id !== check?.id && <div className="rcp-note-caption">The check of {running.directory} is running.
        A new check can start when it ends.</div>}
    </div>
    {check !== null && check.id === running?.id && run !== null && run.checkId === check.id && run.outcome === 'RUNNING' && <div className="rcp-drawer__foot">
      <button type="button" className="rcp-btn rcp-btn--danger" disabled={busy || run.activeTurn?.cancellationRequestedAt != null}
        onClick={() => actions.stop(run.taskId)}>Stop check</button>
    </div>}
  </>;
}

/** The modules a check can start from, those whose own code cites one page in many sections first; typing filters them or names any directory. */
function ChooseDirectory({ workspaceId, onChoose }: { workspaceId: string; onChoose: (directory: string) => void }) {
  const [modules, setModules] = useState<ContextSpecModuleDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState('');
  useEffect(() => {
    let live = true;
    void Promise.resolve().then(() => window.bridge.getRepositoryContextSpecModules(workspaceId))
      .then(found => { if (live) setModules(found); }).catch(cause => { if (live) setError(remoteErrorMessage(cause)); });
    return () => { live = false; };
  }, [workspaceId]);
  const rows = moduleRows(modules ?? [], filter);
  const typed = specCheckDirectory(filter);
  // A directory inside a module, or one the list leaves out, is checked when typed as a path.
  const other = typed !== null && filter.includes('/') && !(modules ?? []).some(module => module.directory === typed) ? typed : null;
  const first = rows.at(0)?.directory ?? other;
  return <section className="rcp-choose" aria-label="Choose a module">
    <div className="rcp-drawer-label">MODULE</div>
    <form className="rcp-q-add" onSubmit={(event: FormEvent) => { event.preventDefault(); if (first != null) onChoose(first); }}>
      <input aria-label="Filter modules" placeholder="Filter modules, or type a directory path" value={filter} maxLength={1000} autoFocus
        onChange={event => setFilter(event.target.value)} />
    </form>
    {error !== null && <div className="rcp-drawer-error">The repository’s modules could not be listed: {error}</div>}
    {modules === null && error === null && <div className="rcp-note-caption">Listing the repository’s modules…</div>}
    <div className="rcp-modules">
      {rows.map(module => <button type="button" className="rcp-module" key={module.directory} title={module.page ?? undefined}
        onClick={() => onChoose(module.directory)}>
        <span className="rcp-src__target">{module.directory}</span>
        {module.page !== null && <span className="rcp-src__sub">{pageLabel(module.page)} · {plural(module.sections, 'section')}</span>}
      </button>)}
      {other !== null && <button type="button" className="rcp-module" onClick={() => onChoose(other)}>
        <span className="rcp-src__target">{other}</span><span className="rcp-src__sub">Check this directory</span></button>}
    </div>
  </section>;
}

/**
 * What a check reads: the linked pages the user keeps, at first those the code cites the way it cites a specification, and the pages the
 * user adds. The user also picks how many candidates it may reproduce, since each reproduction is a build.
 */
function StartCheck({ data, actions, busy, directory, previous }: {
  data: SpecCheckData; actions: SpecCheckActions; busy: boolean; directory: string; previous: ContextSpecCheckDto | null;
}) {
  const [sources, setSources] = useState<ContextSpecSourceDto[] | null>(null);
  const [suggested, setSuggested] = useState<string[]>([]);
  const [picks, setPicks] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [added, setAdded] = useState<string[]>([]);
  const [draft, setDraft] = useState('');
  const [limit, setLimit] = useState(REPRODUCTION_LIMITS[0]);
  useEffect(() => {
    let live = true;
    void Promise.resolve().then(() => window.bridge.getRepositoryContextSpecSources(data.workspaceId, directory)).then(found => {
      if (!live) return;
      setSources(found);
      const linked = (url: string) => found.some(source => source.url === url);
      const initial = defaultPicks(found, previous);
      setSuggested(initial);
      setPicks(initial);
      // A spec the user added to the last check is not in the code's links; it is offered again.
      setAdded(previous === null ? [] : previous.specs.filter(url => !linked(url)));
    }).catch(cause => { if (live) setError(remoteErrorMessage(cause)); });
    return () => { live = false; };
  }, [data.workspaceId, directory, previous]);
  const known = sources ?? [];
  // Rows keep their place while the user picks: the suggested ones first, the other links folded below them.
  const featured = known.filter(source => suggested.includes(source.url));
  const others = known.filter(source => !suggested.includes(source.url));
  const picked = known.filter(source => picks.includes(source.url)).map(source => source.url);
  const specs = [...picked, ...added];
  const address = specAddress(draft);
  const add = (event: FormEvent) => {
    event.preventDefault();
    if (address === null) return;
    if (!specs.includes(address)) setAdded(value => [...value, address]);
    setDraft('');
  };
  const toggle = (url: string) => setPicks(value => value.includes(url) ? value.filter(item => item !== url) : [...value, url]);
  const row = (source: ContextSpecSourceDto) => <label className="rcp-spec-page rcp-spec-pick" key={source.url}>
    <input type="checkbox" checked={picks.includes(source.url)} onChange={() => toggle(source.url)} />
    <span><span className="rcp-src__target">{source.url}</span><span className="rcp-src__sub">{sourceLine(source)}</span></span></label>;
  const blocked = data.model.blocker !== null;
  const canStart = data.build.canStart && !busy && !blocked && sources !== null && specs.length <= MAX_SPECS;
  return <section aria-label="Check against spec">
    <div className="rcp-drawer-label">{previous === null ? 'WHAT THE CHECK READS' : 'CHECK AGAIN'}</div>
    {error !== null && <div className="rcp-drawer-error">The pages the directory’s code links could not be listed: {error}</div>}
    {sources === null && error === null && <div className="rcp-note-caption">Finding the pages the directory’s code links…</div>}
    {sources !== null && <div className="rcp-spec-summary">{readsLine(picked.length, known.length, added.length)}</div>}
    {previous !== null && previous.checkedAgainst.length > 0 && <div className="rcp-note-caption">The last check used {previous.checkedAgainst.join(', ')} as
      {previous.checkedAgainst.length === 1 ? ' its specification' : ' specifications'}.</div>}
    {featured.map(row)}
    {others.length > 0 && <details>
      <summary className="rcp-how">Other links in the directory’s code · {others.length}</summary>
      {others.map(row)}
    </details>}
    {added.map(url => <div className="rcp-spec-page" key={url}><span className="rcp-src__target">{url}</span>
      <span className="rcp-src__sub">Added by you · <button type="button" className="rcp-link" aria-label={`Remove ${url}`}
        onClick={() => setAdded(value => value.filter(item => item !== url))}>Remove</button></span></div>)}
    <form className="rcp-q-add" onSubmit={add}>
      <input aria-label="Spec address" placeholder="Add a spec the code doesn’t link · https://…" value={draft} maxLength={1400}
        onChange={event => setDraft(event.target.value)} />
      <button type="submit" className="rcp-btn rcp-btn--secondary" disabled={address === null}>Add spec</button>
    </form>
    {specs.length > MAX_SPECS && <div className="rcp-drawer-error">A check reads at most {MAX_SPECS} pages; uncheck or remove some.</div>}
    {previous !== null && previous.next.length > 0 && <div className="rcp-note-caption">Continues where the last check stopped: {previous.next.join(', ')}.</div>}
    <div className="rcp-q-deeper">
      <span className="rcp-start">
        <button type="button" className="rcp-btn rcp-btn--primary" disabled={!canStart} onClick={() => actions.start(directory, specs, limit)}>
          <Icon name="bolt" size={13} />Check against spec</button>
        <label className="rcp-limit" title="Each reproduction writes a test and runs a build">Reproduce up to <select aria-label="Reproductions" value={limit}
          onChange={event => setLimit(Number(event.target.value))}>{REPRODUCTION_LIMITS.map(value => <option key={value} value={value}>{value}</option>)}
        </select></label>
      </span>
      <RunLine workspaceId={data.workspaceId} blocked={blocked}>{data.model.blocker
        ?? `Starts 1 task · a check turn, then up to ${limit} reproduction turns that write and run tests in its worktree · ${data.model.label}`}</RunLine>
    </div>
  </section>;
}

function readsLine(picked: number, linked: number, added: number): string {
  if (picked + added === 0) {
    return linked === 0 ? 'The directory’s code links no page. The check proposes the specification it implements, or add its address.'
      : 'No page is picked. The check proposes the specification the directory implements, or pick or add one.';
  }
  const read = [picked > 0 ? `${picked} of the ${plural(linked, 'page')} the directory’s code links` : '', added > 0 ? `${plural(added, 'page')} you added` : '']
    .filter(Boolean).join(' and ');
  return `The check reads ${read}.`;
}

function sourceLine(source: ContextSpecSourceDto): string {
  const kind = source.kind === 'REFERENCE_CODE' ? 'Reference code · for comparison, never a rule' : 'Linked page';
  if (source.links === 0) return kind;
  return `${kind} · cited ${plural(source.links, 'time')}${source.sections.length > 0 ? ` · ${plural(source.sections.length, 'section')}` : ''}`;
}

function Report({ data, check }: { data: SpecCheckData; check: ContextSpecCheckDto }) {
  const of = (status: ContextSpecCandidateDto['status']) => check.candidates.filter(candidate => candidate.status === status);
  const waiting = check.candidates.filter(candidate => candidate.status === 'PENDING' || candidate.status === 'REPRODUCING');
  const dismissed = of('CONFIRMED').filter(candidate => candidate.dismissed !== null);
  return <section aria-label="Spec check report">
    <div className="rcp-drawer-label">LATEST CHECK</div>
    <div className="rcp-spec-summary">{checkActive(check) ? checkProgress(check) : checkSummary(check)}</div>
    <div className="rcp-note-caption">Code at {shortSha(check.baselineSha)} · started {shortDate(check.startedAt, data.now)}
      {check.endedAt !== null && ` · ended ${shortDate(check.endedAt, data.now)}`} · read {plural(check.specs.length, 'page')}</div>
    {check.checkedAgainst.length > 0
      ? <div className="rcp-note-caption">Checked against {check.checkedAgainst
        .map(url => check.specs.includes(url) ? url : `${url} (proposed by the check)`).join(', ')}.</div>
      : !checkActive(check) && check.candidates.length === 0 && <div className="rcp-note-caption">None of the pages it read states rules the code
        must follow. Add the address of the specification and check again.</div>}
    {check.detail !== null && check.state !== 'STOPPED' && <div className="rcp-drawer-error">{check.detail}</div>}
    {check.covered.length > 0 && <div className="rcp-note-caption">Covered: {check.covered.join(', ')}.</div>}
    {check.next.length > 0 && <div className="rcp-note-caption">The next check continues with: {check.next.join(', ')}.</div>}
    {waiting.length > 0 && <>
      <div className="rcp-drawer-label">IN PROGRESS · {waiting.length}</div>
      {waiting.map(candidate => <div className="rcp-spec-row" key={candidate.id}>
        <span className={`rcp-chip rcp-tone--${TYPE_TONE[candidate.type]}`}>{TYPE_LABEL[candidate.type]}</span>
        <span className="rcp-spec-row__title">{candidate.title}</span>
        <span className="rcp-spec-row__meta">{candidate.status === 'REPRODUCING' ? 'Reproducing now' : 'Waiting'}</span>
      </div>)}
    </>}
    <Others title="ALREADY REPORTED" candidates={of('ALREADY_REPORTED')} line={candidate => candidate.duplicate === null ? ''
      : `#${candidate.duplicate.number} ${candidate.duplicate.title} · ${candidate.duplicate.state}`}
      open={candidate => candidate.duplicate === null ? null : candidate.duplicate.url} />
    <Others title="NOT REPRODUCED" candidates={of('NOT_REPRODUCED')} line={candidate => candidate.reason ?? ''} open={() => null} />
    <Others title="REFUTED · THE CODE FOLLOWS THE RULE" candidates={of('REFUTED')} line={candidate => candidate.reason ?? ''} open={() => null} />
    <Others title="DISMISSED" candidates={dismissed} line={candidate => candidate.dismissed ?? ''} open={() => null} />
  </section>;
}

/** A confirmed gap: the rule, the code, the failing test, and the issue a click opens on GitHub for the user to file. */
export function SpecFinding({ data, actions, busy, check, candidate }: {
  data: SpecCheckData; actions: SpecCheckActions; busy: boolean; check: ContextSpecCheckDto; candidate: ContextSpecCandidateDto;
}) {
  const [dismissing, setDismissing] = useState(false);
  const [reason, setReason] = useState<string>(DISMISS_REASONS[0]);
  const [remember, setRemember] = useState(false);
  const [rule, setRule] = useState('');
  const file = findingFile(check.directory, candidate.code);
  const open = (url: string): void => void window.bridge.openInAppBrowser(url);
  return <div className="rcp-finding">
    <div className="rcp-finding__head"><span className={`rcp-chip rcp-tone--${TYPE_TONE[candidate.type]}`}>{TYPE_LABEL[candidate.type]}</span>
      <span className="rcp-finding__title">{candidate.draft?.title ?? candidate.title}</span></div>
    <blockquote className="rcp-finding__rule">{candidate.rule}</blockquote>
    <a className="rcp-finding__spec" href={candidate.specUrl} onClick={event => { event.preventDefault(); open(candidate.specUrl); }}>{candidate.specUrl}</a>
    <div className="rcp-finding__codes">{candidate.code.map(location => {
      const url = codeLink(data.repository, check.baselineSha, location);
      return <a key={location} className="rcp-path-chip rcp-finding__code" href={url} onClick={event => { event.preventDefault(); open(url); }}>{location}</a>;
    })}</div>
    {candidate.test !== null && <Disclosure label={`Failing test · exit ${candidate.test.exitCode} · ${candidate.test.path.slice(candidate.test.path.lastIndexOf('/') + 1)}`}>
      <div className="rcp-mini-label">FILE</div><pre className="rcp-finding__pre">{candidate.test.path}</pre>
      <div className="rcp-mini-label">COMMAND</div><pre className="rcp-finding__pre">{candidate.test.command}</pre>
      <div className="rcp-mini-label">TEST</div><pre className="rcp-finding__pre">{candidate.test.source}</pre>
      <div className="rcp-mini-label">OUTPUT</div><pre className="rcp-finding__pre">{candidate.test.output}</pre>
    </Disclosure>}
    {candidate.draft !== null && <Disclosure label={`Work item description${candidate.draft.labels.length > 0 ? ` · ${candidate.draft.labels.join(', ')}` : ''}`}>
      <pre className="rcp-finding__pre">{candidate.draft.body}</pre></Disclosure>}
    {dismissing
      ? <div className="rcp-finding__dismiss">
        <div className="rcp-finding__actions">
          <select aria-label="Why dismiss" value={reason} onChange={event => setReason(event.target.value)}>
            {DISMISS_REASONS.map(value => <option key={value}>{value}</option>)}</select>
          {file !== null && <label className="rcp-remember" title="Saves your words as a rule agents get for this file">
            <input type="checkbox" checked={remember} onChange={event => setRemember(event.target.checked)} />Remember for <code>{file}</code></label>}
          <button type="button" className="rcp-btn rcp-btn--secondary rcp-btn--xs" disabled={busy || (remember && rule.trim() === '')}
            onClick={() => { actions.dismissFinding(check, candidate.id, reason, remember ? rule.trim() : null); setDismissing(false); }}>Dismiss</button>
          <button type="button" className="rcp-link" onClick={() => setDismissing(false)}>Cancel</button>
        </div>
        {remember && <textarea className="rcp-remember__rule" rows={2} aria-label="Rule to remember" value={rule} onChange={event => setRule(event.target.value)}
          placeholder="Why this is fine here, as a rule agents get for this file, such as a deviation kept on purpose" />}
      </div>
      : <div className="rcp-finding__actions">
        <button type="button" className="rcp-btn rcp-btn--primary rcp-btn--xs" disabled={busy || candidate.draft === null} onClick={() => actions.openIssue(check, candidate)}>
          <Icon name="link" size={12} />Open local work item</button>
        {candidate.draft !== null && <button type="button" className="rcp-btn rcp-btn--secondary rcp-btn--xs"
          onClick={() => void navigator.clipboard.writeText(candidate.draft?.body ?? '').then(() => actions.say('Issue body copied'))}>Copy body</button>}
        <button type="button" className="rcp-link" onClick={() => setDismissing(true)}>Dismiss</button>
        <span className="rcp-finding__hint">Saved locally with the full finding and failing test. Publish later from Work items.</span>
      </div>}
  </div>;
}

function Disclosure({ label, children }: { label: string; children: ReactNode }) {
  return <details className="rcp-finding__more">
    <summary className="rcp-how">{label}</summary>
    <div className="rcp-finding__body">{children}</div>
  </details>;
}

function Others({ title, candidates, line, open }: { title: string; candidates: ContextSpecCandidateDto[]; line: (candidate: ContextSpecCandidateDto) => string;
  open: (candidate: ContextSpecCandidateDto) => string | null }) {
  if (candidates.length === 0) return null;
  return <details>
    <summary className="rcp-drawer-label rcp-spec-toggle">{title} · {candidates.length}</summary>
    {candidates.map(candidate => {
      const url = open(candidate);
      return <div className="rcp-spec-row" key={candidate.id}>
        <span className={`rcp-chip rcp-tone--${TYPE_TONE[candidate.type]}`}>{TYPE_LABEL[candidate.type]}</span>
        <span className="rcp-spec-row__main"><span className="rcp-spec-row__title">{candidate.title}</span>
          <span className="rcp-spec-row__meta">{line(candidate)}</span></span>
        {url !== null && <button type="button" className="rcp-link" onClick={() => void window.bridge.openInAppBrowser(url)}>Open</button>}
      </div>;
    })}
  </details>;
}

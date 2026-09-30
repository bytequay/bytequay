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
import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import type { ContextSpecCandidateDto, ContextSpecCheckDto, ContextSpecSourceDto } from './contracts';
import { RunLine } from './ContextOverview';
import { Chevron, Icon } from './icons';
import { activeCheck, CHECK_REPRODUCTIONS, checkActive, currentRun, plural, shortDate, shortSha } from './model';
import type { ContextActions, ContextData } from './RepositoryContextPage';
import {
  CHECK_STATE, checkSummary, codeLink, defaultSpecs, DISMISS_REASONS, MAX_SPECS, openFindings, specAddress, topicCheck, TYPE_LABEL, TYPE_TONE,
} from './specChecks';

/** One topic's spec check: what to check it against, and the report of its latest check. */
export function SpecCheckDrawer({ data, actions, busy, topicId, onClose }: {
  data: ContextData; actions: ContextActions; busy: boolean; topicId: string; onClose: () => void;
}) {
  const plan = data.planView.plan;
  const topic = plan?.topics.find(value => value.id === topicId) ?? null;
  const check = plan === null ? null : topicCheck(data.checks, plan.id, topicId);
  const running = activeCheck(data.checks);
  const run = currentRun(data.build);
  return <>
    <div className="rcp-drawer__head"><div className="rcp-drawer__top">
      <span className="rcp-drawer__name">Spec check · {topic?.title ?? check?.topicTitle ?? 'Topic'}</span>
      {check !== null && <span className={`rcp-chip rcp-tone--${CHECK_STATE[check.state].tone}`}>{CHECK_STATE[check.state].label}</span>}
      <span className="rcp-spacer" />
      <button type="button" className="rcp-close" aria-label="Close" onClick={onClose}><Icon name="close" size={14} /></button>
    </div>
      <div className="rcp-drawer__sub">Compares the topic’s code with the specifications it implements. Only a gap whose test fails is reported.</div></div>
    <div className="rcp-drawer__body">
      {check !== null && <Report data={data} actions={actions} busy={busy} check={check} />}
      {topic !== null && running === null && <StartCheck key={topicId} data={data} actions={actions} busy={busy} topicId={topicId} previous={check} />}
      {topic === null && check === null && <div className="rcp-empty">This topic is no longer in the learning plan.</div>}
    </div>
    {check !== null && check.id === running?.id && run?.check?.checkId === check.id && <div className="rcp-drawer__foot">
      <button type="button" className="rcp-btn rcp-btn--danger" disabled={busy || data.build.build?.activeTurn?.cancellationRequestedAt != null}
        onClick={() => void actions.stop(run)}>Stop check</button>
    </div>}
  </>;
}

/** The specs to check against, already selected; the check plans which sections to read. */
function StartCheck({ data, actions, busy, topicId, previous }: {
  data: ContextData; actions: ContextActions; busy: boolean; topicId: string; previous: ContextSpecCheckDto | null;
}) {
  const [sources, setSources] = useState<ContextSpecSourceDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [draft, setDraft] = useState('');
  useEffect(() => {
    let live = true;
    void Promise.resolve().then(() => window.bridge.getRepositoryContextSpecSources(data.workspaceId, topicId)).then(found => {
      if (!live) return;
      setSources(found);
      setSelected(previous !== null ? previous.specs : defaultSpecs(found));
    }).catch(cause => { if (live) setError(cause instanceof Error ? cause.message : String(cause)); });
    return () => { live = false; };
  }, [data.workspaceId, previous, topicId]);
  const known = sources ?? [];
  const listed = [...known, ...selected.filter(url => !known.some(source => source.url === url))
    .map((url): ContextSpecSourceDto => ({ url, kind: 'DOCUMENT', links: 0, sections: [], approved: false }))];
  const toggle = (url: string) => setSelected(value => value.includes(url) ? value.filter(item => item !== url) : [...value, url]);
  const address = specAddress(draft);
  const add = (event: FormEvent) => {
    event.preventDefault();
    if (address === null) return;
    if (!selected.includes(address)) setSelected(value => [...value, address]);
    setDraft('');
  };
  const blocked = data.model.blocker !== null;
  const canStart = data.build.canStart && !busy && !blocked && plan(data) && selected.length > 0 && selected.length <= MAX_SPECS;
  return <section aria-label="Check against spec">
    <div className="rcp-drawer-label">{previous === null ? 'SPECS TO CHECK AGAINST' : 'CHECK AGAIN'}</div>
    {error !== null && <div className="rcp-drawer-error">The specs the code links could not be listed: {error}</div>}
    {sources === null && error === null && <div className="rcp-note-caption">Finding the specs the topic’s code links…</div>}
    {sources !== null && listed.length === 0 && <div className="rcp-note-caption">The topic’s code links no spec. Add the address of the specification it implements.</div>}
    {listed.map(source => <div className="rcp-src" key={source.url}>
      <input type="checkbox" id={`rcp-spec-${source.url}`} checked={selected.includes(source.url)} onChange={() => toggle(source.url)} />
      <label htmlFor={`rcp-spec-${source.url}`}><span className="rcp-src__target">{source.url}</span>
        <span className="rcp-src__sub">{sourceLine(source)}</span></label>
    </div>)}
    <form className="rcp-q-add" onSubmit={add}>
      <input aria-label="Spec address" placeholder="Add a spec address · https://…" value={draft} maxLength={1400} onChange={event => setDraft(event.target.value)} />
      <button type="submit" className="rcp-btn rcp-btn--secondary" disabled={address === null}>Add spec</button>
    </form>
    {selected.length > MAX_SPECS && <div className="rcp-drawer-error">Select at most {MAX_SPECS} specs.</div>}
    {previous !== null && previous.next.length > 0 && <div className="rcp-note-caption">Continues where the last check stopped: {previous.next.join(', ')}.</div>}
    <div className="rcp-q-deeper">
      <button type="button" className="rcp-btn rcp-btn--primary" disabled={!canStart} onClick={() => actions.checkSpec(topicId, selected)}>
        <Icon name="bolt" size={13} />Check against spec</button>
      <RunLine workspaceId={data.workspaceId} blocked={blocked}>{data.model.blocker
        ?? `Starts 1 task · a check turn, then up to ${CHECK_REPRODUCTIONS} reproduction turns that write and run tests in its worktree · ${data.model.label}`}</RunLine>
    </div>
  </section>;
}

function plan(data: ContextData): boolean {
  return data.planView.plan !== null && data.planView.plan.batch === null;
}

function sourceLine(source: ContextSpecSourceDto): string {
  const kind = source.kind === 'REFERENCE_CODE' ? 'Reference code · for comparison, never a rule' : source.approved ? 'Approved documentation' : 'Spec';
  if (source.links === 0) return source.approved ? kind : 'Added by you';
  return `${kind} · ${plural(source.links, 'link')} from the topic’s code${source.sections.length > 0 ? ` · ${plural(source.sections.length, 'section')}` : ''}`;
}

function Report({ data, actions, busy, check }: { data: ContextData; actions: ContextActions; busy: boolean; check: ContextSpecCheckDto }) {
  const findings = openFindings(check);
  const of = (status: ContextSpecCandidateDto['status']) => check.candidates.filter(candidate => candidate.status === status);
  const waiting = check.candidates.filter(candidate => candidate.status === 'PENDING' || candidate.status === 'REPRODUCING');
  const dismissed = of('CONFIRMED').filter(candidate => candidate.dismissed !== null);
  return <section aria-label="Spec check report">
    <div className="rcp-drawer-label">LATEST CHECK</div>
    <div className="rcp-spec-summary">{checkSummary(check)}</div>
    <div className="rcp-note-caption">Code at {shortSha(check.baselineSha)} · started {shortDate(check.startedAt, data.now)}
      {check.endedAt !== null && ` · ended ${shortDate(check.endedAt, data.now)}`} · {plural(check.specs.length, 'spec')}</div>
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
    <div className="rcp-drawer-label">FINDINGS · {findings.length}</div>
    {findings.length === 0 && <div className="rcp-note-caption">{checkActive(check) ? 'Confirmed gaps appear here as their tests fail.' : 'No confirmed gap to file.'}</div>}
    {findings.map(candidate => <Finding key={candidate.id} data={data} actions={actions} busy={busy} check={check} candidate={candidate} />)}
    <Others title="ALREADY REPORTED" candidates={of('ALREADY_REPORTED')} line={candidate => candidate.duplicate === null ? ''
      : `#${candidate.duplicate.number} ${candidate.duplicate.title} · ${candidate.duplicate.state}`}
      open={candidate => candidate.duplicate === null ? null : candidate.duplicate.url} />
    <Others title="NOT REPRODUCED" candidates={of('NOT_REPRODUCED')} line={candidate => candidate.reason ?? ''} open={() => null} />
    <Others title="REFUTED · THE CODE FOLLOWS THE RULE" candidates={of('REFUTED')} line={candidate => candidate.reason ?? ''} open={() => null} />
    <Others title="DISMISSED" candidates={dismissed} line={candidate => candidate.dismissed ?? ''} open={() => null} />
  </section>;
}

/** A confirmed gap: the rule, the code, the failing test, and the issue a click opens on GitHub for the user to file. */
function Finding({ data, actions, busy, check, candidate }: {
  data: ContextData; actions: ContextActions; busy: boolean; check: ContextSpecCheckDto; candidate: ContextSpecCandidateDto;
}) {
  const [dismissing, setDismissing] = useState(false);
  const [reason, setReason] = useState<string>(DISMISS_REASONS[0]);
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
    {candidate.draft !== null && <Disclosure label={`Issue draft${candidate.draft.labels.length > 0 ? ` · ${candidate.draft.labels.join(', ')}` : ''}`}>
      <pre className="rcp-finding__pre">{candidate.draft.body}</pre></Disclosure>}
    {dismissing
      ? <div className="rcp-finding__actions">
        <select aria-label="Why dismiss" value={reason} onChange={event => setReason(event.target.value)}>
          {DISMISS_REASONS.map(value => <option key={value}>{value}</option>)}</select>
        <button type="button" className="rcp-btn rcp-btn--secondary rcp-btn--xs" disabled={busy}
          onClick={() => { actions.dismissFinding(check, candidate.id, reason); setDismissing(false); }}>Dismiss</button>
        <button type="button" className="rcp-link" onClick={() => setDismissing(false)}>Cancel</button>
      </div>
      : <div className="rcp-finding__actions">
        <button type="button" className="rcp-btn rcp-btn--primary rcp-btn--xs" disabled={busy || candidate.draft === null} onClick={() => actions.openIssue(candidate)}>
          <Icon name="link" size={12} />Open issue</button>
        {candidate.draft !== null && <button type="button" className="rcp-btn rcp-btn--secondary rcp-btn--xs"
          onClick={() => void navigator.clipboard.writeText(candidate.draft?.body ?? '').then(() => actions.say('Issue body copied'))}>Copy body</button>}
        <button type="button" className="rcp-link" onClick={() => setDismissing(true)}>Dismiss</button>
        <span className="rcp-finding__hint">GitHub’s form opens pre-filled; you file it there.</span>
      </div>}
  </div>;
}

function Disclosure({ label, children }: { label: string; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  return <div className="rcp-finding__more">
    <button type="button" className="rcp-how" aria-expanded={open} onClick={() => setOpen(value => !value)}><Chevron open={open} size={11} />{label}</button>
    {open && <div className="rcp-finding__body">{children}</div>}
  </div>;
}

function Others({ title, candidates, line, open }: { title: string; candidates: ContextSpecCandidateDto[]; line: (candidate: ContextSpecCandidateDto) => string;
  open: (candidate: ContextSpecCandidateDto) => string | null }) {
  const [expanded, setExpanded] = useState(false);
  if (candidates.length === 0) return null;
  return <>
    <button type="button" className="rcp-drawer-label rcp-spec-toggle" aria-expanded={expanded} onClick={() => setExpanded(value => !value)}>
      <Chevron open={expanded} size={10} />{title} · {candidates.length}</button>
    {expanded && candidates.map(candidate => {
      const url = open(candidate);
      return <div className="rcp-spec-row" key={candidate.id}>
        <span className={`rcp-chip rcp-tone--${TYPE_TONE[candidate.type]}`}>{TYPE_LABEL[candidate.type]}</span>
        <span className="rcp-spec-row__main"><span className="rcp-spec-row__title">{candidate.title}</span>
          <span className="rcp-spec-row__meta">{line(candidate)}</span></span>
        {url !== null && <button type="button" className="rcp-link" onClick={() => void window.bridge.openInAppBrowser(url)}>Open</button>}
      </div>;
    })}
  </>;
}

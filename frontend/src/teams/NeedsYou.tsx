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
import { useEffect, useState } from 'react';
import { Check } from 'lucide-react';
import { zoomReference } from '../githubReference';
import { SnoozeMenu } from '../home/AttentionList';
import AttentionRow from '../home/AttentionRow';
import {
  attentionCandidates, handledEntry, issueAttentionItems, loadHandled, saveHandled, snoozeHint, snoozeUntil, visibleAttention,
  type AttentionItem, type Handled, type SnoozeId,
} from '../home/attention';
import { usePinnedItems } from '../home/pinned';
import { useSnoozedAttention } from '../home/useSnoozedAttention';
import { remoteErrorMessage } from '../remoteError';
import { ago } from '../relativeTime';
import type { PullRequestDto } from '../types';
import { ISSUE_DEVELOPMENT_CHANGED } from '../workspaces/issueDevelopment';
import type { CircleNeed } from './priority';
import type { TeamCircle } from './types';

export type Notify = (text: string, undo?: () => void) => void;

/** Home's attention candidates after its eligibility, handled state, timed reminders and pins; handling here clears Home too. */
export function useLiveAttention(accountLogin: string | undefined) {
  const [candidates, setCandidates] = useState<AttentionItem[]>([]);
  const [handled, setHandledState] = useState<Handled>(loadHandled);
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    setCandidates([]);
    if (!accountLogin) return undefined;
    let generation = 0;
    let active = true;
    const reload = async (knownPrs?: PullRequestDto[]) => {
      const request = ++generation;
      try {
        const [prs, workspaces] = await Promise.all([knownPrs ?? window.bridge.fetchAttentionPrs(), window.bridge.listWorkspaces()]);
        const results = await Promise.allSettled(workspaces.map(async workspace =>
          issueAttentionItems(await window.bridge.fetchWorkspaceIssueAttention(workspace.id), workspace.id, accountLogin)));
        if (!active || request !== generation) return;
        setCandidates(attentionCandidates(prs, results.flatMap(result => result.status === 'fulfilled' ? result.value : []), accountLogin));
        setError(results.some(result => result.status === 'rejected') ? 'Some assigned issues could not be loaded.' : null);
      }
      catch (failure) { if (active && request === generation) setError(remoteErrorMessage(failure)); }
    };
    const changed = () => { void reload(); };
    const syncPrs = (event: Event) => {
      const prs = (event as CustomEvent<PullRequestDto[]>).detail;
      setCandidates(current => attentionCandidates(prs, current.filter(item => item.type === 'issue'), accountLogin));
      void reload(prs);
    };
    const handling = () => setHandledState(loadHandled());
    changed();
    const stop = window.bridge.onPullRequestsSynced(changed);
    window.addEventListener('bytequay:pr-attention-updated', syncPrs);
    window.addEventListener('bytequay:attention-handled-changed', handling);
    window.addEventListener(ISSUE_DEVELOPMENT_CHANGED, changed);
    return () => {
      active = false; generation++; stop();
      window.removeEventListener('bytequay:pr-attention-updated', syncPrs);
      window.removeEventListener('bytequay:attention-handled-changed', handling);
      window.removeEventListener(ISSUE_DEVELOPMENT_CHANGED, changed);
    };
  }, [accountLogin]);
  useEffect(() => {
    const deadlines = Object.values(handled).flatMap(entry =>
      entry.kind === 'snooze' && entry.until !== null && entry.until > Date.now() ? [entry.until] : []);
    if (deadlines.length === 0) return undefined;
    const timer = window.setTimeout(() => setNow(Date.now()), Math.min(Math.min(...deadlines) - Date.now(), 2_147_483_647));
    return () => window.clearTimeout(timer);
  }, [handled, now]);
  const at = Math.max(now, Date.now());
  const reminders = useSnoozedAttention(candidates, handled, at);
  const pinned = usePinnedItems();
  const live = visibleAttention(reminders, handled, at).filter(item => !pinned.some(pin => pin.key === item.key));
  const setHandled = (next: Handled) => { setHandledState(next); saveHandled(next); };
  return { live, handled, setHandled, error };
}

export function openAttentionItem(item: AttentionItem): void {
  if (item.type === 'pr') zoomReference({ kind: 'pull', repo: item.repo, number: item.number });
  else if (item.workspaceId) zoomReference({ kind: 'issue', repo: item.repo, number: item.number, workspaceId: item.workspaceId });
}

/** How long it has waited, from source evidence only; an update time is never passed off as a request time. */
export function needAge(need: CircleNeed): { at: string; title: string } {
  const { item } = need;
  const since = (at: string | null | undefined, label: string) => at ? { at, title: `${label} ${ago(at)}` } : null;
  return (need.action === 'assigned' ? since(item.createdAt, 'Issue opened')
    : item.kind === 'commits' ? since(item.pr?.headPushedAt, 'New commits')
      : since(item.pr?.reviewRequestedAt, 'Review requested')) ?? { at: item.updatedAt, title: `Updated ${ago(item.updatedAt)}` };
}

type SnoozeAnchor = { need: CircleNeed; anchor: DOMRect };

/** Review requests and assigned issues from circle people, grouped by what they ask of the viewer. */
export default function NeedsYou({ needs, circles, allMemberLogins, viewerLogin, showCircle, handled, setHandled, notify, onPerson, onActivity, emptyScope }: {
  needs: CircleNeed[];
  circles: TeamCircle[];
  /** Everyone in any circle, so a latest actor outside them all is labeled. */
  allMemberLogins: ReadonlySet<string>;
  viewerLogin: string | null;
  /** All circles names each row's circle; a single circle does not repeat its own name. */
  showCircle: boolean;
  handled: Handled;
  setHandled: (next: Handled) => void;
  notify: Notify;
  onPerson: (memberId: number) => void;
  onActivity: () => void;
  emptyScope: string;
}) {
  const [snooze, setSnooze] = useState<SnoozeAnchor | null>(null);
  function restore(key: string) {
    const next = { ...loadHandled() };
    delete next[key];
    setHandled(next);
  }
  function acknowledge(item: AttentionItem) {
    setHandled({ ...handled, [item.key]: handledEntry(item, 'ack', null, 'acknowledged') });
    notify('Acknowledged · returns when something new happens', () => restore(item.key));
  }
  function snoozeFor(item: AttentionItem, id: SnoozeId, label: string) {
    const until = snoozeUntil(id, Date.now());
    setSnooze(null);
    setHandled({ ...handled, [item.key]: handledEntry(item, 'snooze', until, label.toLowerCase()) });
    const hint = snoozeHint(id, Date.now());
    notify(`Snoozed until ${id === 'tomorrow' ? `tomorrow ${hint}` : hint}`, () => restore(item.key));
  }

  if (needs.length === 0) return <div className="cir-empty">
    <span className="cir-empty__badge is-done" aria-hidden="true"><Check size={18} strokeWidth={2.2} /></span>
    <h2>Nothing needs you right now</h2>
    <p>Review requests and issues assigned to you from {emptyScope} show up here. Other updates stay in Activity.</p>
    <button type="button" className="bq-button" onClick={onActivity}>View activity</button>
  </div>;

  const sections = [
    { label: 'Review requested', rows: needs.filter(need => need.action === 'review') },
    { label: 'Assigned issues', rows: needs.filter(need => need.action === 'assigned') },
  ].filter(section => section.rows.length > 0);
  const outside = (login: string | null) => login !== null && !allMemberLogins.has(login.toLowerCase())
    && login.toLowerCase() !== viewerLogin?.toLowerCase();
  const circlesOf = (githubId: number) => showCircle ? circles.filter(circle => circle.members.some(person => person.githubId === githubId)) : [];
  return <div className="cir-needs">
    {sections.map(section => <section key={section.label} aria-label={section.label}>
      <div className="atn-head"><span className="atn-head__label">{section.label}</span><span className="atn-count">{section.rows.length}</span></div>
      <div className="item-list" role="list">
        {/* The sections already say where each item stands. */}
        {section.rows.map(need => <AttentionRow key={need.item.key} item={need.item} viewerLogin={viewerLogin} showRepo showState={false} age={needAge(need)}
          circle={{ match: need.match, circles: circlesOf(need.match.member.githubId), outside, onPerson }}
          onOpen={() => openAttentionItem(need.item)} onAcknowledge={() => acknowledge(need.item)} onSnooze={anchor => setSnooze({ need, anchor })} />)}
      </div>
    </section>)}
    {snooze && <SnoozeMenu anchor={snooze.anchor} onClose={() => setSnooze(null)} onPick={option => snoozeFor(snooze.need.item, option.id, option.label)} />}
  </div>;
}

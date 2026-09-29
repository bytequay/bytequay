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
import { ArrowUpRight, Check, ChevronLeft, CircleCheck, CircleDot, Eye, GitMerge, GitPullRequest, MessageSquare, MessageSquareText, X } from 'lucide-react';
import type { ReactNode } from 'react';
import Avatar from '../Avatar';
import { SourceIcon } from '../cards/Card';
import { openActivityLink } from '../githubReference';
import { itemRelation } from '../home/AttentionRow';
import { usePopoverDismiss } from '../pulls/code-tests/Outline';
import { relativeTime } from '../relativeTime';
import { activityVerb, METRICS, type Metric } from './activity';
import { needAge, openAttentionItem } from './NeedsYou';
import type { CircleNeed } from './priority';
import type { TeamActivitySnapshot, TeamCircle, TeamEventKind, TeamMember, TeamStatistics } from './types';

const KIND_ICONS: Record<TeamEventKind, ReactNode> = {
  PR_OPENED: <GitPullRequest size={14} className="is-open" />, PR_MERGED: <GitMerge size={14} className="is-merged" />,
  PR_REVIEWED: <MessageSquareText size={14} />, PR_COMMENT: <MessageSquare size={14} />, PR_REVIEW_COMMENT: <MessageSquareText size={14} />,
  REVIEW_REQUESTED: <Eye size={14} className="is-requested" />, ISSUE_OPENED: <CircleDot size={14} className="is-open" />,
  ISSUE_CLOSED: <CircleCheck size={14} className="is-merged" />, ISSUE_COMMENT: <MessageSquare size={14} />,
};

const PANEL_METRICS: [Metric, keyof TeamStatistics][] = [
  ['prs-opened', 'prsOpened'], ['authored-merges', 'authoredPrsMerged'], ['merges-performed', 'mergesPerformed'], ['reviews-performed', 'prsReviewed'],
  ['issues-opened', 'issuesOpened'], ['issues-closed', 'issuesClosed'], ['comments', 'comments'],
];

/** Who a person is, their circles, what needs you from them, and what they did, kept in three separate blocks. */
export default function PersonPanel({ member, circles, backLabel, needs, snapshot, periodLabel, viewerLogin, circlesOpen, busy,
  onClose, onCircles, onSelectCircle, onToggleCircle, onDrill, onAllActivity }: {
  member: TeamMember;
  circles: TeamCircle[];
  backLabel: string;
  needs: CircleNeed[];
  snapshot: TeamActivitySnapshot | null;
  periodLabel: string;
  viewerLogin: string | null;
  circlesOpen: boolean;
  busy: boolean;
  onClose: () => void;
  onCircles: (open: boolean) => void;
  onSelectCircle: (circleId: string) => void;
  onToggleCircle: (circle: TeamCircle, add: boolean) => void;
  onDrill: (metric: Metric) => void;
  onAllActivity: () => void;
}) {
  const statistics = snapshot?.people.find(person => person.memberId === member.githubId)?.statistics ?? null;
  const recent = (snapshot?.events ?? []).filter(event => event.actorId === member.githubId).slice(0, 6);
  const names = new Map(circles.flatMap(circle => circle.members.map(person => [person.githubId, person.login] as const)));
  const memberOf = circles.filter(circle => circle.members.some(person => person.githubId === member.githubId));
  const named = member.name !== '' && member.name !== member.login;
  return <aside className="cir-person" aria-label="Person details">
    <div className="cir-person__bar">
      <button type="button" className="cir-back" onClick={onClose}><ChevronLeft size={14} strokeWidth={2} aria-hidden="true" /><span>{backLabel}</span></button>
      <span className="cir-spacer" />
      <button type="button" className="cir-iconbtn" aria-label="Open GitHub profile" title="Open GitHub profile"
        onClick={() => { void window.bridge.openInAppBrowser(`https://github.com/${encodeURIComponent(member.login)}`); }}>
        <ArrowUpRight size={14} aria-hidden="true" />
      </button>
      <button type="button" className="cir-iconbtn" aria-label="Close person details" title="Close (Esc)" onClick={onClose}><X size={16} aria-hidden="true" /></button>
    </div>
    <div className="cir-person__body">
      <div className="cir-person__who">
        <Avatar login={member.login} avatarUrl={member.avatarUrl} size={44} />
        <div className="cir-stack"><span className="cir-person__name" title={member.name || member.login}>{named ? member.name : member.login}</span>
          <span className="cir-muted">@{member.login}</span></div>
      </div>
      <div className="cir-anchor cir-person__circles">
        {memberOf.map(circle => <button type="button" key={circle.id} className="cir-tag" title="Open circle" onClick={() => onSelectCircle(circle.id)}>{circle.name}</button>)}
        <button type="button" className="cir-link" aria-haspopup="menu" aria-expanded={circlesOpen} onClick={() => onCircles(!circlesOpen)}>Circles…</button>
        {circlesOpen && <CirclesMenu member={member} circles={circles} busy={busy} onToggle={onToggleCircle} onClose={() => onCircles(false)} />}
      </div>

      {needs.length > 0 && <>
        <div className="cir-label">Needs you</div>
        <div className="item-list">
          {needs.map(need => {
            const age = needAge(need);
            return <button type="button" key={need.item.key} className="cir-need" onClick={() => openAttentionItem(need.item)}>
              <SourceIcon source={need.item.type} size={14} tinted />
              <span className="cir-stack"><span className="cir-ellipsis">{need.item.title}</span>
                <span className="cir-muted cir-ellipsis">{itemRelation(need.item, need.match)} · {need.item.repo} #{need.item.number}</span></span>
              <span className="card-age" title={age.title}>{relativeTime(age.at, { suffix: false })}</span>
            </button>;
          })}
        </div>
      </>}

      <div className="cir-label">Recorded contributions <span className="cir-label__note">{periodLabel}</span></div>
      <div className="cir-metrics">
        {PANEL_METRICS.map(([metric, field]) => {
          const value = statistics?.[field] ?? 0;
          return <button type="button" key={metric} className={`cir-metric${value === 0 ? ' is-empty' : ''}`} onClick={() => onDrill(metric)}
            title={value === 0 ? 'No recorded observation in this period' : 'Show in Activity'}>
            <span className="cir-ellipsis">{METRICS[metric]}</span><span className="cir-metric__value">{value}</span>
          </button>;
        })}
      </div>

      <div className="cir-label">With you</div>
      <div className="item-list">
        <button type="button" className="cir-with" onClick={() => onDrill('interactions')}>
          <span className="cir-stack"><span>Direct interactions</span>
            <span className="cir-muted">Mentions, review requests between you, and reviews of each other’s PRs</span></span>
          <span className="cir-with__value">{statistics?.directInteractions ?? 0}</span>
        </button>
        <button type="button" className="cir-with" onClick={() => onDrill('shared')}>
          <span className="cir-stack"><span>Shared work</span><span className="cir-muted">Items you both participated in — not necessarily a conversation</span></span>
          <span className="cir-with__value is-small">{statistics?.sharedPullRequests ?? 0} PRs · {statistics?.sharedIssues ?? 0} issues</span>
        </button>
      </div>

      <div className="cir-label cir-label--row">Latest activity<span className="cir-spacer" />
        <button type="button" className="cir-link" onClick={onAllActivity}>All activity →</button></div>
      {recent.length === 0 ? <p className="cir-muted">No recorded activity in the {periodLabel.toLowerCase()}.</p>
        : <div className="cir-recent">{recent.map(event => <button type="button" key={event.id} className="cir-recent__row" onClick={() => { void openActivityLink(event.url); }}>
          <span className="cir-recent__icon">{KIND_ICONS[event.kind]}</span>
          <span className="cir-recent__text"><span className="cir-muted">{activityVerb(event, viewerLogin, names)} </span>{event.title}</span>
          <span className="card-age">{relativeTime(event.occurredAt, { suffix: false })}</span>
        </button>)}</div>}
    </div>
  </aside>;
}

function CirclesMenu({ member, circles, busy, onToggle, onClose }: {
  member: TeamMember;
  circles: TeamCircle[];
  busy: boolean;
  onToggle: (circle: TeamCircle, add: boolean) => void;
  onClose: () => void;
}) {
  const host = usePopoverDismiss(onClose, true);
  return <div ref={host} className="cir-popover cir-menu" role="menu" aria-label="Circles for this person">
    {circles.map(circle => {
      const on = circle.members.some(person => person.githubId === member.githubId);
      return <button type="button" key={circle.id} role="menuitemcheckbox" aria-checked={on} disabled={busy} className="cir-menu__item" onClick={() => onToggle(circle, !on)}>
        <span className={`cir-check${on ? ' is-on' : ''}`} aria-hidden="true">{on && <Check size={11} strokeWidth={3} />}</span>
        <span className="cir-ellipsis">{circle.name}</span>
      </button>;
    })}
    <div className="cir-menu__note">Changes apply immediately. They aren’t notified.</div>
  </div>;
}

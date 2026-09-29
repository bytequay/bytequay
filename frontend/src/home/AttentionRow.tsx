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
import { useEffect, useState, type ReactNode } from 'react';
import { ArrowRight, ArrowUpRight, Check, CircleX, Clock3, MessageSquare, Play } from 'lucide-react';
import Avatar from '../Avatar';
import { CardActions, CardAge, GoalIcon, IconButton, InGoalBadge, ItemRow, LinkKey, Pill, SourceIcon } from '../cards/Card';
import { jiraIssueLocation } from '../jira/contracts';
import MarkdownBody from '../pulls/MarkdownBody';
import ReferenceCard, { previewMarkdown, toneChip, useHoverCard, type HoverCard, type ReferenceCardItem } from '../ReferenceCard';
import { ago, relativeTime } from '../relativeTime';
import { firstWords } from '../teams/activity';
import type { TeamAttentionMatch } from '../teams/priority';
import type { TeamCircle } from '../teams/types';
import PinButton from './PinButton';
import type { PinnedItem } from './pinned';
import { useAttentionStatus } from './useAttentionVisit';
import {
  ATTENTION_SOURCES,
  attentionEnded,
  attentionSource,
  attentionSummary,
  attentionTime,
  type AttentionCommit,
  type AttentionDetail,
  type AttentionItem,
  type AttentionLabel,
  type AttentionSummary,
} from './attention';

/** How long the pointer rests on a row before the item's hover card opens. */
const PREVIEW_OPEN_MS = 2000;
/** Long enough for the pointer to cross from the row into its card. */
const PREVIEW_CLOSE_MS = 90;
/** How much of a comment or message a row unfolds. */
const QUOTE_WORDS = 100;

/** A row whose person is in the viewer's private circles. */
export type RowCircle = {
  match: TeamAttentionMatch;
  /** The circles named beside the person, the first by name and the rest counted; none names no circle. */
  circles: TeamCircle[];
  /** Whether a login is outside every circle, which the row says beside it. */
  outside?: (login: string | null) => boolean;
  /** Opens the person; without it the name is plain text. */
  onPerson?: (githubId: number) => void;
};

type Props = {
  item: AttentionItem;
  viewerLogin: string | null;
  /** Names the repository, or the workspace an issue would start in; a workspace's own list need not. */
  showRepo: boolean;
  /** Where the item stands, for a list that mixes reasons; Circles' sections already say it. */
  showState?: boolean;
  circle?: RowCircle | null;
  /** How long the item has waited, where the surface knows better than its newest event. */
  age?: { at: string; title: string };
  onOpen: () => void;
  onAcknowledge: () => void;
  onSnooze: (anchor: DOMRect) => void;
  /** Present for an issue or ticket that can start development. */
  onStart?: () => void;
  /** Whether the item is in the pinned goal, shown as a badge beside the title. */
  inGoal?: boolean;
  /** Adds the item to the pinned goal, or takes it out; absent where no goal can hold it. */
  onToggleGoal?: () => void;
};

/** What the person a row leads with did: authored the pull request, opened the issue, or asked for your review. */
export function itemRelation(item: AttentionItem, match?: TeamAttentionMatch | null): string {
  if (match?.reason === 'review-requester') return 'requested your review';
  if (item.task !== undefined) return '';
  if (item.jira !== undefined) return item.author === null ? 'Unassigned' : 'assignee';
  if (item.type === 'issue') return item.kind === 'assigned' ? 'opened · assigned to you' : 'opened';
  return 'authored';
}

function pinTarget(item: AttentionItem): PinnedItem['target'] | null {
  if (item.task) return { kind: 'task', workspaceId: item.task.dto.workspaceId, taskId: item.task.dto.id };
  if (item.jira) return { kind: 'jira', ...jiraIssueLocation(item.jira) };
  if (item.type === 'pr') return { kind: 'pull', repo: item.repo, number: item.number };
  return item.workspaceId === null ? null : { kind: 'issue', repo: item.repo, number: item.number, workspaceId: item.workspaceId };
}

/**
 * When the event a row's latest line names happened, or null for no line. A pull request with no recorded
 * event still gets one when it waits on something to show, such as commits since your review; an issue's
 * or an idle ticket's labels wait for the hover card.
 */
function latestAt(item: AttentionItem, summary: AttentionSummary): string | null {
  if (item.task !== undefined) return item.updatedAt;
  const at = item.pr?.latestActivity?.timestamp ?? item.jira?.latestActivity?.at ?? null;
  return at ?? (item.pr !== null && summary.detail !== null ? attentionTime(item) : null);
}

/**
 * One item that needs you, as both Homes and Circles list it: its source's icon, the title and key, who it
 * is from and where, and its latest event, whose detail unfolds while that line is hovered. Resting on the
 * row opens the item's hover card; its actions take the age's place while the pointer or focus is inside.
 */
export default function AttentionRow({
  item, viewerLogin, showRepo, showState = true, circle = null, age, onOpen, onAcknowledge, onSnooze, onStart, inGoal = false, onToggleGoal,
}: Props) {
  const hover = useHoverCard(PREVIEW_OPEN_MS, PREVIEW_CLOSE_MS);
  const status = useAttentionStatus(item.key);
  const source = attentionSource(item);
  const summary = attentionSummary(item, viewerLogin);
  const reference = item.reference ?? `#${item.number}`;
  const github = source === 'pr' || source === 'issue';
  const member = circle?.match.member ?? null;
  const person = member !== null ? { login: member.login, avatarUrl: member.avatarUrl || null }
    : item.author === null ? null : { login: item.author, avatarUrl: item.authorAvatarUrl };
  const relation = itemRelation(item, circle?.match);
  const where = !showRepo || item.repo === '' ? null : item.workspaceName || item.repo;
  const circles = circle?.circles ?? [];
  const target = pinTarget(item);
  const links = item.links ?? [];
  // An action closes the card first, so nothing stays anchored to a row that may be leaving.
  const closing = (run: () => void) => (): void => { hover.close(); run(); };
  const open = closing(onOpen);
  const acknowledge = closing(onAcknowledge);
  const start = onStart === undefined ? undefined : closing(onStart);
  const snooze = (button: HTMLElement): void => { hover.close(); onSnooze(button.getBoundingClientRect()); };

  const name = person === null ? null : <>
    <Avatar login={person.login} avatarUrl={person.avatarUrl} size={18} allowGitHubFallback={github} /><span>{person.login}</span>
  </>;
  const onPerson = circle?.onPerson;
  const meta = <>
    {name !== null && (member !== null && onPerson !== undefined
      ? <button type="button" className="item-person" title="Circle member · open person" onClick={event => { event.stopPropagation(); onPerson(member.githubId); }}>{name}</button>
      : <span className="item-person">{name}</span>)}
    {relation !== '' && <span>{relation}</span>}
    {circles.length > 0 && (
      <Pill tone="blue" title={`Your private circle${circles.length > 1 ? 's' : ''}: ${circles.map(value => value.name).join(', ')}`}>
        {circles[0].name}{circles.length > 1 ? ` +${circles.length - 1}` : ''}
      </Pill>
    )}
    {where !== null && <><span className="item-row__sep">·</span><span className="item-row__muted">{where}</span></>}
    {circle?.match.reason === 'review-requester' && circle.outside?.(item.author) === true && <>
      <span className="item-row__sep">·</span><span className="item-row__muted">PR by {item.author} (not in your circles)</span>
    </>}
    {links.length > 0 && <span className="item-row__sep">·</span>}
    {links.map(link => (
      <LinkKey key={`${link.source}:${link.key}`} source={link.source} name={link.key} title={link.status === null ? link.title : `${link.title} · ${link.status}`} />
    ))}
  </>;

  return <>
    <div role="listitem" onMouseEnter={event => hover.show(event.currentTarget)} onMouseLeave={() => hover.hide()}>
      <ItemRow label={`${ATTENTION_SOURCES[source].label} ${reference}: ${item.title}`} onOpen={open}
        icon={<SourceIcon source={source} size={16} tinted ended={attentionEnded(item)} />}
        title={item.title}
        marks={<>
          {/* A task's reference names its lane, which the line below already does. */}
          {item.task === undefined && <span className="card-key">{reference}</span>}
          {showState && <Pill outline>{status ?? summary.tag.label}</Pill>}
          {inGoal && <InGoalBadge />}
        </>}
        meta={meta}
        actions={
          <CardActions label={`Actions for ${reference}`}>
            {target !== null && <PinButton item={{ key: item.key, title: item.title, reference, target }} className="card-iconbtn" />}
            {onToggleGoal !== undefined && (
              <IconButton label={`${inGoal ? 'Remove' : 'Add'} ${reference} ${inGoal ? 'from' : 'to'} the goal`} title={inGoal ? 'Remove from goal' : 'Add to goal'}
                tone={inGoal ? 'active' : undefined} pressed={inGoal} onClick={onToggleGoal}>
                <GoalIcon size={15} />
              </IconButton>
            )}
            {start !== undefined && (
              <IconButton label={`Start development for ${reference}`} title={`Start development in ${item.workspaceName || item.repo}`} onClick={start}>
                <Play size={14} strokeWidth={1.9} aria-hidden="true" />
              </IconButton>
            )}
            <IconButton label={`Acknowledge ${reference}`} title="Acknowledge — clears it until something new happens" tone="ack" onClick={acknowledge}>
              <Check size={15} strokeWidth={2} aria-hidden="true" />
            </IconButton>
            <IconButton label={`Snooze ${reference}`} title="Snooze" onClick={event => snooze(event.currentTarget)}>
              <Clock3 size={15} strokeWidth={1.8} aria-hidden="true" />
            </IconButton>
            <IconButton label={`Open ${reference}`} title="Open" onClick={open}>
              <ArrowUpRight size={14} aria-hidden="true" />
            </IconButton>
          </CardActions>
        }
        age={age === undefined ? <CardAge at={attentionTime(item)} /> : <CardAge at={age.at} title={age.title} />}>
        <Latest item={item} summary={summary} viewerLogin={viewerLogin} outside={circle?.outside} />
      </ItemRow>
    </div>
    {/* A sibling of the row rather than inside it, so entering the card is leaving the row. */}
    {hover.anchor !== null && (
      <Preview item={item} summary={summary} hover={hover} onOpen={open} onAcknowledge={acknowledge} onSnooze={snooze} onStart={start} />
    )}
  </>;
}

/** The latest event on one line, and what happened, unfolded while the line is hovered. */
function Latest({ item, summary, viewerLogin, outside }: {
  item: AttentionItem;
  summary: AttentionSummary;
  viewerLogin: string | null;
  outside?: (login: string | null) => boolean;
}) {
  const at = latestAt(item, summary);
  if (at === null) return null;
  // A ticket's own verbs name no actor ("Moved to Blocked"); only a pull request's missing actor is worth saying.
  const actor = summary.actor === null ? item.pr === null ? null : 'an account the source did not identify'
    : summary.actor.name.toLowerCase() === viewerLogin?.toLowerCase() ? 'you'
      : `${summary.actor.name}${outside?.(summary.actor.name) === true ? ' (not in your circles)' : ''}`;
  return (
    <div className={`item-row__latest${summary.detail === null ? '' : ' has-detail'}`}>
      <div className="item-row__latest-line">
        <MessageSquare size={12} aria-hidden="true" />
        <span>{`Latest: ${actor === null ? '' : `${actor} `}${summary.verb} · ${relativeTime(at, { suffix: false })}`}</span>
      </div>
      {summary.detail !== null && <div className="item-row__detail atn-detail"><Detail detail={summary.detail} /></div>}
    </div>
  );
}

function labelStyle(label: AttentionLabel) {
  const hex = label.color !== null && /^[0-9a-f]{6}$/i.test(label.color) ? `#${label.color}` : '#d0d7de';
  return { background: `${hex}33`, color: '#1f2328' };
}

/** The newest commits: all of four, or three and how many more, since "and 1 more" takes the line the fourth would. */
function Commits({ commits }: { commits: AttentionCommit[] }) {
  const shown = commits.length > 4 ? commits.slice(0, 3) : commits;
  return <>
    {shown.map(commit => (
      <div className="atn-detail__commit" key={commit.sha}>
        <span className="atn-detail__sha">{commit.sha}</span>
        <span className="atn-detail__message">{commit.message}</span>
      </div>
    ))}
    {shown.length < commits.length && <div className="atn-detail__lead">and {commits.length - shown.length} more</div>}
  </>;
}

/**
 * What happened, as the event that put the item on the list describes it. Someone's words render as the
 * Markdown they wrote, with no repository passed, so a code permalink stays a link: a row never fetches.
 */
function Detail({ detail }: { detail: AttentionDetail }) {
  switch (detail.kind) {
    case 'text':
      return <div className="atn-detail__lead">{detail.text}</div>;
    case 'quote':
      return <>
        <MarkdownBody body={previewMarkdown(firstWords(detail.text, QUOTE_WORDS))} />
        {detail.file !== null && <div className="atn-detail__file" title={detail.file}>on {detail.file.slice(detail.file.lastIndexOf('/') + 1)}</div>}
      </>;
    case 'commits':
      return <>
        {detail.lead !== null && <div className="atn-detail__lead">{detail.lead}</div>}
        <Commits commits={detail.commits} />
      </>;
    case 'command':
      return <>
        {detail.lead !== null && <div className="atn-detail__lead">{detail.lead}</div>}
        <div className="atn-detail__command">$ {detail.command}</div>
      </>;
    case 'ci':
      return <>
        <Commits commits={detail.commits} />
        <div className="atn-detail__failed">
          <CircleX size={14} strokeWidth={2} aria-hidden="true" />
          {detail.checks.length === 0 ? 'Checks failed' : `${detail.checks[0]} failed`}
        </div>
        {detail.checks.length > 1 && <div className="atn-detail__lead">and {detail.checks.length - 1} more</div>}
      </>;
    case 'status':
      return <>
        <div className="atn-detail__status">
          <Pill tone={detail.from.tone} className="is-square is-struck">{detail.from.name}</Pill>
          <ArrowRight size={14} strokeWidth={2} aria-label="to" />
          <Pill tone={detail.to.tone} className="is-square">{detail.to.name}</Pill>
        </div>
        {detail.note !== null && <MarkdownBody body={previewMarkdown(firstWords(detail.note, QUOTE_WORDS))} />}
      </>;
    case 'labels':
      return (
        <div className="atn-detail__labels">
          {detail.labels.map(label => <Pill key={label.name} style={labelStyle(label)}>{label.name}</Pill>)}
        </div>
      );
  }
}

/** "opened 3d ago", or nothing for a time the source did not give. */
function when(verb: string, at: string | null): string {
  const elapsed = ago(at);
  return elapsed === '' ? '' : `${verb} ${elapsed}`;
}

/** The head of the item's hover card, from what the list already holds. */
function previewItem(item: AttentionItem, summary: AttentionSummary): ReferenceCardItem {
  const updated = when('updated', item.updatedAt);
  const base: ReferenceCardItem = {
    type: attentionSource(item), repo: item.repo, number: item.number, title: item.title, author: item.author,
    authorAvatarUrl: item.authorAvatarUrl, createdAt: item.createdAt, updatedAt: item.updatedAt, labels: item.labels,
    chip: toneChip(summary.tag.label, summary.tag.tone),
  };
  if (item.task !== undefined) {
    return {
      ...base, reference: `${item.workspaceName || item.repo} · ${item.reference ?? 'Agent task'}`, url: null,
      byline: [item.author, when('started', item.task.dto.createdAt), updated].filter(Boolean).join(' · '),
    };
  }
  if (item.jira !== undefined) {
    const { jira } = item;
    return {
      ...base, reference: jira.key, url: jira.url,
      byline: [jira.assignee === null ? 'Unassigned' : `Assigned to ${jira.assignee.displayName}`, updated].filter(Boolean).join(' · '),
    };
  }
  const pr = item.pr;
  if (pr !== null) {
    return {
      ...base,
      byline: [pr.origin === 'AUTHORED' ? 'your PR' : item.author ?? 'unknown', pr.origin === 'AUTHORED' ? '' : when('opened', item.createdAt), updated]
        .filter(Boolean).join(' · '),
    };
  }
  return {
    ...base,
    byline: [item.author ?? 'unknown', when('opened', item.createdAt), updated, item.kind === 'assigned' ? 'assigned to you' : ''].filter(Boolean).join(' · '),
  };
}

/**
 * A pull request's description as its last detail read stored it; undefined while reading and null when none has. The list
 * carries no PR bodies, and the detail read itself would reach GitHub on a miss and mark the pull request viewed.
 */
function useStoredDescription(item: AttentionItem): string | null | undefined {
  const [description, setDescription] = useState<string | null>();
  const pull = item.pr !== null;
  useEffect(() => {
    if (!pull) return;
    let current = true;
    Promise.resolve().then(() => window.bridge.findStoredPullRequestDescription(item.repo, item.number))
      .then(stored => { if (current) setDescription(stored?.body ?? null); })
      .catch(() => { if (current) setDescription(null); });
    return () => { current = false; };
    // An update may come with a newly stored description, so an open card reads again.
  }, [pull, item.repo, item.number, item.updatedAt]);
  return description;
}

/** The item's description, or a task's last message. */
function previewBody(item: AttentionItem, description: string | null | undefined): ReactNode {
  if (item.pr !== null && description === undefined) return <span className="ref-card__muted">Loading description…</span>;
  if (item.pr !== null && description === null) return <span className="ref-card__muted">Open the pull request to load its description.</span>;
  const text = item.task !== undefined ? item.task.activity?.lastMessage?.text : item.jira !== undefined ? item.jira.body : item.pr !== null ? description : item.body;
  const markdown = previewMarkdown(text);
  if (markdown === '') return <span className="ref-card__muted">{item.task === undefined ? 'No description.' : 'No messages yet.'}</span>;
  return <MarkdownBody body={markdown} repository={item.jira === undefined && item.task === undefined ? item.repo : undefined} />;
}

function Preview({ item, summary, hover, onOpen, onAcknowledge, onSnooze, onStart }: {
  item: AttentionItem;
  summary: AttentionSummary;
  hover: HoverCard;
  onOpen: () => void;
  onAcknowledge: () => void;
  onSnooze: (button: HTMLElement) => void;
  onStart?: () => void;
}) {
  const description = useStoredDescription(item);
  return (
    <ReferenceCard hover={hover} item={previewItem(item, summary)} body={previewBody(item, description)} actions={<>
      {onStart !== undefined && (
        <button className="bq-button bq-button--primary" type="button" onClick={onStart}><Play aria-hidden="true" /> Start development</button>
      )}
      <button className="bq-button bq-button--ghost" type="button" onClick={onAcknowledge}><Check aria-hidden="true" /> Ack</button>
      <button className="bq-button bq-button--ghost" type="button" onClick={event => onSnooze(event.currentTarget)}><Clock3 aria-hidden="true" /> Snooze</button>
      <button className="bq-button bq-button--ghost" type="button" onClick={onOpen}>Open <ArrowUpRight aria-hidden="true" /></button>
    </>} />
  );
}

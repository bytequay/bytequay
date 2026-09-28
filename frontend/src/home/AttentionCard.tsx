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
import { useState, type MouseEvent, type ReactNode } from 'react';
import { ArrowRight, Check, ChevronRight, CircleDot, CircleX, Clock3, GitPullRequest, Play, SquareCheck } from 'lucide-react';
import Avatar from '../Avatar';
import { GoalIcon } from '../goals/GoalPanel';
import JiraIcon from '../jira/JiraIcon';
import MarkdownBody from '../pulls/MarkdownBody';
import { previewMarkdown } from '../ReferenceCard';
import RepoAvatar from '../RepoAvatar';
import { relativeTime } from '../relativeTime';
import {
  ATTENTION_SOURCES,
  attentionSource,
  attentionSummary,
  attentionTime,
  type AttentionCommit,
  type AttentionDetail,
  type AttentionItem,
  type AttentionLabel,
  type AttentionSource,
} from './attention';

/** The icon each source is drawn with, on cards and wherever a card's links list it. */
export function SourceIcon({ source, size = 14 }: { source: AttentionSource; size?: number }) {
  if (source === 'jira') return <JiraIcon size={size} />;
  const Icon = source === 'pr' ? GitPullRequest : source === 'issue' ? CircleDot : SquareCheck;
  return <Icon size={size} strokeWidth={1.9} aria-hidden="true" />;
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
 * Someone's words, drawn as the Markdown they wrote. No repository is passed, so a code permalink stays
 * a link rather than loading its snippet: a card never fetches.
 */
function Comment({ text }: { text: string }) {
  return <MarkdownBody body={previewMarkdown(text)} />;
}

function Detail({ detail }: { detail: AttentionDetail }) {
  switch (detail.kind) {
    case 'text':
      return <div className="atn-detail"><div className="atn-detail__lead">{detail.text}</div></div>;
    case 'quote':
      return (
        <div className="atn-detail">
          <Comment text={detail.text} />
          {detail.file !== null && <div className="atn-detail__file" title={detail.file}>on {detail.file.slice(detail.file.lastIndexOf('/') + 1)}</div>}
        </div>
      );
    case 'commits':
      return (
        <div className="atn-detail">
          {detail.lead !== null && <div className="atn-detail__lead">{detail.lead}</div>}
          <Commits commits={detail.commits} />
        </div>
      );
    case 'command':
      return (
        <div className="atn-detail">
          {detail.lead !== null && <div className="atn-detail__lead">{detail.lead}</div>}
          <div className="atn-detail__command">$ {detail.command}</div>
        </div>
      );
    case 'ci':
      return (
        <div className="atn-detail">
          <Commits commits={detail.commits} />
          <div className="atn-detail__failed">
            <CircleX size={14} strokeWidth={2} aria-hidden="true" />
            {detail.checks.length === 0 ? 'Checks failed' : `${detail.checks[0]} failed`}
          </div>
          {detail.checks.length > 1 && <div className="atn-detail__lead">and {detail.checks.length - 1} more</div>}
        </div>
      );
    case 'status':
      return (
        <div className="atn-detail">
          <div className="atn-detail__status">
            <span className={`atn-status is-${detail.from.tone} is-from`}>{detail.from.name}</span>
            <ArrowRight size={14} strokeWidth={2} aria-label="to" />
            <span className={`atn-status is-${detail.to.tone}`}>{detail.to.name}</span>
          </div>
          {detail.note !== null && <Comment text={detail.note} />}
        </div>
      );
    case 'labels':
      return (
        <div className="atn-detail">
          {detail.lead !== null && <div className="atn-detail__lead">{detail.lead}</div>}
          <div className="atn-detail__labels">
            {detail.labels.map(label => <span className="atn-label" key={label.name} style={labelStyle(label)}>{label.name}</span>)}
          </div>
        </div>
      );
  }
}

type Props = {
  item: AttentionItem;
  viewerLogin: string | null;
  /** Global Home names each card's repository; a workspace's own cards need not. */
  showRepo: boolean;
  onOpen: () => void;
  onAcknowledge: () => void;
  onSnooze: (button: HTMLElement) => void;
  /** Present for an issue or ticket that can start development. */
  onStart?: () => void;
  /** Whether the item is in the pinned goal, shown as a badge in the strip. */
  inGoal?: boolean;
  /** Adds the item to the pinned goal, or takes it out; absent where no goal can hold it. */
  onToggleGoal?: () => void;
};

/**
 * One item that needs you: a strip tinted by the source that produced it and naming where the item
 * stands, then who moved it, folded over what happened until the line is clicked. The age in the
 * strip gives way to the card's actions while the pointer or focus is inside it.
 */
export default function AttentionCard({ item, viewerLogin, showRepo, onOpen, onAcknowledge, onSnooze, onStart, inGoal = false, onToggleGoal }: Props) {
  const source = attentionSource(item);
  const summary = attentionSummary(item, viewerLogin);
  const reference = item.reference ?? `#${item.number}`;
  const time = attentionTime(item);
  const [owner, repo = ''] = item.repo.split('/');
  const act = (action: () => void) => (event: MouseEvent) => { event.stopPropagation(); action(); };
  const said = <>
    {summary.actor !== null && (
      <Avatar login={summary.actor.name} avatarUrl={summary.actor.avatarUrl} size={18} allowGitHubFallback={source === 'pr' || source === 'issue'} />
    )}
    <span className="atn-card__verb">{summary.actor !== null && <span className="atn-card__actor">{summary.actor.name}</span>} {summary.verb}</span>
  </>;
  return (
    <div
      className={`atn-card is-${source}`}
      role="button"
      tabIndex={0}
      aria-label={`${ATTENTION_SOURCES[source].label} ${reference}: ${item.title}`}
      onClick={onOpen}
      onKeyDown={event => {
        if (event.target !== event.currentTarget) return;
        if (event.key !== 'Enter' && event.key !== ' ') return;
        event.preventDefault();
        onOpen();
      }}
    >
      <div className="atn-card__head">
        <SourceIcon source={source} />
        <span className="atn-card__key">{reference}</span>
        {showRepo && item.repo !== '' && (
          // An issue names the workspace it would start in, so a repository watched twice is never ambiguous.
          <span className="atn-card__repo"><RepoAvatar owner={owner} repo={repo} size={12} radius={3} />{item.workspaceName || item.repo}</span>
        )}
        <span className={`atn-tag is-${summary.tag.tone}`}>{summary.tag.label}</span>
        {inGoal && <span className="atn-goal" title="In goal"><GoalIcon size={11} />Goal</span>}
        <span className="atn-spacer" />
        <span className="atn-card__actions" role="group" aria-label={`Actions for ${reference}`}>
          {onToggleGoal !== undefined && (
            <button className={`atn-iconbtn is-goal${inGoal ? ' is-active' : ''}`} type="button" title={inGoal ? 'Remove from goal' : 'Add to goal'}
              aria-label={`${inGoal ? 'Remove' : 'Add'} ${reference} ${inGoal ? 'from' : 'to'} the goal`} aria-pressed={inGoal} onClick={act(onToggleGoal)}>
              <GoalIcon size={15} />
            </button>
          )}
          {onStart !== undefined && (
            <button className="atn-iconbtn" type="button" title={`Start development in ${item.workspaceName || item.repo}`}
              aria-label={`Start development for ${reference}`} onClick={act(onStart)}>
              <Play size={14} strokeWidth={1.9} aria-hidden="true" />
            </button>
          )}
          <button className="atn-iconbtn is-ack" type="button" title="Acknowledge — clears it until something new happens"
            aria-label={`Acknowledge ${reference}`} onClick={act(onAcknowledge)}>
            <Check size={15} strokeWidth={2} aria-hidden="true" />
          </button>
          <button className="atn-iconbtn" type="button" title="Snooze" aria-label={`Snooze ${reference}`}
            onClick={event => { event.stopPropagation(); onSnooze(event.currentTarget); }}>
            <Clock3 size={15} strokeWidth={1.8} aria-hidden="true" />
          </button>
        </span>
        <time className="atn-card__age" dateTime={time} title={new Date(time).toLocaleString()}>
          {relativeTime(time, { suffix: false })}
        </time>
        <ChevronRight className="atn-card__chev" size={13} aria-hidden="true" />
      </div>
      <div className="atn-card__body">
        <div className="atn-card__title">{item.title}</div>
        <Event detail={summary.detail}>{said}</Event>
        <Links item={item} />
      </div>
    </div>
  );
}

/** Who did what, folded like the linked items: the line unfolds what happened in place. */
function Event({ detail, children }: { detail: AttentionDetail | null; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  if (detail === null) return <div className="atn-card__why">{children}</div>;
  return <>
    <button className="atn-fold" type="button" aria-expanded={open} title={open ? 'Hide details' : 'Show details'}
      onClick={event => { event.stopPropagation(); setOpen(value => !value); }}>
      <ChevronRight className="atn-fold__chev" size={12} strokeWidth={2.4} aria-hidden="true" />
      {children}
    </button>
    <div hidden={!open}><Detail detail={detail} /></div>
  </>;
}

/** Linked items start folded to one row of icons and keys; the row unfolds them in place. */
function Links({ item }: { item: AttentionItem }) {
  const [open, setOpen] = useState(false);
  const links = item.links ?? [];
  if (links.length === 0) return null;
  return (
    <div className="atn-links">
      <button className="atn-fold" type="button" aria-expanded={open} title={open ? 'Hide linked items' : 'Show linked items'}
        onClick={event => { event.stopPropagation(); setOpen(value => !value); }}>
        <ChevronRight className="atn-fold__chev" size={12} strokeWidth={2.4} aria-hidden="true" />
        {links.map(link => (
          <span className={`atn-link-key is-${link.source}`} key={`${link.source}:${link.key}`}>
            <SourceIcon source={link.source} size={14} />
            <span>{link.key}</span>
          </span>
        ))}
      </button>
      {open && links.map(link => (
        <div className={`atn-link is-${link.source}`} key={`${link.source}:${link.key}`}>
          <SourceIcon source={link.source} size={14} />
          <span className="atn-link__key">{link.key}</span>
          <span className="atn-link__title">{link.title}</span>
          {link.status !== null && <span className={`atn-link__status is-${link.tone}`}>{link.status}</span>}
        </div>
      ))}
    </div>
  );
}

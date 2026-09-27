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
import { useState, type MouseEvent } from 'react';
import { Check, ChevronRight, CircleDot, CircleX, Clock3, GitPullRequest, Play, SquareCheck } from 'lucide-react';
import Avatar from '../Avatar';
import JiraIcon from '../jira/JiraIcon';
import RepoAvatar from '../RepoAvatar';
import { relativeTime } from '../relativeTime';
import {
  ATTENTION_SOURCES,
  attentionSource,
  attentionSummary,
  attentionTime,
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

function Detail({ detail }: { detail: AttentionDetail }) {
  switch (detail.kind) {
    case 'text':
      return <div className="atn-detail"><div className="atn-detail__lead">{detail.text}</div></div>;
    case 'ci':
      return (
        <div className="atn-detail">
          <div className="atn-detail__failed">
            <CircleX size={14} strokeWidth={2} aria-hidden="true" />
            {detail.checks.length === 0 ? 'Checks failed' : `${detail.checks[0]} failed`}
          </div>
          {detail.checks.length > 1 && <div className="atn-detail__lead">and {detail.checks.length - 1} more</div>}
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
};

/**
 * One item that needs you: a strip tinted by the source that produced it, then what happened. The
 * age in the strip gives way to the card's actions while the pointer or focus is inside it.
 */
export default function AttentionCard({ item, viewerLogin, showRepo, onOpen, onAcknowledge, onSnooze, onStart }: Props) {
  const source = attentionSource(item);
  const summary = attentionSummary(item, viewerLogin);
  const reference = item.reference ?? `#${item.number}`;
  const time = attentionTime(item);
  const [owner, repo = ''] = item.repo.split('/');
  const act = (action: () => void) => (event: MouseEvent) => { event.stopPropagation(); action(); };
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
        <span className="atn-card__type">{ATTENTION_SOURCES[source].label}</span>
        <span className="atn-card__key">{reference}</span>
        {showRepo && item.repo !== '' && (
          // An issue names the workspace it would start in, so a repository watched twice is never ambiguous.
          <span className="atn-card__repo"><RepoAvatar owner={owner} repo={repo} size={12} radius={3} />{item.workspaceName || item.repo}</span>
        )}
        <span className="atn-spacer" />
        <span className="atn-card__actions" role="group" aria-label={`Actions for ${reference}`}>
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
        <div className="atn-card__why">
          <span className={`atn-tag is-${summary.tag.tone}`}>{summary.tag.label}</span>
          {summary.actor !== null && (
            <Avatar login={summary.actor.name} avatarUrl={summary.actor.avatarUrl} size={18} allowGitHubFallback={source === 'pr' || source === 'issue'} />
          )}
          <span className="atn-card__verb">{summary.actor !== null && <b>{summary.actor.name}</b>} {summary.verb}</span>
        </div>
        {summary.detail !== null && <Detail detail={summary.detail} />}
        <Links item={item} />
      </div>
    </div>
  );
}

/** Linked items start folded to one row of icons and keys; the row unfolds them in place. */
function Links({ item }: { item: AttentionItem }) {
  const [open, setOpen] = useState(false);
  const links = item.links ?? [];
  if (links.length === 0) return null;
  return (
    <div className="atn-links">
      <button className="atn-links__fold" type="button" aria-expanded={open} title={open ? 'Hide linked items' : 'Show linked items'}
        onClick={event => { event.stopPropagation(); setOpen(value => !value); }}>
        <ChevronRight className="atn-links__chev" size={12} strokeWidth={2.4} aria-hidden="true" />
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

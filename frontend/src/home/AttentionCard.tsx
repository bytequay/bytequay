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
import type { ReactNode } from 'react';
import { ArrowRight, Check, ChevronRight, CircleX, Clock3, Play } from 'lucide-react';
import Avatar from '../Avatar';
import { Card, CardActions, CardAge, Fold, GoalIcon, IconButton, InGoalBadge, LinkKey, Pill, SourceIcon } from '../cards/Card';
import MarkdownBody from '../pulls/MarkdownBody';
import { previewMarkdown } from '../ReferenceCard';
import RepoAvatar from '../RepoAvatar';
import {
  ATTENTION_SOURCES,
  attentionSource,
  attentionSummary,
  attentionTime,
  type AttentionCommit,
  type AttentionDetail,
  type AttentionItem,
  type AttentionLabel,
} from './attention';

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

function Box({ children }: { children: ReactNode }) {
  return <div className="card-box atn-detail">{children}</div>;
}

function Detail({ detail }: { detail: AttentionDetail }) {
  switch (detail.kind) {
    case 'text':
      return <Box><div className="atn-detail__lead">{detail.text}</div></Box>;
    case 'quote':
      return (
        <Box>
          <Comment text={detail.text} />
          {detail.file !== null && <div className="atn-detail__file" title={detail.file}>on {detail.file.slice(detail.file.lastIndexOf('/') + 1)}</div>}
        </Box>
      );
    case 'commits':
      return (
        <Box>
          {detail.lead !== null && <div className="atn-detail__lead">{detail.lead}</div>}
          <Commits commits={detail.commits} />
        </Box>
      );
    case 'command':
      return (
        <Box>
          {detail.lead !== null && <div className="atn-detail__lead">{detail.lead}</div>}
          <div className="atn-detail__command">$ {detail.command}</div>
        </Box>
      );
    case 'ci':
      return (
        <Box>
          <Commits commits={detail.commits} />
          <div className="atn-detail__failed">
            <CircleX size={14} strokeWidth={2} aria-hidden="true" />
            {detail.checks.length === 0 ? 'Checks failed' : `${detail.checks[0]} failed`}
          </div>
          {detail.checks.length > 1 && <div className="atn-detail__lead">and {detail.checks.length - 1} more</div>}
        </Box>
      );
    case 'status':
      return (
        <Box>
          <div className="atn-detail__status">
            <Pill tone={detail.from.tone} className="is-square is-struck">{detail.from.name}</Pill>
            <ArrowRight size={14} strokeWidth={2} aria-label="to" />
            <Pill tone={detail.to.tone} className="is-square">{detail.to.name}</Pill>
          </div>
          {detail.note !== null && <Comment text={detail.note} />}
        </Box>
      );
    case 'labels':
      return (
        <Box>
          {detail.lead !== null && <div className="atn-detail__lead">{detail.lead}</div>}
          <div className="atn-detail__labels">
            {detail.labels.map(label => <Pill key={label.name} style={labelStyle(label)}>{label.name}</Pill>)}
          </div>
        </Box>
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
  const [owner, repo = ''] = item.repo.split('/');
  const said = <>
    {summary.actor !== null && (
      <Avatar login={summary.actor.name} avatarUrl={summary.actor.avatarUrl} size={18} allowGitHubFallback={source === 'pr' || source === 'issue'} />
    )}
    <span className="atn-card__verb">{summary.actor !== null && <span className="atn-card__actor">{summary.actor.name}</span>} {summary.verb}</span>
  </>;
  return (
    <Card className={`atn-card is-${source}`} label={`${ATTENTION_SOURCES[source].label} ${reference}: ${item.title}`} onOpen={onOpen}>
      <div className="atn-card__head">
        <SourceIcon source={source} />
        <span className="atn-card__key">{reference}</span>
        {showRepo && item.repo !== '' && (
          // An issue names the workspace it would start in, so a repository watched twice is never ambiguous.
          <span className="atn-card__repo"><RepoAvatar owner={owner} repo={repo} size={12} radius={3} />{item.workspaceName || item.repo}</span>
        )}
        <Pill outline>{summary.tag.label}</Pill>
        {inGoal && <InGoalBadge />}
        <span className="atn-spacer" />
        <CardActions label={`Actions for ${reference}`}>
          {onToggleGoal !== undefined && (
            <IconButton label={`${inGoal ? 'Remove' : 'Add'} ${reference} ${inGoal ? 'from' : 'to'} the goal`} title={inGoal ? 'Remove from goal' : 'Add to goal'}
              tone={inGoal ? 'active' : undefined} pressed={inGoal} onClick={onToggleGoal}>
              <GoalIcon size={15} />
            </IconButton>
          )}
          {onStart !== undefined && (
            <IconButton label={`Start development for ${reference}`} title={`Start development in ${item.workspaceName || item.repo}`} onClick={onStart}>
              <Play size={14} strokeWidth={1.9} aria-hidden="true" />
            </IconButton>
          )}
          <IconButton label={`Acknowledge ${reference}`} title="Acknowledge — clears it until something new happens" tone="ack" onClick={onAcknowledge}>
            <Check size={15} strokeWidth={2} aria-hidden="true" />
          </IconButton>
          <IconButton label={`Snooze ${reference}`} title="Snooze" onClick={event => onSnooze(event.currentTarget)}>
            <Clock3 size={15} strokeWidth={1.8} aria-hidden="true" />
          </IconButton>
        </CardActions>
        <CardAge at={attentionTime(item)} />
        <ChevronRight className="atn-card__chev" size={13} aria-hidden="true" />
      </div>
      <div className="atn-card__body">
        <div className="atn-card__title">{item.title}</div>
        {summary.detail === null
          ? <div className="atn-card__why">{said}</div>
          : <Fold summary={said} showTitle="Show details" hideTitle="Hide details"><Detail detail={summary.detail} /></Fold>}
        <Links item={item} />
      </div>
    </Card>
  );
}

/** Linked items start folded to one row of icons and keys; the row unfolds them in place. */
function Links({ item }: { item: AttentionItem }) {
  const links = item.links ?? [];
  if (links.length === 0) return null;
  return (
    <div className="atn-links">
      <Fold showTitle="Show linked items" hideTitle="Hide linked items"
        summary={links.map(link => <LinkKey key={`${link.source}:${link.key}`} source={link.source} name={link.key} />)}>
        {links.map(link => (
          <div className="card-fold-row" key={`${link.source}:${link.key}`}>
            <SourceIcon source={link.source} tinted />
            <span className="card-key">{link.key}</span>
            <span className="atn-link__title">{link.title}</span>
            {link.status !== null && <Pill tone={link.tone}>{link.status}</Pill>}
          </div>
        ))}
      </Fold>
    </div>
  );
}

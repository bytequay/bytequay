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
import { useState } from 'react';
import type { WorkspaceCardDto } from '../types';

/** Render the repo owner's GitHub avatar on the first paint. The workspace
 *  summary already carries the owner, so a second metadata lookup only
 *  caused a coloured-letter flash before the same image appeared. */
function RepoLogo({ repo, owner }: { repo: string; owner?: string }) {
  const [failedOwner, setFailedOwner] = useState<string | null>(null);

  if (owner !== undefined && owner !== '' && failedOwner !== owner) {
    return (
      <img
        src={`https://github.com/${encodeURIComponent(owner)}.png?size=72`}
        alt=""
        title={repo}
        className="workspace-landing-card__repo-avatar"
        onError={() => setFailedOwner(owner)}
      />
    );
  }
  return (
    <span
      className="workspace-landing-card__repo-fallback"
      title={repo}
      aria-label={`${repo} repository`}
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor"
        strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H19a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H6.5A2.5 2.5 0 0 1 4 18.5v-13Z" />
        <path d="M4 18.5A2.5 2.5 0 0 1 6.5 16H20M8 3v13" />
      </svg>
    </span>
  );
}

type Props = {
  card: WorkspaceCardDto;
  /** True when this is the workspace the user most recently entered.
   *  Drives the CURRENT chip + a primary-coloured ring. */
  isCurrent: boolean;
  onEnter: (workspaceId: string) => void;
  /** When set, a hover-revealed delete affordance is shown on the card
   *  (real workspaces only). The host confirms + calls the backend. */
  onDelete?: (workspaceId: string) => void;
};

/** One tile in the Workspaces landing grid. Mirrors the claude_design
 *  mockup (docs/mockups/design/claude_design_v1): the primary repo's
 *  Logo badge + name + CURRENT chip in the header, an activity status
 *  line, repo chips, a three-stat row, and a footer that surfaces
 *  last-edited time, the "N needs you" amber chip, and the Enter
 *  affordance. The whole tile is a button so the keyboard hits it as
 *  one focusable affordance. */
function WorkspaceCard({ card, isCurrent, onEnter, onDelete }: Props) {
  const recentThreads = card.recentActivity ?? [];

  if (card.isScratch) {
    return <ScratchCard card={card} onEnter={onEnter} />;
  }
  return (
    <div className="workspace-landing-card-wrap">
    <div
      role="button"
      tabIndex={0}
      className={`workspace-landing-card${isCurrent ? ' workspace-landing-card--current' : ''}`}
      onClick={() => onEnter(card.id)}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onEnter(card.id);
        }
      }}
      aria-label={`Enter workspace ${card.name}`}
    >
      <header className="workspace-landing-card__head">
        <RepoLogo repo={card.repos[0] ?? card.name} owner={card.repository?.owner} />
        <div className="workspace-landing-card__heading">
          <div className="workspace-landing-card__name-row">
            <span className="workspace-landing-card__name" title={card.id}>{card.name}</span>
            {isCurrent && (
              <span className="workspace-landing-card__chip">CURRENT</span>
            )}
            {!isCurrent && card.needsAttentionCount > 0 && (
              <span className="workspace-landing-card__chip workspace-landing-card__chip--attention">
                {card.needsAttentionCount} need you
              </span>
            )}
          </div>
          <div className="workspace-landing-card__meta">
            {card.repository?.fullName ?? card.repos[0] ?? card.name}
          </div>
        </div>
      </header>

      <div className={`workspace-landing-card__status${
        card.tasksInFlight === 0 ? ' workspace-landing-card__status--idle' : ''}`}>
        <span
          className={`workspace-landing-card__live-dot${
            card.tasksInFlight === 0 ? ' workspace-landing-card__live-dot--idle' : ''}`}
          aria-hidden
        />
        <span>{activeSummary(card)}</span>
      </div>

      <div className="workspace-landing-card__feed" aria-label="Recent activity">
        {recentThreads.length === 0 ? (
          <div className="workspace-landing-card__feed-row workspace-landing-card__feed-row--empty">
            <span aria-hidden>·</span>
            <span>No recent activity</span>
          </div>
        ) : recentThreads.map(thread => (
          <div className="workspace-landing-card__feed-row" key={thread.id}>
            <span
              className={`workspace-landing-card__feed-mark ${activityTone(thread.title)}`}
              aria-hidden
            >
              <ActivityIcon title={thread.title} />
            </span>
            <span className="workspace-landing-card__feed-title">{thread.title}</span>
            <span className="workspace-landing-card__feed-time">
              {relativeTime(thread.occurredAt)}
            </span>
          </div>
        ))}
      </div>

      <footer className="workspace-landing-card__foot">
        <FooterBranchIcon />
        <WorkspaceWorkSummary card={card} isCurrent={isCurrent} />
        <span className="separator" aria-hidden>·</span>
        <span>{formatSpend(card.spendTodayMilliUsd)} today</span>
        <span className="separator" aria-hidden>·</span>
        <span>{relativeTime(card.lastActivityMs)}</span>
        <span className="workspace-landing-card__enter">Enter →</span>
      </footer>
    </div>
      {onDelete !== undefined && (
        <button
          type="button"
          className="workspace-landing-card__delete"
          aria-label={`Delete workspace ${card.name}`}
          title="Delete workspace"
          onClick={() => onDelete(card.id)}
        >
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
            strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
            <path d="M3 6h18M8 6V4h8v2M19 6l-1 15H6L5 6M10 10v7M14 10v7" />
          </svg>
        </button>
      )}
    </div>
  );
}

/** Muted card for a scratch workspace. Kept for compatibility while the
 *  workspace≡repo migration retires scratch creation from the public UI. */
function ScratchCard({
  card, onEnter,
}: { card: WorkspaceCardDto; onEnter: (id: string) => void }) {
  return (
    <div
      role="button"
      tabIndex={0}
      className="workspace-landing-card workspace-landing-card--scratch"
      onClick={() => onEnter(card.id)}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onEnter(card.id);
        }
      }}
      aria-label={`Enter scratch workspace ${card.name}`}
    >
      <header className="workspace-landing-card__head">
        <span className="workspace-landing-card__avatar--scratch" aria-hidden>—</span>
        <div className="workspace-landing-card__heading">
          <div className="workspace-landing-card__name-row">
            <span className="workspace-landing-card__name">{card.name}</span>
          </div>
          <div className="workspace-landing-card__meta">Temporary workspace</div>
        </div>
      </header>
      <div className="workspace-landing-card__feed">
        <div className="workspace-landing-card__feed-row workspace-landing-card__feed-row--empty">
          <span aria-hidden>·</span>
          <span>No repository or durable memory</span>
        </div>
      </div>
      <footer className="workspace-landing-card__foot">
        <span>idle</span>
        <span className="workspace-landing-card__enter">Enter →</span>
      </footer>
    </div>
  );
}

function activeSummary(card: WorkspaceCardDto): string {
  if (card.tasksInFlight > 0) {
    if (card.activeThreadCount === 1) {
      return '1 session running · review sweep';
    }
    return `Agent running · ${card.activeThreadCount} ${
      pluralize('thread', card.activeThreadCount)} active`;
  }
  if (card.activeThreadCount > 0) {
    return `${card.activeThreadCount} ${pluralize('thread', card.activeThreadCount)} active`;
  }
  return 'Idle · no active threads';
}

function WorkspaceWorkSummary({
  card, isCurrent,
}: { card: WorkspaceCardDto; isCurrent: boolean }) {
  if (isCurrent) {
    return (
      <span>
        {card.activeThreadCount} PRs ·{' '}
        <strong className="attention">
          {card.needsAttentionCount} {pluralize('review', card.needsAttentionCount)}
        </strong>
      </span>
    );
  }
  if (card.needsAttentionCount > 0) {
    return <strong className="attention">{card.needsAttentionCount} to review</strong>;
  }
  return <span>{card.activeThreadCount} open</span>;
}

function FooterBranchIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden>
      <circle cx="18" cy="18" r="2.6" />
      <circle cx="6" cy="6" r="2.6" />
      <path d="M13 6h3a2 2 0 0 1 2 2v7" />
      <path d="M6 9v12" />
    </svg>
  );
}

function ActivityIcon({ title }: { title: string }) {
  if (title.startsWith('Brain replied')) {
    return (
      <svg viewBox="0 0 24 24"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" /></svg>
    );
  }
  if (title.includes('RELEASE-BLOCKER')) {
    return <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5" /><circle className="filled" cx="12" cy="12" r="2.6" /></svg>;
  }
  if (title.startsWith('Review round')) {
    return <svg viewBox="0 0 24 24"><path d="M20 6 9 17l-5-5" /></svg>;
  }
  if (title.startsWith('branch cleanup')) {
    return <svg viewBox="0 0 24 24"><path d="m6 3 12 0" /><path d="M6 3v18" /><path d="M18 3v18" /></svg>;
  }
  if (title.includes('requested your review')) {
    return (
      <svg viewBox="0 0 24 24"><circle cx="18" cy="18" r="2.6" /><circle cx="6" cy="6" r="2.6" /><path d="M13 6h3a2 2 0 0 1 2 2v7M6 9v12" /></svg>
    );
  }
  return (
    <svg viewBox="0 0 24 24"><circle cx="18" cy="18" r="2.6" /><circle cx="6" cy="6" r="2.6" /><path d="M6 21V9a9 9 0 0 0 9 9" /></svg>
  );
}

function activityTone(title: string): string {
  if (title.startsWith('Review round')) return 'success';
  if (title.includes('requested your review')) return 'attention';
  if (title.startsWith('Task merged')) return 'purple';
  return 'muted';
}

function pluralize(word: string, count: number): string {
  return count === 1 ? word : `${word}s`;
}

function formatSpend(milliUsd: number): string {
  if (milliUsd <= 0) {
    return '$0';
  }
  const usd = milliUsd / 1000;
  if (usd >= 10) {
    return `$${usd.toFixed(0)}`;
  }
  return `$${usd.toFixed(2)}`;
}

/** Short relative-time renderer for the card's last-activity line.
 *  Stays under a dozen characters so the metadata row doesn't wrap
 *  on the narrowest grid cell. */
function relativeTime(ms: number | null): string {
  if (ms == null) {
    return 'not touched yet';
  }
  const diff = Date.now() - ms;
  if (diff < 60_000) {
    return 'just now';
  }
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 60) {
    return `${minutes}m`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours}h`;
  }
  const days = Math.floor(hours / 24);
  if (days < 30) {
    return `${days}d`;
  }
  return new Date(ms).toLocaleDateString();
}

export default WorkspaceCard;

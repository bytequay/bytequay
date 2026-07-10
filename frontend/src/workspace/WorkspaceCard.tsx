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
import { Logo } from '../ui/primitives';
import type { LogoColor } from '../ui/primitives';
import { logoColorFor, monogram } from '../pages/useWorkspaceNav';
import { resolveAvatarByRepoName } from '../threads/RepoAvatar';
import type { WorkspaceCardDto } from '../types';

/** Soft chip tint per logo colour, so a repo's pill chip matches its
 *  Logo badge. Reuses base.css's semantic soft/border tokens where one
 *  already exists (teal/orange/blue); the rest are the same hue as the
 *  matching .v3-logo--* gradient's dark stop (see css/v3.css). */
const CHIP_STYLE: Record<LogoColor, React.CSSProperties> = {
  purple: { background: 'rgba(139, 92, 246, 0.1)', color: '#7c3aed', border: '1px solid rgba(139, 92, 246, 0.22)' },
  teal: { background: 'var(--teal-soft)', color: 'var(--teal)', border: '1px solid var(--teal-border)' },
  orange: { background: 'var(--orange-soft)', color: 'var(--orange)', border: '1px solid var(--orange-border)' },
  blue: { background: 'var(--blue-soft)', color: 'var(--blue)', border: '1px solid var(--blue-border)' },
  pink: { background: 'rgba(219, 39, 119, 0.1)', color: '#db2777', border: '1px solid rgba(219, 39, 119, 0.24)' },
  slate: { background: 'rgba(71, 85, 105, 0.1)', color: '#475569', border: '1px solid rgba(71, 85, 105, 0.22)' },
};

/** The card's primary-repo icon: the repo's real GitHub owner avatar
 *  when one's resolvable from the tracked local-repo list, falling
 *  back to the generic Logo badge (same as every other repo icon in
 *  the app) while it resolves or when there's no match. */
function RepoLogo({ repo }: { repo: string }) {
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    setUrl(null);
    let cancelled = false;
    void resolveAvatarByRepoName(repo).then(u => {
      if (!cancelled) setUrl(u);
    });
    return () => { cancelled = true; };
  }, [repo]);

  if (url) {
    return (
      <img
        src={url}
        alt=""
        title={repo}
        className="workspace-landing-card__repo-avatar"
        onError={() => setUrl(null)}
      />
    );
  }
  return <Logo initials={monogram(repo)} color={logoColorFor(repo)} size="lg" title={repo} />;
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
  if (card.isScratch) {
    return <ScratchCard card={card} onEnter={onEnter} />;
  }
  return (
    <div className="workspace-landing-card-wrap">
    <button
      type="button"
      className={`workspace-landing-card${isCurrent ? ' workspace-landing-card--current' : ''}`}
      onClick={() => onEnter(card.id)}
      aria-label={`Enter workspace ${card.name}`}
    >
      {isCurrent && <span className="workspace-landing-card__strip" aria-hidden />}
      <header className="workspace-landing-card__head">
        <RepoLogo repo={card.repos[0] ?? card.name} />
        <div className="workspace-landing-card__heading">
          <div className="workspace-landing-card__name-row">
            <span className="workspace-landing-card__name" title={card.id}>{card.name}</span>
            {isCurrent && (
              <span className="workspace-landing-card__chip">CURRENT</span>
            )}
          </div>
          <div className="workspace-landing-card__meta">
            <span
              className={`workspace-landing-card__live-dot${
                card.activeThreadCount === 0 ? ' workspace-landing-card__live-dot--idle' : ''}`}
              aria-hidden
            />
            <span>{activeSummary(card.activeThreadCount)}</span>
          </div>
        </div>
      </header>

      {card.repos.length > 0 && (
        <div className="workspace-landing-card__repos" aria-label="Repos">
          {card.repos.map(repo => (
            <span
              key={repo}
              className="workspace-landing-card__repo"
              style={CHIP_STYLE[logoColorFor(repo)]}
            >
              {repo}
            </span>
          ))}
        </div>
      )}

      <div className="workspace-landing-card__stats">
        <Stat label="threads" value={String(card.activeThreadCount)} />
        <Stat label="in flight" value={String(card.tasksInFlight)} />
        <Stat
          label="today"
          value={formatSpend(card.spendTodayMilliUsd)}
          dimWhenZero={card.spendTodayMilliUsd === 0}
        />
      </div>

      <footer className="workspace-landing-card__foot">
        <span className="workspace-landing-card__edited">
          edited {relativeTime(card.lastActivityMs)}
        </span>
        {card.needsAttentionCount > 0 && (
          <span className="workspace-landing-card__attention">
            {card.needsAttentionCount} {pluralize('needs', card.needsAttentionCount)} you
          </span>
        )}
        <span className="workspace-landing-card__enter">Enter →</span>
      </footer>
    </button>
      {onDelete !== undefined && (
        <button
          type="button"
          className="workspace-landing-card__delete"
          aria-label={`Delete workspace ${card.name}`}
          title="Delete workspace"
          onClick={() => onDelete(card.id)}
        >
          ⌫
        </button>
      )}
    </div>
  );
}

/** Muted card for a scratch workspace. Same outer shape so the grid
 *  reflow doesn't reorder, but no memory strip, dashed border, and
 *  zeroed stats — matches the design's "throwaway" treatment. */
function ScratchCard({
  card, onEnter,
}: { card: WorkspaceCardDto; onEnter: (id: string) => void }) {
  return (
    <button
      type="button"
      className="workspace-landing-card workspace-landing-card--scratch"
      onClick={() => onEnter(card.id)}
      aria-label={`Enter scratch workspace ${card.name}`}
    >
      <header className="workspace-landing-card__head">
        <span className="workspace-landing-card__avatar--scratch" aria-hidden>
          —
        </span>
        <div className="workspace-landing-card__heading">
          <div className="workspace-landing-card__name-row">
            <span className="workspace-landing-card__name">{card.name}</span>
          </div>
          <div className="workspace-landing-card__meta">
            throwaway · no durable memory
          </div>
        </div>
      </header>
      <p className="workspace-landing-card__scratch-blurb">
        One-off exploration lands here. Accrues no project memory and never
        contaminates a real workspace — promote a thread out if it turns into
        real work.
      </p>
      <div className="workspace-landing-card__stats">
        <Stat label="threads" value="0" dimWhenZero />
        <Stat label="tasks" value="0" dimWhenZero />
        <Stat label="memory" value="—" dimWhenZero />
      </div>
      <footer className="workspace-landing-card__foot">
        <span className="workspace-landing-card__edited">idle</span>
        <span className="workspace-landing-card__enter">Enter →</span>
      </footer>
    </button>
  );
}

function Stat({
  label, value, dimWhenZero,
}: { label: string; value: string; dimWhenZero?: boolean }) {
  return (
    <div
      className={`workspace-landing-card__stat${
        dimWhenZero ? ' workspace-landing-card__stat--dim' : ''}`}
    >
      <span className="workspace-landing-card__stat-value">{value}</span>
      <span className="workspace-landing-card__stat-label">{label}</span>
    </div>
  );
}

function activeSummary(count: number): string {
  if (count === 0) {
    return 'no active threads · idle';
  }
  return `${count} active ${pluralize('thread', count)} · active now`;
}

function pluralize(word: string, count: number): string {
  if (word === 'needs') {
    // "1 needs you" / "3 need you" — match the design's chip copy.
    return count === 1 ? 'needs' : 'need';
  }
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
    return 'no activity yet';
  }
  const diff = Date.now() - ms;
  if (diff < 60_000) {
    return 'just now';
  }
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 60) {
    return `${minutes}m ago`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours}h ago`;
  }
  const days = Math.floor(hours / 24);
  if (days < 30) {
    return `${days}d ago`;
  }
  return new Date(ms).toLocaleDateString();
}

export default WorkspaceCard;

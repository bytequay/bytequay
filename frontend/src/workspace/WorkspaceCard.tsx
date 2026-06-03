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
import type { WorkspaceCardDto } from '../types';

type Props = {
  card: WorkspaceCardDto;
  /** True when this is the workspace the user most recently entered.
   *  Drives the CURRENT chip + a primary-coloured ring. */
  isCurrent: boolean;
  onEnter: (workspaceId: string) => void;
};

/** One tile in the Workspaces landing grid. Mirrors the structure of
 *  the design mockup: avatar + name + CURRENT chip + ⋯ menu in the
 *  header, repo chips, a three-stat row, a memory strip with a budget
 *  bar, and a footer that surfaces the "N needs you" amber chip,
 *  last-edited time, and the Enter affordance. The whole tile is a
 *  button so the keyboard hits it as one focusable affordance. */
function WorkspaceCard({ card, isCurrent, onEnter }: Props) {
  if (card.isScratch) {
    return <ScratchCard card={card} onEnter={onEnter} />;
  }
  return (
    <button
      type="button"
      className={`workspace-landing-card${isCurrent ? ' workspace-landing-card--current' : ''}`}
      onClick={() => onEnter(card.id)}
      aria-label={`Enter workspace ${card.name}`}
    >
      <header className="workspace-landing-card__head">
        <span
          className="workspace-landing-card__avatar"
          aria-hidden
          style={avatarGradient(card.color)}
        >
          {initialOf(card.name)}
        </span>
        <div className="workspace-landing-card__heading">
          <div className="workspace-landing-card__name-row">
            <span className="workspace-landing-card__name">{card.name}</span>
            <span className="workspace-landing-card__id" title={card.id}>
              {card.id}
            </span>
            {isCurrent && (
              <span className="workspace-landing-card__chip">CURRENT</span>
            )}
          </div>
          <div className="workspace-landing-card__meta">
            <span className="workspace-landing-card__live-dot" aria-hidden />
            <span>{activeSummary(card.activeThreadCount)}</span>
            <span aria-hidden>·</span>
            <span>{relativeTime(card.lastActivityMs)}</span>
          </div>
        </div>
        <span className="workspace-landing-card__menu" aria-hidden>⋯</span>
      </header>

      {card.repos.length > 0 && (
        <div className="workspace-landing-card__repos" aria-label="Repos">
          {card.repos.map(repo => (
            <span key={repo} className="workspace-landing-card__repo">
              {repo}
            </span>
          ))}
        </div>
      )}

      <div className="workspace-landing-card__stats">
        <Stat label="threads" value={String(card.activeThreadCount)} />
        <Stat label="tasks in flight" value={String(card.tasksInFlight)} />
        <Stat
          label="today"
          value={formatSpend(card.spendTodayMilliUsd)}
          dimWhenZero={card.spendTodayMilliUsd === 0}
        />
      </div>

      <div className="workspace-landing-card__memory">
        <span className="workspace-landing-card__memory-diamond" aria-hidden>◆</span>
        <span className="workspace-landing-card__memory-text">
          {card.memory.decisionCount} {pluralize('decision', card.memory.decisionCount)}
          {card.memory.blockerCount > 0 && (
            <>
              {' · '}
              <span className="workspace-landing-card__memory-blocker">
                {card.memory.blockerCount} {pluralize('blocker', card.memory.blockerCount)}
              </span>
            </>
          )}
        </span>
        <div
          className="workspace-landing-card__budget"
          role="img"
          aria-label={`Memory ${tokenLabel(card.memory.tokensUsed)} of ${tokenLabel(card.memory.tokensCap)} used`}
        >
          <div
            className="workspace-landing-card__budget-fill"
            style={{ width: `${budgetPercent(card.memory)}%` }}
          />
        </div>
        <span className="workspace-landing-card__budget-text">
          {tokenLabel(card.memory.tokensUsed)} / {tokenLabel(card.memory.tokensCap)}
        </span>
      </div>

      <footer className="workspace-landing-card__foot">
        {card.needsAttentionCount > 0 ? (
          <span className="workspace-landing-card__attention">
            {card.needsAttentionCount} {pluralize('needs', card.needsAttentionCount)} you
          </span>
        ) : (
          <span className="workspace-landing-card__edited">
            edited {relativeTime(card.lastActivityMs)}
          </span>
        )}
        <span className="workspace-landing-card__enter">Enter →</span>
      </footer>
    </button>
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
        <span
          className="workspace-landing-card__avatar workspace-landing-card__avatar--scratch"
          aria-hidden
        >
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

function initialOf(name: string): string {
  if (!name) {
    return '?';
  }
  const codePoint = name.codePointAt(0);
  return codePoint === undefined ? '?' : String.fromCodePoint(codePoint).toUpperCase();
}

function avatarGradient(color: string): React.CSSProperties {
  // The avatar reads as a soft gradient pill in the mockup; we layer a
  // lighter tint over the workspace's hash-derived colour so the start
  // and stop differ even when the colour is otherwise a single hue.
  return {
    background: `linear-gradient(135deg, ${color} 0%, ${lighten(color, 12)} 100%)`,
  };
}

/** Crude per-channel lighten used by the avatar gradient. The colours
 *  in the palette are dense enough that adding a few percent in HSL is
 *  overkill — additive R/G/B shifts read fine and have no perceptual
 *  surprises in this palette. */
function lighten(hex: string, amount: number): string {
  const trimmed = hex.startsWith('#') ? hex.slice(1) : hex;
  if (trimmed.length !== 6) {
    return hex;
  }
  const num = Number.parseInt(trimmed, 16);
  if (Number.isNaN(num)) {
    return hex;
  }
  const r = Math.min(255, ((num >> 16) & 0xff) + amount);
  const g = Math.min(255, ((num >> 8) & 0xff) + amount);
  const b = Math.min(255, (num & 0xff) + amount);
  return `#${((r << 16) | (g << 8) | b).toString(16).padStart(6, '0')}`;
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

function tokenLabel(tokens: number): string {
  if (tokens >= 1000) {
    return `${(tokens / 1000).toFixed(1)}k`;
  }
  return String(tokens);
}

function budgetPercent(memory: WorkspaceCardDto['memory']): number {
  if (memory.tokensCap <= 0) {
    return 0;
  }
  const pct = (memory.tokensUsed / memory.tokensCap) * 100;
  return Math.max(0, Math.min(100, pct));
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

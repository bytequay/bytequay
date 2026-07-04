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
import type { PullRequestDto, UserProfileDto } from '../types';
import Avatar from '../Avatar';
import YearInCodeHeatmap from '../YearInCodeHeatmap';

type Props = {
  profile: UserProfileDto | null;
  /** Cached PR list — the reviewed / contributed strips derive from it. */
  prs: PullRequestDto[] | null;
  onOpenPr: (owner: string, repo: string, prNumber: number) => void;
  /** "See all" on a strip — opens the PR-activity view on that tab. */
  onSeeAllActivity?: (kind: 'reviewed' | 'contributed') => void;
};

const STRIP_ROWS = 5;

/** True when the ISO timestamp falls on the current (UTC) calendar day —
 *  mirrors the workspace home's isUpdatedToday so "today" means the same
 *  thing across the app. */
function isToday(iso: string | null): boolean {
  if (iso === null) return false;
  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) return false;
  const now = new Date();
  return then.getUTCFullYear() === now.getUTCFullYear()
      && then.getUTCMonth() === now.getUTCMonth()
      && then.getUTCDate() === now.getUTCDate();
}

function openPrRow(pr: PullRequestDto, onOpenPr: Props['onOpenPr']) {
  const slash = pr.repo.indexOf('/');
  if (slash <= 0) return;
  onOpenPr(pr.repo.slice(0, slash), pr.repo.slice(slash + 1), pr.number);
}

function PrStripColumn({ label, accentClass, rows, onOpenPr, onSeeAll }: {
  label: string;
  accentClass: string;
  rows: PullRequestDto[];
  onOpenPr: Props['onOpenPr'];
  onSeeAll?: () => void;
}) {
  return (
    <div className="home-contrib__strip-col">
      <div className="home-contrib__strip-head">
        <span className={`home-contrib__strip-mark ${accentClass}`} aria-hidden="true" />
        <span className="home-contrib__strip-label">{label}</span>
        {onSeeAll && (
          <button type="button" className="home-contrib__strip-seeall" onClick={onSeeAll}>
            See all
          </button>
        )}
      </div>
      {rows.length === 0 ? (
        <p className="home-contrib__strip-empty">Nothing recent.</p>
      ) : rows.map(pr => (
        <button
          key={pr.id}
          type="button"
          className="home-contrib__strip-row"
          onClick={() => openPrRow(pr, onOpenPr)}
          title={`${pr.repo} #${pr.number}`}
        >
          <span className="home-contrib__strip-title">{pr.title}</span>
          <Avatar login={pr.repo.split('/')[0]} size={16} className="home-contrib__strip-logo" />
          <span className="home-contrib__strip-ref">#{pr.number}</span>
        </button>
      ))}
    </div>
  );
}

/**
 * Split the PR list into the home strip's three TODAY buckets. Each is scoped
 * to its own relevant timestamp being today and capped at {@link STRIP_ROWS}:
 *  - reviewed:    PRs you reviewed today (any origin).
 *  - inProgress:  your authored PRs still open — not merged, not closed.
 *  - merged:      your authored PRs that merged today.
 * A PR authored-and-closed-without-merging lands in none, by design.
 */
export function todayPrStrips(prs: PullRequestDto[] | null): {
  reviewed: PullRequestDto[];
  inProgress: PullRequestDto[];
  merged: PullRequestDto[];
} {
  const list = prs ?? [];
  return {
    reviewed: list
      .filter(p => isToday(p.reviewedAt))
      .sort((a, b) => Date.parse(b.reviewedAt as string) - Date.parse(a.reviewedAt as string))
      .slice(0, STRIP_ROWS),
    inProgress: list
      .filter(p => p.origin === 'AUTHORED' && p.mergedAt === null && p.state !== 'closed' && isToday(p.updatedAt))
      .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
      .slice(0, STRIP_ROWS),
    merged: list
      .filter(p => p.origin === 'AUTHORED' && p.mergedAt !== null && isToday(p.mergedAt))
      .sort((a, b) => Date.parse(b.mergedAt as string) - Date.parse(a.mergedAt as string))
      .slice(0, STRIP_ROWS),
  };
}

/** The home page's lead card: contribution graph on the left, a
 *  compact profile bio on the right, and a reviewed / in-progress /
 *  merged PR strip along the bottom. */
function ContributionCard({ profile, prs, onOpenPr, onSeeAllActivity }: Props) {
  const { reviewed, inProgress, merged } = todayPrStrips(prs);

  return (
    <div className="home-card home-contrib">
      <div className="home-contrib__top">
        <div className="home-contrib__graph">
          <div className="home-contrib__graph-title">Your year in code</div>
          {profile
            ? <YearInCodeHeatmap login={profile.login} />
            : <div className="hp-loading">Loading…</div>}
        </div>
        {profile && (
          <div className="home-contrib__bio">
            <div className="home-contrib__bio-id">
              <button
                type="button"
                className="hp-avatar-btn"
                onClick={() => { void window.bridge.openExternal(profile.htmlUrl); }}
                title="Open GitHub profile"
              >
                <Avatar login={profile.login} size={40} className="avatar--profile" />
              </button>
              <div className="home-contrib__bio-names">
                <span className="home-contrib__bio-name">{profile.name ?? profile.login}</span>
                <span className="home-contrib__bio-login">@{profile.login}</span>
              </div>
            </div>
            {profile.bio && <p className="home-contrib__bio-text">{profile.bio}</p>}
            <div className="home-contrib__bio-stats">
              <b>{profile.followers}</b> followers
              <b>{profile.following}</b> following
            </div>
            <div className="home-contrib__bio-meta">
              <b>{profile.publicRepos}</b> public repos
            </div>
          </div>
        )}
      </div>
      <div className="home-contrib__strip">
        <PrStripColumn
          label="Reviewed today"
          accentClass="home-contrib__strip-mark--green"
          rows={reviewed}
          onOpenPr={onOpenPr}
          onSeeAll={onSeeAllActivity && (() => onSeeAllActivity('reviewed'))}
        />
        <PrStripColumn
          label="Work in progress"
          accentClass="home-contrib__strip-mark--accent"
          rows={inProgress}
          onOpenPr={onOpenPr}
          onSeeAll={onSeeAllActivity && (() => onSeeAllActivity('contributed'))}
        />
        <PrStripColumn
          label="Merged today"
          accentClass="home-contrib__strip-mark--merged"
          rows={merged}
          onOpenPr={onOpenPr}
          onSeeAll={onSeeAllActivity && (() => onSeeAllActivity('contributed'))}
        />
      </div>
    </div>
  );
}

export default ContributionCard;

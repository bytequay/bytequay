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
};

const STRIP_ROWS = 3;

function openPrRow(pr: PullRequestDto, onOpenPr: Props['onOpenPr']) {
  const slash = pr.repo.indexOf('/');
  if (slash <= 0) return;
  onOpenPr(pr.repo.slice(0, slash), pr.repo.slice(slash + 1), pr.number);
}

function PrStripColumn({ label, accentClass, rows, onOpenPr }: {
  label: string;
  accentClass: string;
  rows: PullRequestDto[];
  onOpenPr: Props['onOpenPr'];
}) {
  return (
    <div className="home-contrib__strip-col">
      <div className="home-contrib__strip-head">
        <span className={`home-contrib__strip-mark ${accentClass}`} aria-hidden="true" />
        <span className="home-contrib__strip-label">{label}</span>
        <span className="home-contrib__strip-count">{rows.length}</span>
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
          <span className="home-contrib__strip-ref">{pr.repo} #{pr.number}</span>
        </button>
      ))}
    </div>
  );
}

/** The home page's lead card: contribution graph on the left, a
 *  compact profile bio on the right, and a reviewed / contributed PR
 *  strip along the bottom. */
function ContributionCard({ profile, prs, onOpenPr }: Props) {
  const reviewed = (prs ?? [])
    .filter(p => p.reviewedAt !== null)
    .sort((a, b) => Date.parse(b.reviewedAt as string) - Date.parse(a.reviewedAt as string))
    .slice(0, STRIP_ROWS);
  const contributed = (prs ?? [])
    .filter(p => p.origin === 'AUTHORED')
    .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
    .slice(0, STRIP_ROWS);

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
          label="PRs reviewed"
          accentClass="home-contrib__strip-mark--green"
          rows={reviewed}
          onOpenPr={onOpenPr}
        />
        <PrStripColumn
          label="PRs contributed"
          accentClass="home-contrib__strip-mark--accent"
          rows={contributed}
          onOpenPr={onOpenPr}
        />
      </div>
    </div>
  );
}

export default ContributionCard;

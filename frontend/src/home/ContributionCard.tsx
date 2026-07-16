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
import type { UserProfileDto } from '../types';
import Avatar from '../Avatar';
import YearInCodeHeatmap from '../YearInCodeHeatmap';

type Props = {
  profile: UserProfileDto | null;
};

/** The home page's lead card: contribution graph with a compact profile bio. */
function ContributionCard({ profile }: Props) {
  return (
    <div className="home-card home-contrib">
      <div className="home-contrib__top">
        <div className="home-contrib__graph">
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
    </div>
  );
}

export default ContributionCard;

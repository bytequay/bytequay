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
import { membersOf } from './activity';
import TeamActivityList from './TeamActivityList';
import { useTeams } from './useTeams';
import { useTeamActivity } from './useTeamActivity';
import './teams.css';

export default function TeamHomePreview({ viewerLogin }: { viewerLogin: string | null }) {
  const { directory } = useTeams(viewerLogin);
  const { snapshot, loading, error } = useTeamActivity(directory?.accountLogin, { days: 7, limit: 100 });
  if (!directory || directory.circles.length === 0) return null;
  return <section className="home-side-section teams-home-preview">
    <div className="teams-section-heading"><h3 className="home-side-section__title">From your teams</h3>
      <button type="button" onClick={() => { window.location.hash = '#/teams'; }}>View all</button></div>
    {error ? <p className="teams-error" role="alert">Unable to load team activity.</p>
      : loading ? <p className="teams-muted">Loading recorded updates…</p>
        : <TeamActivityList events={snapshot?.events ?? []} members={membersOf(directory.circles)} compact />}
  </section>;
}

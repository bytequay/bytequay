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
import type { TeamSummaryDto } from '../types';

type Props = {
  teams: TeamSummaryDto[];
  onOpenTeam?: (teamId: number) => void;
  onGoToTeams?: () => void;
};

/** "Teams you track" as a two-column card grid. Counts come from the
 *  team summary (members + open PRs in the inbox). */
function TeamsGrid({ teams, onOpenTeam, onGoToTeams }: Props) {
  return (
    <div className="home-section">
      <div className="home-section__header">
        <span className="home-section__title">Teams you track</span>
        {onGoToTeams && (
          <button className="home-section__action" onClick={onGoToTeams} type="button">
            + Manage
          </button>
        )}
      </div>
      {teams.length === 0 ? (
        <div className="hp-empty">
          No teams yet.{' '}
          {onGoToTeams && (
            <button type="button" className="hp-empty__link" onClick={onGoToTeams}>
              Create one
            </button>
          )}{' '}to filter the Kanban by author.
        </div>
      ) : (
        <div className="home-grid-2">
          {teams.map(t => (
            <div
              key={t.id}
              className="home-team-tile"
              role="button"
              tabIndex={0}
              onClick={() => onOpenTeam?.(t.id)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  onOpenTeam?.(t.id);
                }
              }}
            >
              <div className="home-team-tile__head">
                <span className={`team-avatar team-avatar--${t.color}`} aria-hidden="true">
                  {t.avatar}
                </span>
                <div className="home-team-tile__names">
                  <span className="home-team-tile__name">{t.name}</span>
                  <span className="home-team-tile__meta">
                    {t.memberCount} member{t.memberCount === 1 ? '' : 's'}
                  </span>
                </div>
              </div>
              <div className="home-team-tile__foot">
                <span>
                  <b>{t.inboxCount}</b> open PR{t.inboxCount === 1 ? '' : 's'}
                </span>
                <span className="home-team-tile__view">View ›</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export default TeamsGrid;

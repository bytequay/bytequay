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
import Avatar from '../Avatar';
import { CircleIcon } from '../teams/CircleDialog';
import { circleNeeds } from '../teams/priority';
import { openCircle } from '../teams/TeamsPage';
import type { TeamDirectory } from '../teams/types';
import type { AttentionItem } from './attention';

type Props = {
  directory: TeamDirectory | null;
  /** Visible attention items, so "need you" matches the circle's Needs you tab. */
  attention: AttentionItem[];
  viewerLogin: string | null;
};

/** "Circles": one tile per circle, like the repo tiles; a click opens that circle. */
function CirclesGrid({ directory, attention, viewerLogin }: Props) {
  if (directory === null || directory.circles.length === 0) return null;
  const account = directory.accountLogin;
  return (
    <div className="home-section">
      <div className="home-section__header">
        <span className="home-section__title">Circles</span>
      </div>
      <div className="home-repo-grid">
        {directory.circles.map(circle => {
          const open = () => openCircle(account, circle.id);
          return (
            <div
              key={circle.id}
              data-circle-id={circle.id}
              className="home-repo-tile"
              role="button"
              tabIndex={0}
              title={circle.description || circle.name}
              onClick={open}
              onKeyDown={(e) => {
                if (e.target !== e.currentTarget) return;
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  open();
                }
              }}
            >
              <div className="home-repo-tile__head">
                <CircleIcon avatar={circle.avatar} size="small" />
                <span className="home-repo-tile__name">{circle.name}</span>
              </div>
              <div className="home-repo-tile__stats">
                {circle.members.length === 0 ? <span><b>0</b> people</span> : <span className="home-circle-tile__people">
                  <span className="cir-avatars">{circle.members.slice(0, 3).map(member =>
                    <Avatar key={member.githubId} login={member.login} avatarUrl={member.avatarUrl} size={16} />)}</span>
                  {circle.members.length > 3 && <span>{circle.members.length - 3} {circle.members.length === 4 ? 'other' : 'others'}</span>}
                </span>}
                <span><b>{circleNeeds(attention, [circle], viewerLogin).length}</b> need you</span>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

export default CirclesGrid;

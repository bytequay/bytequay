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
import Avatar from '../Avatar';
import { remoteErrorMessage } from '../pulls/PullOverview';
import CircleDialog, { CircleIcon } from '../teams/CircleDialog';
import { parseTeamCreateInput } from '../teams/contracts';
import { circleNeeds } from '../teams/priority';
import { openCircle } from '../teams/TeamsPage';
import type { TeamCreateInput, TeamDirectory } from '../teams/types';
import { updateTeams } from '../teams/useTeams';
import type { AttentionItem } from './attention';

type Props = {
  directory: TeamDirectory | null;
  /** Visible attention items, so "need you" matches the circle's Needs you tab. */
  attention: AttentionItem[];
  viewerLogin: string | null;
};

/** "Circles": one tile per circle, like the repo tiles; a click opens that circle. */
function CirclesGrid({ directory, attention, viewerLogin }: Props) {
  const [creating, setCreating] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function createCircle(value: TeamCreateInput) {
    if (busy) return;
    setBusy(true); setError(null);
    try {
      updateTeams(await window.bridge.createTeam(parseTeamCreateInput(value)));
      setCreating(false);
    }
    catch (failure) { setError(remoteErrorMessage(failure)); }
    finally { setBusy(false); }
  }

  if (directory === null) return null;
  const account = directory.accountLogin;
  return (
    <div className="home-section">
      <div className="home-section__header">
        <span className="home-section__title">Circles</span>
        <button type="button" className="home-section__action" aria-haspopup="dialog" onClick={() => { setError(null); setCreating(true); }}>
          + Add circle
        </button>
      </div>
      {directory.circles.length === 0 && <div className="hp-empty">No circles yet.</div>}
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
      {creating && <CircleDialog circle={null} circles={directory.circles} busy={busy} error={error}
        onSave={createCircle} onCancel={() => setCreating(false)} onClearError={() => setError(null)} />}
    </div>
  );
}

export default CirclesGrid;

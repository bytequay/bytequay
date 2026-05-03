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
import type { TeamColor, TeamDto } from '../types';
import TagInput, { type TagSuggestion } from './TagInput';

/** Hits the backend's /api/search/users endpoint and shapes the result
 *  into TagSuggestion rows for the dropdown. Filters out empty/null
 *  logins defensively. */
async function searchGitHubUsers(query: string): Promise<TagSuggestion[]> {
  const matches = await window.bridge.searchUsers(query);
  return matches
    .filter(m => m.login)
    .map(m => ({
      value: m.login,
      render: (
        <div className="user-suggestion">
          {m.avatarUrl
            ? <img className="user-suggestion__avatar" src={m.avatarUrl} alt="" width={20} height={20} />
            : <span className="user-suggestion__avatar user-suggestion__avatar--fallback">{m.login.charAt(0).toUpperCase()}</span>}
          <span className="user-suggestion__login">{m.login}</span>
          {m.name && <span className="user-suggestion__name">{m.name}</span>}
        </div>
      ),
    }));
}

const COLORS: TeamColor[] = ['purple', 'green', 'orange'];

type Props = {
  /** null when creating a new team. */
  team: TeamDto | null;
  onClose: () => void;
  onSaved: () => void;
};

function TeamEditorModal({ team, onClose, onSaved }: Props) {
  const [name, setName] = useState(team?.name ?? '');
  const [avatar, setAvatar] = useState(team?.avatar ?? '');
  const [color, setColor] = useState<TeamColor>(team?.color ?? 'purple');
  const [members, setMembers] = useState<string[]>(team?.members ?? []);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Auto-generate the avatar from the name's first two letters when the user
  // hasn't manually set it. Lets the common case ("type the team name, hit
  // Save") just work without a second pass.
  useEffect(() => {
    if (team) return;
    if (avatar.length > 0) return;
    if (name.trim().length >= 2) {
      setAvatar(name.trim().slice(0, 2).toUpperCase());
    }
  }, [name, avatar, team]);

  const save = async () => {
    if (!name.trim()) {
      setError('Team name must not be blank.');
      return;
    }
    if (!avatar.trim()) {
      setError('Avatar (1–2 letters) must not be blank.');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      if (team) {
        // Update + replace members in two calls. Atomicity is best-effort —
        // metadata patches first, then the roster, so a roster-only change
        // doesn't trigger an unnecessary metadata write.
        await window.bridge.updateTeam(team.id, { name: name.trim(), avatar: avatar.trim(), color });
        await window.bridge.replaceTeamMembers(team.id, members);
      } else {
        await window.bridge.createTeam({
          name: name.trim(),
          avatar: avatar.trim().toUpperCase(),
          color,
          members,
        });
      }
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-shell" onClick={e => e.stopPropagation()} role="dialog" aria-modal="true">
        <header className="modal-shell__head">
          <h2 className="modal-shell__title">{team ? 'Edit team' : 'New team'}</h2>
          <button type="button" className="modal-shell__close" onClick={onClose} aria-label="Close">×</button>
        </header>

        <div className="modal-shell__body">
          <div className="settings-field">
            <label className="settings-label">Name</label>
            <input
              className="modal-text-input"
              type="text"
              value={name}
              onChange={e => setName(e.target.value)}
              placeholder="e.g. Trino core"
              autoFocus
            />
          </div>

          <div className="settings-field" style={{ display: 'flex', gap: 12, alignItems: 'flex-end' }}>
            <div style={{ flex: '0 0 120px' }}>
              <label className="settings-label">Avatar</label>
              <input
                className="modal-text-input"
                type="text"
                maxLength={2}
                value={avatar}
                onChange={e => setAvatar(e.target.value.toUpperCase())}
                placeholder="TR"
              />
            </div>
            <div style={{ flex: 1 }}>
              <label className="settings-label">Color</label>
              <div style={{ display: 'flex', gap: 8 }}>
                {COLORS.map(c => (
                  <button
                    key={c}
                    type="button"
                    className={`team-color-swatch team-color-swatch--${c}${color === c ? ' team-color-swatch--active' : ''}`}
                    onClick={() => setColor(c)}
                    aria-label={c}
                    title={c}
                  />
                ))}
              </div>
            </div>
          </div>

          <div className="settings-field">
            <label className="settings-label">Members</label>
            <TagInput
              value={members}
              onChange={setMembers}
              placeholder="Search a GitHub login…"
              fetchSuggestions={searchGitHubUsers}
            />
            <p className="section-copy" style={{ marginTop: 4, fontSize: 12 }}>
              Start typing — matching GitHub users appear in the dropdown.
              ↑/↓ to highlight, Enter to add. The team's Kanban shows PRs
              <b> authored by</b> any of these logins.
            </p>
          </div>

          {error && <div className="repo-error">{error}</div>}
        </div>

        <footer className="modal-shell__foot">
          <button type="button" className="button button--secondary" onClick={onClose} disabled={saving}>
            Cancel
          </button>
          <button type="button" className="button button--primary" onClick={() => void save()} disabled={saving}>
            {saving ? 'Saving…' : team ? 'Save' : 'Create team'}
          </button>
        </footer>
      </div>
    </div>
  );
}

export default TeamEditorModal;

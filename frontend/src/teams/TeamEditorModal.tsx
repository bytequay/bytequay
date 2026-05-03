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
import { useEffect, useRef, useState } from 'react';
import type { TeamColor, TeamDto } from '../types';
import TagInput, { type TagSuggestion } from './TagInput';

/** Hits the backend's /api/search/users endpoint and shapes the result
 *  into TagSuggestion rows for the dropdown. */
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

/** Eight-color palette matching the mockup. Order is identical to the
 *  swatch grid so keyboard / screen-reader navigation is predictable. */
const COLOR_PALETTE: { id: TeamColor; hex: string }[] = [
  { id: 'purple', hex: '#7c3aed' },
  { id: 'blue', hex: '#2563eb' },
  { id: 'cyan', hex: '#0891b2' },
  { id: 'green', hex: '#16a34a' },
  { id: 'amber', hex: '#d97706' },
  { id: 'red', hex: '#dc2626' },
  { id: 'pink', hex: '#db2777' },
  { id: 'slate', hex: '#475569' },
];

const HEX_BY_COLOR: Record<TeamColor, string> = {
  purple: '#7c3aed',
  blue: '#2563eb',
  cyan: '#0891b2',
  green: '#16a34a',
  amber: '#d97706',
  red: '#dc2626',
  pink: '#db2777',
  slate: '#475569',
  // Legacy values kept rendering against their nearest modern hex so
  // existing teams don't change appearance after the palette expansion.
  orange: '#d97706',
};

function autoInitials(name: string): string {
  if (!name.trim()) return '';
  return name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map(w => w[0] ?? '')
    .join('')
    .toUpperCase()
    .slice(0, 2);
}

type Props = {
  /** null when creating a new team. */
  team: TeamDto | null;
  onClose: () => void;
  onSaved: () => void;
};

function TeamEditorModal({ team, onClose, onSaved }: Props) {
  const [name, setName] = useState(team?.name ?? '');
  const [description, setDescription] = useState(team?.description ?? '');
  const [avatar, setAvatar] = useState(team?.avatar ?? '');
  const [color, setColor] = useState<TeamColor>(team?.color ?? 'purple');
  const [members, setMembers] = useState<string[]>(team?.members ?? []);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Track whether the user has manually edited the avatar so we know
  // when to keep auto-syncing it from the name (mockup change #4).
  const [avatarManual, setAvatarManual] = useState(team !== null && (team.avatar ?? '').length > 0);

  // Auto-derive avatar from name unless the user has overridden it. The
  // mockup shows "Auto · click to reset" — clicking that pill drops
  // back into auto mode, which the next name keystroke picks up.
  useEffect(() => {
    if (avatarManual) return;
    setAvatar(autoInitials(name));
  }, [name, avatarManual]);

  const trimmedName = name.trim();
  const isValid = trimmedName.length > 0 && avatar.trim().length > 0;

  const save = async () => {
    if (!isValid || saving) return;
    setSaving(true);
    setError(null);
    try {
      if (team) {
        await window.bridge.updateTeam(team.id, {
          name: trimmedName,
          avatar: avatar.trim().toUpperCase(),
          color,
          description: description.trim() || null,
        });
        await window.bridge.replaceTeamMembers(team.id, members);
      }
      else {
        await window.bridge.createTeam({
          name: trimmedName,
          avatar: avatar.trim().toUpperCase(),
          color,
          description: description.trim() || null,
          members,
        });
      }
      onSaved();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSaving(false);
    }
  };

  // Keyboard shortcuts surfaced in the footer hint: Cmd+Enter to
  // submit (the form's natural submit handler picks up the click),
  // Esc to cancel.
  const dialogRef = useRef<HTMLFormElement>(null);
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
      }
      if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
        e.preventDefault();
        void save();
      }
    };
    window.addEventListener('keydown', handler);
    return () => { window.removeEventListener('keydown', handler); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isValid, name, avatar, color, description, members]);

  const previewName = trimmedName || 'Untitled team';
  const previewDesc = description.trim() || (team ? '' : 'A short description shows up here');
  const memberCountLabel = `${members.length} member${members.length === 1 ? '' : 's'}`;
  const previewMeta = team
    ? memberCountLabel
    : `${memberCountLabel} · created today`;
  const colorHex = HEX_BY_COLOR[color] ?? HEX_BY_COLOR.purple;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <form
        ref={dialogRef}
        className="team-modal"
        onClick={e => e.stopPropagation()}
        onSubmit={(e) => { e.preventDefault(); void save(); }}
        role="dialog"
        aria-modal="true"
        aria-labelledby="team-modal-title"
      >
        <header className="team-modal__head">
          <h2 id="team-modal-title" className="team-modal__title">
            {team ? 'Edit team' : 'New team'}
          </h2>
          <button
            type="button"
            className="team-modal__close"
            onClick={onClose}
            aria-label="Close"
          >×</button>
        </header>

        <div className="team-modal__preview">
          <div className="team-modal__preview-label">
            Preview · how this team will appear in your sidebar
          </div>
          <div className="team-card-preview">
            <div
              className="team-card-preview__avatar"
              style={{ background: colorHex }}
            >
              {avatar || autoInitials(previewName) || '?'}
            </div>
            <div className="team-card-preview__info">
              <div className={`team-card-preview__name${trimmedName ? '' : ' team-card-preview__name--empty'}`}>
                {previewName}
              </div>
              {previewDesc && (
                <div className="team-card-preview__desc">{previewDesc}</div>
              )}
              <div className="team-card-preview__meta">{previewMeta}</div>
            </div>
          </div>
        </div>

        <div className="team-modal__body">
          <div className="team-modal__field">
            <label className="team-modal__label" htmlFor="team-modal-name">
              Team name <span className="team-modal__required">*</span>
            </label>
            <input
              id="team-modal-name"
              className="team-modal__input"
              type="text"
              value={name}
              onChange={e => setName(e.target.value)}
              placeholder="e.g. Dev core"
              maxLength={64}
              autoFocus
            />
          </div>

          <div className="team-modal__field">
            <label className="team-modal__label" htmlFor="team-modal-desc">
              Description <span className="team-modal__optional">(optional)</span>
            </label>
            <input
              id="team-modal-desc"
              className="team-modal__input"
              type="text"
              value={description}
              onChange={e => setDescription(e.target.value)}
              placeholder="What does this team work on?"
              maxLength={120}
            />
          </div>

          <div className="team-modal__field">
            <label className="team-modal__label">Identity</label>
            <div className="team-identity-row">
              <div>
                <span className="team-modal__micro-label">Avatar</span>
                <div className="team-avatar-input-wrap">
                  <input
                    className="team-modal__input team-avatar-input"
                    type="text"
                    maxLength={2}
                    value={avatar}
                    onChange={e => {
                      setAvatar(e.target.value.toUpperCase());
                      setAvatarManual(true);
                    }}
                    placeholder="TR"
                    aria-label="Team avatar"
                  />
                  <button
                    type="button"
                    className={`team-avatar-auto-tag${avatarManual ? '' : ' team-avatar-auto-tag--active'}`}
                    onClick={() => {
                      setAvatarManual(false);
                      setAvatar(autoInitials(name));
                    }}
                    title="Reset avatar to auto-generated initials from the team name"
                  >
                    {avatarManual ? 'Reset to auto' : 'Auto · click to reset'}
                  </button>
                </div>
              </div>
              <div className="team-color-control">
                <span className="team-modal__micro-label">Color</span>
                <div className="team-color-grid" role="radiogroup" aria-label="Team color">
                  {COLOR_PALETTE.map(c => (
                    <button
                      key={c.id}
                      type="button"
                      role="radio"
                      aria-checked={color === c.id}
                      className={`team-color-pick${color === c.id ? ' team-color-pick--active' : ''}`}
                      style={{ background: c.hex }}
                      onClick={() => setColor(c.id)}
                      title={c.id}
                      aria-label={c.id}
                    />
                  ))}
                </div>
              </div>
            </div>
          </div>

          <div className="team-modal__field">
            <label className="team-modal__label">
              Members <span className="team-modal__optional">(optional · add later anytime)</span>
            </label>
            <TagInput
              value={members}
              onChange={setMembers}
              placeholder="Search GitHub user…"
              fetchSuggestions={searchGitHubUsers}
            />
            <p className="team-modal__helper">
              The team's Kanban will show PRs <b>authored by</b> these members.
            </p>
          </div>

          {error && <div className="team-modal__error" role="alert">{error}</div>}
        </div>

        <footer className="team-modal__foot">
          <span className="team-modal__kbd-hint">
            <kbd>⌘</kbd>+<kbd>Enter</kbd> to {team ? 'save' : 'create'} · <kbd>Esc</kbd> to cancel
          </span>
          <div className="team-modal__foot-actions">
            <button
              type="button"
              className="button button--secondary"
              onClick={onClose}
              disabled={saving}
            >
              Cancel
            </button>
            <button
              type="submit"
              className="button button--primary"
              disabled={saving || !isValid}
              title={!isValid ? 'Name is required' : undefined}
            >
              {saving ? 'Saving…' : team ? 'Save' : 'Create team'}
            </button>
          </div>
        </footer>
      </form>
    </div>
  );
}

export default TeamEditorModal;

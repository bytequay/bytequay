import { useEffect, useState } from 'react';
import type { TeamColor, TeamDto, TeamSummaryDto } from '../../types';
import SettingCard from '../shared/SettingCard';
import TeamEditorModal from '../../teams/TeamEditorModal';

type Props = {
  /** Set by App.tsx — opens the team detail page when a row is clicked. */
  onOpenTeam?: (id: number) => void;
};

function TeamsPage({ onOpenTeam }: Props) {
  const [teams, setTeams] = useState<TeamSummaryDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<TeamDto | 'new' | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const list = await window.bridge.listTeams();
      setTeams(list);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const startEdit = async (id: number) => {
    try {
      const full = await window.bridge.getTeam(id);
      setEditing(full);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const handleDelete = async (id: number, name: string) => {
    if (!confirm(`Delete the team "${name}"? Members aren't affected — this just removes the local grouping.`)) return;
    try {
      await window.bridge.deleteTeam(id);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <>
      <div className="settings-shell-page__head">
        <div>
          <h2 className="settings-shell-page__title">Teams</h2>
          <div className="settings-shell-page__subtitle">
            Group people whose PRs you care about. Each team gets its own Kanban filtered to PRs they authored.
          </div>
        </div>
        <button className="button button--primary" type="button" onClick={() => setEditing('new')}>
          + New team
        </button>
      </div>

      {loading && <div className="repo-loading">Loading…</div>}
      {error && <div className="repo-error">{error}</div>}

      {!loading && teams.length === 0 && (
        <SettingCard>
          <div className="settings-shell-page__subtitle" style={{ padding: '20px 0' }}>
            No teams yet. Create one to start tracking a group of GitHub authors.
          </div>
        </SettingCard>
      )}

      {!loading && teams.length > 0 && (
        <div className="team-list">
          {teams.map(t => (
            <button
              key={t.id}
              type="button"
              className="team-row"
              onClick={() => onOpenTeam?.(t.id)}
            >
              <span
                className={`team-avatar team-avatar--${t.color}`}
                aria-hidden="true"
                title={t.name}
              >
                {t.avatar}
              </span>
              <div className="team-row__text">
                <div className="team-row__name">{t.name}</div>
                <div className="team-row__meta">
                  {t.memberCount} member{t.memberCount === 1 ? '' : 's'} · {t.inboxCount} PR{t.inboxCount === 1 ? '' : 's'} in inbox
                </div>
              </div>
              <div className="team-row__actions" onClick={e => e.stopPropagation()}>
                <button
                  type="button"
                  className="button button--secondary"
                  onClick={(e) => { e.stopPropagation(); void startEdit(t.id); }}
                >
                  Manage
                </button>
                <button
                  type="button"
                  className="button button--danger"
                  onClick={(e) => { e.stopPropagation(); void handleDelete(t.id, t.name); }}
                >
                  Delete
                </button>
              </div>
            </button>
          ))}
        </div>
      )}

      {editing && (
        <TeamEditorModal
          team={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null);
            await load();
          }}
        />
      )}
    </>
  );
}

export default TeamsPage;

// Re-export the color list so the editor can offer the same options.
export const TEAM_COLORS: TeamColor[] = ['purple', 'green', 'orange'];

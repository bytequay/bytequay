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
import type { SkillDto } from '../../types';
import SettingsPage from '../shared/SettingsPage';

type Stage = 'Plan' | 'Build' | 'Review';

/** The stage a role acts in, and what that stage means. Roles are grouped
 *  by it so the page reads as a pipeline rather than a flat card grid. */
const STAGES: { id: Stage; note: string }[] = [
  { id: 'Plan', note: 'before any code exists' },
  { id: 'Build', note: 'inside a task worktree' },
  { id: 'Review', note: 'on an open pull request' },
];

/** The four agent roles with their fixed (app-owned, non-editable)
 *  capability templates. Roles are identity the runtime owns — not a
 *  skill kind — so this surface is read-only. Which skills a role
 *  resolves is derived from a skill's usage: Trunk / Task see
 *  development skills; Reviewer / Lead see review skills. */
const ROLES: {
  id: string;
  stage: Stage;
  name: string;
  sub: string;
  usage: 'build' | 'review';
  desc: string;
  can: string[];
  cant: string[];
}[] = [
  {
    id: 'trunk',
    stage: 'Plan',
    name: 'Trunk',
    sub: 'fixed template',
    usage: 'build',
    desc: 'Orchestrates planning; cuts tasks but never writes code or pushes.',
    can: ['create_task', 'search', 'recall'],
    cant: ['edit files', 'push'],
  },
  {
    id: 'task',
    stage: 'Build',
    name: 'Task',
    sub: 'generated per task · frozen',
    usage: 'build',
    desc: 'Composed at task creation from the task’s repo / branch / PR, then frozen so behaviour is reproducible.',
    can: ['edit files', 'push (gated)', 'comment'],
    cant: ['create_task', 'change role'],
  },
  {
    id: 'reviewer',
    stage: 'Review',
    name: 'Reviewer',
    sub: 'review panel seat',
    usage: 'review',
    desc: 'A panel seat’s reviewing voice. Reads the diff and reports findings; never writes.',
    can: ['read diff', 'comment'],
    cant: ['edit files', 'push', 'create_task'],
  },
  {
    id: 'lead',
    stage: 'Review',
    name: 'Lead',
    sub: 'review panel orchestrator',
    usage: 'review',
    desc: 'Final arbiter on the panel — drives the agenda and dispatches reviewers.',
    can: ['arbitrate panel', 'publish review'],
    cant: ['edit files', 'push code'],
  },
];

function AgentRolesPage() {
  const [skills, setSkills] = useState<SkillDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    window.bridge.listSkills()
      .then(list => { if (!cancelled) setSkills(list); })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  return (
    <SettingsPage
      title="Agent roles"
      width={940}
      subtitle={
        'Four roles ship with the runtime, grouped by the stage they act in. Capabilities are '
        + 'fixed templates — read-only here. Which skills a role loads is derived from each skill’s usage.'
      }
      action={<span className="sv2-pill">READ-ONLY</span>}
    >
      {error !== null && <div className="sv2-error" role="alert">{error}</div>}

      {STAGES.map(stage => (
        <div className="sv2-stage" key={stage.id}>
          <div className="sv2-stage__head">
            <span className="sv2-stage__label">Stage · {stage.id}</span>
            <span className="sv2-stage__note">{stage.note}</span>
            <span className="sv2-stage__rule" />
          </div>
          {ROLES.filter(r => r.stage === stage.id).map(role => {
            // A role resolves the enabled skills whose usage matches its surface.
            const resolved = skills.filter(s => s.enabled && s.usage === role.usage);
            const open = expanded === role.id;
            return (
              <div className="sv2-role" key={role.id}>
                <span className="sv2-role__id">
                  <span className="sv2-role__name">{role.name}</span>
                  <span className="sv2-role__sub">{role.sub}</span>
                </span>
                <span className="sv2-role__body">
                  <span className="sv2-role__desc">{role.desc}</span>
                  <span className="sv2-role__chips">
                    <span className="sv2-role__cap sv2-role__cap--can">CAN</span>
                    {role.can.map(c => <span className="sv2-role__chip sv2-role__chip--can" key={c}>{c}</span>)}
                  </span>
                  <span className="sv2-role__chips">
                    <span className="sv2-role__cap sv2-role__cap--cant">CAN’T</span>
                    {role.cant.map(c => <span className="sv2-role__chip sv2-role__chip--cant" key={c}>{c}</span>)}
                  </span>
                </span>
                <button
                  className="sv2-role__skills"
                  type="button"
                  aria-expanded={open}
                  disabled={loading || resolved.length === 0}
                  onClick={() => setExpanded(open ? null : role.id)}
                >
                  {loading
                    ? 'resolving…'
                    : `resolves ${resolved.length} skill${resolved.length === 1 ? '' : 's'}`}
                </button>
                {open && (
                  <ul className="sv2-role__resolved">
                    {resolved.map(s => (
                      <li key={s.id}>
                        <span>{s.name}</span>
                        <em>{s.scope === 'repo' ? (s.repo ?? 'repo') : s.scope}</em>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            );
          })}
        </div>
      ))}
    </SettingsPage>
  );
}

export default AgentRolesPage;

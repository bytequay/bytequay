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
import { MetadataPicker, remoteErrorMessage } from '../pulls/PullOverview';
import { usePopoverDismiss } from '../pulls/code-tests/Outline';
import type { RepositoryMetadataDto } from '../types';
import type { TeamCircle, TeamMemberInput } from './types';

/** Add people: the assignee picker over contributors cached from every watched repository; a pick adds at once. */
export default function TeamPeoplePicker({ circle, circles, busy, onAdd, onClose }: {
  circle: TeamCircle;
  circles: TeamCircle[];
  busy: boolean;
  onAdd: (member: TeamMemberInput) => void;
  onClose: () => void;
}) {
  const host = usePopoverDismiss(onClose, true);
  const [contributors, setContributors] = useState<RepositoryMetadataDto['contributors']>([]);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let current = true;
    Promise.resolve().then(() => window.bridge.fetchTeamContributors()).then(value => { if (current) setContributors(value); })
      .catch((failure: unknown) => { if (current) setError(remoteErrorMessage(failure)); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, []);
  const normalized = query.trim().replace(/^@/, '').toLowerCase();
  const names = new Map(circles.flatMap(item => item.members.map(member => [member.githubId, member.name] as const)));
  const options = contributors.map(person => {
    const name = names.get(person.id);
    const selected = circle.members.some(member => member.githubId === person.id);
    const others = circles.filter(item => item.id !== circle.id && item.members.some(member => member.githubId === person.id)).map(item => item.name);
    return {
      key: String(person.id), login: person.login, avatarUrl: person.avatarUrl, bot: person.bot,
      label: name && name !== person.login ? `${name} · @${person.login}` : `@${person.login}`, selected, disabled: selected,
      disabledReason: selected ? 'Already in this circle' : undefined,
      hint: selected ? 'Already in this circle' : others.length > 0 ? `In ${others[0]}${others.length > 1 ? ` +${others.length - 1}` : ''}` : undefined,
    };
  }).filter(person => person.label.toLowerCase().includes(normalized)).sort((left, right) => left.login.localeCompare(right.login));
  return <div ref={host} className="cir-popover cir-add" role="dialog" aria-label="Add people">
    <MetadataPicker ariaLabel="contributors" placeholder="Search cached contributors…" query={query} setQuery={setQuery}
      options={options} loading={loading} updating={busy} loadingText="Loading cached contributors…"
      emptyText={error ?? (contributors.length === 0 ? 'No contributors cached yet. Watch a repository and let it sync to load its people.'
        : `No cached contributor matches “${query}”. Only people from your watched repositories are searchable.`)}
      onToggle={option => {
        const person = contributors.find(candidate => candidate.id === Number(option.key));
        if (person) onAdd({ githubId: person.id, login: person.login });
      }} />
    <div className="cir-popover__foot">Contributors from all your watched repositories. Selecting adds them right away; they aren’t notified.</div>
  </div>;
}

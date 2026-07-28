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
import type { CiHarnessRuleDto } from './workspaceApi';

type Filter = 'candidate' | 'active' | 'retired';

const FILTERS: { key: Filter; label: string }[] = [
  { key: 'candidate', label: 'Candidates' },
  { key: 'active', label: 'Active' },
  { key: 'retired', label: 'Retired' },
];

/** The per-repository knowledge base. A candidate never routes a failure until
 * it is approved here or reaches its hit threshold. */
export function KnowledgeBase({ rules, busy, onApprove, onRetire }: {
  rules: CiHarnessRuleDto[];
  busy: boolean;
  onApprove: (ruleId: string) => void;
  onRetire: (ruleId: string) => void;
}) {
  const [filter, setFilter] = useState<Filter>(
    rules.some(rule => rule.status === 'candidate') ? 'candidate' : 'active',
  );
  const shown = rules.filter(rule => rule.status === filter);
  if (rules.length === 0) return null;
  return (
    <section className="ci-harness-kb">
      <header>
        <h2>Knowledge base</h2>
        <div className="ci-harness-kb__filters" role="tablist" aria-label="Rule status">
          {FILTERS.map(({ key, label }) => (
            <button type="button" key={key} role="tab" aria-selected={filter === key}
              className={filter === key ? 'active' : ''} onClick={() => setFilter(key)}>
              {label}<small>{rules.filter(rule => rule.status === key).length}</small>
            </button>
          ))}
        </div>
      </header>
      {shown.map(rule => (
        <article key={rule.id}>
          <span className="ci-harness-kb__head">
            <strong>{rule.bucket}</strong><code>{rule.binding}</code>
            <small>{rule.scope ?? 'repository'} · {rule.origin} · priority {rule.priority}</small>
          </span>
          <code className="ci-harness-kb__matcher">{rule.matcherPattern}</code>
          <small>{rule.hits} evidence hit{rule.hits === 1 ? '' : 's'}
            {rule.approvedAtMs === null ? '' : ` · active since ${date(rule.approvedAtMs)}`}</small>
          <span className="ci-harness-kb__actions">
            {rule.status === 'candidate' && (
              <button type="button" disabled={busy} onClick={() => onApprove(rule.id)}>
                Approve
              </button>
            )}
            {rule.status !== 'retired' && (
              <button type="button" disabled={busy} onClick={() => onRetire(rule.id)}
                title="Stop routing failures on this rule">Retire</button>
            )}
          </span>
        </article>
      ))}
      {shown.length === 0 && <p className="ci-harness-empty-feed">No {filter} rules.</p>}
    </section>
  );
}

function date(value: number): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? String(value) : parsed.toLocaleDateString(undefined, {
    month: 'short', day: 'numeric',
  });
}

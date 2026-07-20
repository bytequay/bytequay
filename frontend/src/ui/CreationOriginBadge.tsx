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
import type { CSSProperties } from 'react';

const LABELS: Record<string, string> = {
  unknown: 'Unknown',
  user: 'User',
  'user-report': 'User · ByteQuay',
  agent: 'Agent',
  automation: 'ByteQuay',
  'issue-monitor': 'Issue monitor',
  'quality-scan': 'Quality scan',
};

export function isAutomatedOrigin(origin?: string | null): boolean {
  return origin === 'agent'
    || origin === 'automation'
    || origin === 'issue-monitor'
    || origin === 'quality-scan';
}

export function CreationOriginBadge({ origin }: { origin?: string | null }) {
  if (origin === undefined || origin === null || origin.length === 0) return null;
  const automated = isAutomatedOrigin(origin);
  return (
    <span
      data-origin={origin}
      style={badgeStyle(automated)}
      title={`Created by ${LABELS[origin] ?? origin}`}
    >
      {LABELS[origin] ?? origin}
    </span>
  );
}

function badgeStyle(automated: boolean): CSSProperties {
  return {
    display: 'inline-flex',
    alignItems: 'center',
    flexShrink: 0,
    borderRadius: 999,
    padding: '1px 6px',
    border: `1px solid ${automated ? 'rgba(124, 58, 237, 0.25)' : 'var(--border)'}`,
    background: automated ? 'rgba(124, 58, 237, 0.09)' : 'var(--bg-hover)',
    color: automated ? '#6d28d9' : 'var(--text-3)',
    fontSize: 9,
    fontWeight: 700,
    lineHeight: 1.5,
    letterSpacing: '0.03em',
    whiteSpace: 'nowrap',
    marginLeft: 4,
  };
}

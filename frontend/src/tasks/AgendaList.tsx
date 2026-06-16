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
import type { AgendaPhaseDto, AgendaPhaseStatusDto } from '../types';

/**
 * The task's dev-agenda checklist, rendered vertically (one milestone per
 * line) for the task page's right rail. Same ◻ / ◼ / ✓ glyphs and status
 * colors as the review-pass agenda. The data lives on the task as
 * {@code agendaJson}; this only renders it — no fetch, no mutation.
 */
export function AgendaList({ agenda }: { agenda: AgendaPhaseDto[] }) {
  return (
    <ul style={listStyle}>
      {agenda.map(item => (
        <li key={item.id} style={rowStyle}>
          <span aria-hidden style={glyphStyle(item.status)}>{glyphFor(item.status)}</span>
          <span style={titleStyle(item.status)}>{item.title}</span>
        </li>
      ))}
    </ul>
  );
}

/** Parse a task's {@code agendaJson} into agenda items. Tolerant: returns
 *  an empty list for null / blank / malformed JSON so the caller can hide
 *  the section entirely. */
export function parseAgenda(agendaJson: string | null): AgendaPhaseDto[] {
  if (agendaJson === null || agendaJson.trim() === '') {
    return [];
  }
  try {
    const parsed: unknown = JSON.parse(agendaJson);
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed.filter(isAgendaItem);
  }
  catch {
    return [];
  }
}

function isAgendaItem(value: unknown): value is AgendaPhaseDto {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const v = value as Record<string, unknown>;
  return typeof v.id === 'string' && typeof v.title === 'string'
    && (v.status === 'OPEN' || v.status === 'IN_PROGRESS' || v.status === 'DONE');
}

function glyphFor(status: AgendaPhaseStatusDto): string {
  switch (status) {
    case 'DONE': return '✓';
    case 'IN_PROGRESS': return '◼';
    default: return '◻';
  }
}

const listStyle: React.CSSProperties = {
  listStyle: 'none', margin: 0, padding: 0, display: 'flex',
  flexDirection: 'column', gap: 5,
};
const rowStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'flex-start', gap: 8, fontSize: 11.5, lineHeight: 1.4,
};

function glyphStyle(status: AgendaPhaseStatusDto): React.CSSProperties {
  const base: React.CSSProperties = { flexShrink: 0, marginTop: 1, fontSize: 11, width: 12 };
  switch (status) {
    case 'DONE': return { ...base, color: '#16a34a' };
    case 'IN_PROGRESS': return { ...base, color: '#d97706' };
    default: return { ...base, color: 'var(--text-4)' };
  }
}

function titleStyle(status: AgendaPhaseStatusDto): React.CSSProperties {
  const base: React.CSSProperties = { minWidth: 0, wordBreak: 'break-word' };
  switch (status) {
    case 'DONE':
      return { ...base, color: 'var(--text-3)', textDecoration: 'line-through' };
    case 'IN_PROGRESS':
      return { ...base, color: 'var(--text-1)', fontWeight: 600 };
    default:
      return { ...base, color: 'var(--text-2)' };
  }
}

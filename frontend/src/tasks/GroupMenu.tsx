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
import type { TaskDto, TaskGroupDto } from '../types';

type Props = {
  task: TaskDto;
  groups: TaskGroupDto[];
  /** Group IDs the task currently belongs to. Tasks are many-to-many
   *  with groups now, so a single task can have several entries. */
  currentGroupIds: string[];
  /** Toggle membership in one group. {@code present} indicates the
   *  desired post-click state — {@code true} adds, {@code false}
   *  removes. The parent runs the mutation and refreshes state. */
  onToggle: (taskId: string, groupId: string, present: boolean) => void | Promise<void>;
};

/**
 * Compact "Pin to groups…" popover for a single task. Rendered as a
 * `⋯` trigger button that toggles a small absolute-positioned menu.
 * Each row is a checkbox-like toggle — checking adds the task to that
 * group, unchecking removes it. Hard-caps per-group membership at the
 * backend; the dialog only surfaces backend errors when the toggle
 * fails (e.g. removing the last member of a group).
 */
export default function GroupMenu({ task, groups, currentGroupIds, onToggle }: Props) {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);

  // Outside-click / Escape closes the menu. Wired on mount so the
  // first interaction reaches the document listener — the trigger
  // itself stops propagation so its own click doesn't immediately
  // close the popover.
  useEffect(() => {
    if (!open) return;
    function onDocClick(e: MouseEvent) {
      if (!wrapRef.current?.contains(e.target as Node)) setOpen(false);
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false);
    }
    document.addEventListener('mousedown', onDocClick);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDocClick);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div ref={wrapRef} style={wrapStyle}>
      <button
        type="button"
        onClick={e => { e.stopPropagation(); setOpen(o => !o); }}
        style={triggerStyle}
        title="Pin this task to a group"
        aria-haspopup="menu"
        aria-expanded={open}
      >
        + Pin
      </button>
      {open && (
        // No header inside the popover — every caller already sits
        // inside a "Groups" labelled section / column, so a second
        // "GROUPS" heading inside the menu was redundant and made
        // the popover read as a noisy duplicate. Just list the
        // toggle rows.
        <div
          style={menuStyle}
          onClick={e => e.stopPropagation()}
          role="menu"
        >
          {groups.length === 0 ? (
            <div style={emptyStyle}>
              No groups yet. Create one from the Tasks rail.
            </div>
          ) : (
            groups.map(g => {
              const active = currentGroupIds.includes(g.id);
              return (
                <MenuRow
                  key={g.id}
                  glyph={g.glyph || '•'}
                  color={g.color}
                  label={g.name}
                  active={active}
                  onClick={() => void onToggle(task.id, g.id, !active)}
                />
              );
            })
          )}
        </div>
      )}
    </div>
  );
}

function MenuRow({ glyph, color, label, active, onClick }: {
  glyph?: string;
  color?: string;
  label: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      role="menuitem"
      onClick={onClick}
      style={{
        ...rowStyle,
        background: active ? 'var(--accent-a10)' : 'transparent',
      }}
    >
      <span
        aria-hidden
        style={{
          ...glyphStyle,
          background: glyph ? swatchBg(color) : 'transparent',
          color: glyph ? '#fff' : 'transparent',
          visibility: glyph ? 'visible' : 'hidden',
        }}
      >
        {glyph || ' '}
      </span>
      <span style={rowLabelStyle}>{label}</span>
      <span style={rowCheckStyle}>{active ? '✓' : ''}</span>
    </button>
  );
}

function swatchBg(color: string | undefined): string {
  switch (color) {
    case 'violet': return 'linear-gradient(135deg, #7c3aed, #4c1d95)';
    case 'amber':  return 'linear-gradient(135deg, #d97706, #92400e)';
    case 'green':  return 'linear-gradient(135deg, #10b981, #047857)';
    case 'blue':   return 'linear-gradient(135deg, #2563eb, #1e3a8a)';
    case 'rose':   return 'linear-gradient(135deg, #e11d48, #9f1239)';
    default:       return 'linear-gradient(135deg, #64748b, #334155)';
  }
}

const wrapStyle: React.CSSProperties = {
  position: 'relative',
  display: 'inline-flex',
};
// "+ Pin" reads as a verb the way "⋯" never did. Keep the
// dashed border + slight tint so it doesn't dominate the row of
// solid group chips beside it.
const triggerStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 2,
  background: 'transparent',
  border: '1px dashed var(--border)',
  color: 'var(--text-3)',
  fontSize: 11,
  fontWeight: 600,
  cursor: 'pointer',
  padding: '2px 8px',
  borderRadius: 999,
  lineHeight: 1.4,
  letterSpacing: '0.02em',
};
// Solid panel background + bigger shadow so the popover reads as
// its own surface against either a sidebar column or a card grid.
// Previously used `var(--bg-card)`, which is so close to the
// surrounding card colours that the popover felt translucent.
const menuStyle: React.CSSProperties = {
  position: 'absolute',
  top: '100%',
  right: 0,
  marginTop: 4,
  minWidth: 200,
  background: 'var(--bg-panel, var(--bg-elevated))',
  border: '1px solid var(--border)',
  borderRadius: 8,
  boxShadow: '0 12px 28px rgba(15, 23, 42, 0.22), 0 2px 6px rgba(15, 23, 42, 0.10)',
  padding: '4px 0',
  zIndex: 30,
};
const rowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  width: '100%',
  padding: '6px 10px',
  border: 'none',
  cursor: 'pointer',
  font: 'inherit',
  color: 'var(--text-1)',
  textAlign: 'left',
};
const glyphStyle: React.CSSProperties = {
  width: 18,
  height: 18,
  borderRadius: 4,
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 10,
  fontWeight: 700,
  flexShrink: 0,
};
const rowLabelStyle: React.CSSProperties = {
  flex: 1,
  fontSize: 13,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};
const rowCheckStyle: React.CSSProperties = {
  width: 14,
  color: 'var(--accent)',
  fontSize: 12,
  textAlign: 'right',
};
const emptyStyle: React.CSSProperties = {
  padding: '8px 12px',
  fontSize: 12,
  color: 'var(--text-3)',
};

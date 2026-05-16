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
  /** Called with the new group id (or {@code null} to unpin) after
   *  the user picks a row. The parent decides how to refresh state. */
  onChange: (taskId: string, groupId: string | null) => void | Promise<void>;
};

/**
 * Compact "Move to…" popover for a single task. Rendered as a `⋯`
 * trigger button that toggles a small absolute-positioned menu next
 * to it. Used from list rows and grid tiles to organize tasks
 * without drilling into the detail page.
 */
export default function GroupMenu({ task, groups, onChange }: Props) {
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

  function pick(groupId: string | null) {
    setOpen(false);
    void onChange(task.id, groupId);
  }

  return (
    <div ref={wrapRef} style={wrapStyle}>
      <button
        type="button"
        onClick={e => { e.stopPropagation(); setOpen(o => !o); }}
        style={triggerStyle}
        title="Move to group…"
        aria-haspopup="menu"
        aria-expanded={open}
      >
        ⋯
      </button>
      {open && (
        <div
          style={menuStyle}
          onClick={e => e.stopPropagation()}
          role="menu"
        >
          <div style={menuHeaderStyle}>Move to group</div>
          <MenuRow
            label="— Ungrouped —"
            active={task.groupId == null}
            onClick={() => pick(null)}
          />
          {groups.length === 0 && (
            <div style={emptyStyle}>
              No groups yet. Create one from the rail.
            </div>
          )}
          {groups.map(g => (
            <MenuRow
              key={g.id}
              glyph={g.glyph || '•'}
              color={g.color}
              label={g.name}
              active={task.groupId === g.id}
              onClick={() => pick(g.id)}
            />
          ))}
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
        color: active ? 'var(--accent-dark)' : 'var(--text-1)',
        fontWeight: active ? 600 : 500,
      }}
    >
      {glyph != null && (
        <span style={{ ...glyphStyle, background: groupColorBg(color || '') }}>
          {glyph}
        </span>
      )}
      <span style={labelStyle}>{label}</span>
      {active && <span style={checkStyle}>✓</span>}
    </button>
  );
}

function groupColorBg(color: string): string {
  switch (color.toLowerCase()) {
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
const triggerStyle: React.CSSProperties = {
  background: 'transparent',
  border: '1px solid transparent',
  borderRadius: 4,
  padding: '2px 8px',
  color: 'var(--text-3)',
  fontSize: 18,
  lineHeight: 1,
  cursor: 'pointer',
};
const menuStyle: React.CSSProperties = {
  position: 'absolute',
  top: '100%',
  right: 0,
  marginTop: 6,
  minWidth: 200,
  maxHeight: 320,
  overflowY: 'auto',
  background: 'var(--bg-card)',
  border: '1px solid var(--border)',
  borderRadius: 8,
  boxShadow: '0 12px 32px rgba(15, 23, 42, 0.15)',
  padding: 4,
  zIndex: 30,
};
const menuHeaderStyle: React.CSSProperties = {
  padding: '6px 10px 4px',
  fontSize: 10.5,
  fontWeight: 700,
  letterSpacing: '0.06em',
  textTransform: 'uppercase',
  color: 'var(--text-3)',
};
const rowStyle: React.CSSProperties = {
  width: '100%',
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '6px 10px',
  border: 'none',
  borderRadius: 5,
  textAlign: 'left',
  fontSize: 13,
  fontFamily: 'inherit',
  cursor: 'pointer',
};
const glyphStyle: React.CSSProperties = {
  width: 18,
  height: 18,
  borderRadius: 4,
  color: '#fff',
  fontSize: 10,
  fontWeight: 700,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
};
const labelStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};
const checkStyle: React.CSSProperties = { color: 'var(--accent)', fontSize: 12 };
const emptyStyle: React.CSSProperties = {
  padding: '8px 10px',
  fontSize: 12,
  color: 'var(--text-4)',
  fontStyle: 'italic',
};

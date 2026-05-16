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
import type { PendingPermission } from './ConversationPane';

/** Extra payload for a permission reply. When supplied, the backend
 *  records the per-call decision *and* grants a per-tool auto-approval
 *  budget for subsequent invocations of the same tool name in the
 *  session. {@code count == -1} is the "always" sentinel. */
export type PermissionPreApprove = { toolName: string; count: number };

export type PermissionDecideHandler = (
  callId: string,
  decision: 'ALLOW' | 'DENY',
  preApprove?: PermissionPreApprove,
) => void;

/** Options surfaced in the "Allow next N ▾" picker. The count is the
 *  number of *additional* auto-allows granted; the current call is
 *  always allowed first. */
const PRE_APPROVE_CHOICES: { count: number; label: string; menu: string }[] = [
  { count: 5, label: 'Allow next 5', menu: '5 times' },
  { count: 10, label: 'Allow next 10', menu: '10 times' },
  { count: 50, label: 'Allow next 50', menu: '50 times' },
  { count: -1, label: 'Always for this tool', menu: 'Always for this tool' },
];

/**
 * Yellow "Approval needed" card with three actions:
 *   • Approve once — allows the current call only.
 *   • Allow next N ▾ — allows the current call and grants budget for
 *     N more (or "always") invocations of the same tool. The dropdown
 *     picks N from 5 / 10 / 50 / Always.
 *   • Reject — denies the current call.
 *
 * Used in both the Structured and Raw views so the same actions show
 * regardless of which renderer the user is on.
 */
export function PermissionCard({ permission, onDecide }: {
  permission: PendingPermission;
  onDecide: PermissionDecideHandler;
}) {
  const [choiceIdx, setChoiceIdx] = useState(0);
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement | null>(null);

  // Close the picker when clicking outside — a native <select> would
  // do this for free, but we want the custom popover look.
  useEffect(() => {
    if (!menuOpen) return;
    const onDown = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [menuOpen]);

  const choice = PRE_APPROVE_CHOICES[choiceIdx];

  return (
    <article style={cardStyle}>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={titleStyle}>
          ⚠ Approval needed for <strong>{permission.toolName}</strong>
        </div>
        {permission.summary && (
          <div style={summaryStyle}>{permission.summary}</div>
        )}
      </div>
      <div style={actionsStyle}>
        <button
          type="button"
          onClick={() => onDecide(permission.callId, 'ALLOW')}
          style={approveOnceBtnStyle}
        >Approve once</button>

        <div ref={menuRef} style={splitWrapStyle}>
          <button
            type="button"
            onClick={() => onDecide(
              permission.callId,
              'ALLOW',
              { toolName: permission.toolName, count: choice.count })}
            style={allowNextBtnStyle}
            title={`Allow now + auto-approve ${choice.count === -1
              ? 'every future call to this tool'
              : `the next ${choice.count} calls to this tool`} in this session`}
          >{choice.label}</button>
          <button
            type="button"
            onClick={() => setMenuOpen(o => !o)}
            style={allowNextCaretStyle}
            aria-label="Change pre-approval count"
            aria-haspopup="menu"
            aria-expanded={menuOpen}
          >▾</button>
          {menuOpen && (
            <div role="menu" style={menuStyle}>
              <div style={menuHeaderStyle}>auto-approve</div>
              {PRE_APPROVE_CHOICES.map((c, i) => (
                <button
                  key={c.count}
                  type="button"
                  role="menuitemradio"
                  aria-checked={i === choiceIdx}
                  onClick={() => { setChoiceIdx(i); setMenuOpen(false); }}
                  style={{
                    ...menuItemStyle,
                    ...(i === choiceIdx ? menuItemSelectedStyle : null),
                  }}
                >{c.menu}</button>
              ))}
            </div>
          )}
        </div>

        <button
          type="button"
          onClick={() => onDecide(permission.callId, 'DENY')}
          style={denyBtnStyle}
        >Reject</button>
      </div>
    </article>
  );
}

// ────────────────────────────────────────────────────────────────────
// Styles. Yellow palette is intentionally literal (not themed) so the
// card reads as a warning regardless of theme.
// ────────────────────────────────────────────────────────────────────

const cardStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 14,
  padding: '12px 14px',
  background: '#FFFBEB',
  border: '1px solid #FCD34D',
  borderRadius: 8,
  flexWrap: 'wrap',
};
const titleStyle: React.CSSProperties = {
  color: '#92400E', fontSize: 13, fontWeight: 600,
};
const summaryStyle: React.CSSProperties = {
  color: '#78350F', fontSize: 12, marginTop: 2,
};
const actionsStyle: React.CSSProperties = {
  display: 'flex', gap: 8, alignItems: 'center', flexShrink: 0,
};
const splitWrapStyle: React.CSSProperties = {
  position: 'relative', display: 'inline-flex',
};
const approveOnceBtnStyle: React.CSSProperties = {
  padding: '6px 12px',
  background: '#10B981', color: '#fff',
  border: 'none', borderRadius: 4,
  fontWeight: 600, cursor: 'pointer', fontSize: 12,
};
const allowNextBtnStyle: React.CSSProperties = {
  padding: '6px 10px 6px 12px',
  background: '#15803d', color: '#fff',
  border: 'none',
  borderTopLeftRadius: 4, borderBottomLeftRadius: 4,
  borderTopRightRadius: 0, borderBottomRightRadius: 0,
  fontWeight: 600, cursor: 'pointer', fontSize: 12,
};
const allowNextCaretStyle: React.CSSProperties = {
  padding: '6px 8px',
  background: '#15803d', color: '#fff',
  border: 'none',
  borderLeft: '1px solid #166534',
  borderTopLeftRadius: 0, borderBottomLeftRadius: 0,
  borderTopRightRadius: 4, borderBottomRightRadius: 4,
  cursor: 'pointer', fontSize: 11,
};
const menuStyle: React.CSSProperties = {
  position: 'absolute', top: 'calc(100% + 4px)', right: 0,
  minWidth: 180,
  background: 'var(--bg-card)',
  border: '1px solid var(--border)',
  borderRadius: 6,
  boxShadow: '0 6px 18px rgba(0,0,0,0.12)',
  padding: 4,
  zIndex: 30,
  display: 'flex', flexDirection: 'column',
};
const menuHeaderStyle: React.CSSProperties = {
  fontSize: 10, color: 'var(--text-4)',
  textTransform: 'uppercase', letterSpacing: 0.5,
  padding: '4px 8px 2px',
};
const menuItemStyle: React.CSSProperties = {
  background: 'transparent', border: 'none',
  textAlign: 'left', padding: '6px 8px',
  borderRadius: 4, cursor: 'pointer',
  fontSize: 12, color: 'var(--text-1)',
};
const menuItemSelectedStyle: React.CSSProperties = {
  background: 'var(--accent-a10)', color: 'var(--accent-dark)', fontWeight: 600,
};
const denyBtnStyle: React.CSSProperties = {
  padding: '6px 14px',
  background: 'transparent', color: '#92400E',
  border: '1px solid #FCD34D', borderRadius: 4,
  cursor: 'pointer', fontSize: 12,
};

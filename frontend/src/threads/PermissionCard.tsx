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
import { useEffect, useMemo, useRef, useState } from 'react';
import type { PendingPermission } from './ConversationPane';

/** A human-readable read of a permission prompt: a plain-language
 *  action ("Run shell command", "Edit file"), an optional target the
 *  user recognises at a glance (the file's basename), and the salient
 *  body to show — the command line or the full path — instead of the
 *  raw {@code {"command":"…"}} JSON the backend ships as the summary. */
export type PermissionDescription = {
  action: string;
  target: string | null;
  body: string | null;
};

/** Map a raw tool name (often {@code mcp__bytequay__run_shell} or a
 *  bare built-in like {@code Edit}) to a plain-language verb. */
function humanizeToolName(toolName: string): string {
  const bare = toolName.replace(/^mcp__[^_]+__/, '');
  switch (bare) {
    case 'run_shell':
    case 'Bash': return 'Run shell command';
    case 'Edit':
    case 'MultiEdit': return 'Edit file';
    case 'Write': return 'Write file';
    case 'NotebookEdit': return 'Edit notebook';
    case 'Read': return 'Read file';
    default: return bare.replace(/_/g, ' ');
  }
}

/** Parse the summary into a {field: value} map. The summary is the
 *  tool input as compact JSON, but the backend caps it at 240 chars, so
 *  a long command can arrive as truncated (unparseable) JSON. We try a
 *  real parse first, then fall back to lifting the first key's string
 *  value out of the unterminated text. */
function parseSummaryFields(summary: string): Record<string, string> {
  try {
    const parsed = JSON.parse(summary) as Record<string, unknown>;
    if (parsed && typeof parsed === 'object') {
      const out: Record<string, string> = {};
      for (const [k, v] of Object.entries(parsed)) {
        if (typeof v === 'string') out[k] = v;
      }
      return out;
    }
  }
  catch { /* truncated JSON — fall through to the lenient extractor */ }
  const m = /^\s*\{\s*"(\w+)"\s*:\s*"((?:[^"\\]|\\.)*)/.exec(summary);
  if (m) {
    const value = m[2]
      .replace(/\\n/g, '\n').replace(/\\t/g, '\t')
      .replace(/\\"/g, '"').replace(/\\\\/g, '\\');
    return { [m[1]]: value };
  }
  return {};
}

export function describePermission(toolName: string, summary: string): PermissionDescription {
  const action = humanizeToolName(toolName);
  const fields = parseSummaryFields(summary ?? '');
  const command = fields.command ?? fields.cmd ?? null;
  const path = fields.file_path ?? fields.path ?? fields.notebook_path ?? null;
  if (command !== null) {
    return { action, target: null, body: command };
  }
  if (path !== null) {
    const base = path.split('/').filter(Boolean).pop() ?? null;
    return { action, target: base, body: path };
  }
  const firstValue = Object.values(fields)[0] ?? null;
  return { action, target: null, body: firstValue };
}

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
  // Once the user acts, the card stays — recoloured to the outcome (green
  // approved / red rejected) instead of vanishing — so the decision is
  // legible. Yellow while still requesting.
  const [decided, setDecided] = useState<'ALLOW' | 'DENY' | null>(null);
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
  const desc = useMemo(
    () => describePermission(permission.toolName, permission.summary),
    [permission.toolName, permission.summary]);

  // Record the decision locally so the card recolours to the outcome, then
  // forward it to the gate.
  const decide = (decision: 'ALLOW' | 'DENY', preApprove?: PermissionPreApprove) => {
    setDecided(decision);
    if (preApprove !== undefined) onDecide(permission.callId, decision, preApprove);
    else onDecide(permission.callId, decision);
  };

  const pal = decided === 'ALLOW' ? APPROVED : decided === 'DENY' ? DENIED : REQUESTING;
  const title = decided === 'ALLOW'
    ? <>✓ Approved — <strong>{desc.action}</strong></>
    : decided === 'DENY'
      ? <>✕ Rejected — <strong>{desc.action}</strong></>
      : <>⚠ Approval needed — <strong>{desc.action}</strong></>;

  return (
    <article style={{ ...cardStyle, background: pal.bg, borderColor: pal.border }}>
      <div style={textColStyle}>
        <div style={{ ...titleStyle, color: pal.title }} title={permission.toolName}>
          {title}
          {desc.target && <span style={{ color: pal.title, fontWeight: 500 }}> · {desc.target}</span>}
        </div>
        {desc.body && (
          <pre style={{
            ...bodyStyle,
            color: pal.bodyText, background: pal.bodyBg, borderColor: pal.bodyBorder,
          }}
          >{desc.body}</pre>
        )}
      </div>
      {decided !== null ? (
        <div style={{ ...statusStyle, color: pal.title, borderColor: pal.border }}>
          {decided === 'ALLOW' ? '✓ Approved' : '✕ Rejected'}
        </div>
      ) : (
        <div style={actionsStyle}>
          <button
            type="button"
            onClick={() => decide('ALLOW')}
            style={approveOnceBtnStyle}
          >Approve once</button>

          <div ref={menuRef} style={splitWrapStyle}>
            <button
              type="button"
              onClick={() => decide('ALLOW', { toolName: permission.toolName, count: choice.count })}
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
            onClick={() => decide('DENY')}
            style={denyBtnStyle}
          >Reject</button>
        </div>
      )}
    </article>
  );
}

// ────────────────────────────────────────────────────────────────────
// Styles. Palettes are intentionally literal (not themed) so the card
// reads as warning / success / danger regardless of theme:
//   requesting → yellow, approved → green, rejected → red.
// ────────────────────────────────────────────────────────────────────

type Palette = {
  bg: string; border: string; title: string;
  bodyBg: string; bodyBorder: string; bodyText: string;
};
const REQUESTING: Palette = {
  bg: '#FFFBEB', border: '#FCD34D', title: '#92400E',
  bodyBg: 'rgba(146, 64, 14, 0.08)', bodyBorder: 'rgba(146, 64, 14, 0.15)', bodyText: '#451A03',
};
const APPROVED: Palette = {
  bg: '#ECFDF5', border: '#6EE7B7', title: '#065F46',
  bodyBg: 'rgba(6, 95, 70, 0.08)', bodyBorder: 'rgba(6, 95, 70, 0.15)', bodyText: '#064E3B',
};
const DENIED: Palette = {
  bg: '#FEF2F2', border: '#FCA5A5', title: '#991B1B',
  bodyBg: 'rgba(153, 27, 27, 0.07)', bodyBorder: 'rgba(153, 27, 27, 0.15)', bodyText: '#7F1D1D',
};

const cardStyle: React.CSSProperties = {
  display: 'flex',
  // Bottom-align the buttons so they hug the lower edge of the card
  // when the summary wraps to several lines, instead of riding next
  // to the title.
  alignItems: 'flex-end',
  gap: 18,
  padding: '18px 22px',
  border: '1px solid',
  borderRadius: 10,
  flexWrap: 'wrap',
};
const statusStyle: React.CSSProperties = {
  flexShrink: 0,
  padding: '8px 16px',
  border: '1px solid',
  borderRadius: 6,
  fontSize: 13,
  fontWeight: 700,
  letterSpacing: 0.2,
  background: 'rgba(255,255,255,0.45)',
};
const textColStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  // Without this the summary (often a long JSON path with no spaces)
  // overflows its computed flex width and visually bleeds underneath
  // the action buttons. Clip + word-break together keep it contained.
  overflow: 'hidden',
};
const titleStyle: React.CSSProperties = {
  fontSize: 14.5, fontWeight: 600, lineHeight: 1.4,
};
// The salient detail — a command line or a file path — in a readable
// monospace block instead of raw JSON. Caps + scrolls like the old
// summary so a long command can't blow the card's height out.
const bodyStyle: React.CSSProperties = {
  margin: '8px 0 0',
  padding: '9px 12px',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 12.5,
  lineHeight: 1.5,
  border: '1px solid',
  borderRadius: 6,
  whiteSpace: 'pre-wrap',
  overflowWrap: 'anywhere',
  wordBreak: 'break-word',
  maxHeight: 140,
  overflowY: 'auto',
};
const actionsStyle: React.CSSProperties = {
  display: 'flex', gap: 8, alignItems: 'center', flexShrink: 0,
};
const splitWrapStyle: React.CSSProperties = {
  position: 'relative', display: 'inline-flex',
};
const approveOnceBtnStyle: React.CSSProperties = {
  padding: '9px 16px',
  background: '#10B981', color: '#fff',
  border: 'none', borderRadius: 5,
  fontWeight: 600, cursor: 'pointer', fontSize: 13,
};
const allowNextBtnStyle: React.CSSProperties = {
  padding: '9px 12px 9px 16px',
  background: '#15803d', color: '#fff',
  border: 'none',
  borderTopLeftRadius: 5, borderBottomLeftRadius: 5,
  borderTopRightRadius: 0, borderBottomRightRadius: 0,
  fontWeight: 600, cursor: 'pointer', fontSize: 13,
};
const allowNextCaretStyle: React.CSSProperties = {
  padding: '9px 10px',
  background: '#15803d', color: '#fff',
  border: 'none',
  borderLeft: '1px solid #166534',
  borderTopLeftRadius: 0, borderBottomLeftRadius: 0,
  borderTopRightRadius: 5, borderBottomRightRadius: 5,
  cursor: 'pointer', fontSize: 12,
};
const menuStyle: React.CSSProperties = {
  // Anchor to the button's top edge so the menu opens upward — the
  // card lives near the reply box and a downward menu used to fall
  // behind / off the visible region.
  position: 'absolute', bottom: 'calc(100% + 4px)', right: 0,
  minWidth: 180,
  background: 'var(--bg-card)',
  border: '1px solid var(--border)',
  borderRadius: 6,
  boxShadow: '0 -6px 18px rgba(0,0,0,0.12)',
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
  padding: '9px 18px',
  background: 'transparent', color: '#B91C1C',
  border: '1px solid #FCA5A5', borderRadius: 5,
  cursor: 'pointer', fontWeight: 600, fontSize: 13,
};

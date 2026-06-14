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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { ResolvedWorkModelDto, WorkModelDto } from '../types';
import { WorkModelPicker } from './WorkModelPicker';

/** Fixed popover width — used both for the box and for clamping it
 *  inside the viewport when the pill sits near the right edge (the
 *  task rail lives on the right, so a left-anchored popover would
 *  otherwise spill off-screen). */
const POPOVER_WIDTH = 380;

type Scope =
  | { kind: 'thread'; threadId: string }
  | { kind: 'task'; threadId: string; taskId: string };

type Props = {
  scope: Scope;
  /** Optional callback when the override changes — the parent uses
   *  this to refresh any cached resolved-model display elsewhere on
   *  the page. */
  onChange?: (resolved: ResolvedWorkModelDto) => void;
};

/**
 * Compact "current work model" chip with a click-to-pick popover.
 *
 * <p>Reads {@code ResolvedWorkModelDto} from the matching bridge
 * method (thread or task) and renders both the effective label and a
 * subtle inheritance hint (e.g. "Inherited from workspace ByteQuay").
 * Opening the popover surfaces the existing {@link WorkModelPicker};
 * picking a model commits it through the matching {@code set*}
 * method and closes the popover.
 *
 * <p>Esc + outside-click dismiss; focus returns to the pill. The
 * picker writes go through the same bridge as the workspace settings
 * page so the per-scope cascade stays consistent across surfaces.
 */
export function WorkModelPill({ scope, onChange }: Props) {
  const [resolved, setResolved] = useState<ResolvedWorkModelDto | null>(null);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Viewport-fixed coordinates for the portaled popover (see below).
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const popoverRef = useRef<HTMLDivElement | null>(null);

  // The popover is portaled to <body> and positioned with fixed coords
  // off the trigger's rect. It HAS to escape the rail: each rail card
  // sets backdrop-filter (its own stacking context) and the rail is an
  // overflow:auto scroller, so an in-flow absolute popover gets painted
  // behind the cards below it and clipped at the rail edge.
  const place = useCallback(() => {
    const trigger = triggerRef.current;
    if (trigger === null) return;
    const r = trigger.getBoundingClientRect();
    const left = Math.max(8, Math.min(r.left, window.innerWidth - POPOVER_WIDTH - 8));
    setPos({ top: r.bottom + 6, left });
  }, []);

  const load = useCallback(async () => {
    try {
      const next = scope.kind === 'thread'
        ? await window.bridge.getThreadWorkModel(scope.threadId)
        : await window.bridge.getTaskWorkModel(scope.threadId, scope.taskId);
      setResolved(next);
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [scope]);

  useEffect(() => { void load(); }, [load]);

  // Keep the fixed popover glued to the trigger while it's open: the
  // rail scrolls and the window can resize under it. Capture-phase
  // scroll catches the rail's own scroll container, not just window.
  useEffect(() => {
    if (!open) return;
    place();
    window.addEventListener('scroll', place, true);
    window.addEventListener('resize', place);
    return () => {
      window.removeEventListener('scroll', place, true);
      window.removeEventListener('resize', place);
    };
  }, [open, place]);

  // Outside-click + Esc dismiss for the popover.
  useEffect(() => {
    if (!open) return;
    const onMouseDown = (ev: MouseEvent) => {
      const popover = popoverRef.current;
      const trigger = triggerRef.current;
      const target = ev.target as Node | null;
      if (target === null) return;
      if (popover !== null && popover.contains(target)) return;
      if (trigger !== null && trigger.contains(target)) return;
      setOpen(false);
    };
    const onKey = (ev: KeyboardEvent) => {
      if (ev.key === 'Escape') {
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener('mousedown', onMouseDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onMouseDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const commit = useCallback(async (next: WorkModelDto | null) => {
    try {
      const updated = scope.kind === 'thread'
        ? await window.bridge.setThreadWorkModel(scope.threadId, next)
        : await window.bridge.setTaskWorkModel(scope.threadId, scope.taskId, next);
      setResolved(updated);
      onChange?.(updated);
      setOpen(false);
      triggerRef.current?.focus();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [scope, onChange]);

  const label = useMemo(() => formatLabel(resolved), [resolved]);
  const hint = useMemo(() => formatHint(resolved), [resolved]);

  if (resolved === null && error === null) {
    return (
      <button type="button" style={pillStyle(false)} disabled>
        <span style={pillGlyphStyle}>◇</span>
        <span style={pillTextStyle}>Loading…</span>
      </button>
    );
  }

  if (error !== null) {
    return (
      <button
        type="button"
        style={pillStyle(false)}
        onClick={() => { void load(); }}
        title={error}
      >
        <span style={pillGlyphStyle}>!</span>
        <span style={pillTextStyle}>Work model error</span>
      </button>
    );
  }

  const isInherited = resolved !== null && resolved.override === null;
  return (
    <div style={wrapperStyle}>
      <button
        ref={triggerRef}
        type="button"
        style={pillStyle(open)}
        onClick={() => {
          // Compute the spot before flipping open so the portaled
          // popover renders in place with no first-frame flash.
          if (!open) place();
          setOpen((prev) => !prev);
        }}
        title={hint ?? 'Pick the work model'}
        aria-haspopup="dialog"
        aria-expanded={open}
      >
        <span style={pillGlyphStyle}>◇</span>
        <span style={pillTextStyle}>{label}</span>
        <span style={pillChevStyle} aria-hidden>▾</span>
      </button>
      {open && pos !== null && createPortal(
        <div
          ref={popoverRef}
          style={{ ...popoverStyle, top: pos.top, left: pos.left }}
          role="dialog"
          aria-label="Work model picker"
        >
          {hint !== null && (
            <div style={inheritanceHintStyle}>
              <span aria-hidden style={inheritanceGlyphStyle}>↰</span>
              {hint}
            </div>
          )}
          <WorkModelPicker
            value={resolved?.override ?? null}
            onChange={(next) => { void commit(next); }}
          />
          {!isInherited && (
            <div style={footerStyle}>
              <button
                type="button"
                style={clearBtnStyle}
                onClick={() => { void commit(null); }}
                title="Clear the override on this scope"
              >
                Clear override
              </button>
            </div>
          )}
        </div>,
        document.body,
      )}
    </div>
  );
}

function formatLabel(resolved: ResolvedWorkModelDto | null): string {
  if (resolved === null) return 'Work model';
  const eff = resolved.effective;
  // Compact: "Claude Sonnet 4.6 · CLI" — the agent / provider id
  // alone is too cryptic; the model id alone hides whether it's CLI
  // or API. Joining both keeps the pill scannable at a glance.
  const modelLabel = eff.model ?? 'default';
  return `${eff.agentOrProvider} · ${modelLabel} · ${eff.kind}`;
}

function formatHint(resolved: ResolvedWorkModelDto | null): string | null {
  if (resolved === null) return null;
  switch (resolved.provenance.source) {
    case 'TASK':
      return 'Override pinned on this task';
    case 'THREAD':
      return 'Override pinned on this thread';
    case 'WORKSPACE':
      return `Inherited from ${resolved.provenance.scopeLabel}`;
    case 'GLOBAL_DEFAULT':
      return `Inherited from ${resolved.provenance.scopeLabel}`;
    default:
      return null;
  }
}

/* ── styles ─────────────────────────────────────────────────────── */

const wrapperStyle: React.CSSProperties = {
  position: 'relative',
  display: 'inline-flex',
};

function pillStyle(active: boolean): React.CSSProperties {
  return {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 6,
    padding: '4px 10px',
    height: 26,
    border: active
      ? '1px solid rgba(124,58,237,0.55)'
      : '1px solid rgba(124,58,237,0.25)',
    background: active ? 'rgba(124,58,237,0.18)' : 'rgba(124,58,237,0.08)',
    color: '#5b21b6',
    borderRadius: 999,
    cursor: 'pointer',
    fontFamily: 'inherit',
    fontSize: 11,
    fontWeight: 500,
    whiteSpace: 'nowrap',
  };
}

const pillGlyphStyle: React.CSSProperties = {
  fontSize: 11,
  lineHeight: 1,
};

const pillTextStyle: React.CSSProperties = {
  display: 'inline-block',
  maxWidth: 240,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const pillChevStyle: React.CSSProperties = {
  fontSize: 9,
  opacity: 0.7,
};

const popoverStyle: React.CSSProperties = {
  // Fixed + portaled to <body>: top/left are set inline from the
  // trigger's rect so the popover floats above the backdrop-filter
  // rail cards and isn't clipped by the rail's overflow scroller.
  position: 'fixed',
  width: POPOVER_WIDTH,
  padding: 14,
  background: '#fff',
  border: '1px solid var(--border)',
  borderRadius: 12,
  boxShadow: '0 12px 32px rgba(0,0,0,0.16)',
  zIndex: 1000,
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
};

const inheritanceHintStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  padding: '6px 10px',
  fontSize: 11,
  color: 'var(--text-3)',
  background: 'var(--bg-elevated)',
  border: '1px dashed var(--border)',
  borderRadius: 6,
};

const inheritanceGlyphStyle: React.CSSProperties = {
  fontSize: 12,
  color: 'var(--text-3)',
};

const footerStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
};

const clearBtnStyle: React.CSSProperties = {
  padding: '4px 10px',
  fontSize: 11,
  border: '1px solid var(--border)',
  background: '#fff',
  color: 'var(--text-2)',
  borderRadius: 999,
  cursor: 'pointer',
  fontFamily: 'inherit',
};

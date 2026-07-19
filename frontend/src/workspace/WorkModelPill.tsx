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
import type { ResolvedWorkModelDto, WorkModelDto, WorkModelOptionsDto } from '../types';
import { ComposerModelPicker } from './ComposerModelPicker';

/** Fixed popover width — used both for the box and for clamping it
 *  inside the viewport when the pill sits near the right edge (the
 *  task rail lives on the right, so a left-anchored popover would
 *  otherwise spill off-screen). */
const POPOVER_WIDTH = 380;

/** Tallest the popover gets (search box + 300px list cap + hints/padding);
 *  drives the flip-up decision when the trigger is near the viewport bottom. */
const POPOVER_MAX_H = 380;

/** Window event that opens the pill's picker — dispatched by the composer's
 *  "/model" slash command. ponytail: global event, one pill per composer in
 *  the live shell; scope by composer node if two pills ever coexist. */
export const OPEN_WORK_MODEL_EVENT = 'bytequay:open-work-model';

type Scope =
  | { kind: 'thread'; threadId: string }
  | { kind: 'task'; threadId: string; taskId: string }
  | { kind: 'stage'; stageId: string };

type Props = {
  scope: Scope;
  /** Optional callback when the override changes — the parent uses
   *  this to refresh any cached resolved-model display elsewhere on
   *  the page. */
  onChange?: (resolved: ResolvedWorkModelDto) => void;
  variant?: 'default' | 'workspace-v2';
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
export function WorkModelPill({ scope, onChange, variant = 'default' }: Props) {
  const workspaceVariant = variant === 'workspace-v2';
  const [resolved, setResolved] = useState<ResolvedWorkModelDto | null>(null);
  const [options, setOptions] = useState<WorkModelOptionsDto | null>(null);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Viewport-fixed coordinates for the portaled popover (see below). Anchors
  // by `top` (opens down) or `bottom` (opens up) depending on room — the pill
  // lives at the bottom of the composer, so it usually opens up.
  const [pos, setPos] = useState<{ left: number; top?: number; bottom?: number } | null>(null);
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
    // Open upward when there isn't room for the popover below the trigger
    // (the usual case in the composer). POPOVER_MAX_H is the tallest the
    // popover gets — search box + the list's 300px cap + hints/padding.
    const spaceBelow = window.innerHeight - r.bottom;
    setPos(spaceBelow < POPOVER_MAX_H
      ? { bottom: window.innerHeight - r.top + 6, left }
      : { top: r.bottom + 6, left });
  }, []);

  const load = useCallback(async () => {
    try {
      const next = scope.kind === 'thread'
        ? await window.bridge.getThreadWorkModel(scope.threadId)
        : scope.kind === 'task'
          ? await window.bridge.getTaskWorkModel(scope.threadId, scope.taskId)
          : await window.bridge.getStageWorkModel(scope.stageId);
      setResolved(next);
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [scope]);

  useEffect(() => { void load(); }, [load]);

  // Options give the effective model's human name for the pill label and
  // feed the picker. Non-refresh read — served from the backend's cached
  // catalog+credentials merge, no CLI re-probe. ponytail: one read per
  // composer mount; lift to a shared cache if that ever shows up hot.
  useEffect(() => {
    // Optional-chained: short-circuits when the bridge lacks the method
    // (partial test bridges) instead of throwing; the label then falls
    // back to the raw model id.
    window.bridge.getWorkModelOptions?.()
      .then(setOptions)
      .catch(() => { /* label falls back to the raw id */ });
  }, []);

  // Open when the composer's "/model" command fires. place() reads the
  // trigger rect, so run it before flipping open (same order as onClick).
  useEffect(() => {
    const onOpen = () => { place(); setOpen(true); };
    window.addEventListener(OPEN_WORK_MODEL_EVENT, onOpen);
    return () => window.removeEventListener(OPEN_WORK_MODEL_EVENT, onOpen);
  }, [place]);

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
        : scope.kind === 'task'
          ? await window.bridge.setTaskWorkModel(scope.threadId, scope.taskId, next)
          : await window.bridge.setStageWorkModel(scope.stageId, next);
      setResolved(updated);
      onChange?.(updated);
      setOpen(false);
      triggerRef.current?.focus();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [scope, onChange]);

  const label = useMemo(() => formatLabel(resolved, options), [resolved, options]);
  const hint = useMemo(() => formatHint(resolved), [resolved]);

  if (resolved === null && error === null) {
    return (
      <button type="button" className={workspaceVariant ? 'workspace-work-model-pill' : undefined}
        style={pillStyle(false, workspaceVariant)} disabled>
        <span style={pillTextStyle}>Model…</span>
      </button>
    );
  }

  if (error !== null) {
    return (
      <button
        type="button"
        className={workspaceVariant ? 'workspace-work-model-pill' : undefined}
        style={pillStyle(false, workspaceVariant)}
        onClick={() => { void load(); }}
        title={error}
      >
        <span style={pillTextStyle}>Model error</span>
      </button>
    );
  }

  return (
    <div style={wrapperStyle}>
      <button
        ref={triggerRef}
        type="button"
        className={workspaceVariant ? 'workspace-work-model-pill' : undefined}
        style={pillStyle(open, workspaceVariant)}
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
        <span style={pillTextStyle}>{label}</span>
        {workspaceVariant ? (
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#8b949e"
            strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
            <path d="m6 9 6 6 6-6" />
          </svg>
        ) : <span style={pillChevStyle} aria-hidden>⌄</span>}
      </button>
      {open && pos !== null && createPortal(
        <div
          ref={popoverRef}
          style={{ ...popoverStyle, top: pos.top, bottom: pos.bottom, left: pos.left }}
          role="dialog"
          aria-label="Work model picker"
        >
          {options === null || resolved === null
            ? <div style={loadingStyle}>Loading models…</div>
            : (
              <ComposerModelPicker
                options={options}
                override={resolved.override}
                effective={resolved.effective}
                onChange={(next) => { void commit(next); }}
              />
            )}
          {hint !== null && (
            <div style={inheritanceHintStyle}>
              <span aria-hidden style={inheritanceGlyphStyle}>↰</span>
              {hint}
            </div>
          )}
          {scope.kind === 'stage' && (
            <div style={inheritanceHintStyle}>
              <span aria-hidden style={inheritanceGlyphStyle}>⏳</span>
              A stage's agent runs one session for its whole lifetime —
              this applies next time this stage starts a new one, not to
              a session already running.
            </div>
          )}
        </div>,
        document.body,
      )}
    </div>
  );
}

function formatLabel(resolved: ResolvedWorkModelDto | null, options: WorkModelOptionsDto | null): string {
  if (resolved === null) return 'Model';
  // Copilot-style: the model's human name alone (e.g. "Claude Sonnet
  // 4.6"). Resolve the id through the catalog options; fall back to the
  // raw id (then the agent id) before options land or for a custom model.
  const eff = resolved.effective;
  const list = options === null
    ? []
    : eff.kind === 'CLI'
      ? options.cliAgents.find(a => a.id === eff.agentOrProvider)?.models ?? []
      : options.apiProviders.find(p => p.id === eff.agentOrProvider)?.models ?? [];
  const modelId = eff.model
    ?? (eff.kind === 'CLI'
      ? options?.cliAgents.find(a => a.id === eff.agentOrProvider)?.defaultModel
      : options?.apiProviders.find(p => p.id === eff.agentOrProvider)?.defaultModel)
    ?? null;
  const named = modelId === null ? undefined : list.find(m => m.id === modelId);
  return named?.displayName ?? modelId ?? eff.agentOrProvider;
}

function formatHint(resolved: ResolvedWorkModelDto | null): string | null {
  if (resolved === null) return null;
  switch (resolved.provenance.source) {
    case 'STAGE':
      return 'Override pinned on this stage';
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

function pillStyle(active: boolean, workspaceVariant = false): React.CSSProperties {
  if (workspaceVariant) {
    return {
      display: 'inline-flex',
      alignItems: 'center',
      gap: 5,
      height: 26,
      padding: '4px 9px',
      border: 0,
      ...(active ? { background: '#f6f8fa' } : {}),
      color: '#454c54',
      borderRadius: 7,
      cursor: 'pointer',
      fontFamily: 'inherit',
      fontSize: 12.5,
      fontWeight: 400,
      whiteSpace: 'nowrap',
    };
  }
  return {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 4,
    padding: '4px 8px',
    height: 26,
    border: '1px solid transparent',
    background: active ? 'var(--bg-elev, rgba(0,0,0,0.06))' : 'transparent',
    color: 'var(--text-2, #4b5563)',
    borderRadius: 8,
    cursor: 'pointer',
    fontFamily: 'inherit',
    fontSize: 12,
    fontWeight: 500,
    whiteSpace: 'nowrap',
  };
}

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

const loadingStyle: React.CSSProperties = {
  padding: '10px 4px',
  fontSize: 12,
  color: 'var(--text-3)',
};

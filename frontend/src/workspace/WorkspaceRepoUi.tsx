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
import { useEffect, useRef, type ReactNode } from 'react';
import type { CherryPickResultDto } from './workspaceApi';

/** Headline for a cherry-pick outcome. Shared so the Commits tab and the
 *  branch detail page describe the same result the same way. */
export function cherryResultTitle(result: CherryPickResultDto): string {
  if (result.status === 'done') return `Created local branch ${result.resultBranch}`;
  if (result.status === 'aborted') return 'Cherry-pick aborted';
  const count = result.conflictPaths.length;
  return `Conflict in ${count} file${count === 1 ? '' : 's'}`;
}

/**
 * Dismisses an open popup when the pointer goes down outside it, or on
 * Escape. Attach the returned ref to the element that wraps *both* the
 * trigger and the menu, so clicking the trigger again toggles rather than
 * closing and reopening in the same gesture.
 *
 * These menus are absolutely-positioned divs, not `<dialog>`/`<select>`,
 * so nothing dismisses them for free — without this the only way out is
 * picking an item.
 */
export function useDismissOnOutside<T extends HTMLElement>(
  open: boolean,
  close: () => void,
) {
  const ref = useRef<T>(null);
  // Held in a ref so an inline `() => setOpen(false)` doesn't resubscribe
  // the listeners on every render.
  const closeRef = useRef(close);
  closeRef.current = close;
  useEffect(() => {
    if (!open) return undefined;
    const onPointerDown = (event: PointerEvent) => {
      if (ref.current !== null && !ref.current.contains(event.target as Node)) {
        closeRef.current();
      }
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeRef.current();
    };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);
  return ref;
}

export function PageHeader({
  title,
  detail,
  children,
}: {
  title: string;
  detail?: string;
  children?: ReactNode;
}) {
  return (
    <header className="wu-page-header">
      <div className="wu-page-heading"><h1>{title}</h1>{detail && <span>{detail}</span>}</div>
      <div className="wu-header-actions">{children}</div>
    </header>
  );
}

export function BodyMessage({ children }: { children: ReactNode }) {
  return <div className="wu-body-message">{children}</div>;
}

/** Electron wraps a bridge rejection as `Error invoking remote method
 *  '<channel>': Error: <cause>`, and the bridge itself appends the raw
 *  response body. Users should see neither layer. */
const IPC_WRAPPER = /^Error invoking remote method '[^']*':\s*(?:Error:\s*)?/;

/**
 * The server's own sentence, dug out of the layers wrapped around it.
 * Falls back to the unwrapped text, so a plain client-side Error passes
 * through unchanged.
 */
export function message(value: unknown): string {
  const raw = value instanceof Error ? value.message : String(value);
  const unwrapped = raw.replace(IPC_WRAPPER, '');
  const at = unwrapped.indexOf('{');
  if (at >= 0) {
    try {
      const body = JSON.parse(unwrapped.slice(at)) as { message?: unknown };
      if (typeof body.message === 'string' && body.message.trim().length > 0) {
        return body.message;
      }
    }
    catch {
      // Not a JSON body after all — the unwrapped text is the best we have.
    }
  }
  return unwrapped;
}

export function relative(iso: string): string {
  const delta = Date.now() - Date.parse(iso);
  if (!Number.isFinite(delta) || delta < 60_000) return 'now';
  if (delta < 3_600_000) return `${Math.floor(delta / 60_000)}m`;
  if (delta < 86_400_000) return `${Math.floor(delta / 3_600_000)}h`;
  return `${Math.floor(delta / 86_400_000)}d`;
}

export function isToday(iso: string | null): boolean {
  if (iso === null) return false;
  const date = new Date(iso);
  const now = new Date();
  return date.getFullYear() === now.getFullYear()
    && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate();
}

export function prInitials(name: string): string {
  const known: Record<string, string> = {
    chenjian2664: 'CJ',
    ebyhr: 'EB',
    skyglass: 'SG',
  };
  const knownValue = known[name.toLowerCase()];
  if (knownValue !== undefined) return knownValue;
  const parts = name.split(/[-_\s]+/).filter(Boolean);
  if (parts.length > 1) return parts.slice(0, 2).map(part => part[0]).join('').toUpperCase();
  return name.slice(0, 2).toUpperCase();
}

export function SearchIcon() {
  return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.8" strokeLinecap="round"><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></svg>;
}

export function ExternalIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M7 17 17 7" />
      <path d="M8 7h9v9" />
    </svg>
  );
}

export function ChevronDownIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="m6 9 6 6 6-6" />
    </svg>
  );
}

export function BranchIcon() {
  return <svg className="wu-branch-icon" width="14" height="14" viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
    <path d="M6 3v12" />
    <circle cx="18" cy="6" r="2.6" />
    <circle cx="6" cy="18" r="2.6" />
    <path d="M18 9a9 9 0 0 1-9 9" />
  </svg>;
}

export function BranchCheckIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none"
      stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 6 9 17l-5-5" />
    </svg>
  );
}

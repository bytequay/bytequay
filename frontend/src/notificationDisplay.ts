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
import type { NotificationDto, NotificationKindDto } from './types';

/** Icon glyph for a notification row's leading slot. AUTO_FIX_DONE
 *  rows always get the check; the title/preview differentiate
 *  approved vs discarded vs interrupted audit rows. */
export function kindIcon(kind: NotificationKindDto): string {
  switch (kind) {
    case 'AWAITING_REVIEW':  return '\u{1F441}';
    case 'NEEDS_ATTENTION':  return '⚠';
    case 'AUTO_FIX_DONE':    return '✓';
  }
}

/** Headline copy for a row. AUTO_FIX_DONE rows split into two flavours:
 *  publish-gate audit rows (carrying `publishResolution` + `action`)
 *  get a verb that matches what actually happened; the legacy
 *  ship-and-continue payload falls back to "Shipped". */
export function titleFor(n: NotificationDto): string {
  if (n.kind === 'AWAITING_REVIEW') return 'Awaiting your review';
  if (n.kind === 'NEEDS_ATTENTION') return 'Needs your attention';
  const payload = payloadOf(n);
  if (payload) {
    const resolution = typeof payload.publishResolution === 'string'
        ? payload.publishResolution
        : null;
    const action = typeof payload.action === 'string' ? payload.action : null;
    if (resolution === 'approved' || resolution === 'approved_concurrent') {
      if (action === 'push') return 'Pushed';
      if (action === 'post_comment') return 'Posted comment';
      return 'Approved';
    }
    if (resolution === 'discarded') return 'Discarded';
    if (resolution === 'discarded_after_interrupt') return 'Interrupted approval discarded';
    // Two distinct interrupt audits — both render as "Approval
    // interrupted" so the bell title stays stable; the preview line
    // carries the publish-outcome detail (confirmed remote vs unknown).
    if (resolution === 'interrupted'
        || resolution === 'interrupted_unconfirmed'
        || resolution === 'interrupted_confirmed') {
      return 'Approval interrupted';
    }
    if (resolution === 'recovered') return 'Resolved locally';
    if (resolution === 'failed') return 'Publish failed';
  }
  return 'Shipped';
}

/** Sub-line copy under the title. AUTO_FIX_DONE rows that carry a
 *  publish-audit shape surface the backend's pre-baked `message`
 *  directly (the side-effect summary or the failure reason); other
 *  shapes fall through to the legacy renderer. */
export function previewFor(n: NotificationDto): string {
  const payload = payloadOf(n);
  if (!payload) return '';
  if (n.kind === 'AUTO_FIX_DONE') {
    if (typeof payload.publishResolution === 'string'
        && typeof payload.message === 'string') {
      return payload.message;
    }
    const repo = typeof payload.repoFullName === 'string' ? payload.repoFullName : null;
    const pr = typeof payload.prNumber === 'number' ? `#${payload.prNumber}` : null;
    const nextTitle = typeof payload.nextTitle === 'string' ? payload.nextTitle : null;
    const left = [repo, pr].filter(Boolean).join(' ');
    if (left && nextTitle) return `${left} · next: ${nextTitle}`;
    if (left) return left;
    if (nextTitle) return `next: ${nextTitle}`;
  }
  // Debugging fallback for unknown payloads — surfaces the first few
  // keys so a developer can see what shape landed.
  return Object.entries(payload)
      .slice(0, 3)
      .map(([k, v]) => `${k}: ${String(v)}`)
      .join(' · ');
}

/** Relative-time string for the row's right-side meta column.
 *  Pulled into the helper module so all the display code lives
 *  together. */
export function relativeTime(iso: string): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return '';
  const deltaSec = Math.round((Date.now() - then) / 1000);
  if (deltaSec < 60) return 'just now';
  if (deltaSec < 3600) return `${Math.round(deltaSec / 60)}m ago`;
  if (deltaSec < 86400) return `${Math.round(deltaSec / 3600)}h ago`;
  return `${Math.round(deltaSec / 86400)}d ago`;
}

function payloadOf(n: NotificationDto): Record<string, unknown> | null {
  if (!n.payloadJson) return null;
  try {
    const raw: unknown = JSON.parse(n.payloadJson);
    return typeof raw === 'object' && raw !== null ? raw as Record<string, unknown> : null;
  }
  catch {
    return null;
  }
}

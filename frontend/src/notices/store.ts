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
import fs from 'node:fs';
import { APPROVAL_NUDGE_MS, needsAction, type AppNotice, type NoticeSnapshot } from './contracts';

const RECENT_COMPLETION_MS = 24 * 60 * 60 * 1000;

/** Delivery state with durable dismissals. Resolving a notice never changes its task or Home attention. */
export class NoticeStore {
  private initialized = false;
  private restored = false;
  private readonly seen = new Set<string>();
  private readonly removed = new Set<string>();
  private readonly notices = new Map<string, AppNotice>();
  private readonly popups = new Set<string>();
  private readonly viewed = new Set<string>();
  private readonly settling = new Map<string, { since: number; popup: boolean }>();
  private readonly nudged = new Set<string>();

  constructor(private readonly storagePath: () => string) {}

  /** Returns the approvals whose alert came back because the agent stops waiting within a minute. */
  update(incoming: AppNotice[], now = Date.now()): string[] {
    this.restore();
    const current = new Set(incoming.map(notice => notice.id));
    for (const [id, notice] of this.notices) {
      if ((!current.has(id) && needsAction(notice)) || (!needsAction(notice) && now - Date.parse(notice.occurredAt) > RECENT_COMPLETION_MS)) {
        this.notices.delete(id); this.popups.delete(id);
      }
    }
    for (const id of this.settling.keys()) if (!current.has(id)) this.settling.delete(id);
    for (const id of this.nudged) if (!current.has(id)) this.nudged.delete(id);
    const nudges: string[] = [];
    for (const notice of incoming) {
      if (this.removed.has(notice.id)) continue;
      const known = this.seen.has(notice.id);
      if (!needsAction(notice) && now - Date.parse(notice.occurredAt) > RECENT_COMPLETION_MS) { this.remember(notice.id); continue; }
      // Let ordinary turn finalization release its worktree before calling it blocked.
      if (notice.kind === 'RECOVERY' && !known) {
        const pending = this.settling.get(notice.id);
        if (pending === undefined) { this.settling.set(notice.id, { since: now, popup: this.initialized }); continue; }
        if (now - pending.since < 10_000) continue;
      }
      if (!known || needsAction(notice) || this.notices.has(notice.id)) {
        for (const [id, prior] of this.notices) {
          if (id !== notice.id && prior.taskId === notice.taskId) { this.notices.delete(id); this.popups.delete(id); }
        }
        this.notices.set(notice.id, notice);
        if (!known && this.initialized && (this.settling.get(notice.id)?.popup ?? true)) this.popups.add(notice.id);
        // Notices already waiting at startup are old news: no alert and no unread dot.
        if (!known && !this.initialized) this.viewed.add(notice.id);
        const left = notice.expiresAt === undefined ? Number.NaN : Date.parse(notice.expiresAt) - now;
        // Once, even after the alert was closed: in real use, one approval in five expired unanswered. A deadline already past
        // still counts while the approval is listed: the backend's wait does not count time the Mac sleeps.
        if (left <= APPROVAL_NUDGE_MS && !this.nudged.has(notice.id)) {
          this.nudged.add(notice.id); this.popups.add(notice.id); this.viewed.delete(notice.id); nudges.push(notice.id);
        }
      }
      this.remember(notice.id);
    }
    this.initialized = true;
    const recent = [...this.notices.values()].filter(notice => !needsAction(notice)).sort(newest);
    for (const notice of recent.slice(50)) { this.notices.delete(notice.id); this.popups.delete(notice.id); }
    for (const id of this.viewed) if (!this.notices.has(id)) this.viewed.delete(id);
    return nudges;
  }

  dismiss(id: string): void { this.popups.delete(id); }

  remove(id: string): void {
    if (!this.notices.has(id)) return;
    const file = this.storagePath();
    fs.writeFileSync(`${file}.tmp`, JSON.stringify([...this.removed, id]), { mode: 0o600 });
    fs.renameSync(`${file}.tmp`, file);
    this.notices.delete(id);
    this.removed.add(id); this.popups.delete(id); this.viewed.delete(id); this.settling.delete(id);
  }

  markViewed(id: string): void { this.viewed.add(id); this.dismiss(id); }

  snapshot(active: boolean, maxHeight = 600): NoticeSnapshot {
    const notices = [...this.notices.values()].sort((a, b) => Number(needsAction(b)) - Number(needsAction(a)) || newest(a, b));
    return { notices, popupIds: notices.filter(notice => this.popups.has(notice.id)).map(notice => notice.id),
      viewedIds: notices.filter(notice => this.viewed.has(notice.id)).map(notice => notice.id), active, maxHeight };
  }

  private restore(): void {
    if (this.restored) return;
    try {
      const saved: unknown = JSON.parse(fs.readFileSync(this.storagePath(), 'utf8'));
      if (!Array.isArray(saved) || saved.some(id => typeof id !== 'string' || !id.trim() || id.length > 1000)) throw new Error('Invalid dismissed notices');
      for (const id of saved) this.removed.add(id);
    }
    catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
    }
    this.restored = true;
  }

  private remember(id: string): void {
    this.seen.add(id);
    if (this.seen.size > 4096) {
      const removable = [...this.seen].find(key => !this.notices.has(key));
      if (removable !== undefined) this.seen.delete(removable);
    }
  }
}
const newest = (a: AppNotice, b: AppNotice): number => Date.parse(b.occurredAt) - Date.parse(a.occurredAt) || a.id.localeCompare(b.id);

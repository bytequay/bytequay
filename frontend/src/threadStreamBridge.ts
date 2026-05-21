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
import { ipcMain, type BrowserWindow } from 'electron';
import { BACKEND_BASE } from './backendProcess';
import type { ThreadStreamEvent } from './types';

/**
 * Main-process broker for the per-thread Server-Sent Events stream that
 * the backend exposes at {@code GET /api/threads/:id/stream}. The
 * renderer can't open an SSE connection itself under the sandboxed
 * preload model, so we keep one open in main and forward each parsed
 * event to the renderer via {@code webContents.send('threads:stream:event', ...)}.
 *
 * <p>Multiple subscribers per threadId are supported through a small
 * refcount: the first subscribe opens the upstream connection; the
 * last unsubscribe closes it. The renderer-side preload helper
 * filters events by threadId so callers only see what they asked for.
 */
type Subscription = {
  controller: AbortController;
  refCount: number;
};

const subscriptions = new Map<string, Subscription>();

/** Each line of an SSE response is either a comment ({@code :...}),
 *  a field (e.g. {@code event: AssistantText}, {@code data: {...}}),
 *  or blank (terminator). Blank lines flush the accumulated buffer
 *  into one dispatched event. */
type SseBuffer = {
  event: string | null;
  data: string[];
};

export function registerTaskStreamIpc(getMainWindow: () => BrowserWindow | null): void {
  ipcMain.handle('threads:stream:start', async (_e, id: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const existing = subscriptions.get(id);
    if (existing) {
      existing.refCount += 1;
      return;
    }
    const controller = new AbortController();
    subscriptions.set(id, { controller, refCount: 1 });
    void runStream(id, controller, getMainWindow);
  });

  ipcMain.handle('threads:stream:stop', async (_e, id: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      return;
    }
    const entry = subscriptions.get(id);
    if (!entry) return;
    entry.refCount -= 1;
    if (entry.refCount <= 0) {
      entry.controller.abort();
      subscriptions.delete(id);
    }
  });
}

async function runStream(
  threadId: string,
  controller: AbortController,
  getMainWindow: () => BrowserWindow | null,
): Promise<void> {
  const url = `${BACKEND_BASE}/api/threads/${encodeURIComponent(threadId)}/stream`;
  let reason = 'closed';
  try {
    const res = await fetch(url, {
      method: 'GET',
      headers: { Accept: 'text/event-stream' },
      signal: controller.signal,
    });
    if (!res.ok || !res.body) {
      reason = `backend returned ${res.status}`;
      return;
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let carry = '';
    const buf: SseBuffer = { event: null, data: [] };

    // Each chunk is arbitrary bytes — accumulate into a string and
    // split on \n, holding any incomplete trailing line for the next
    // chunk so we never lose half a field.
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      carry += decoder.decode(value, { stream: true });
      let nl = carry.indexOf('\n');
      while (nl !== -1) {
        const line = carry.slice(0, nl).replace(/\r$/, '');
        carry = carry.slice(nl + 1);
        nl = carry.indexOf('\n');
        if (line.length === 0) {
          dispatch(buf, threadId, getMainWindow);
          continue;
        }
        if (line.startsWith(':')) {
          // SSE comment / keepalive — ignore.
          continue;
        }
        const colon = line.indexOf(':');
        const field = colon === -1 ? line : line.slice(0, colon);
        const rawValue = colon === -1 ? '' : line.slice(colon + 1).replace(/^ /, '');
        if (field === 'event') {
          buf.event = rawValue;
        }
        else if (field === 'data') {
          buf.data.push(rawValue);
        }
        // Other fields (id, retry) are ignored — we don't use them.
      }
    }
    // Flush whatever's left if the server cut us off mid-stream.
    if (carry.length > 0) {
      dispatch(buf, threadId, getMainWindow);
    }
  }
  catch (err) {
    if ((err as { name?: string }).name === 'AbortError') {
      reason = 'unsubscribed';
    }
    else {
      reason = `error: ${(err as Error).message}`;
    }
  }
  finally {
    const win = getMainWindow();
    if (win && !win.isDestroyed()) {
      win.webContents.send('threads:stream:close', { threadId, reason });
    }
    subscriptions.delete(threadId);
  }
}

function dispatch(
  buf: SseBuffer,
  threadId: string,
  getMainWindow: () => BrowserWindow | null,
): void {
  if (buf.data.length === 0 && buf.event === null) return;
  const name = buf.event ?? 'message';
  const dataText = buf.data.join('\n');
  let data: Record<string, unknown> = {};
  if (dataText.length > 0) {
    try {
      data = JSON.parse(dataText) as Record<string, unknown>;
    }
    catch {
      // Malformed payload — drop the event rather than crash; surface
      // it as an empty object so the renderer can still log the name.
      data = { _raw: dataText };
    }
  }
  buf.event = null;
  buf.data.length = 0;
  const win = getMainWindow();
  if (win && !win.isDestroyed()) {
    const ev: ThreadStreamEvent = { name, data };
    win.webContents.send('threads:stream:event', { threadId, event: ev });
  }
}

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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LAB_LINK_CHANGED, openLab, readLabLink, saveLabLink, takeLabTask } from './labLink';
import type { LabHost } from './contracts';

const settingsRoute = () => '#/settings/integrations/lab';
function launchLab() {
  const current = window as unknown as { bridge: Omit<LabHost, 'openSettings'>; location: { hash: string } };
  return openLab({ ...current.bridge, openSettings: () => { current.location.hash = settingsRoute(); } });
}

afterEach(() => { vi.unstubAllGlobals(); });

function storage() {
  const values = new Map<string, string>();
  const dispatchEvent = vi.fn();
  vi.stubGlobal('window', {
    localStorage: { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => values.set(key, value) },
    dispatchEvent,
  });
  return { values, dispatchEvent };
}

describe('lab link', () => {
  it('starts off and ignores malformed saved settings', () => {
    const { values } = storage();
    expect(readLabLink()).toEqual({ enabled: false });
    for (const stored of ['{', 'null', '[]', '{}', '{"enabled":"yes"}']) {
      values.set('bytequay.lab-link', stored);
      expect(readLabLink()).toEqual({ enabled: false });
    }
  });

  it('keeps only sidebar visibility on this Mac and announces changes', () => {
    const { values, dispatchEvent } = storage();
    saveLabLink({ enabled: true });
    expect(JSON.parse(values.get('bytequay.lab-link') ?? '')).toEqual({ enabled: true });
    expect(readLabLink()).toEqual({ enabled: true });
    expect(dispatchEvent.mock.calls.map(call => (call[0] as Event).type)).toEqual([LAB_LINK_CHANGED]);
    saveLabLink({ enabled: false });
    expect(readLabLink()).toEqual({ enabled: false });
  });

  it('stays off without local storage and does not fail the page that saves', () => {
    const unavailable = () => { throw new Error('Storage unavailable'); };
    vi.stubGlobal('window', { localStorage: { getItem: unavailable, setItem: unavailable }, dispatchEvent: vi.fn() });
    expect(readLabLink()).toEqual({ enabled: false });
    expect(() => saveLabLink({ enabled: true })).not.toThrow();
  });
});

describe('opening the Lab', () => {
  it('waits for startup and refresh before opening one browser tab, despite concurrent clicks', async () => {
    let ready: (url: string) => void = () => {};
    const start = vi.fn(() => new Promise<string>(resolve => { ready = resolve; }));
    const browse = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('window', { bridge: { openLab: start, openInAppBrowser: browse }, location: { hash: '#/recent' } });
    const first = launchLab();
    await launchLab();
    expect(start).toHaveBeenCalledTimes(1);
    expect(browse).not.toHaveBeenCalled();
    ready('http://127.0.0.1:42311/');
    await first;
    expect(browse).toHaveBeenCalledTimes(1);
    expect(browse).toHaveBeenCalledWith('http://127.0.0.1:42311/');
  });

  it('shows startup errors in Lab Settings and lets the user retry', async () => {
    const start = vi.fn().mockRejectedValueOnce(new Error('Dashboard could not start')).mockResolvedValue('http://127.0.0.1:42311/');
    const browse = vi.fn().mockResolvedValue(undefined);
    const location = { hash: '#/recent' };
    vi.stubGlobal('window', { bridge: { openLab: start, openInAppBrowser: browse }, location });
    await launchLab();
    expect(browse).not.toHaveBeenCalled();
    expect(location.hash).toBe(settingsRoute());
    await launchLab();
    expect(start).toHaveBeenCalledTimes(2);
    expect(browse).toHaveBeenCalledTimes(1);
    expect(browse).toHaveBeenCalledWith('http://127.0.0.1:42311/');
  });

  it('also handles a browser failure without leaving launch disabled', async () => {
    const start = vi.fn().mockResolvedValue('http://127.0.0.1:42311/');
    const browse = vi.fn().mockRejectedValueOnce(new Error('Browser could not open')).mockResolvedValue(undefined);
    const location = { hash: '#/recent' };
    vi.stubGlobal('window', { bridge: { openLab: start, openInAppBrowser: browse }, location });
    await launchLab();
    expect(location.hash).toBe(settingsRoute());
    await launchLab();
    expect(start).toHaveBeenCalledTimes(2);
    expect(browse).toHaveBeenCalledTimes(2);
  });
});

describe('a task from the dashboard', () => {
  const task = { repository: 'bytequay/bytequay', text: '  Shorten it\n\nLab suggestion: Shorten it\n' };
  const host = (startTask: LabHost['startTask']) => ({ openLab: vi.fn(), openInAppBrowser: vi.fn(), openSettings: vi.fn(), startTask });

  it('hands the text unchanged to the repository that watches it and stays there', async () => {
    const opened = host(vi.fn().mockResolvedValue(true));
    await takeLabTask(opened, task);
    expect(opened.startTask).toHaveBeenCalledWith(task);
    expect(opened.openSettings).not.toHaveBeenCalled();
  });

  it('opens Lab Settings when no repository watches it, or the hand-over fails', async () => {
    for (const startTask of [vi.fn().mockResolvedValue(false), vi.fn().mockRejectedValue(new Error('Workspaces could not load'))]) {
      const missing = host(startTask);
      await takeLabTask(missing, task);
      expect(missing.openSettings).toHaveBeenCalledOnce();
    }
  });
});

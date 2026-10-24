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
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { restoreSourceDrafts, sourceDrafts, trackSourceDrafts } from './drafts';
import type { IdeRuntime } from './runtime';

const store = new Map<string, string>();
const key = 'bytequay.ide.sourceDrafts';
beforeEach(() => { store.clear(); vi.stubGlobal('localStorage', { getItem: (key: string) => store.get(key) ?? null, setItem: (key: string, value: string) => store.set(key, value) }); });
function fixture(text: string) {
  const document = { uri: { scheme: 'vscode-remote', path: '/task/main.go' }, isDirty: false, nativeDirty: false, getText: () => text, positionAt: (offset: number) => offset };
  const applyEdit = vi.fn(async () => true);
  const change = { listener: (_event: { document: typeof document }) => {} };
  const save = { listener: (_event: { resource: typeof document.uri; text: string }) => {} };
  const runtime = { saves: { isDirty: () => document.nativeDirty, onDidSave: (listener: typeof save.listener) => { save.listener = listener; return { dispose(): void { return; } }; } }, workspace: { path: '/task', with: (value: unknown) => value }, api: {
    WorkspaceEdit: class { replace(): void { return; } }, Range: class {}, window: { showTextDocument: vi.fn() }, workspace: {
      textDocuments: [document], openTextDocument: async () => document, applyEdit,
      onDidOpenTextDocument: () => ({ dispose(): void { return; } }), onDidSaveTextDocument: () => ({ dispose(): void { return; } }),
      onDidChangeTextDocument: (listener: typeof change.listener) => { change.listener = listener; return { dispose(): void { return; } }; },
    },
  } } as unknown as IdeRuntime;
  return { runtime, document, applyEdit, change, save };
}

describe('IDE draft recovery', () => {
  it('restores an unchanged base without saving it to disk', async () => {
    store.set(key, JSON.stringify([{ path: 'main.go', base: 'base', text: 'draft' }]));
    const { runtime, applyEdit } = fixture('base');
    const conflict = vi.fn();
    await restoreSourceDrafts(runtime, conflict);
    expect(applyEdit).toHaveBeenCalledOnce();
    expect(conflict).not.toHaveBeenCalled();
    expect(sourceDrafts(localStorage)[0].text).toBe('draft');
  });
  it('preserves an external-edit conflict separately so subsequent edits cannot replace it', async () => {
    const draft = { path: 'main.go', base: 'base', text: 'my draft' };
    store.set(key, JSON.stringify([draft]));
    const { runtime, applyEdit } = fixture('agent edit');
    const conflict = vi.fn();
    await restoreSourceDrafts(runtime, conflict);
    expect(applyEdit).not.toHaveBeenCalled();
    expect(conflict).toHaveBeenCalledWith(draft);
    expect(sourceDrafts(localStorage)).toEqual([]);
    await restoreSourceDrafts(runtime, conflict);
    expect(conflict).toHaveBeenCalledTimes(2);
  });
  it('keeps the original base through repeated keystrokes', () => {
    const { runtime, document, change } = fixture('base');
    const tracking = trackSourceDrafts(runtime, vi.fn());
    document.isDirty = true; document.nativeDirty = true; document.getText = () => 'first edit'; change.listener({ document });
    document.getText = () => 'second edit'; change.listener({ document });
    expect(sourceDrafts(localStorage)).toEqual([{ path: 'main.go', base: 'base', text: 'second edit' }]);
    tracking.dispose();
  });
  it('keeps newer edits when a previous save completes, including the extension API clean notification', async () => {
    const { runtime, document, change, save } = fixture('base');
    const tracking = trackSourceDrafts(runtime, vi.fn());
    document.isDirty = true; document.nativeDirty = true;
    document.getText = () => 'saved snapshot'; change.listener({ document });
    document.getText = () => 'typed during save'; change.listener({ document });
    save.listener({ resource: document.uri, text: 'saved snapshot' });
    // The pinned extension API reports clean on save even when the native model has newer edits.
    document.isDirty = false; change.listener({ document });
    expect(sourceDrafts(localStorage)).toEqual([{ path: 'main.go', base: 'saved snapshot', text: 'typed during save' }]);
    tracking.dispose();
    const restored = fixture('saved snapshot');
    await restoreSourceDrafts(restored.runtime, vi.fn());
    expect(restored.applyEdit).toHaveBeenCalledOnce();
    expect(sourceDrafts(localStorage)[0].text).toBe('typed during save');
  });
  it('removes a draft when the saved snapshot is still the current text', () => {
    const { runtime, document, change, save } = fixture('base');
    const tracking = trackSourceDrafts(runtime, vi.fn());
    document.isDirty = true; document.nativeDirty = true;
    document.getText = () => 'saved snapshot'; change.listener({ document });
    save.listener({ resource: document.uri, text: 'saved snapshot' });
    expect(sourceDrafts(localStorage)).toEqual([]);
    tracking.dispose();
  });
  it('retains malformed storage instead of treating it as an empty draft list', () => {
    store.set(key, '[{"path":"../other","base":"x","text":"y"}]');
    expect(() => sourceDrafts(localStorage)).toThrow('retained for recovery');
    expect(store.get(key)).toContain('../other');
  });
  it('offers the retained draft when the editor refuses restoration', async () => {
    const draft = { path: 'main.go', base: 'base', text: 'draft' };
    store.set(key, JSON.stringify([draft]));
    const { runtime, applyEdit } = fixture('base');
    applyEdit.mockResolvedValue(false);
    const conflict = vi.fn();
    await restoreSourceDrafts(runtime, conflict);
    expect(conflict).toHaveBeenCalledWith(draft);
    expect(sourceDrafts(localStorage)).toEqual([]);
    expect(sourceDrafts(localStorage, 'bytequay.ide.recoveredDrafts')).toEqual([draft]);
  });
  it('retains a restored draft still dirty in the native model when the extension API reports clean', async () => {
    const draft = { path: 'main.go', base: 'base', text: 'draft' };
    store.set(key, JSON.stringify([draft]));
    const { runtime, document, applyEdit } = fixture('draft');
    document.nativeDirty = true;
    await restoreSourceDrafts(runtime, vi.fn());
    expect(applyEdit).not.toHaveBeenCalled();
    expect(sourceDrafts(localStorage)).toEqual([draft]);
  });
  it('clears an already saved draft without changing the file', async () => {
    store.set(key, JSON.stringify([{ path: 'main.go', base: 'base', text: 'saved' }]));
    const { runtime, applyEdit } = fixture('saved');
    await restoreSourceDrafts(runtime, vi.fn());
    expect(applyEdit).not.toHaveBeenCalled();
    expect(sourceDrafts(localStorage)).toEqual([]);
  });

});

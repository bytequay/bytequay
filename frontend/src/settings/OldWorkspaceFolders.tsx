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

import { useEffect, useState } from 'react';
import type { OldWorkspaceFolderDto } from '../types';
import { SettingsCard, SettingsRow } from './primitives';

const UNITS = ['KB', 'MB', 'GB', 'TB'];

/** A size in Finder's decimal units. */
export function folderSize(bytes: number): string {
  let value = bytes;
  let unit = -1;
  while (value >= 1000 && unit < UNITS.length - 1) {
    value /= 1000;
    unit += 1;
  }
  return unit < 0 ? `${bytes} bytes` : `${value < 10 ? value.toFixed(1) : Math.round(value)} ${UNITS[unit]}`;
}

export function folderDetails(folder: OldWorkspaceFolderDto): string {
  const checkouts = folder.checkouts === 1 ? '1 checkout' : `${folder.checkouts} checkouts`;
  const changed = new Date(folder.modifiedAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
  return `${folderSize(folder.sizeBytes)} · ${checkouts} · last changed ${changed}`;
}

/**
 * Workspace folders an older database left behind. Nothing removes them on its own, because an older release that still opens
 * that database needs them; the section shows only when there are some.
 */
export function OldWorkspaceFolders() {
  const [folders, setFolders] = useState<OldWorkspaceFolderDto[] | null>(null);
  const [moving, setMoving] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    void window.bridge.listOldWorkspaceFolders()
      .then(value => { if (current) setFolders(value); })
      .catch((failure: unknown) => { if (current) setError(failure instanceof Error ? failure.message : String(failure)); });
    return () => { current = false; };
  }, []);

  const move = (folder: OldWorkspaceFolderDto) => {
    setMoving(folder.id);
    setError(null);
    void window.bridge.trashOldWorkspaceFolder(folder.id)
      .then(() => setFolders(list => list?.filter(entry => entry.id !== folder.id) ?? null))
      .catch((failure: unknown) => setError(failure instanceof Error ? failure.message : String(failure)))
      .finally(() => setMoving(null));
  };

  if (error === null && (folders === null || folders.length === 0)) return null;
  return (
    <>
      <div className="setw-section"><span className="setw-section-title">Old workspace folders</span></div>
      {error !== null && <div className="setw-error" role="alert">{error}</div>}
      {folders !== null && folders.length > 0 && <>
        <SettingsCard>
          {folders.map(folder => (
            <SettingsRow key={folder.id} title={folder.repository ?? folder.id} subtitle={folderDetails(folder)}>
              <button type="button" className="setw-btn" disabled={moving !== null} onClick={() => move(folder)}>
                {moving === folder.id ? 'Moving…' : 'Move to Trash'}
              </button>
            </SettingsRow>
          ))}
        </SettingsCard>
        <div className="setw-footnote">
          Left behind when ByteQuay started a new database. An older ByteQuay that still uses that database needs them. They stay in the Trash until you empty it.
        </div>
      </>}
    </>
  );
}

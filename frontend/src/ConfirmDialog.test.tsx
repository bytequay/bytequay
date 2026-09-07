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

import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ConfirmDialog } from './ConfirmDialog';

describe('ConfirmDialog', () => {
  it('labels the modal with its title and explanation, and names the action', () => {
    const html = renderToStaticMarkup(
      <ConfirmDialog title="Stop this task’s agent?" confirmLabel="Stop agent" onConfirm={() => {}} onCancel={() => {}}>
        <p>Your messages and file changes will stay in this task.</p>
      </ConfirmDialog>,
    );

    const titleId = html.match(/aria-labelledby="([^"]+)"/)?.[1];
    const bodyId = html.match(/aria-describedby="([^"]+)"/)?.[1];
    expect(titleId).toBeTruthy();
    expect(bodyId).toBeTruthy();
    expect(html).toContain('aria-modal="true"');
    expect(html).toContain(`<h2 id="${titleId}"`);
    expect(html).toContain(`<div id="${bodyId}"`);
    expect(html).toContain('>Cancel</button>');
    expect(html).toContain('>Stop agent</button>');
    expect(html).not.toContain('disabled=""');
  });

  it('disables both actions while the confirmed operation is in progress', () => {
    const html = renderToStaticMarkup(
      <ConfirmDialog title="Mark this task as failed?" confirmLabel="Marking failed…" busy destructive onConfirm={() => {}} onCancel={() => {}}>
        <p>Your messages and file changes will stay in this task.</p>
      </ConfirmDialog>,
    );

    expect(html).toContain('aria-busy="true"');
    expect(html.match(/disabled=""/g)).toHaveLength(2);
    expect(html).toContain('is-destructive');
    expect(html).toContain('Marking failed…</button>');
  });

  it('keeps cancellation available when confirmation requirements are incomplete', () => {
    const html = renderToStaticMarkup(
      <ConfirmDialog title="Delete local data?" confirmLabel="Delete" confirmDisabled onConfirm={() => {}} onCancel={() => {}}>
        <p>Enter the repository name to confirm.</p>
      </ConfirmDialog>,
    );

    expect(html.match(/disabled=""/g)).toHaveLength(1);
    expect(html).toContain('class="bq-button">Cancel</button>');
  });
});

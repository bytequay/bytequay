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
import { describe, expect, it, vi } from 'vitest';
import { DeleteWorkspaceConfirmation } from './DeleteWorkspaceDialog';

function confirmation(finalConfirmation = false, name = '', busy = false) {
  const onContinue = vi.fn();
  const onDelete = vi.fn();
  const onCancel = vi.fn();
  const view = DeleteWorkspaceConfirmation({
    repositoryName: 'octocat/trino',
    finalConfirmation,
    confirmation: name,
    busy,
    error: null,
    onContinue,
    onConfirmationChange: vi.fn(),
    onDelete,
    onCancel,
  });
  return { view, html: renderToStaticMarkup(view), onContinue, onDelete, onCancel };
}

describe('workspace deletion confirmations', () => {
  it('discloses local data loss and preserves the GitHub repository before moving to a separate final dialog', () => {
    const { view, html, onContinue, onDelete } = confirmation();

    expect(view.key).toBe('disclosure');
    expect(html).toContain('checkout, worktrees, task history, and settings');
    expect(html).toContain('Your GitHub repository and fork will be kept.');
    expect(html).toContain('choose Unwatch instead');
    expect(html).toContain('>Continue</button>');
    expect(onDelete).not.toHaveBeenCalled();

    view.props.onConfirm();

    expect(onContinue).toHaveBeenCalledOnce();
    expect(onDelete).not.toHaveBeenCalled();
  });

  it.each(['', 'trino', 'trinodb/trino', 'octocat/trino '])('rejects the final confirmation text %j', name => {
    const { view, html, onDelete } = confirmation(true, name);

    expect(view.key).toBe('final');
    expect(view.props.confirmDisabled).toBe(true);
    expect(html).toContain('Unsaved and unpushed work will be lost.');
    expect(html).toContain('Type <strong>octocat/trino</strong> to confirm.');
    view.props.onConfirm();

    expect(onDelete).not.toHaveBeenCalled();
  });

  it('deletes only after the second confirmation with the exact repository name', () => {
    const { view, html, onDelete } = confirmation(true, 'octocat/trino');

    expect(view.props.confirmDisabled).toBe(false);
    expect(html).toContain('is-destructive');
    expect(html).toContain('>Delete local data</button>');
    expect(onDelete).not.toHaveBeenCalled();
    view.props.onConfirm();

    expect(onDelete).toHaveBeenCalledOnce();
  });

  it.each([false, true])('cancels without deleting from either confirmation (final: %s)', finalConfirmation => {
    const { view, onDelete, onCancel } = confirmation(finalConfirmation, 'octocat/trino');

    view.props.onCancel();

    expect(onCancel).toHaveBeenCalledOnce();
    expect(onDelete).not.toHaveBeenCalled();
  });

  it('prevents another deletion while the confirmed operation is in progress', () => {
    const { view, html, onDelete } = confirmation(true, 'octocat/trino', true);

    expect(html.match(/disabled=""/g)).toHaveLength(3);
    view.props.onConfirm();
    expect(onDelete).not.toHaveBeenCalled();
  });
});

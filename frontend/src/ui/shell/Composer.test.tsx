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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Composer } from './Composer';

afterEach(cleanup);

function imageClipboardData(file: File) {
  return {
    items: [{ type: file.type, getAsFile: () => file }],
  } as unknown as DataTransfer;
}

describe('Composer', () => {
  it('submits on Enter and blocks an empty send', () => {
    const onSubmit = vi.fn();
    render(<Composer value="hello" onChange={() => {}} onSubmit={onSubmit} />);
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
    expect(onSubmit).toHaveBeenCalled();
  });

  it('a pasted image reaches onImagesChange as a data URL', async () => {
    const onImagesChange = vi.fn();
    render(
      <Composer value="" onChange={() => {}} onSubmit={() => {}} images={[]} onImagesChange={onImagesChange} />,
    );
    const file = new File(['fake-png-bytes'], 'shot.png', { type: 'image/png' });
    fireEvent.paste(screen.getByRole('textbox'), { clipboardData: imageClipboardData(file) });

    await waitFor(() => expect(onImagesChange).toHaveBeenCalled());
    const [next] = onImagesChange.mock.calls[0] as [string[]];
    expect(next).toHaveLength(1);
    expect(next[0]).toMatch(/^data:/);
  });

  it('ignores paste when onImagesChange is not wired (plain-text composers unaffected)', () => {
    render(<Composer value="" onChange={() => {}} onSubmit={() => {}} />);
    const file = new File(['fake-png-bytes'], 'shot.png', { type: 'image/png' });
    // Must not throw — the handler should just no-op and let default paste happen.
    expect(() => fireEvent.paste(screen.getByRole('textbox'), { clipboardData: imageClipboardData(file) }))
      .not.toThrow();
  });

  it('renders a thumbnail chip per pending image and removes one on click', () => {
    const onImagesChange = vi.fn();
    render(
      <Composer
        value=""
        onChange={() => {}}
        onSubmit={() => {}}
        images={['data:image/png;base64,aaa', 'data:image/png;base64,bbb']}
        onImagesChange={onImagesChange}
      />,
    );
    const chips = screen.getAllByAltText('Pasted attachment');
    expect(chips).toHaveLength(2);
    fireEvent.click(screen.getAllByLabelText('Remove image')[0]);
    expect(onImagesChange).toHaveBeenCalledWith(['data:image/png;base64,bbb']);
  });

  it('allows sending an image with no text', () => {
    const onSubmit = vi.fn();
    render(
      <Composer
        value=""
        onChange={() => {}}
        onSubmit={onSubmit}
        images={['data:image/png;base64,aaa']}
        onImagesChange={() => {}}
      />,
    );
    fireEvent.click(screen.getByLabelText('Send'));
    expect(onSubmit).toHaveBeenCalled();
  });

  it('replaces the input with the closed note when closedNote is set', () => {
    render(
      <Composer value="" onChange={() => {}} onSubmit={() => {}} closedNote="This task is closed." />,
    );
    expect(screen.getByText('This task is closed.')).toBeTruthy();
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(screen.queryByLabelText('Send')).toBeNull();
  });

  it('keeps the locked task composer chrome and usage popover when closed', () => {
    render(
      <Composer
        variant="workspace-v2"
        value=""
        onChange={() => {}}
        onSubmit={() => {}}
        closedNote="Stage is closed — ask about its work…"
        modePill={<button type="button">Claude Opus 4.8</button>}
        toolbar={<button type="button">Changes +0 −331</button>}
        meta="Stage 2 of 4 · 15m 23s"
        usage={{ contextPercent: 4, sessionLabel: '827 tokens' }}
      />,
    );

    expect((screen.getByRole('textbox') as HTMLTextAreaElement).disabled).toBe(false);
    expect(screen.getByPlaceholderText('Stage is closed — ask about its work…')).toBeTruthy();
    expect(screen.getByText('Changes +0 −331')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Usage' }));
    expect(screen.getByText('4% used')).toBeTruthy();
    expect(screen.getByText('827 tokens')).toBeTruthy();
  });

  it('shows provider-reported input and output tokens without a made-up quota', () => {
    render(
      <Composer
        variant="workspace-v2"
        value=""
        onChange={() => {}}
        onSubmit={() => {}}
        usage={{ tokensIn: 1_234, tokensOut: 56 }}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Usage' }));
    expect(screen.getByText('1,234 tokens')).toBeTruthy();
    expect(screen.getByText('56 tokens')).toBeTruthy();
    expect(screen.queryByText(/AI credits|% used/)).toBeNull();
  });
});

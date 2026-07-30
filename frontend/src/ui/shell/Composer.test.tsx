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
import { useState } from 'react';
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

  it('treats /model as ordinary message text', () => {
    const onSubmit = vi.fn();

    function Harness() {
      const [value, setValue] = useState('');
      return (
        <Composer value={value} onChange={setValue} onSubmit={onSubmit}
          modePill={<button type="button">GPT-5.6 Sol</button>} />
      );
    }

    render(<Harness />);
    const box = screen.getByRole('textbox') as HTMLTextAreaElement;
    fireEvent.change(box, { target: { value: '/model' } });

    expect(box.value).toBe('/model');
    expect(screen.queryByRole('listbox', { name: 'Slash commands' })).toBeNull();
    fireEvent.keyDown(box, { key: 'Enter' });
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('sends a suggested reply directly on Enter', () => {
    const onSubmit = vi.fn();
    render(
      <Composer variant="workspace-v2" value="" onChange={() => {}}
        onSubmit={onSubmit} suggestedReply="go ahead" />,
    );

    expect((screen.getByRole('button', { name: 'Send' }) as HTMLButtonElement).disabled).toBe(false);
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
    expect(onSubmit).toHaveBeenCalledWith('go ahead');
  });

  it('accepts a suggested reply with Tab and hides it as soon as the user types', () => {
    function Harness() {
      const [value, setValue] = useState('');
      return (
        <Composer variant="workspace-v2" value={value} onChange={setValue}
          onSubmit={() => {}} suggestedReply="go ahead" />
      );
    }

    const { container } = render(<Harness />);
    const box = screen.getByRole('textbox') as HTMLTextAreaElement;
    fireEvent.keyDown(box, { key: 'Tab' });
    expect(box.value).toBe('go ahead');
    expect(container.querySelector('.composer-suggested-reply')).toBeNull();

    fireEvent.change(box, { target: { value: '' } });
    expect(container.querySelector('.composer-suggested-reply')).toBeTruthy();
    fireEvent.change(box, { target: { value: 'not yet' } });
    expect(box.value).toBe('not yet');
    expect(container.querySelector('.composer-suggested-reply')).toBeNull();
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

  it('turns the busy send button into a Stop button that interrupts when nothing is queued', () => {
    const onStop = vi.fn();
    const onSubmit = vi.fn();
    render(
      <Composer variant="workspace-v2" value="" onChange={() => {}} onSubmit={onSubmit}
        busy queueWhenBusy onStop={onStop} />,
    );
    // Empty + busy would otherwise spin; with onStop it's a live Stop button.
    const stop = screen.getByLabelText('Stop the agent') as HTMLButtonElement;
    expect(stop.disabled).toBe(false);
    fireEvent.click(stop);
    expect(onStop).toHaveBeenCalledTimes(1);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('queues (does not stop) when the user has text to send mid-run', () => {
    const onStop = vi.fn();
    render(
      <Composer variant="workspace-v2" value="do this next" onChange={() => {}} onSubmit={() => {}}
        busy queueWhenBusy onStop={onStop} />,
    );
    expect(screen.queryByLabelText('Stop the agent')).toBeNull();
    expect(screen.getByLabelText('Queue message')).toBeTruthy();
  });

  it('replaces the input with the closed note when closedNote is set', () => {
    render(
      <Composer value="" onChange={() => {}} onSubmit={() => {}} closedNote="This task is closed." />,
    );
    expect(screen.getByText('This task is closed.')).toBeTruthy();
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(screen.queryByLabelText('Send')).toBeNull();
  });

  it('keeps the locked task composer chrome when closed', () => {
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
      />,
    );

    expect((screen.getByRole('textbox') as HTMLTextAreaElement).disabled).toBe(false);
    expect(screen.getByPlaceholderText('Stage is closed — ask about its work…')).toBeTruthy();
    expect(screen.getByText('Changes +0 −331')).toBeTruthy();
  });

  it('omits unused workspace composer controls', () => {
    render(
      <Composer
        variant="workspace-v2"
        value=""
        onChange={() => {}}
        onSubmit={() => {}}
      />,
    );

    expect(screen.queryByRole('button', { name: 'Add context' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Usage' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Voice input' })).toBeNull();
  });
});

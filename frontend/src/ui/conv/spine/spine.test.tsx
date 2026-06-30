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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  ActivityStrip, Headline, Round, Spine, SpineNode, UserTurn, WorkFold,
} from './index';

afterEach(cleanup);

describe('SpineNode', () => {
  it('renders a coloured boundary node with name + state + meta', () => {
    const { container } = render(
      <Spine><SpineNode mark="◆" color="blue" name="Development" state="done" meta="7m" /></Spine>,
    );
    expect(container.querySelector('.spine')).toBeTruthy();
    const node = container.querySelector('.sp-node');
    expect(node?.classList.contains('sp-node--blue')).toBe(true);
    expect(container.querySelector('.sp-node__mark')?.textContent).toBe('◆');
    expect(container.querySelector('.sp-node__nm')?.textContent).toBe('Development');
    expect(container.querySelector('.sp-node__st')?.textContent).toBe('done');
    expect(container.querySelector('.sp-node__meta')?.textContent).toBe('7m');
  });

  it('becomes a toggle button when onToggle is given', () => {
    let toggled = 0;
    render(<SpineNode mark="◆" name="Planning" collapsed onToggle={() => { toggled += 1; }} />);
    const btn = screen.getByRole('button');
    expect(btn.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(btn);
    expect(toggled).toBe(1);
  });
});

describe('UserTurn', () => {
  it('renders a teal block with the message', () => {
    const { container } = render(<UserTurn text="Run the gate" timestamp="3m ago" />);
    expect(container.querySelector('.sp-uturn__mark')?.textContent).toBe('Y');
    expect(container.querySelector('.sp-ublock__tx')?.textContent).toContain('Run the gate');
  });
});

describe('Headline', () => {
  it('renders a normal headline with the who row', () => {
    const { container } = render(<Headline who="Brain" body="All **7** sites routed." />);
    expect(container.querySelector('.sp-headline__who')?.textContent).toContain('Brain');
    expect(container.querySelector('.sp-headline__tx strong')?.textContent).toBe('7');
  });

  it('renders as a tucked reply when reply is set', () => {
    const { container } = render(<Headline body="Because line 177 calls it." reply />);
    expect(container.querySelector('.sp-reply')).toBeTruthy();
    expect(container.querySelector('.sp-headline__who')).toBeNull();
  });
});

describe('WorkFold', () => {
  it('folds by default and expands on click; failure badges while folded', () => {
    const { container } = render(
      <WorkFold meta="4 steps · 18 tool calls" failed={1}>
        <div className="inner-detail">detail</div>
      </WorkFold>,
    );
    // Failed badge shows even while collapsed.
    expect(container.querySelector('.sp-badge--fail')?.textContent).toContain('1 failed');
    expect(container.querySelector('.inner-detail')).toBeNull();
    fireEvent.click(screen.getByRole('button'));
    expect(container.querySelector('.inner-detail')).toBeTruthy();
  });

  it('forceOpen (density Full) shows the inner content and disables the toggle', () => {
    const { container } = render(
      <WorkFold forceOpen><div className="inner-detail">d</div></WorkFold>,
    );
    expect(container.querySelector('.inner-detail')).toBeTruthy();
    expect((screen.getByRole('button') as HTMLButtonElement).disabled).toBe(true);
  });
});

describe('ActivityStrip', () => {
  const groups = [
    { kind: 'Edit', rows: [{ label: 'a.ts' }, { label: 'b.ts' }] },
    { kind: 'Bash', rows: [{ label: 'mvn compile', failed: true, error: 'unused import' }, { label: 'git diff' }] },
  ];

  it('shows total + breakdown + failure badge, folded', () => {
    const { container } = render(<ActivityStrip groups={groups} filesChanged={2} />);
    expect(container.querySelector('.sp-act__cnt')?.textContent).toBe('4 tool calls');
    expect(container.querySelector('.sp-act__brk')?.textContent).toContain('2 Edit · 2 Bash');
    expect(container.querySelector('.sp-badge--edit')?.textContent).toContain('2 files changed');
    expect(container.querySelector('.sp-badge--fail')?.textContent).toContain('1 failed');
    expect(container.querySelector('.sp-act__log')).toBeNull();
  });

  it('pins the failed row to the top of its group when expanded', () => {
    const { container } = render(<ActivityStrip groups={groups} />);
    fireEvent.click(screen.getByRole('button'));
    const bashGroup = container.querySelectorAll('.sp-act__grp')[1];
    const firstRow = bashGroup.querySelector('.sp-trow');
    expect(firstRow?.classList.contains('sp-trow--fail')).toBe(true);
    expect(firstRow?.querySelector('.sp-trow__err')?.textContent).toBe('unused import');
  });
});

describe('Round', () => {
  it('renders an autonomous round tag', () => {
    const { container } = render(<Round tag="R1"><Headline body="done" /></Round>);
    expect(container.querySelector('.sp-round__tag')?.textContent).toBe('R1');
  });
});

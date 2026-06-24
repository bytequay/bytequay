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
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  Callout, Card, Conv, EventRow, InlineAction, StageFold, Thought, ToolBlock, UserMsg, Working,
} from './index';

afterEach(cleanup);

describe('EventRow', () => {
  it('renders the kind icon + name and a markdown body', () => {
    const { container } = render(
      <EventRow kind="agent" who="claude-code" taskRef="Task #142" timestamp="3d ago" markdown="Ran **build**." />,
    );
    expect(container.querySelector('.ic.agent')?.textContent).toBe('C');
    expect(container.querySelector('.who')?.textContent).toBe('claude-code');
    // MarkdownProse turns **build** into a <strong>.
    expect(container.querySelector('.tx strong')?.textContent).toBe('build');
  });

  it('hides the body when collapsed and toggles via the chev', () => {
    const onToggle = vi.fn();
    const { container, rerender } = render(
      <EventRow kind="brain" who="Brain" collapsible collapsed markdown="hidden body" />,
    );
    expect(container.querySelector('.tx')).toBeNull();
    expect(container.querySelector('.who.brain')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Expand' }));
    // onToggle not wired in this render; rerender expanded shows body.
    rerender(<EventRow kind="brain" who="Brain" collapsible onToggle={onToggle} markdown="shown body" />);
    expect(container.querySelector('.tx')).toBeTruthy();
  });
});

describe('UserMsg', () => {
  it('renders as a teal user row with plain text (no markdown)', () => {
    const { container } = render(<UserMsg text="**not bold**" timestamp="now" />);
    expect(container.querySelector('.ic.user')?.textContent).toBe('Y');
    expect(container.querySelector('.who.user')?.textContent).toBe('You');
    expect(container.querySelector('.tx')?.textContent).toBe('**not bold**');
    expect(container.querySelector('.tx strong')).toBeNull();
  });
});

describe('ToolBlock', () => {
  it('discloses the code body on header click', () => {
    const { container } = render(
      <ToolBlock tag="Bash" desc="npm test" meta="0.6s"><span>output</span></ToolBlock>,
    );
    expect(container.querySelector('.body-code')).toBeNull();
    fireEvent.click(container.querySelector('.head') as HTMLElement);
    expect(container.querySelector('.body-code')?.textContent).toBe('output');
  });

  it('tints the plan-family tag and omits the disclosure when bodyless', () => {
    const { container } = render(<ToolBlock tag="record_plan" plan desc="recorded the plan" />);
    expect(container.querySelector('.tag.plan')).toBeTruthy();
    expect(container.querySelector('.disc')).toBeNull();
  });
});

describe('Working', () => {
  it('renders a pulsing dot and the label', () => {
    const { container } = render(<Working label="Brain is thinking…" />);
    expect(container.querySelector('.working__dot')).toBeTruthy();
    expect(screen.getByText('Brain is thinking…')).toBeTruthy();
    expect(container.querySelector('.working')?.getAttribute('role')).toBe('status');
  });
});

describe('Thought / Callout / InlineAction', () => {
  it('Thought shows the elapsed label', () => {
    render(<Thought seconds={7} />);
    expect(screen.getByText('Thought for 7s')).toBeTruthy();
  });

  it('Thought with a body is a collapsible disclosure (collapsed by default)', () => {
    render(<Thought seconds={7}><div>reasoning</div></Thought>);
    expect(screen.getByText('Thought for 7s')).toBeTruthy();
    expect(screen.queryByText('reasoning')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /Thought for 7s/ }));
    expect(screen.getByText('reasoning')).toBeTruthy();
  });

  it('Callout renders an italic passage', () => {
    const { container } = render(<Callout>verbatim note</Callout>);
    expect(container.querySelector('.callout')?.textContent).toBe('verbatim note');
  });

  it('InlineAction fires onClick', () => {
    const onClick = vi.fn();
    render(<InlineAction icon="⚖" onClick={onClick}>Get a panel review</InlineAction>);
    fireEvent.click(screen.getByRole('button', { name: /Get a panel review/ }));
    expect(onClick).toHaveBeenCalledOnce();
  });
});

describe('StageFold', () => {
  it('hides children until expanded and shows the count', () => {
    const { container } = render(
      <StageFold label="DevelopmentStage" count={7}><div>chatter</div></StageFold>,
    );
    expect(screen.getByText('7 steps')).toBeTruthy();
    expect(screen.queryByText('chatter')).toBeNull();
    fireEvent.click(container.querySelector('.stage-fold__bar') as HTMLElement);
    expect(screen.getByText('chatter')).toBeTruthy();
  });
});

describe('Card', () => {
  it('task variant: diamond + status pill + branch', () => {
    const { container } = render(
      <Card kind="task" title="Task 4 · cost meter" body="desc" branch="feat/cost" status="foreground" />,
    );
    expect(container.querySelector('.task-card')?.className).toBe('task-card');
    expect(container.querySelector('.diamond')).toBeTruthy();
    expect(container.querySelector('.branch-tag')?.textContent).toContain('feat/cost');
    const pill = container.querySelector('.status-pill.foreground');
    expect(pill?.textContent).toContain('FOREGROUND');
    expect(pill?.querySelector('.arrow')).toBeTruthy();
  });

  it('backlog variant: tags + Start-development CTA, no diamond/spine', () => {
    const onStart = vi.fn();
    const { container } = render(
      <Card kind="backlog" title="Parking idea" body="desc" tags={[{ label: 'ui', color: 'green' }]} onStartDevelopment={onStart} />,
    );
    expect(container.querySelector('.task-card')?.className).toBe('task-card backlog');
    expect(container.querySelector('.diamond')).toBeNull();
    expect(container.querySelector('.v3-tag--green')?.textContent).toBe('ui');
    fireEvent.click(screen.getByRole('button', { name: /Start development/ }));
    expect(onStart).toHaveBeenCalledOnce();
  });

  it('started backlog item fades and shows the linked badge instead of the CTA', () => {
    const onOpenLinked = vi.fn();
    const { container } = render(
      <Card kind="backlog" title="Shipped idea" started linkedTaskLabel="→ Task #12" onOpenLinked={onOpenLinked} />,
    );
    expect(container.querySelector('.task-card')?.className).toContain('started');
    expect(container.querySelector('.start-dev-btn.started')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Start development/ })).toBeNull();
    fireEvent.click(screen.getByText('→ Task #12'));
    expect(onOpenLinked).toHaveBeenCalledOnce();
  });

  it('fires onClick and is keyboard-activable', () => {
    const onClick = vi.fn();
    const { container } = render(<Card kind="task" title="t" onClick={onClick} />);
    const card = container.querySelector('.task-card') as HTMLElement;
    fireEvent.click(card);
    fireEvent.keyDown(card, { key: 'Enter' });
    expect(onClick).toHaveBeenCalledTimes(2);
  });
});

describe('Conv', () => {
  it('wraps children in the scroll container', () => {
    const { container } = render(<Conv><div>row</div></Conv>);
    expect(container.querySelector('.conv')?.textContent).toBe('row');
  });
});

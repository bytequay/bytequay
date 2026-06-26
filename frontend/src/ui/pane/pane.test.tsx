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
  BacklogTabContent, DetailsTabContent, InlineChips, NotificationsTabContent, PRTabContent,
  PlanTabContent, RightPane, TasksTabContent,
} from './index';

afterEach(cleanup);

describe('RightPane', () => {
  it('renders tabs, marks the active one, and fires onSelect', () => {
    const onSelect = vi.fn();
    const { container } = render(
      <RightPane>
        <RightPane.Tabs
          tabs={[
            { key: 'tasks', label: 'Tasks', count: 2, countColor: 'acc' },
            { key: 'backlog', label: 'Backlog', count: 5, countColor: 'muted' },
            { key: 'notifications', label: 'Notifications', count: 1 },
          ]}
          active="tasks"
          onSelect={onSelect}
        />
        <RightPane.Content>body</RightPane.Content>
      </RightPane>,
    );
    expect(container.querySelector('.pane-tab.active')?.textContent).toContain('Tasks');
    expect(container.querySelector('.count.acc')?.textContent).toBe('2');
    fireEvent.click(screen.getByText('Backlog'));
    expect(onSelect).toHaveBeenCalledWith('backlog');
  });
});

describe('InlineChips', () => {
  it('renders nothing when empty, chips with counts otherwise', () => {
    const { container, rerender } = render(<InlineChips chips={[]} />);
    expect(container.querySelector('.inline-chips')).toBeNull();
    const onClick = vi.fn();
    rerender(<InlineChips chips={[{ icon: '◳', label: 'Changes', count: 3, countColor: 'acc', onClick }]} />);
    fireEvent.click(screen.getByRole('button', { name: /Changes/ }));
    expect(onClick).toHaveBeenCalledOnce();
    expect(container.querySelector('.count.acc')?.textContent).toBe('3');
  });
});

describe('PlanTabContent', () => {
  it('renders goal / steps / confidence + actions and switches to locked when approved', () => {
    const onApprove = vi.fn();
    const { container, rerender } = render(
      <PlanTabContent
        source={{ label: 'recorded 2m ago' }}
        goal="Add a cost meter"
        steps={[{ text: 'Wire the meter', file: 'Bar.java' }]}
        confidence="high"
        onApprove={onApprove}
        onRequestChanges={() => {}}
      />,
    );
    expect(container.querySelector('.plan-step .ord')?.textContent).toBe('1');
    expect(container.querySelector('.plan-goal')?.textContent).toBe('Add a cost meter');
    // A single confidence badge, not the old signal-chip row.
    expect(container.querySelector('.conf.conf--high')?.textContent).toBe('high');
    fireEvent.click(screen.getByRole('button', { name: 'Approve plan' }));
    expect(onApprove).toHaveBeenCalledOnce();
    rerender(<PlanTabContent goal="Add a cost meter" approved />);
    expect(screen.getByText(/Approved/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Approve plan' })).toBeNull();
  });
});

describe('DetailsTabContent', () => {
  it('renders grouped key/value rows and tints cost', () => {
    const { container } = render(
      <DetailsTabContent sections={[
        { title: 'Task metrics', rows: [{ label: 'Cost', value: '$0.42', cost: true }, { label: 'Tokens', value: '12k' }] },
      ]}
      />,
    );
    expect(container.querySelector('.details-sec-h')?.textContent).toBe('Task metrics');
    expect(container.querySelector('.v.cost')?.textContent).toBe('$0.42');
    expect(container.querySelectorAll('.details-row').length).toBe(2);
  });
});

describe('TasksTabContent', () => {
  it('renders active cards on top and a collapsible Queued folder', () => {
    const onOpenTask = vi.fn();
    const { container } = render(
      <TasksTabContent
        active={[{ id: 'a', title: 'Active task', status: 'foreground' }]}
        queued={[{ id: 'q', title: 'Queued task', status: 'pending' }]}
        onOpenTask={onOpenTask}
      />,
    );
    expect(container.querySelectorAll('.task-card').length).toBe(2);
    expect(screen.getByText('Queued')).toBeTruthy();
    expect(container.querySelector('.status-pill.pending')).toBeTruthy();
    // Collapse the queued folder → only the active card remains.
    fireEvent.click(container.querySelector('.folder-row') as HTMLElement);
    expect(container.querySelectorAll('.task-card').length).toBe(1);
    fireEvent.click(screen.getByText('Active task'));
    expect(onOpenTask).toHaveBeenCalledWith('a');
  });
});

describe('BacklogTabContent', () => {
  it('renders the add dropzone + backlog cards and wires Start development', () => {
    const onAddItem = vi.fn();
    const onStart = vi.fn();
    const { container } = render(
      <BacklogTabContent
        items={[{ id: 'b1', title: 'Idea', tags: [{ label: 'ui', color: 'green' }] }]}
        onAddItem={onAddItem}
        onStartDevelopment={onStart}
      />,
    );
    fireEvent.click(container.querySelector('.backlog-add') as HTMLElement);
    expect(onAddItem).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByRole('button', { name: /Start development/ }));
    expect(onStart).toHaveBeenCalledWith('b1');
  });
});

describe('NotificationsTabContent', () => {
  it('renders rows with severity icons and an empty state', () => {
    const { rerender, container } = render(<NotificationsTabContent notifications={[]} />);
    expect(screen.getByText('No notifications yet.')).toBeTruthy();
    const onOpen = vi.fn();
    rerender(
      <NotificationsTabContent
        notifications={[{ id: 'n1', iconKind: 'success', title: 'Pushed', unread: true }]}
        onOpen={onOpen}
      />,
    );
    expect(container.querySelector('.notif-row.unread .ic.success')).toBeTruthy();
    fireEvent.click(screen.getByText('Pushed'));
    expect(onOpen).toHaveBeenCalledWith('n1');
  });
});

describe('PRTabContent', () => {
  it('renders status, branch flow, threads, and disables the empty comment button', () => {
    const { container } = render(
      <PRTabContent
        status="open"
        statusLabel="Open"
        headBranch="feat/x"
        baseBranch="main"
        threads={[{ id: 't1', author: 'You', file: 'Foo.java', status: 'open', body: 'rename this' }]}
        onAddComment={() => {}}
      />,
    );
    expect(container.querySelector('.pr-status-badge.open')?.textContent).toBe('Open');
    expect(container.querySelector('.pr-branch-flow')?.textContent).toContain('feat/x');
    expect(container.querySelector('.pr-comment-thread .status.open')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Comment' }) as HTMLButtonElement).disabled).toBe(true);
  });
});

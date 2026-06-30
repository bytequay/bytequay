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
import { ApprovalNode, AskQuestionNode } from './index';

afterEach(cleanup);

describe('ApprovalNode', () => {
  it('renders an orange decision node with the command + actions', () => {
    const { container } = render(
      <ApprovalNode tool="Bash" command="mvn verify" why="verify runs integration steps" allowLabel="mvn" onDecision={() => {}} />,
    );
    expect(container.querySelector('.sp-node--orange')).toBeTruthy();
    expect(container.querySelector('.sp-appr__cmd')?.textContent).toContain('mvn verify');
    expect(container.querySelector('.sp-appr__why')?.textContent).toContain('integration');
    expect(screen.getByText(/Always allow mvn/)).toBeTruthy();
  });

  it('resolves in place to a green record on approve', () => {
    const onDecision = vi.fn();
    const { container } = render(<ApprovalNode command="mvn verify" onDecision={onDecision} />);
    fireEvent.click(screen.getByText('Approve & run'));
    expect(onDecision).toHaveBeenCalledWith('approve');
    expect(container.querySelector('.sp-appr--done')?.textContent).toContain('Approved');
    // The actions are gone once resolved.
    expect(screen.queryByText('Deny')).toBeNull();
  });

  it('shows a denied record on deny', () => {
    const onDeny = vi.fn();
    const { container } = render(<ApprovalNode command="rm -rf x" onDecision={onDeny} />);
    fireEvent.click(screen.getByText('Deny'));
    expect(onDeny).toHaveBeenCalledWith('deny');
    expect(container.querySelector('.sp-appr--denied')?.textContent).toContain('Denied');
  });

  it('resolves with the allowlist note on always', () => {
    const onAlways = vi.fn();
    render(<ApprovalNode command="mvn verify" allowLabel="mvn" onDecision={onAlways} />);
    fireEvent.click(screen.getByText(/Always allow/));
    expect(onAlways).toHaveBeenCalledWith('always');
    expect(screen.getByText(/mvn allowlisted/)).toBeTruthy();
  });
});

describe('AskQuestionNode', () => {
  it('wraps a question card on an amber node and shows the answer chip when resolved', () => {
    const { container, rerender } = render(
      <AskQuestionNode><div className="q-body">Tighten validation?</div></AskQuestionNode>,
    );
    expect(container.querySelector('.sp-node--amber')).toBeTruthy();
    expect(container.querySelector('.q-body')).toBeTruthy();

    rerender(<AskQuestionNode resolvedLabel="Tighten — require /" />);
    expect(container.querySelector('.sp-answered__chip')?.textContent).toContain('Tighten — require /');
    expect(container.querySelector('.q-body')).toBeNull();
  });
});

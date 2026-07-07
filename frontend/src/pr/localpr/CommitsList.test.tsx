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
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { CommitsList } from './CommitsList';
import type { LocalPRCommit } from '../../types/localPr';

afterEach(cleanup);

function commit(over: Partial<LocalPRCommit> = {}): LocalPRCommit {
  return {
    id: 'c1', localPrId: 'pr1', sha: '41fe94c47b900f09b27f753e73fcd8845dcf0419',
    message: 'Move MetadataAndProtocolEntries to out package', additions: 0, deletions: 0,
    authoredAt: Date.now(), pushedAt: null, ...over,
  };
}

describe('CommitsList', () => {
  it('renders a short sha and the commit subject', () => {
    render(<CommitsList commits={[commit()]} author="@chenjian2664" />);

    expect(screen.getByText('41fe94c')).toBeTruthy();
    expect(screen.getByText('Move MetadataAndProtocolEntries to out package')).toBeTruthy();
  });

  it('shows +/- stats only when the commit carries real per-commit numbers', () => {
    render(<CommitsList commits={[commit({ additions: 10, deletions: 2 }), commit({ id: 'c2', sha: 'bbb' })]} author={null} />);

    expect(screen.getByText('+10')).toBeTruthy();
    expect(screen.getByText('−2')).toBeTruthy();
  });
});

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
import { flushSync } from 'react-dom';
import { useAsyncAction } from '../useAsyncAction';
import { createFixtureRoot } from './reactFixture';
import { assert, runScenarios, settle } from './harness';

function mount(message?: Parameters<typeof useAsyncAction>[0]) {
  const view = createFixtureRoot();
  let current: ReturnType<typeof useAsyncAction>;
  function Harness() {
    current = useAsyncAction(message);
    return <button disabled={current.busy}>{current.error ?? 'Ready'}</button>;
  }
  flushSync(() => view.root.render(<Harness />));
  return { ...view, state: () => current };
}

export async function run() {
  return runScenarios([
    ['holds pending state until the complete action finishes', async () => {
      const view = mount();
      try {
        const gate = Promise.withResolvers<void>();
        let pending!: Promise<void>;
        let called = false;
        flushSync(() => {
          pending = view.state().run(() => { called = true; return gate.promise; });
        });
        assert(called && view.state().busy && view.host.querySelector('button')?.disabled, 'The action starts immediately and disables its control');
        gate.resolve();
        await pending; await settle();
        assert(!view.state().busy && view.state().error === null, 'Success releases the control without an error');
      } finally { view.dispose(); }
    }],
    ['reports synchronous and asynchronous failures without rejecting the action', async () => {
      const view = mount();
      try {
        await view.state().run(() => { throw new Error('Could not save'); }); await settle();
        assert(!view.state().busy && view.state().error === 'Could not save', 'Synchronous failure keeps the Error message and releases pending state');
        await view.state().run(async () => { throw 'Connection lost'; }); await settle();
        assert(!view.state().busy && view.state().error === 'Connection lost', 'A rejected non-Error value remains visible');
      } finally { view.dispose(); }
    }],
    ['keeps caller failure wording and clears it when retrying', async () => {
      const view = mount(failure => failure instanceof Error ? `Account: ${failure.message}` : 'Account unavailable');
      try {
        await view.state().run(async () => { throw new Error('Sign in again'); }); await settle();
        assert(view.state().error === 'Account: Sign in again', 'The caller formats a failure');
        await view.state().run(async () => { throw null; }); await settle();
        assert(view.state().error === 'Account unavailable', 'The caller keeps its non-Error fallback');
        const gate = Promise.withResolvers<void>();
        let pending!: Promise<void>;
        flushSync(() => { pending = view.state().run(() => gate.promise); });
        assert(view.state().busy && view.state().error === null, 'Retry clears the prior error before awaiting its result');
        gate.resolve(); await pending; await settle();
        flushSync(() => view.state().setError('Read failed'));
        assert(view.state().error === 'Read failed', 'Related reads can still supply an error');
        flushSync(() => view.state().setError(null));
        assert(view.state().error === null, 'Closing an editor can still clear its error');
      } finally { view.dispose(); }
    }],
    ['keeps separate component actions isolated', async () => {
      const first = mount();
      const second = mount();
      try {
        const gate = Promise.withResolvers<void>();
        let pending!: Promise<void>;
        flushSync(() => { pending = first.state().run(() => gate.promise); });
        await second.state().run(async () => { throw new Error('Second failed'); }); await settle();
        assert(first.state().busy && first.state().error === null, 'Another component does not change the pending action');
        assert(!second.state().busy && second.state().error === 'Second failed', 'The other component owns its failure');
        gate.resolve(); await pending;
      } finally { first.dispose(); second.dispose(); }
    }],
    ['lets an already-started action finish after its view closes', async () => {
      const view = mount();
      const gate = Promise.withResolvers<void>();
      let completed = false;
      const pending = view.state().run(async () => { await gate.promise; completed = true; });
      view.dispose();
      gate.resolve(); await pending;
      assert(completed, 'Closing the view does not cancel accepted work or reject its completion');
    }],
  ]);
}

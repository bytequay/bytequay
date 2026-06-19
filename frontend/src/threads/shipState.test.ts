import { describe, it, expect } from 'vitest';
import { isTaskShippable } from './shipState';

describe('isTaskShippable', () => {
  it('allows shipping an active task', () => {
    expect(isTaskShippable({ status: 'RUNNING' })).toBe(true);
    expect(isTaskShippable({ status: 'IDLE' })).toBe(true);
    expect(isTaskShippable({ status: 'PENDING' })).toBe(true);
  });

  it('blocks a task that was already shipped (IN_REVIEW, PR open)', () => {
    // The reported bug: a shipped task still offered "Ship — finalize & merge".
    expect(isTaskShippable({ status: 'IN_REVIEW' })).toBe(false);
  });

  it('blocks parked / merged / errored / canceled tasks', () => {
    expect(isTaskShippable({ status: 'AWAITING_REVIEW' })).toBe(false);
    expect(isTaskShippable({ status: 'COMPLETED' })).toBe(false);
    expect(isTaskShippable({ status: 'ERRORED' })).toBe(false);
    expect(isTaskShippable({ status: 'CANCELED' })).toBe(false);
  });

  it('blocks when the merge phase completed even if runtime status lags', () => {
    expect(isTaskShippable({ status: 'IDLE', phase: 'COMPLETED' })).toBe(false);
  });

  it('blocks a missing task', () => {
    expect(isTaskShippable(null)).toBe(false);
    expect(isTaskShippable(undefined)).toBe(false);
  });
});

import { describe, it, expect } from 'vitest';
import { prRefFromNotification } from './notificationNav';
import type { NotificationDto } from '../types';

function notif(payload: object | null): NotificationDto {
  return {
    id: 'n1',
    threadId: 't1',
    kind: 'NEEDS_ATTENTION',
    status: 'UNREAD',
    payloadJson: payload === null ? null : JSON.stringify(payload),
  } as unknown as NotificationDto;
}

describe('prRefFromNotification', () => {
  it('extracts owner / repo / prNumber from a PR notification', () => {
    const ref = prRefFromNotification(
      notif({ repoFullName: 'chenjian2664/ByteQuay', prNumber: 18, reason: 'CI failing' }));
    expect(ref).toEqual({ owner: 'chenjian2664', repo: 'ByteQuay', prNumber: 18 });
  });

  it('returns null when there is no PR number', () => {
    expect(prRefFromNotification(notif({ repoFullName: 'a/b' }))).toBeNull();
  });

  it('returns null when there is no repo', () => {
    expect(prRefFromNotification(notif({ prNumber: 18 }))).toBeNull();
  });

  it('returns null for a malformed slug (no owner or no repo)', () => {
    expect(prRefFromNotification(notif({ repoFullName: 'noslash', prNumber: 1 }))).toBeNull();
    expect(prRefFromNotification(notif({ repoFullName: '/repo', prNumber: 1 }))).toBeNull();
    expect(prRefFromNotification(notif({ repoFullName: 'owner/', prNumber: 1 }))).toBeNull();
  });

  it('returns null for an empty or unparseable payload', () => {
    expect(prRefFromNotification(notif(null))).toBeNull();
    expect(prRefFromNotification({ ...notif({}), payloadJson: '{bad json' } as NotificationDto))
      .toBeNull();
  });
});

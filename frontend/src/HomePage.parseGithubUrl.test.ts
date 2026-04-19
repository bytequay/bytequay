import { describe, expect, it } from 'vitest';
import { parseGithubUrl } from './HomePage';

describe('parseGithubUrl', () => {
  it('parses a plain repo URL', () => {
    expect(parseGithubUrl('https://github.com/trinodb/trino'))
      .toEqual({ owner: 'trinodb', repo: 'trino' });
  });

  it('parses a PR URL with the number', () => {
    expect(parseGithubUrl('https://github.com/trinodb/trino/pull/42'))
      .toEqual({ owner: 'trinodb', repo: 'trino', prNumber: 42 });
  });

  it('returns null for issue URLs (no in-app issues page yet)', () => {
    expect(parseGithubUrl('https://github.com/trinodb/trino/issues/42')).toBeNull();
  });

  it('returns null for non-github hosts', () => {
    expect(parseGithubUrl('https://gitlab.com/foo/bar')).toBeNull();
    expect(parseGithubUrl('https://example.com/trinodb/trino')).toBeNull();
  });

  it('returns null for profile or root URLs', () => {
    expect(parseGithubUrl('https://github.com/octocat')).toBeNull();
    expect(parseGithubUrl('https://github.com/')).toBeNull();
  });

  it('returns null for non-numeric PR refs (defensive)', () => {
    expect(parseGithubUrl('https://github.com/trinodb/trino/pull/abc')).toBeNull();
    expect(parseGithubUrl('https://github.com/trinodb/trino/pull/0')).toBeNull();
  });

  it('returns null for malformed URLs', () => {
    expect(parseGithubUrl('not a url')).toBeNull();
  });

  it('ignores deeper PR sub-paths but still resolves the PR', () => {
    // /pull/42/files, /pull/42/commits — still navigate to the PR.
    expect(parseGithubUrl('https://github.com/trinodb/trino/pull/42/files'))
      .toEqual({ owner: 'trinodb', repo: 'trino', prNumber: 42 });
  });
});

#!/usr/bin/env node
// Vendors the pinned CodeGraph platform bundle into frontend/vendor/codegraph.
//
// The bundle is the only CodeGraph artifact ByteQuay ships: its own Node 24
// runtime, the Rust kernel as a Node addon, the grammars, and the CLI script.
// It is fetched by sha512 integrity at build or dev time and never at runtime;
// the backend runs `node lib/dist/bin/codegraph.js` from this directory. A
// rerun is a no-op once the marker matches the pinned version.
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const VERSION = '1.6.0';
const INTEGRITY = {
  'darwin-arm64': 'sha512-usYVM30d7kH2Fm3ZPkiE84J8bWAicPsw/3Kh8LjbwvHkBkU3IlEYRQFkTDAf+HSRLLs8h5w/5WHkx/FvNKRZ/g==',
  'darwin-x64': 'sha512-MMYFseqAmytsj47ZsGZI+0OVDTYsEktHg6x42tB6y+8MWUjvH1ivz5Yk6RtX6iFAnNJeMSlV+SJepfP7pvbNbQ==',
};
// Upstream refuses an absolute CODEGRAPH_DIR, so its index can never leave the
// source root. This five-line patch honors an absolute CODEGRAPH_DATA_DIR so
// the index lives under the task runtime and never dirties a worktree. Every
// index path in the bundle derives from this one function.
const PATCH_FILE = 'lib/dist/directory.js';
const PATCH_FROM = 'function getCodeGraphDir(projectRoot) {\n    return path.join(projectRoot, codeGraphDirName());\n}';
const PATCH_TO = [
  'function getCodeGraphDir(projectRoot) {',
  '    // ByteQuay patch: an absolute CODEGRAPH_DATA_DIR keeps the index outside the source root.',
  '    const external = process.env.CODEGRAPH_DATA_DIR?.trim();',
  '    if (external && path.isAbsolute(external)) {',
  '        return external;',
  '    }',
  '    return path.join(projectRoot, codeGraphDirName());',
  '}',
].join('\n');

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const platform = process.argv[2] ?? `${process.platform}-${process.arch}`;
const integrity = INTEGRITY[platform];
if (!integrity) {
  console.error(`codegraph-bundle: no pinned bundle for ${platform}`);
  process.exit(1);
}
const target = join(root, 'frontend', 'vendor', 'codegraph');
const marker = join(target, 'BYTEQUAY_BUNDLE');
const stamp = `${platform} ${VERSION} ${integrity}\n`;
if (existsSync(marker) && readFileSync(marker, 'utf8') === stamp) {
  process.exit(0);
}

const url = `https://registry.npmjs.org/@colbymchenry/codegraph-${platform}/-/codegraph-${platform}-${VERSION}.tgz`;
console.log(`[codegraph-bundle] fetching ${url}`);
const response = await fetch(url);
if (!response.ok) {
  console.error(`codegraph-bundle: ${response.status} ${response.statusText}`);
  process.exit(1);
}
const bytes = Buffer.from(await response.arrayBuffer());
const actual = `sha512-${createHash('sha512').update(bytes).digest('base64')}`;
if (actual !== integrity) {
  console.error(`codegraph-bundle: integrity mismatch for ${platform}: ${actual}`);
  process.exit(1);
}
const work = `${target}.tmp`;
rmSync(work, { recursive: true, force: true });
mkdirSync(work, { recursive: true });
const tarball = join(work, 'bundle.tgz');
writeFileSync(tarball, bytes);
execFileSync('tar', ['-xzf', tarball, '-C', work, '--strip-components=1'], { stdio: 'inherit' });
rmSync(tarball);
const patched = join(work, PATCH_FILE);
const source = readFileSync(patched, 'utf8');
if (source.split(PATCH_FROM).length !== 2) {
  console.error('codegraph-bundle: patch anchor not found once; refusing to vendor an unknown build');
  process.exit(1);
}
writeFileSync(patched, source.replace(PATCH_FROM, PATCH_TO));
rmSync(target, { recursive: true, force: true });
renameSync(work, target);
writeFileSync(marker, stamp);
console.log(`[codegraph-bundle] vendored ${platform} ${VERSION} at ${target}`);

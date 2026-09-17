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
// Each patch replaces text that must occur exactly once, so an unknown build is
// refused rather than half-patched. PATCH_REVISION is part of the marker: raise
// it with every patch change so an already-vendored bundle is fetched and
// patched again.
const PATCH_REVISION = 2;
const PATCHES = [
  // Upstream refuses an absolute CODEGRAPH_DIR, so its index can never leave the
  // source root. This honors an absolute CODEGRAPH_DATA_DIR so the index lives
  // under the task runtime and never dirties a worktree. Every index path in
  // the bundle derives from this one function.
  {
    file: 'lib/dist/directory.js',
    from: 'function getCodeGraphDir(projectRoot) {\n    return path.join(projectRoot, codeGraphDirName());\n}',
    to: [
      'function getCodeGraphDir(projectRoot) {',
      '    // ByteQuay patch: an absolute CODEGRAPH_DATA_DIR keeps the index outside the source root.',
      '    const external = process.env.CODEGRAPH_DATA_DIR?.trim();',
      '    if (external && path.isAbsolute(external)) {',
      '        return external;',
      '    }',
      '    return path.join(projectRoot, codeGraphDirName());',
      '}',
    ].join('\n'),
  },
  // Upstream prints a file's symbols only as markdown. `node -f <file>
  // --symbols-only --json` prints the library's own getNodesInFile(file) as a
  // JSON array instead, for the Code & Tests outline.
  {
    file: 'lib/dist/bin/codegraph.js',
    from: "        .option('--symbols-only', 'File mode: just the symbol map + dependents')\n",
    to: [
      "        .option('--symbols-only', 'File mode: just the symbol map + dependents')",
      "        .option('-j, --json', 'File mode with --symbols-only: print the file nodes as JSON (ByteQuay patch)')",
      '',
    ].join('\n'),
  },
  {
    file: 'lib/dist/bin/codegraph.js',
    from: [
      '            const cg = await CodeGraph.open(projectPath);',
      "            const { ToolHandler } = await Promise.resolve().then(() => __importStar(require('../mcp/tools')));",
      '            const handler = new ToolHandler(cg);',
      '            // A name with a path separator is a file read;',
    ].join('\n'),
    to: [
      '            const cg = await CodeGraph.open(projectPath);',
      '            // ByteQuay patch: print the file nodes as JSON.',
      '            if (options.file && options.symbolsOnly && options.json) {',
      '                console.log(JSON.stringify(cg.getNodesInFile(options.file)));',
      '                cg.destroy();',
      '                return;',
      '            }',
      "            const { ToolHandler } = await Promise.resolve().then(() => __importStar(require('../mcp/tools')));",
      '            const handler = new ToolHandler(cg);',
      '            // A name with a path separator is a file read;',
    ].join('\n'),
  },
];

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const platform = process.argv[2] ?? `${process.platform}-${process.arch}`;
const integrity = INTEGRITY[platform];
if (!integrity) {
  console.error(`codegraph-bundle: no pinned bundle for ${platform}`);
  process.exit(1);
}
const target = join(root, 'frontend', 'vendor', 'codegraph');
const marker = join(target, 'BYTEQUAY_BUNDLE');
const stamp = `${platform} ${VERSION} ${integrity} patches=${PATCH_REVISION}\n`;
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
for (const patch of PATCHES) {
  const patched = join(work, patch.file);
  const source = readFileSync(patched, 'utf8');
  if (source.split(patch.from).length !== 2) {
    console.error(`codegraph-bundle: patch anchor in ${patch.file} not found once; refusing to vendor an unknown build`);
    process.exit(1);
  }
  writeFileSync(patched, source.replace(patch.from, () => patch.to));
}
rmSync(target, { recursive: true, force: true });
renameSync(work, target);
writeFileSync(marker, stamp);
console.log(`[codegraph-bundle] vendored ${platform} ${VERSION} at ${target}`);

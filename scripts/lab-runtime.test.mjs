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
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { copyFile, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'bytequay-lab-runtime-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const script = join(root, 'scripts', 'lab-runtime.mjs');
  const source = join(root, 'scripts', 'review-calibration');
  const bin = join(root, 'build tools');
  const target = join(root, 'frontend', 'vendor', 'lab');
  await mkdir(join(source, 'pages'), { recursive: true });
  await mkdir(join(root, 'scripts', 'lab-runtime-notices'));
  await mkdir(bin);
  await copyFile(new URL('./lab-runtime.mjs', import.meta.url), script);
  await copyFile(new URL('./lab-runtime-python.json', import.meta.url), join(root, 'scripts', 'lab-runtime-python.json'));
  await writeFile(join(root, 'LICENSE'), 'ByteQuay license');
  await writeFile(join(root, 'scripts', 'lab-runtime-notices', 'LICENSE.zstd.txt'), 'Zstandard license');
  await writeFile(join(source, 'dashboard.py'), 'dashboard');
  await writeFile(join(source, 'test_dashboard.py'), 'tests should not ship');
  await writeFile(join(source, 'pages', 'Context.py'), 'context');
  await writeFile(join(source, 'uv.lock'), 'locked dependencies');
  await writeFile(join(source, 'pyproject.toml'), 'project');
  await writeFile(join(bin, 'uv'), `#!${process.execPath}
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const args = process.argv.slice(2);
const after = name => args[args.indexOf(name) + 1];
fs.appendFileSync(path.join(process.env.UV_CACHE_DIR, 'calls'), args.slice(0, 2).join(' ') + '\\n');
if (args[0] === 'python') {
  assert.ok(args.includes('--no-bin'));
  const metadata = JSON.parse(fs.readFileSync(require('node:url').fileURLToPath(after('--python-downloads-json-url')), 'utf8'));
  assert.equal(metadata['cpython-3.14.8-darwin-${process.arch === 'arm64' ? 'aarch64' : 'x86_64'}-none'].build, '20261009');
  const directory = path.join(process.env.UV_PYTHON_INSTALL_DIR, 'cpython-3.14.8-macos-${process.arch === 'arm64' ? 'aarch64' : 'x86_64'}-none');
  fs.mkdirSync(path.join(directory, 'bin'), { recursive: true });
  fs.mkdirSync(path.join(directory, 'lib', 'python3.14', 'site-packages'), { recursive: true });
  fs.mkdirSync(path.join(directory, 'include'), { recursive: true });
  fs.writeFileSync(path.join(directory, 'include', 'Python.h'), 'build-only header');
  fs.writeFileSync(path.join(directory, 'lib', 'python3.14', 'LICENSE.txt'), 'Python license');
  fs.writeFileSync(path.join(directory, 'bin', 'python3'), ${JSON.stringify(`#!${process.execPath}
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
assert.equal(process.env.PATH, '');
assert.ok(process.argv[1].includes('relocated lab'));
assert.ok(!fs.existsSync(path.resolve(process.cwd(), '../../image')));
assert.deepEqual(process.argv.slice(2, 6), ['-E', '-s', '-B', '-c']);
assert.ok(process.argv[6].includes('import pandas, pyarrow, plotly, psutil, streamlit, dashboard'));
assert.equal(fs.readFileSync('dashboard.py', 'utf8'), 'dashboard');
assert.ok(fs.existsSync('../python/lib/python3.14/site-packages/streamlit.dist-info/LICENSE'));
fs.writeFileSync('../smoke-passed', 'yes');
`)}, { mode: 0o755 });
} else if (args[0] === 'export') {
  assert.ok(args.includes('--locked'));
  assert.ok(args.includes('--no-emit-project'));
  fs.writeFileSync(after('--output-file'), 'locked requirements');
} else if (args[0] === 'pip') {
  assert.ok(args.includes('--require-hashes'));
  assert.equal(after('--only-binary'), ':all:');
  assert.equal(after('--link-mode'), 'copy');
  if (process.env.FAIL_SYNC) process.exit(1);
  const site = after('--target');
  fs.mkdirSync(path.join(site, 'streamlit.dist-info'), { recursive: true });
  fs.writeFileSync(path.join(site, 'streamlit.dist-info', 'LICENSE'), 'Streamlit license');
  for (const file of [
    'pandas/tests/test_frame.py', 'pandas/testing.py', 'pandas/_testing/__init__.py',
    'numpy/testing/tests/test_utils.py', 'numpy/testing/__init__.py', 'numpy/_core/tests/test_api.py', 'numpy/_core/include/numpy/random/LICENSE.txt',
    'pyarrow/include/arrow/api.h', 'pyarrow/src/module.cpp', 'pyarrow/tests/test_array.py', 'pyarrow/libarrow.dylib',
    'pydeck/nbextension/__init__.py', 'pydeck/nbextension/static/index.js', 'pydeck/nbextension/static/index.js.map',
    'share/jupyter/nbextensions/pydeck/index.js', 'share/jupyter/nbextensions/pydeck/index.js.map',
    'plotly/labextension/static/chart.js', 'plotly/labextension/static/third-party-licenses.json',
    'share/jupyter/labextensions/jupyterlab-plotly/static/chart.js',
    'share/jupyter/labextensions/jupyterlab-plotly/static/third-party-licenses.json',
    'plotly/package_data/plotly.min.js', 'plotly/package_data/widgetbundle.js', 'streamlit/static/app.js',
    'pip/__init__.py',
  ]) {
    fs.mkdirSync(path.dirname(path.join(site, file)), { recursive: true });
    fs.writeFileSync(path.join(site, file), file);
  }
}
`, { mode: 0o755 });
  const run = (args = [], env = {}) => spawnSync(process.execPath, [script, ...args], {
    env: { ...process.env, PATH: bin, ...env }, encoding: 'utf8',
  });
  return { root, target, source, run };
}

test('ships the runtime, locked dependencies, sources and licenses after a relocation smoke test', { skip: process.platform !== 'darwin' }, async t => {
  const { root, target, source, run } = await fixture(t);
  const built = run();
  assert.equal(built.status, 0, built.stderr);
  assert.equal(await readFile(join(target, 'LICENSE'), 'utf8'), 'ByteQuay license');
  assert.equal(await readFile(join(target, 'notices', 'LICENSE.zstd.txt'), 'utf8'), 'Zstandard license');
  assert.equal(await readFile(join(target, 'python', 'lib', 'python3.14', 'LICENSE.txt'), 'utf8'), 'Python license');
  assert.equal(await readFile(join(target, 'app', 'pages', 'Context.py'), 'utf8'), 'context');
  assert.ok(!(await readdir(join(target, 'app'))).includes('test_dashboard.py'));
  assert.equal(await readFile(join(target, 'smoke-passed'), 'utf8'), 'yes');
  const python = join(target, 'python');
  const site = join(python, 'lib', 'python3.14', 'site-packages');
  for (const path of ['include', 'lib/python3.14/site-packages/pandas/tests', 'lib/python3.14/site-packages/numpy/testing/tests',
    'lib/python3.14/site-packages/numpy/_core/tests', 'lib/python3.14/site-packages/pyarrow/include',
    'lib/python3.14/site-packages/pyarrow/src', 'lib/python3.14/site-packages/pyarrow/tests',
    'lib/python3.14/site-packages/pydeck/nbextension/static/index.js.map', 'lib/python3.14/site-packages/share/jupyter/nbextensions/pydeck',
    'lib/python3.14/site-packages/plotly/labextension/static/chart.js']) {
    await assert.rejects(readFile(join(python, path)), { code: 'ENOENT' });
  }
  for (const path of ['pandas/testing.py', 'pandas/_testing/__init__.py', 'numpy/testing/__init__.py', 'numpy/_core/include/numpy/random/LICENSE.txt', 'pyarrow/libarrow.dylib',
    'pydeck/nbextension/__init__.py', 'pydeck/nbextension/static/index.js', 'plotly/package_data/plotly.min.js',
    'plotly/package_data/widgetbundle.js', 'streamlit/static/app.js', 'pip/__init__.py',
    'plotly/labextension/static/third-party-licenses.json', 'share/jupyter/labextensions/jupyterlab-plotly/static/third-party-licenses.json']) {
    assert.equal(await readFile(join(site, path), 'utf8'), path);
  }
  const cached = run();
  assert.equal(cached.status, 0, cached.stderr);
  assert.match(cached.stdout, /already present/);
  const calls = join(root, 'frontend', 'vendor', '.lab-cache', 'calls');
  assert.equal(await readFile(calls, 'utf8'), 'python install\nexport --locked\npip sync\n');
  const originalMarker = await readFile(join(target, '.manifest'), 'utf8');
  await writeFile(join(source, 'pages', 'Context.py'), 'updated context');
  assert.equal(run().status, 0);
  assert.equal(await readFile(join(target, 'app', 'pages', 'Context.py'), 'utf8'), 'updated context');
  assert.notEqual(await readFile(join(target, '.manifest'), 'utf8'), originalMarker);
});

test('rejects cross-architecture packaging', { skip: process.platform !== 'darwin' }, async t => {
  const { run } = await fixture(t);
  const result = run([process.arch === 'arm64' ? 'darwin-x64' : 'darwin-arm64']);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Mac of the same architecture/);
});

test('preserves the previous bundle and removes staging files when dependency installation fails', { skip: process.platform !== 'darwin' }, async t => {
  const { root, target, source, run } = await fixture(t);
  assert.equal(run().status, 0);
  const originalMarker = await readFile(join(target, '.manifest'), 'utf8');
  await writeFile(join(source, 'uv.lock'), 'changed locked dependencies');
  const failed = run([], { FAIL_SYNC: '1' });
  assert.notEqual(failed.status, 0);
  assert.equal(await readFile(join(target, '.manifest'), 'utf8'), originalMarker);
  assert.deepEqual((await readdir(join(root, 'frontend', 'vendor'))).sort(), ['.lab-cache', 'lab']);
});

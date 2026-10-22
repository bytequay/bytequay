#!/usr/bin/env node
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
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { access, copyFile, cp, mkdir, mkdtemp, readFile, readdir, rename, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const platform = process.argv[2] ?? `${process.platform}-${process.arch}`;
if (process.platform !== 'darwin' || !['arm64', 'x64'].includes(process.arch) || platform !== `${process.platform}-${process.arch}`) {
  throw new Error(`Build ${platform} on a Mac of the same architecture; Lab includes native Python dependencies`);
}
const pythonVersion = '3.14.8';
const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const source = join(root, 'scripts', 'review-calibration');
const notices = join(root, 'scripts', 'lab-runtime-notices');
const pythonManifest = join(root, 'scripts', 'lab-runtime-python.json');
const pythonBuild = createHash('sha256').update(await readFile(pythonManifest)).digest('hex');
const vendor = join(root, 'frontend', 'vendor');
const target = join(vendor, 'lab');
const cache = join(vendor, '.lab-cache');
const sources = (await readdir(source)).filter(name => name.endsWith('.py') && !name.startsWith('test_') || ['cases.json', 'pyproject.toml', 'uv.lock'].includes(name));
sources.push(...(await readdir(join(source, 'pages'))).filter(name => name.endsWith('.py')).map(name => join('pages', name)));
const digest = createHash('sha256').update(platform).update(pythonVersion).update(pythonBuild).update(await readFile(fileURLToPath(import.meta.url))).update(await readFile(join(root, 'LICENSE')));
for (const name of sources.sort()) digest.update(name).update(await readFile(join(source, name)));
for (const name of (await readdir(notices)).sort()) digest.update(name).update(await readFile(join(notices, name)));
const marker = digest.digest('hex');
const current = await readFile(join(target, '.manifest'), 'utf8').catch(error => {
  if (error.code !== 'ENOENT') throw error;
});
if (current === marker && existsSync(join(target, 'python', 'bin', 'python3')) && existsSync(join(target, 'app', 'dashboard.py'))) {
  console.log(`lab-runtime: pinned ${platform} runtime already present`);
  process.exit(0);
}

await mkdir(cache, { recursive: true });
const env = { ...process.env, UV_CACHE_DIR: cache, UV_PYTHON_INSTALL_DIR: join(cache, 'python', pythonBuild), UV_PYTHON_CACHE_DIR: join(cache, 'downloads') };
const uv = (args, options = {}) => execFileSync('uv', args, { cwd: source, env, stdio: 'inherit', ...options });
const staging = await mkdtemp(join(vendor, '.lab-'));
try {
  // uv is a build dependency only; ship its standalone Python rather than a venv tied to the builder's machine.
  uv(['python', 'install', '--no-bin', '--no-config', '--python-downloads-json-url', pathToFileURL(pythonManifest).href, `cpython-${pythonVersion}`]);
  const installed = join(env.UV_PYTHON_INSTALL_DIR, `cpython-${pythonVersion}-macos-${process.arch === 'arm64' ? 'aarch64' : 'x86_64'}-none`);
  const image = join(staging, 'image');
  const pythonHome = join(image, 'python');
  await cp(installed, pythonHome, { recursive: true, verbatimSymlinks: true });
  const python = join(pythonHome, 'bin', 'python3');
  const sitePackages = join(pythonHome, 'lib', 'python3.14', 'site-packages');
  const requirements = join(staging, 'requirements.txt');
  uv(['export', '--locked', '--no-dev', '--no-emit-project', '--python', python, '--output-file', requirements], { stdio: ['ignore', 'ignore', 'inherit'] });
  uv(['pip', 'sync', requirements, '--python', python, '--target', sitePackages, '--require-hashes', '--only-binary', ':all:', '--link-mode', 'copy', '--strict']);
  // Console entry points contain build paths; Lab starts Python modules directly.
  await rm(join(sitePackages, 'bin'), { recursive: true, force: true });
  // The desktop does not compile extensions or run dependency test suites; keep their public testing helpers and notices.
  for (const path of ['include', 'lib/pkgconfig', 'lib/python3.14/config-3.14-darwin', 'share/man', 'bin/python3-config', 'bin/python3.14-config']) {
    await rm(join(pythonHome, path), { recursive: true, force: true });
  }
  const unusedPackages = [
    'pandas/tests', 'pyarrow/tests', 'pyarrow/include', 'pyarrow/src',
    ...['', '_core', '_pyinstaller', 'f2py', 'fft', 'lib', 'linalg', 'ma', 'matrixlib', 'polynomial', 'random', 'testing', 'typing']
      .map(module => join('numpy', module, 'tests')),
    'share/jupyter/nbextensions/pydeck', 'pydeck/nbextension/static/index.js.map',
  ];
  for (const path of unusedPackages) await rm(join(sitePackages, path), { recursive: true, force: true });
  // Lab never opens an Arrow Flight connection, and only Flight's own modules link its libraries.
  const pyarrow = join(sitePackages, 'pyarrow');
  for (const name of (await readdir(pyarrow)).filter(name => name.includes('flight'))) await rm(join(pyarrow, name));
  // These duplicate bundles serve Jupyter only; preserve their license manifests and pydeck's offline HTML bundle.
  for (const path of ['plotly/labextension/static', 'share/jupyter/labextensions/jupyterlab-plotly/static']) {
    const directory = join(sitePackages, path);
    const entries = await readdir(directory).catch(error => {
      if (error.code !== 'ENOENT') throw error;
      return [];
    });
    for (const name of entries.filter(name => name.endsWith('.js'))) await rm(join(directory, name));
  }
  await access(join(pythonHome, 'lib', 'python3.14', 'LICENSE.txt'));
  for (const name of sources) {
    const destination = join(image, 'app', name);
    await mkdir(dirname(destination), { recursive: true });
    await copyFile(join(source, name), destination);
  }
  await copyFile(join(root, 'LICENSE'), join(image, 'LICENSE'));
  await cp(notices, join(image, 'notices'), { recursive: true });

  // Prove the binary, native wheels, retained licenses and imports survive moving the app with no system Python or uv.
  const moved = join(staging, 'relocated lab');
  await rename(image, moved);
  execFileSync(join(moved, 'python', 'bin', 'python3'), ['-E', '-s', '-B', '-c',
    `import sys, platform; assert sys.version_info[:3] == (3, 14, 8); assert platform.machine() == '${process.arch === 'arm64' ? 'arm64' : 'x86_64'}'; import pandas, pyarrow, plotly, psutil, streamlit, dashboard`], {
    cwd: join(moved, 'app'), env: { PATH: '', PYTHONDONTWRITEBYTECODE: '1' }, stdio: 'inherit',
  });
  await writeFile(join(moved, '.manifest'), marker);
  await rm(target, { recursive: true, force: true });
  await rename(moved, target);
  console.log(`lab-runtime: ready ${platform}, Python ${pythonVersion}`);
} finally {
  await rm(staging, { recursive: true, force: true });
}

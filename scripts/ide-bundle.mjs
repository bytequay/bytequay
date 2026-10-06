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
import { copyFile, mkdir, mkdtemp, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// Build-time only. Runtime startup never contacts a marketplace or installs tools into the user's IDE.
const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const manifestText = await readFile(join(root, 'frontend/src/ide/runtimeManifest.json'), 'utf8');
const manifest = JSON.parse(manifestText);
const platform = process.argv[2] ?? `${process.platform}-${process.arch}`;
const server = manifest.server[platform];
if (!server) throw new Error(`No pinned ByteQuay IDE runtime for ${platform}`);

const vendor = join(root, 'frontend/vendor');
const target = join(vendor, 'ide');
const cache = join(vendor, '.ide-downloads');
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const marker = `${platform}:${digest(manifestText)}:1`;
await mkdir(cache, { recursive: true });

async function cached(artifact) {
  const path = join(cache, artifact.sha256);
  const existing = await readFile(path).catch(error => {
    if (error.code !== 'ENOENT') throw error;
  });
  if (existing && digest(existing) === artifact.sha256) return path;
  console.log(`ide-bundle: fetching ${new URL(artifact.url).pathname.split('/').pop()}`);
  const response = await fetch(artifact.url);
  if (!response.ok) throw new Error(`IDE download failed (${response.status}): ${artifact.url}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  if (digest(bytes) !== artifact.sha256) throw new Error(`IDE checksum mismatch: ${artifact.url}`);
  await writeFile(path, bytes);
  return path;
}

const current = await readFile(join(target, '.manifest'), 'utf8').catch(error => {
  if (error.code !== 'ENOENT') throw error;
});
if (current === marker) {
  console.log(`ide-bundle: pinned ${platform} runtime already present`);
  process.exit(0);
}

const staging = await mkdtemp(join(vendor, '.ide-'));
try {
  const serverDirectory = join(staging, 'server');
  await mkdir(serverDirectory);
  execFileSync('/usr/bin/tar', ['-xzf', await cached(server), '-C', serverDirectory]);
  const product = JSON.parse(await readFile(join(serverDirectory, 'product.json'), 'utf8'));
  const serverPackage = JSON.parse(await readFile(join(serverDirectory, 'package.json'), 'utf8'));
  if (product.commit !== manifest.serverCommit || serverPackage.version !== manifest.serverVersion) {
    throw new Error('The pinned IDE server identity does not match the manifest');
  }

  // No built-in command extensions: these include Git writes, authentication and auto-starting helpers.
  // Syntax/theme contributions come from the separately pinned frontend bundle.
  await rm(join(serverDirectory, 'extensions'), { recursive: true });
  await mkdir(join(serverDirectory, 'extensions'));
  product.extensionsGallery = undefined;
  product.webEndpointUrlTemplate = undefined;
  await writeFile(join(serverDirectory, 'product.json'), JSON.stringify(product));

  const extensions = join(staging, 'extensions');
  await mkdir(extensions);
  for (const extension of manifest.extensions) {
    const artifact = extension.platforms[platform] ?? extension.platforms.universal;
    if (!artifact) throw new Error(`No ${platform} bundle for ${extension.id}`);
    const extracted = join(staging, 'extract');
    await mkdir(extracted);
    execFileSync('/usr/bin/unzip', ['-q', await cached(artifact), '-d', extracted]);
    const directory = join(extracted, 'extension');
    const metadata = JSON.parse(await readFile(join(directory, 'package.json'), 'utf8'));
    if (`${metadata.publisher}.${metadata.name}`.toLowerCase() !== extension.id || metadata.version !== extension.version) {
      throw new Error(`IDE extension identity mismatch: ${extension.id}`);
    }
    await rename(directory, join(extensions, `${extension.id}-${extension.version}`));
    await rm(extracted, { recursive: true });
  }

  const notices = join(staging, 'notices');
  await mkdir(notices);
  for (const notice of manifest.notices) await copyFile(await cached(notice), join(notices, notice.name));
  await writeFile(join(staging, 'runtimeManifest.json'), manifestText);
  await writeFile(join(staging, '.manifest'), marker);
  await rm(target, { recursive: true, force: true });
  await rename(staging, target);
  console.log(`ide-bundle: ready ${platform}, Code OSS ${manifest.vscodeVersion}, ${manifest.extensions.length} extensions`);
} finally {
  await rm(staging, { recursive: true, force: true });
}

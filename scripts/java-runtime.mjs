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
import { access, mkdir, mkdtemp, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const platform = process.argv[2] ?? `${process.platform}-${process.arch}`;
if (process.platform !== 'darwin' || platform !== `${process.platform}-${process.arch}`) {
  throw new Error(`Build ${platform} on a Mac of the same architecture; jlink creates a native runtime`);
}
const javaHome = process.env.JAVA_HOME || execFileSync('/usr/libexec/java_home', ['-v', '25'], { encoding: 'utf8' }).trim();
const release = await readFile(join(javaHome, 'release'), 'utf8');
const javaArch = { arm64: 'aarch64', x64: 'x86_64' }[process.arch];
if (!/^JAVA_VERSION="25(?:\.|"|\+)/m.test(release) || !release.includes('OS_NAME="Darwin"') || !release.includes(`OS_ARCH="${javaArch}"`)) {
  throw new Error(`Packaging requires a ${platform} JDK 25; set JAVA_HOME to that JDK`);
}

// Temurin 25 links without jmods and cannot include jdk.jlink or its dependent jdk.jpackage.
const modules = execFileSync(join(javaHome, 'bin', 'java'), ['--list-modules'], { encoding: 'utf8' })
  .trim().split(/\r?\n/).map(module => module.split('@')[0])
  .filter(module => module !== 'jdk.jlink' && module !== 'jdk.jpackage').join(',');
// Keep reflective and service-loaded modules available to Spring, Hibernate, and provider helpers.
const options = ['--add-modules', modules, '--strip-debug', '--no-header-files', '--no-man-pages'];
const marker = createHash('sha256').update(JSON.stringify({ platform, release, options })).digest('hex');
const vendor = join(dirname(fileURLToPath(import.meta.url)), '..', 'frontend', 'vendor');
const target = join(vendor, 'jre');
const current = await readFile(join(target, '.manifest'), 'utf8').catch(error => {
  if (error.code !== 'ENOENT') throw error;
});
if (current === marker && existsSync(join(target, 'bin', 'java')) && existsSync(join(target, 'legal', 'java.base', 'LICENSE'))) {
  console.log(`java-runtime: ${platform} runtime already present`);
  process.exit(0);
}

await mkdir(vendor, { recursive: true });
const staging = await mkdtemp(join(vendor, '.jre-'));
try {
  const image = join(staging, 'jre');
  execFileSync(join(javaHome, 'bin', 'jlink'), [...options, '--output', image], { stdio: 'inherit' });
  execFileSync(join(image, 'bin', 'java'), ['-version'], { stdio: 'inherit' });
  // jlink carries the JDK's license and third-party notices in its legal directory.
  await access(join(image, 'legal', 'java.base', 'LICENSE'));
  await writeFile(join(image, '.manifest'), marker);
  await rm(target, { recursive: true, force: true });
  await rename(image, target);
  console.log(`java-runtime: ready ${platform}`);
} finally {
  await rm(staging, { recursive: true, force: true });
}

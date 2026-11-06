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
import { afterEach, describe, expect, it } from 'vitest';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ignoreSigningResource, macSigningConfig } from './forge.signing';

const env = {
  BYTEQUAY_SIGN_RELEASE: '1',
  APPLE_SIGNING_IDENTITY: 'Developer ID Application: Example (EXAMPLE123)',
  BYTEQUAY_SIGNING_KEYCHAIN: '/tmp/signing.keychain-db',
  BYTEQUAY_NOTARY_PROFILE: 'release',
};

describe('release signing', () => {
  it('keeps ordinary development builds unsigned', () => {
    expect(macSigningConfig({})).toEqual({});
  });

  it.each(['APPLE_SIGNING_IDENTITY', 'BYTEQUAY_SIGNING_KEYCHAIN', 'BYTEQUAY_NOTARY_PROFILE'])('fails closed without %s', name => {
    expect(() => macSigningConfig({ ...env, [name]: '' })).toThrow(name);
  });

  it('rejects development or ad-hoc signing for releases', () => {
    expect(() => macSigningConfig({ ...env, APPLE_SIGNING_IDENTITY: '-' })).toThrow('Developer ID Application');
  });

  it('refuses to import credentials or publish artifacts when secrets are absent', () => {
    for (const script of ['prepare-macos-signing.sh', 'verify-macos-release.sh']) {
      const result = spawnSync('/bin/bash', [join(__dirname, '..', 'scripts', script)], { env: { PATH: '/usr/bin:/bin' }, encoding: 'utf8' });
      expect(result.status).not.toBe(0);
      expect(result.stderr).toMatch(/Missing required release credential|Release Team ID is required/);
    }
  });

  it('uses the same isolated keychain for signing and notarization', () => {
    const config = macSigningConfig(env);
    expect(config?.osxSign).toMatchObject({ identity: env.APPLE_SIGNING_IDENTITY, keychain: env.BYTEQUAY_SIGNING_KEYCHAIN });
    expect(config?.osxNotarize).toEqual({ keychain: env.BYTEQUAY_SIGNING_KEYCHAIN, keychainProfile: 'release' });
    const sign = config?.osxSign;
    if (!sign || typeof sign !== 'object') throw new Error('Signing must be enabled');
    expect(sign.optionsForFile?.('/ByteQuay.app/Contents/Resources/jre/bin/java').entitlements).toMatch(/entitlements-java.plist$/);
    expect(sign.optionsForFile?.('/ByteQuay.app/Contents/Resources/lab/python/bin/python3').entitlements).toMatch(/entitlements-python.plist$/);
    expect(sign.optionsForFile?.('/ByteQuay.app/Contents/Frameworks/ByteQuay Helper.app')).toEqual({});
  });
});

describe('native code selection', () => {
  const directories: string[] = [];
  afterEach(() => {
    for (const directory of directories.splice(0)) rmSync(directory, { recursive: true, force: true });
  });

  it('signs native helpers and libraries, but not binary resources or symlink aliases', () => {
    const directory = mkdtempSync(join(tmpdir(), 'bytequay-signing-test-'));
    directories.push(directory);
    for (const [name, bytes, ignored] of [
      ['java', 'cffaedfe00000001', false],
      ['extension.so', 'feedfacf00000001', false],
      ['universal', 'cafebabe00000002', false],
      ['Example.class', 'cafebabe00000045', true],
      ['backend.jar', '504b030400000000', true],
      ['short', '00', true],
    ] as const) {
      const path = join(directory, name);
      writeFileSync(path, Buffer.from(bytes, 'hex'));
      expect(ignoreSigningResource(path), name).toBe(ignored);
    }
    const app = join(directory, 'ByteQuay.app');
    mkdirSync(app);
    expect(ignoreSigningResource(app)).toBe(false);
    expect(ignoreSigningResource(directory)).toBe(true);
    const alias = join(directory, 'java-alias');
    symlinkSync('java', alias);
    expect(ignoreSigningResource(alias)).toBe(true);
  });
});

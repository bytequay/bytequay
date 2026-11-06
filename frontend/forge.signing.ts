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
import type { ForgeConfig } from '@electron-forge/shared-types';
import { closeSync, lstatSync, openSync, readSync } from 'node:fs';
import { join } from 'node:path';

// osx-sign otherwise signs arbitrary binary resources (including JARs and ASARs)
// using extended attributes, which do not survive every distribution format.
export function ignoreSigningResource(path: string): boolean {
  const stat = lstatSync(path);
  if (stat.isSymbolicLink()) return true;
  if (stat.isDirectory()) return !/\.(app|framework)$/.test(path);
  const file = openSync(path, 'r');
  try {
    const header = Buffer.alloc(8);
    if (readSync(file, header, 0, 8, 0) < 8) return true;
    const magic = header.readUInt32BE(0);
    if (magic === 0xcafebabe || magic === 0xcafebabf) {
      // Java class files share the fat Mach-O magic, but have a version here.
      return header.readUInt32BE(4) > 32;
    }
    return ![0xfeedface, 0xcefaedfe, 0xfeedfacf, 0xcffaedfe, 0xbebafeca, 0xbfbafeca].includes(magic);
  } finally {
    closeSync(file);
  }
}

export function macSigningConfig(env: NodeJS.ProcessEnv = process.env): ForgeConfig['packagerConfig'] {
  if (env.BYTEQUAY_SIGN_RELEASE !== '1') return {};
  const required = (name: string): string => {
    const value = env[name]?.trim();
    if (!value) throw new Error(`Signed releases require ${name}`);
    return value;
  };
  const identity = required('APPLE_SIGNING_IDENTITY');
  if (!identity.startsWith('Developer ID Application: ')) throw new Error('A Developer ID Application identity is required');
  const keychain = required('BYTEQUAY_SIGNING_KEYCHAIN');
  return {
    osxSign: {
      identity,
      keychain,
      type: 'distribution',
      preAutoEntitlements: false,
      preEmbedProvisioningProfile: false,
      ignore: ignoreSigningResource,
      optionsForFile: path => {
        if (path.includes('/Contents/Resources/jre/')) {
          return { entitlements: join(__dirname, '..', 'build', 'entitlements-java.plist') };
        }
        if (path.includes('/Contents/Resources/lab/')) {
          return { entitlements: join(__dirname, '..', 'build', 'entitlements-python.plist') };
        }
        return {};
      },
    },
    osxNotarize: {
      keychain,
      keychainProfile: required('BYTEQUAY_NOTARY_PROFILE'),
    },
  };
}

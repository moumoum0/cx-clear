import { readFile, readdir, access } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const manifest = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'));
const source = JSON.parse(await readFile(join(root, 'source.json'), 'utf8'));
if (manifest.version !== source.version) throw new Error('npm and Gradle versions do not match');
for (const name of ['cli', 'core']) {
  await access(join(root, 'native', 'lib', `${name}-${manifest.version}.jar`));
}
const files = await readdir(join(root, 'native'));
if (files.some(name => name !== 'lib')) {
  throw new Error('Unexpected files in native/: Java and the EXE launcher must not be bundled');
}
console.error(`Verified cxclear ${manifest.version} (without bundled Java)`);

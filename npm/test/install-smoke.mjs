import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdir, mkdtemp, readFile, readdir, writeFile, access } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { ensureJava } from '../lib/java.js';

if (process.platform !== 'win32' || process.arch !== 'x64') throw new Error('Run npm smoke tests on Windows x64');
if (!process.argv[2]) throw new Error('Usage: node npm/test/install-smoke.mjs <package.tgz> [--download-java]');
const tarball = resolve(process.argv[2]);
const build = resolve('build', 'npm-smoke');
await mkdir(build, { recursive: true });
const root = await mkdtemp(join(build, 'install space \u4e2d\u6587-'));
const npmCli = process.env.npm_execpath || join(dirname(process.execPath), 'node_modules', 'npm', 'bin', 'npm-cli.js');
await access(npmCli);
const env = { ...process.env, CXCLEAR_RUNTIME_DIR: join(root, 'java-cache') };
if (process.argv.includes('--download-java')) {
  env.JAVA_HOME = '';
  env.PATH = `${dirname(process.execPath)};${join(process.env.SystemRoot, 'System32')}`;
}

function run(args, { input, expected = 0, executable = process.execPath, shell = false } = {}) {
  const result = spawnSync(executable, args, { env, input, encoding: 'utf8', windowsHide: true, timeout: 600000, shell });
  if (result.error) throw result.error;
  assert.equal(result.status, expected, `${args.join(' ')}\n${result.stdout}\n${result.stderr}`);
  return result;
}

const npmArgs = ['--cache', resolve('build', 'npm-smoke', 'npm-cache'), '--no-audit', '--no-fund'];
const prefix = join(root, 'global');
run([npmCli, ...npmArgs, 'install', '--global', '--prefix', prefix, '--foreground-scripts', tarball]);
const installed = join(prefix, 'node_modules', 'cxclear');
const manifest = JSON.parse(await readFile(join(installed, 'package.json'), 'utf8'));
const source = JSON.parse(await readFile(join(installed, 'source.json'), 'utf8'));
assert.equal(manifest.version, source.version);
assert.deepEqual(await readdir(join(installed, 'native')), ['lib']);
const installKey = createHash('sha256').update(installed).digest('hex');
const record = join(env.CXCLEAR_RUNTIME_DIR, 'installs', `${installKey}.json`);
const selected = JSON.parse(await readFile(record, 'utf8'));
if (process.argv.includes('--download-java')) assert.ok(selected.home.startsWith(env.CXCLEAR_RUNTIME_DIR));
const launcher = join(installed, 'bin', 'cxclear.js');
for (const alias of ['--version', '-v', 'version']) {
  assert.equal(JSON.parse(run([launcher, alias]).stdout).version, manifest.version);
}
assert.equal(JSON.parse(run([launcher, 'schema']).stdout).version, manifest.version);
assert.equal(JSON.parse(run([launcher, '--help']).stdout).ok, true);
assert.equal(JSON.parse(run([launcher, 'rules', 'validate'], { input: '{"version":2,"rules":[]}' }).stdout).ok, true);
const invalid = run([launcher, 'schema', '--nope'], { expected: 3 });
assert.match(invalid.stderr, /unknown option/);
assert.equal(JSON.parse(invalid.stdout).ok, false);

const fixture = join(root, 'rule space \u4e2d\u6587.json');
await writeFile(fixture, '{"version":2,"rules":[]}');
assert.equal(JSON.parse(run([launcher, 'rules', 'validate', '--file', fixture]).stdout).ok, true);

const shimPrefix = resolve('build', 'npm-smoke', 'shim');
run([npmCli, ...npmArgs, 'install', '--global', '--prefix', shimPrefix, '--ignore-scripts', tarball]);
env.PATH = `${dirname(process.execPath)};${join(process.env.SystemRoot, 'System32')}`;
const asciiCommandShim = join(shimPrefix, 'cxclear.cmd');
await access(asciiCommandShim);
const command = process.env.ComSpec || join(process.env.SystemRoot, 'System32', 'cmd.exe');
assert.equal(JSON.parse(run(['/d', '/c', 'call', asciiCommandShim, 'schema'], { executable: command }).stdout).version, manifest.version);

// A skipped postinstall must invoke the same Java installer on first launch.
const skippedPrefix = join(root, 'ignored scripts');
run([npmCli, ...npmArgs, 'install', '--global', '--prefix', skippedPrefix, '--ignore-scripts', tarball]);
const skipped = join(skippedPrefix, 'node_modules', 'cxclear');
const skippedKey = createHash('sha256').update(skipped).digest('hex');
const skippedRecord = join(env.CXCLEAR_RUNTIME_DIR, 'installs', `${skippedKey}.json`);
await assert.rejects(access(skippedRecord));
assert.equal(JSON.parse(run([join(skipped, 'bin', 'cxclear.js'), 'schema']).stdout).version, manifest.version);
assert.equal(JSON.parse(await readFile(skippedRecord, 'utf8')).home, selected.home);

const npx = run([npmCli, ...npmArgs, 'exec', '--yes', '--offline', '--ignore-scripts', `--package=${tarball}`, '--', 'cxclear', 'schema']);
assert.equal(JSON.parse(npx.stdout).version, manifest.version);

const before = await readdir(env.CXCLEAR_RUNTIME_DIR).catch(() => []);
const npmSource = dirname(dirname(fileURLToPath(import.meta.url)));
assert.equal(await ensureJava({ root: npmSource, env }), selected.home);
assert.deepEqual(await readdir(env.CXCLEAR_RUNTIME_DIR).catch(() => []), before);
console.log(`npm install / npx smoke tests passed for cxclear ${manifest.version}: ${root}`);

#!/usr/bin/env node
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { access } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { installedJava, assertSupportedPlatform } from '../lib/java.js';

async function main() {
  assertSupportedPlatform();
  const root = dirname(dirname(fileURLToPath(import.meta.url)));
  await access(join(root, 'native', 'lib'));
  const javaHome = await installedJava({ root });
  const child = spawn(join(javaHome, 'bin', 'java.exe'), [
    '-Dfile.encoding=UTF-8', '-cp', join(root, 'native', 'lib', '*'),
    'dev.cxclear.cli.MainKt', ...process.argv.slice(2),
  ], {
    stdio: 'inherit',
    windowsHide: true,
  });
  let interrupted;
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => {
      interrupted = signal === 'SIGINT' ? 130 : 143;
      child.kill(signal);
    });
  }
  child.on('error', fail);
  child.on('exit', (code, signal) => {
    process.exitCode = interrupted ?? code ?? (signal === 'SIGINT' ? 130 : 1);
  });
}

function fail(error) {
  console.error(`cxclear: ${error.message}`);
  process.exitCode = 1;
}

main().catch(fail);

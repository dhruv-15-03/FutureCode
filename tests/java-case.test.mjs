import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { practiceCase } from '../public/interview-case.js';

const compiler = spawnSync('javac', ['-version'], { encoding: 'utf8', timeout: 10_000, windowsHide: true });
const available = compiler.status === 0;

test('trusted Java 17 fixtures reproduce the original and seeded failures and pass the atomic reference', {
  skip: available ? false : 'JDK 17+ is required to compile the repository-owned Java fixture.',
  timeout: 60_000
}, async () => {
  const directory = await mkdtemp(join(tmpdir(), 'readcode-curated-java-'));
  try {
    for (const [name, source, expectedStatus, expectedCount] of [
      ['original', practiceCase.files.find(file => file.name === 'RequestCounter.java').source, 1, 1],
      ['seeded-proposal', practiceCase.reference.proposedCounterSource, 1, 1],
      ['atomic-reference', practiceCase.reference.counterSource, 0, 2]
    ]) {
      await Promise.all(practiceCase.files.map(file => writeFile(join(directory, file.name), file.name === 'RequestCounter.java' ? source : file.source)));
      const compiled = spawnSync('javac', ['--release', '17', ...practiceCase.files.map(file => file.name)], {
        cwd: directory, encoding: 'utf8', timeout: 15_000, windowsHide: true
      });

      assert.equal(compiled.status, 0, `${name} compilation failed: ${compiled.stderr}`);
      const run = spawnSync('java', ['-cp', directory, 'RequestCounterTest'], {
        cwd: directory, encoding: 'utf8', timeout: 10_000, windowsHide: true
      });
      assert.equal(run.status, expectedStatus, `${name}: ${run.stdout}\n${run.stderr}`);
      assert.match(run.stdout, /success-and-failure expected=2 actual=2/);
      assert.match(run.stdout, /route-isolation expected=1 actual=1/);
      assert.match(run.stdout, new RegExp(`forced-overlap expected=2 actual=${expectedCount}`));
      if (expectedStatus === 0) assert.match(run.stdout, /PASS: all reference checks/);
      else assert.match(run.stderr, /AssertionError: forced-overlap/);
    }
  } finally {
    assert.ok(directory.startsWith(join(tmpdir(), 'readcode-curated-java-')));
    await rm(directory, { recursive: true, force: true });
  }
});

test('the repository-owned external harness rejects lost updates and accepts atomic and serialized repairs', {
  skip: available ? false : 'JDK 17+ is required for trusted development fixtures.',
  timeout: 60_000
}, async () => {
  const directory = await mkdtemp(join(tmpdir(), 'futurecode-harness-'));
  const original = practiceCase.files.find(file => file.name === 'RequestCounter.java').source;
  const controller = practiceCase.files.find(file => file.name === 'RequestController.java').source;
  const harness = await readFile(new URL('../src/main/resources/execution/Main.java.txt', import.meta.url), 'utf8');
  try {
    await writeFile(join(directory, 'Main.java'), harness);
    await writeFile(join(directory, 'RequestController.java'), controller);
    for (const [name, source, expectedStatus] of [
      ['original', original, 1],
      ['seeded', practiceCase.reference.proposedCounterSource, 1],
      ['atomic', practiceCase.reference.counterSource, 0],
      ['serialized', original.replace('public void record(', 'public synchronized void record('), 0]
    ]) {
      await writeFile(join(directory, 'RequestCounter.java'), source);
      const compiled = spawnSync('javac', ['--release', '13', 'Main.java', 'RequestController.java', 'RequestCounter.java'], {
        cwd: directory, encoding: 'utf8', timeout: 15_000, windowsHide: true
      });
      assert.equal(compiled.status, 0, `${name}: ${compiled.stderr}`);
      const run = spawnSync('java', ['-cp', directory, 'Main'], { cwd: directory, encoding: 'utf8', timeout: 10_000, windowsHide: true });
      assert.equal(run.status, expectedStatus, `${name}: ${run.stdout}\n${run.stderr}`);
      if (expectedStatus === 0) assert.equal(run.stdout.trim(), 'PASS: FutureCode counter regression v1');
      else assert.match(run.stderr, /controlled overlap/);
      if (name === 'serialized') assert.match(run.stderr, /overlap was not forced/);
    }
  } finally {
    assert.ok(directory.startsWith(join(tmpdir(), 'futurecode-harness-')));
    await rm(directory, { recursive: true, force: true });
  }
});

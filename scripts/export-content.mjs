import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { challenges } from '../public/challenges.js';
import { practiceCase, ARTIFACT_LIMITS, CASE_MAX_PINS, COACH_MAX_TURNS } from '../public/interview-case.js';
import { exerciseSchema } from '../public/exercise.js';
import { coachSchema } from '../public/interview-case.js';
import { sampleSource, sampleExercise, sampleCoachReply } from '../tests/fixtures.mjs';
import { createHash } from 'node:crypto';

const file = new URL('../src/main/resources/content/curated.json', import.meta.url);
const digest = value => createHash('sha256').update(value).digest('hex');
const browserSourceHashes = {};
for (const name of ['challenges.js', 'interview-case.js', 'exercise.js']) {
  browserSourceHashes[name] = digest((await readFile(new URL(`../public/${name}`, import.meta.url), 'utf8')).replace(/\r\n?/g, '\n'));
}
const content = JSON.stringify({
  challenges, practiceCase, exerciseSchema, coachSchema,
  limits: { artifacts: ARTIFACT_LIMITS, caseMaxPins: CASE_MAX_PINS, coachMaxTurns: COACH_MAX_TURNS },
  browserSourceHashes,
  versions: Object.fromEntries([
    ...challenges.map(challenge => [`drill:${challenge.id}`, digest(JSON.stringify(challenge))]),
    [`case:${practiceCase.id}`, digest(JSON.stringify(practiceCase.files))]
  ])
}, null, 2) + '\n';
if (process.argv.includes('--check')) {
  if (await readFile(file, 'utf8') !== content) throw new Error('Curated content changed. Run node scripts/export-content.mjs before packaging Java.');
  console.log('Java and browser curated content match.');
} else {
  await mkdir(new URL('../src/main/resources/content/', import.meta.url), { recursive: true });
  await writeFile(file, content);
  console.log('Exported repository-authored content for the Java backend; no uploaded source was read.');
}
const fixtureFile = new URL('../src/test/resources/samples.json', import.meta.url);
const fixtures = JSON.stringify({ source: sampleSource, exercise: sampleExercise(), coach: sampleCoachReply() }, null, 2) + '\n';
if (process.argv.includes('--check')) {
  if (await readFile(fixtureFile, 'utf8') !== fixtures) throw new Error('Shared test fixtures changed. Run node scripts/export-content.mjs.');
} else {
  await mkdir(new URL('../src/test/resources/', import.meta.url), { recursive: true });
  await writeFile(fixtureFile, fixtures);
}

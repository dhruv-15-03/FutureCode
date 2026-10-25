import { test } from 'node:test';
import assert from 'node:assert/strict';
import { challenges } from '../public/challenges.js';
import { problemIssue, publicProblemBody, blankProblem } from '../public/community.js';
import { exerciseProblem, sourceProblem } from '../public/exercise.js';
import { sampleSource, sampleExercise } from './fixtures.mjs';

const problem = () => ({
  ...blankProblem(), title: 'A shared counter', statement: 'What does concurrent access lose?',
  files: [{ name: 'Counter.java', source: 'int previous = count;\ncount = previous + 1;' }],
  options: ['An increment', 'A file', 'A thread', 'Nothing'], correctIndex: 0,
  rightsConfirmed: true,
  reference: {
    explanation: 'Both requests may read the same old count.', solution: 'Use an atomic update.',
    verification: 'Force both reads before the writes and assert two increments.',
    evidence: [{ file: 'Counter.java', line: 2, quote: 'count = previous + 1;' }]
  }
});

test('five curated browser references retain deterministic answers and real evidence lines', () => {
  assert.deepEqual(challenges.map(item => item.questions[0].correctIndex), [0, 2, 1, 2, 1]);
  assert.equal(new Set(challenges.map(item => item.kind)).size, 5);
  for (const item of challenges) {
    assert.equal(item.questions.length, 1);
    assert.equal(item.questions[0].options.length, 4);
    for (const line of item.questions[0].lines) assert.ok(line >= 1 && line <= item.code.split('\n').length);
  }
});

test('browser community contracts match drafts, written mode, exact citations, and public/private fields', () => {
  const body = problem();
  assert.equal(problemIssue(body, true), '');
  assert.equal(problemIssue({ ...blankProblem(), title: 'A private draft' }), '');
  for (const variant of [
    { ...body, role: 'admin' }, { ...body, rightsConfirmed: false },
    { ...body, correctIndex: 4 }, { ...body, statement: '\ud800' },
    { ...body, options: ['same', 'same', 'same', 'same'] },
    { ...body, files: [{ name: '../secret', source: 'x' }] },
    { ...body, reference: { ...body.reference, evidence: [{ file: 'Counter.java', line: 2, quote: 'invented();' }] } }
  ]) assert.ok(problemIssue(variant, true));
  assert.equal(problemIssue({ ...body, responseMode: 'written', options: [], correctIndex: null }, true), '');
  const exposed = publicProblemBody(body);
  for (const field of ['reference', 'correctIndex', 'rightsConfirmed']) assert.equal(exposed[field], undefined);
});

test('browser exercise validation anchors source but does not prove model reasoning', () => {
  for (const ending of ['\n', '\r\n', '\r']) assert.equal(exerciseProblem(sampleExercise(), sampleSource.replace(/\n/g, ending)), '');
  const fabricated = sampleExercise();
  fabricated.questions[0].evidence[0].quote = 'invented();';
  assert.match(exerciseProblem(fabricated, sampleSource), /do not match/);
  const uncertain = sampleExercise();
  uncertain.questions[0].correctIndex = 1;
  assert.equal(exerciseProblem(uncertain, sampleSource), '');
  assert.ok(sourceProblem('x\n'.repeat(400)));
  assert.ok(sourceProblem('x\0'));
});

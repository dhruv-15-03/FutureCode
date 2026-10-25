import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  practiceCase, createAttempt, traceSchedule, attemptProblem,
  evaluateAttempt, coachProblem, ARTIFACT_LIMITS, practiceRecord
} from '../public/interview-case.js';

test('the curated interview has five coherent source files and distinct flawed/reference patches', () => {
  assert.equal(practiceCase.files.length, 5);
  assert.equal(new Set(practiceCase.files.map(file => file.name)).size, 5);
  assert.ok(practiceCase.files.every(file => file.name.endsWith('.java') && file.source.trim()));
  assert.match(practiceCase.files[0].source, /finally/);
  assert.match(practiceCase.reference.proposedCounterSource, /counts\.replace/);
  assert.match(practiceCase.reference.counterSource, /counts\.merge/);
  assert.match(practiceCase.proposal.disclosure, /not a live model response/);
});

test('all six legal read-before-write schedules are modeled, with only overlapping reads losing an update', () => {
  const schedules = [
    ['A:read', 'A:write', 'B:read', 'B:write'],
    ['B:read', 'B:write', 'A:read', 'A:write'],
    ['A:read', 'B:read', 'A:write', 'B:write'],
    ['A:read', 'B:read', 'B:write', 'A:write'],
    ['B:read', 'A:read', 'A:write', 'B:write'],
    ['B:read', 'A:read', 'B:write', 'A:write']
  ];
  for (const [index, schedule] of schedules.entries()) {
    const result = traceSchedule(schedule);
    assert.equal(result.finished, true);
    assert.equal(result.completed, 2);
    assert.equal(result.value, index < 2 ? 2 : 1);
    assert.equal(result.lost, index >= 2);
  }
  assert.equal(traceSchedule([]).finished, false);
  assert.equal(traceSchedule(['A:read', 'B:read']).value, 0);
});

test('invalid trace operations are explicit errors, not a success-shaped simulation', () => {
  for (const schedule of [null, ['execute'], ['A:write'], ['A:read', 'A:read'], ['A:read', 'A:write', 'A:write'], Array(5).fill('A:read')]) {
    assert.throws(() => traceSchedule(schedule));
  }
});

test('stage completion requires cross-file evidence and written artifacts but does not grade prose or patch syntax', () => {
  const attempt = createAttempt();
  assert.equal(attemptProblem(attempt, 0).field, 'case-flow');
  attempt.flow = 'A recorded explanation.';
  assert.equal(attemptProblem(attempt, 0).field, 'case-questions');
  attempt.questions = 'Assume a shared counter.';
  attempt.pins.add('RequestCounter.java#15');
  assert.equal(attemptProblem(attempt, 0).field, 'case-source');
  attempt.pins.add('RequestController.java#12');
  assert.equal(attemptProblem(attempt, 0), null);
  assert.equal(attemptProblem(attempt, 1).field, 'case-trace');
  attempt.schedule = ['A:read', 'B:read', 'A:write', 'B:write'];
  attempt.aiDecision = 'accept';
  attempt.critique = 'This deliberately wrong judgment still records an attempt.';
  assert.equal(attemptProblem(attempt, 1), null);
  assert.equal(attemptProblem(attempt, 2).field, 'case-patch');
  Object.assign(attempt, {
    patch: 'unverified candidate text', patchReasoning: 'An explanation.',
    testPlan: 'A test plan.', verification: 'Compile and review independently.',
    invariant: 'container', expectedCount: '1'
  });
  assert.equal(attemptProblem(attempt, 2), null);
  const checks = evaluateAttempt(attempt);
  assert.deepEqual(checks.map(check => check.passed), [true, false, false, false]);
  attempt.aiDecision = 'reject'; attempt.invariant = 'atomic'; attempt.expectedCount = '2';
  assert.ok(evaluateAttempt(attempt).every(check => check.passed));
  assert.ok(Object.values(ARTIFACT_LIMITS).every(limit => limit > 0 && limit <= 6000));
});

test('coach replies allow clarification without citations and strictly anchor any claimed source quotes', () => {
  const valid = { reply: 'Which path records completion?', assumptions: [], citations: [] };
  assert.equal(coachProblem(valid), '');
  const citation = { file: 'RequestController.java', line: 12, quote: 'counter.record(route);' };
  assert.equal(coachProblem({ ...valid, citations: [citation] }), '');
  for (const invalid of [
    null, { ...valid, extra: true }, { ...valid, reply: '' }, { ...valid, reply: 'x'.repeat(2001) },
    { ...valid, assumptions: Array(4).fill('assumption') }, { ...valid, citations: [citation, citation] },
    { ...valid, citations: [{ ...citation, file: '../../.env' }] },
    { ...valid, citations: [{ ...citation, line: 0 }] },
    { ...valid, citations: [{ ...citation, line: 999 }] },
    { ...valid, citations: [{ ...citation, line: 1.5 }] },
    { ...valid, citations: [{ ...citation, quote: 'invented();' }] },
    { ...valid, citations: [{ ...citation, extra: true }] }
  ]) assert.ok(coachProblem(invalid));
});

test('practice record uses explicit fields, distinguishes seeded-only work and excludes credential properties', () => {
  const attempt = createAttempt();
  Object.assign(attempt, {
    startedAt: 1000, completedAt: 61000, key: 'never-export-credential', consent: true,
    schedule: ['A:read', 'B:read', 'A:write', 'B:write']
  });
  attempt.pins.add('RequestController.java#12');
  const record = practiceRecord(attempt, []);
  assert.equal(record.elapsedSeconds, 60);
  assert.equal(record.requiresHumanReview, true);
  assert.equal(record.coach.mode, 'seeded-proposal-only');
  assert.equal(record.evidence[0].quote, 'counter.record(route);');
  assert.ok(!JSON.stringify(record).includes('never-export-credential'));
  const coached = practiceRecord(attempt, [{
    prompt: 'Help me reason.', status: 'answered', key: 'never-export-credential',
    response: { reply: 'A proposed explanation.', assumptions: [], citations: [], key: 'never-export-credential' }
  }]);
  assert.equal(coached.coach.mode, 'provider-replies-unverified');
  assert.equal(coached.coach.requestsAttempted, 1);
  assert.equal(coached.coach.repliesReceived, 1);
  assert.ok(!JSON.stringify(coached).includes('never-export-credential'));
});

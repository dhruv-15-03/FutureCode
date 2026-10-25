export const sampleSource = `function total(prices) {
  let sum = 0;
  for (let i = 0; i < prices.length - 1; i++) {
    sum += prices[i];
  }
  return sum;
}`;

export function sampleExercise() {
  return {
    title: 'The skipped final price',
    assumptions: ['JavaScript semantics; prices is a finite array of numbers.'],
    questions: [
      {
        kind: 'trace', prompt: 'What does total([7]) return?',
        options: ['0', '7', 'undefined', 'It throws a TypeError'],
        correctIndex: 0,
        explanation: 'For one element, i < prices.length - 1 is false at the first check, so sum stays zero.',
        evidence: [{ line: 3, quote: 'for (let i = 0; i < prices.length - 1; i++) {' }, { line: 6, quote: 'return sum;' }],
        verification: 'Proposed check: total([7]) returns 0 in this version; a complete sum should return 7. This has not been run here.'
      },
      {
        kind: 'risk', prompt: 'Which change includes the final array element without reading beyond the array?',
        options: ['Use i <= prices.length', 'Start i at 1', 'Use i < prices.length', 'Remove the increment'],
        correctIndex: 2,
        explanation: 'The valid indexes end at length - 1, so i < prices.length includes every valid index.',
        evidence: [{ line: 3, quote: 'for (let i = 0; i < prices.length - 1; i++) {' }, { line: 4, quote: 'sum += prices[i];' }],
        verification: 'Proposed check after the change: total([2, 7]) should be 9, and total([]) should still be 0.'
      },
      {
        kind: 'test', prompt: 'Which smallest test exposes the skipped final item?',
        options: ['total([]) === 0', 'total([0]) === 0', 'total([0, 0]) === 0', 'total([7]) === 7'],
        correctIndex: 3,
        explanation: 'A nonzero singleton makes the missing last item observable; empty and zero-only arrays mask it.',
        evidence: [{ line: 3, quote: 'for (let i = 0; i < prices.length - 1; i++) {' }],
        verification: 'Proposed regression: expect total([7]) to equal 7. It should fail for the shown loop and pass with i < prices.length.'
      }
    ]
  };
}

export function sampleCoachReply() {
  return {
    reply: 'What happens when the conditional replacement returns false? Use the controlled overlap to check whether every completed request still contributes an increment.',
    assumptions: ['Both services share the same RequestCounter instance.'],
    citations: [
      { file: 'RequestCounter.java', line: 16, quote: 'int previous = counts.getOrDefault(route, 0);' },
      { file: 'RequestCounter.java', line: 17, quote: 'counts.put(route, previous + 1);' }
    ]
  };
}

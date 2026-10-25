export const MAX_SOURCE = 12_000;
export const MAX_SOURCE_LINES = 400;
export const MAX_EVIDENCE = 3;
export const QUESTION_LABELS = Object.freeze({
  trace: 'Trace behavior',
  risk: 'Challenge an assumption',
  test: 'Choose a regression test'
});

export const splitSourceLines = source => source.split(/\r\n?|\n/);
const text = (value, min, max) => typeof value === 'string' && value.trim().length >= min && value.length <= max;
const exactKeys = (value, keys) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));

export function sourceProblem(source) {
  if (!text(source, 1, MAX_SOURCE) || /[\x00-\x08\x0b\x0c\x0e-\x1f]/.test(source)) {
    return 'Add a plain-text snippet with 1-12,000 characters and no binary content.';
  }
  if (splitSourceLines(source).length > MAX_SOURCE_LINES) {
    return 'Use no more than 400 source lines. Choose a smaller excerpt for a focused round.';
  }
  return '';
}

export const exerciseSchema = {
  type: 'object', additionalProperties: false, required: ['title', 'assumptions', 'questions'],
  properties: {
    title: { type: 'string' },
    assumptions: { type: 'array', maxItems: 4, items: { type: 'string' } },
    questions: {
      type: 'array', minItems: 3, maxItems: 3,
      items: {
        type: 'object', additionalProperties: false,
        required: ['kind', 'prompt', 'options', 'correctIndex', 'explanation', 'evidence', 'verification'],
        properties: {
          kind: { type: 'string', enum: Object.keys(QUESTION_LABELS) },
          prompt: { type: 'string' },
          options: { type: 'array', minItems: 4, maxItems: 4, items: { type: 'string' } },
          correctIndex: { type: 'integer', minimum: 0, maximum: 3 },
          explanation: { type: 'string' },
          evidence: {
            type: 'array', minItems: 1, maxItems: MAX_EVIDENCE,
            items: {
              type: 'object', additionalProperties: false, required: ['line', 'quote'],
              properties: { line: { type: 'integer', minimum: 1 }, quote: { type: 'string' } }
            }
          },
          verification: { type: 'string' }
        }
      }
    }
  }
};

export function exerciseProblem(data, source) {
  const invalid = 'The provider returned an invalid exercise. Nothing was loaded; try again.';
  if (!exactKeys(data, ['title', 'assumptions', 'questions']) || !text(data.title, 1, 120) ||
      !Array.isArray(data.assumptions) || data.assumptions.length > 4 ||
      !data.assumptions.every(item => text(item, 1, 500)) ||
      !Array.isArray(data.questions) || data.questions.length !== 3 ||
      typeof source !== 'string') return invalid;

  const lines = splitSourceLines(source);
  const kinds = new Set();
  const prompts = new Set();
  for (const q of data.questions) {
    if (!exactKeys(q, ['kind', 'prompt', 'options', 'correctIndex', 'explanation', 'evidence', 'verification']) ||
        !Object.hasOwn(QUESTION_LABELS, q.kind) || kinds.has(q.kind) ||
        !text(q.prompt, 1, 1000) || !text(q.explanation, 1, 2400) || !text(q.verification, 1, 1200) ||
        !Array.isArray(q.options) || q.options.length !== 4 ||
        !q.options.every(option => text(option, 1, 700)) ||
        new Set(q.options.map(option => option.trim())).size !== 4 ||
        !Number.isInteger(q.correctIndex) || q.correctIndex < 0 || q.correctIndex > 3 ||
        !Array.isArray(q.evidence) || q.evidence.length < 1 || q.evidence.length > MAX_EVIDENCE) return invalid;

    const cited = new Set();
    for (const item of q.evidence) {
      if (!exactKeys(item, ['line', 'quote']) || !Number.isInteger(item.line) ||
          item.line < 1 || item.line > lines.length || cited.has(item.line) ||
          !text(item.quote, 1, MAX_SOURCE) || item.quote !== lines[item.line - 1].trim()) {
        return 'The provider cited lines or quotes that do not match this snippet. Nothing was loaded; try again.';
      }
      cited.add(item.line);
    }
    kinds.add(q.kind);
    prompts.add(q.prompt.trim());
  }
  return prompts.size === 3 ? '' : invalid;
}

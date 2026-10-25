import { splitSourceLines } from './exercise.js';

export const PROBLEM_KINDS = {
  reading: 'Read & reason', debugging: 'Diagnose a defect', 'test-design': 'Design a test',
  'ai-review': 'Review an AI proposal', 'case-study': 'Engineering case'
};
export const DIFFICULTIES = {
  introductory: 'Introductory', intermediate: 'Intermediate', advanced: 'Advanced'
};
export const MAX_PROBLEM_BYTES = 48_000;
export const isUuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
export const exactKeys = (value, keys) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));
export const boundedString = (value, max, required = false) => typeof value === 'string' &&
  value.length <= max && value.isWellFormed() && (!required || value.trim().length > 0) && !/[\x00-\x08\x0b\x0c\x0e-\x1f]/.test(value);

export function blankProblem() {
  return {
    title: '', statement: '', language: 'Java', difficulty: 'intermediate', kind: 'reading',
    files: [{ name: 'Snippet.java', source: '' }], responseMode: 'choice',
    options: ['', '', '', ''], correctIndex: null,
    reference: { explanation: '', solution: '', verification: '', evidence: [] },
    rightsConfirmed: false
  };
}

export function problemIssue(value, complete = false) {
  if (!exactKeys(value, ['title', 'statement', 'language', 'difficulty', 'kind', 'files', 'responseMode', 'options', 'correctIndex', 'reference', 'rightsConfirmed'])) {
    return 'The problem has an invalid structure. Reload the editor and retry.';
  }
  if (!boundedString(value.title, 120, true) || value.title.trim().length < 3) return 'Give the problem a title of 3-120 characters.';
  if (!boundedString(value.statement, 5000, complete)) return 'Write the problem statement (up to 5,000 characters).';
  if (!boundedString(value.language, 32, true) || !Object.hasOwn(DIFFICULTIES, value.difficulty) || !Object.hasOwn(PROBLEM_KINDS, value.kind)) return 'Choose a language, difficulty, and problem type.';
  if (!Array.isArray(value.files) || value.files.length < 1 || value.files.length > 6) return 'Include 1-6 source files.';
  const names = new Set();
  let sourceBytes = 0;
  for (const file of value.files) {
    if (!exactKeys(file, ['name', 'source']) || typeof file.name !== 'string' ||
        !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,79}$/.test(file.name) || file.name.includes('..') ||
        names.has(file.name.toLowerCase()) || !boundedString(file.source, 12000, complete) ||
        splitSourceLines(file.source).length > 400) return 'Use unique plain file names, at most 12,000 characters and 400 lines per file, with no binary content.';
    names.add(file.name.toLowerCase());
    sourceBytes += new TextEncoder().encode(file.source).length;
  }
  if (sourceBytes > 24000) return 'Source files together exceed the 24 KB UTF-8 limit. Use a smaller excerpt.';
  if (!['choice', 'written'].includes(value.responseMode) || !Array.isArray(value.options)) return 'Choose a multiple-choice or written response.';
  if (value.responseMode === 'choice') {
    if (value.options.length !== 4 || !value.options.every(option => boundedString(option, 500, complete))) return 'Multiple-choice problems need four options, up to 500 characters each.';
    if (complete && (new Set(value.options.map(option => option.trim())).size !== 4 ||
        !Number.isInteger(value.correctIndex) || value.correctIndex < 0 || value.correctIndex > 3)) return 'Provide four distinct options and select your proposed reference answer.';
    if (!complete && value.correctIndex !== null && (!Number.isInteger(value.correctIndex) || value.correctIndex < 0 || value.correctIndex > 3)) return 'The reference answer index is invalid.';
  } else if (value.options.length !== 0 || value.correctIndex !== null) return 'Written responses do not have multiple-choice options or an automatic score.';
  const reference = value.reference;
  if (!exactKeys(reference, ['explanation', 'solution', 'verification', 'evidence']) ||
      !boundedString(reference.explanation, 4000, complete) || !boundedString(reference.solution, 6000) ||
      !boundedString(reference.verification, 2000, complete) || !Array.isArray(reference.evidence) ||
      reference.evidence.length > 6 || (complete && reference.evidence.length === 0)) {
    return 'Include a reference explanation, a verification suggestion, and 1-6 evidence citations before submitting for review. A code solution is optional.';
  }
  const cited = new Set();
  for (const item of reference.evidence) {
    if (!exactKeys(item, ['file', 'line', 'quote'])) return 'An evidence citation has an invalid structure.';
    const file = value.files.find(source => source.name === item.file);
    const lines = file ? splitSourceLines(file.source) : [];
    const key = `${item.file}#${item.line}`;
    if (!Number.isInteger(item.line) || item.line < 1 || item.line > lines.length ||
        !boundedString(item.quote, 12000, true) || item.quote !== lines[item.line - 1].trim() || cited.has(key)) {
      return 'Each evidence quote must match its exact, nonblank source line. Remove or reselect stale citations after editing source.';
    }
    cited.add(key);
  }
  if (typeof value.rightsConfirmed !== 'boolean' || (complete && !value.rightsConfirmed)) return 'Confirm you have permission to publish the source and solution and have removed secrets.';
  if (new TextEncoder().encode(JSON.stringify(value)).length > MAX_PROBLEM_BYTES) return 'The complete problem exceeds 48 KB. Shorten its source or reference solution.';
  return '';
}

export function publicProblemBody(body) {
  return {
    title: body.title, statement: body.statement, language: body.language,
    difficulty: body.difficulty, kind: body.kind,
    files: body.files.map(file => ({ name: file.name, source: file.source })),
    responseMode: body.responseMode, options: [...body.options]
  };
}

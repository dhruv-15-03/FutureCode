import { challenges } from './challenges.js';
import { MAX_EVIDENCE, QUESTION_LABELS, exerciseProblem, sourceProblem, splitSourceLines } from './exercise.js';
import { element, renderSource, answerOptions, providerName, providerRequest } from './ui.js';
import { mountInterview, setInterviewVisible } from './interview.js';
import { apiRequest, signedIn, requireAccount, refreshSession, onSessionChange, sessionInfo } from './account.js';
import { mountPlatform, setGuide } from './platform.js';
import { mountCommunityTools } from './community-tools.js';
import { closeNavigation } from './shell.js';
import { clearExternalKeys } from './integrations.js';

const $ = id => document.getElementById(id);
const state = { current: 0, question: 0, answers: new Map(), solved: new Set(), generating: false, round: null };
let generationController;
let uploadVersion = 0;
const letters = ['A', 'B', 'C', 'D'];
let platformUI;
let activeRoute = 'catalog';
let checkingAnswer = false;
let progressVersion = 0;
let hasActivated = false;

function renderLibrary() {
  $('challenge-list').replaceChildren();
  challenges.forEach((challenge, i) => {
    const button = element('button', 'challenge-item');
    button.setAttribute('aria-current', String(state.current === i));
    button.append(element('span', 'challenge-number', state.solved.has(challenge.id) ? '✓' : String(i + 1).padStart(2, '0')));
    const copy = element('span');
    copy.append(element('strong', '', challenge.title), element('small', '', challenge.kind));
    button.append(copy);
    button.addEventListener('click', () => {
      go(`library/${challenge.id}`);
    });
    $('challenge-list').append(button);
  });
}

function render() {
  renderLibrary();
  const c = challenges[state.current];
  const q = c.questions[state.question];
  const saved = state.answers.get(c.id);
  $('challenge-meta').replaceChildren(document.createTextNode(c.kind), element('span', '', c.level));
  $('challenge-title').textContent = c.title;
  $('challenge-time').textContent = c.time;
  $('filename').textContent = c.file;
  $('question-count').textContent = `Question ${state.question + 1} of ${c.questions.length}`;
  $('question-prompt').textContent = q.prompt;
  $('feedback').hidden = true;
  $('answer-fieldset').disabled = !signedIn();
  $('check-answer').disabled = sessionInfo().status === 'loading' || checkingAnswer || (signedIn() && saved === undefined);
  $('check-answer').textContent = sessionInfo().status === 'loading' ? 'Checking session…' : !signedIn() ? 'Sign in to submit' : checkingAnswer ? 'Saving submission…' : 'Submit my reasoning';
  $('next-challenge').hidden = true;
  renderSource($('source'), c.code);
  answerOptions($('answer-options'), q, 'curated-answer', () => {
    $('check-answer').disabled = !signedIn() || checkingAnswer;
    $('feedback').hidden = true;
    $('next-challenge').hidden = true;
    renderSource($('source'), c.code);
  }, saved);
  $('solved-count').textContent = `${state.solved.size} / ${challenges.length}`;
  mountCommunityTools($('drill-community'), `drill:${c.id}`);
}

$('answer-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (!requireAccount() || checkingAnswer) return;
  const checked = $('answer-options').querySelector('input:checked');
  if (!checked) {
    $('feedback').replaceChildren(element('p', 'error', 'Choose an answer before submitting.')); $('feedback').hidden = false; return;
  }
  const c = challenges[state.current], q = c.questions[state.question];
  const answer = Number(checked.value), owner = sessionInfo().user.id;
  state.answers.set(c.id, answer);
  checkingAnswer = true; progressVersion++;
  let submitted = false;
  $('check-answer').disabled = true; $('check-answer').textContent = 'Saving submission…';
  try {
    const result = await apiRequest('/api/attempts', { method: 'POST', data: { problemKey: `drill:${c.id}`, payload: { answer } } });
    if (sessionInfo().user?.id !== owner) return;
    submitted = true;
    const correct = result.feedback.matched;
    if (correct) state.solved.add(c.id);
    window.dispatchEvent(new CustomEvent('futurecode-attempt-saved'));
    $('solved-count').textContent = `${state.solved.size} / ${challenges.length}`; renderLibrary();
    if (activeRoute !== 'library' || challenges[state.current].id !== c.id) return;
    $('feedback').className = correct ? 'correct' : '';
    $('feedback').replaceChildren(
      element('strong', '', correct ? 'Exactly. Here is the evidence.' : `Not quite. The answer is ${letters[result.feedback.correctIndex]}.`),
      element('p', '', result.feedback.explanation), element('small', '', 'Saved to your account · curated reference check')
    );
    $('feedback').hidden = false; renderSource($('source'), c.code, result.feedback.lines);
    $('check-answer').textContent = correct ? 'Reasoning checked' : 'Select another answer to retry';
    $('next-challenge').hidden = false;
    $('next-challenge').textContent = state.current === challenges.length - 1 ? 'Back to first challenge →' : 'Next challenge →';
  } catch (error) {
    if (activeRoute === 'library' && challenges[state.current].id === c.id) {
      $('feedback').replaceChildren(element('p', 'error', error.message)); $('feedback').hidden = false;
    }
  } finally {
    checkingAnswer = false;
    if (activeRoute === 'library' && challenges[state.current].id === c.id) $('check-answer').disabled = submitted || !signedIn();
  }
});
$('next-challenge').addEventListener('click', () => {
  go(`library/${challenges[(state.current + 1) % challenges.length].id}`);
});

const views = {
  catalog: ['catalog-view', 'catalog-title', 'The problemset', 'Practice collection'],
  library: ['library', 'workspace', 'Snippet drills', 'Curated references'],
  interview: ['interview-workspace', 'case-title', 'The missing requests', 'Engineering case'],
  private: ['private-workspace', 'private-workspace', 'Your private snippet', 'No automatic persistence'],
  account: ['account-view', 'account-title', 'Your account', 'Profile & privacy'],
  attempts: ['attempts-view', 'attempts-title', 'Saved work', 'Private to your account'],
  bookmarks: ['bookmarks-view', 'bookmarks-title', 'Saved entries', 'Private reading list'],
  progress: ['progress-view', 'progress-title', 'Your progress', 'Retained reference checks'],
  leaderboard: ['leaderboard-view', 'leaderboard-title', 'Practice board', 'Opt-in learning activity'],
  execution: ['execution-view', 'execution-title', 'External tools', 'Separate consent and provider billing'],
  contribute: ['contribute-view', 'contribute-title', 'Contributions', 'Drafts & reference solutions'],
  review: ['review-view', 'review-title', 'The review desk', 'Moderator review'],
  problem: ['problem-view', 'problem-content', 'Community problem', 'Moderator-reviewed reference']
};
const anchors = new Set(Object.values(views).map(([, anchor]) => anchor));
function go(destination) {
  if (location.hash.slice(1) === destination) activateRoute(); else location.hash = destination;
}
function activateRoute() {
  const initial = !hasActivated;
  let path = location.hash.slice(1).split('?')[0] || 'catalog';
  if (anchors.has(path)) {
    if (hasActivated && Object.hasOwn(views, activeRoute) && views[activeRoute][1] === path) return;
    path = Object.keys(views).find(route => views[route][1] === path) || 'catalog';
  }
  const [rawRoute, id] = path.split('/');
  const route = Object.hasOwn(views, rawRoute) ? rawRoute : 'catalog';
  if (activeRoute === 'private' && route !== 'private') forgetKey();
  if (activeRoute === 'execution' && route !== 'execution') clearExternalKeys();
  hasActivated = true;
  activeRoute = route;
  setGuide(views[route][2], views[route][3]);
  document.body.dataset.view = route;
  for (const [key, [view]] of Object.entries(views)) $(view).hidden = key !== route;
  for (const button of document.querySelectorAll('[data-route]')) button.setAttribute('aria-pressed', String(button.dataset.route === route));
  setInterviewVisible(route === 'interview');
  document.querySelector('.skip').setAttribute('href', `#${views[route][1]}`);
  closeNavigation();
  if (route === 'library') {
    if (id) {
      const found = challenges.findIndex(challenge => challenge.id === id);
      if (found >= 0) state.current = found;
      else { $('platform-status').textContent = 'That curated entry was not found. Choose from the contents.'; go('catalog'); return; }
    }
    state.question = 0; render(); refreshProgress();
    if (id) $('workspace').focus({ preventScroll: true });
    setGuide(challenges[state.current].title, 'Curated reference');
  }
  $('case-community').hidden = route !== 'interview';
  if (route === 'interview') mountCommunityTools($('case-community'), 'case:missing-requests-v1');
  platformUI?.show(route, id, new URLSearchParams(location.hash.split('?')[1] || '').get('notice'));
  if (!initial && !(route === 'library' && id)) $(views[route][1]).focus({ preventScroll: false });
}
for (const button of document.querySelectorAll('[data-route]')) button.addEventListener('click', () => go(button.dataset.route));
window.addEventListener('hashchange', activateRoute);
$('privacy-toggle').addEventListener('click', () => {
  $('privacy').hidden = !$('privacy').hidden;
  $('privacy-toggle').setAttribute('aria-expanded', String(!$('privacy').hidden));
});

function status(message, isError = false) {
  $('ai-status').textContent = message;
  $('ai-status').className = isError ? 'error' : '';
}
function updateCount() { $('char-count').textContent = `${$('snippet').value.length.toLocaleString('en-US')} / 12,000`; }
function clearAIRounds() {
  state.round = null;
  $('ai-result').hidden = true;
  $('ai-exercise-host').replaceChildren();
  $('ai-title').textContent = '';
}
function snippetChanged() {
  const needsReview = $('provider-consent').checked || state.generating || state.round !== null;
  generationController?.abort('source-changed');
  $('provider-consent').checked = false;
  clearAIRounds();
  updateCount();
  if (needsReview) status('Snippet changed. Previous AI rounds were cleared; review the new source and consent again.');
}
$('snippet').addEventListener('input', () => { uploadVersion++; snippetChanged(); });
function validSource() {
  const problem = sourceProblem($('snippet').value);
  if (problem) {
    status(problem, true);
    $('snippet').focus();
    return false;
  }
  return true;
}
$('source-file').addEventListener('change', async () => {
  const file = $('source-file').files[0];
  if (!file) return;
  const version = ++uploadVersion;
  $('upload-error').hidden = true;
  try {
    if (!/\.(txt|java|js|ts|py|go|rs|c|cpp|cs|json|sql|kt|rb|html|css)$/i.test(file.name)) throw new Error('Choose a supported plain-text source file, not an archive or executable.');
    if (file.size > 12000) throw new Error('File exceeds 12 KB. Paste a smaller excerpt.');
    const content = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer());
    if (content.includes('\0') || /[\x01-\x08\x0b\x0c\x0e-\x1f]/.test(content)) throw new Error('Binary content is not supported. Choose a UTF-8 source file.');
    if (!content.trim()) throw new Error('The file is empty. Choose a source snippet.');
    const problem = sourceProblem(content);
    if (problem) throw new Error(problem);
    if (version !== uploadVersion) return;
    $('snippet').value = content; snippetChanged();
    status('File loaded locally. Provider consent was reset; no request was made for this file.');
  } catch (error) {
    if (version !== uploadVersion) return;
    $('upload-error').textContent = error instanceof TypeError ? 'File is not valid UTF-8 text.' : error.message;
    $('upload-error').hidden = false;
  } finally { if ($('source-file').files[0] === file) $('source-file').value = ''; }
});
$('worksheet-button').addEventListener('click', () => {
  if (!requireAccount()) return;
  if (!validSource()) return;
  $('worksheet').hidden = false;
  status('Reading worksheet ready. No provider request was made.');
  $('reflection-input').focus();
});
function forgetKey() {
  generationController?.abort('key-forgotten');
  $('api-key').value = ''; $('provider-consent').checked = false;
}
$('forget-key').addEventListener('click', () => {
  forgetKey(); status('Key and consent cleared. Any pending browser request was cancelled; data already sent cannot be recalled.');
});
$('ai-provider').addEventListener('change', () => {
  forgetKey(); clearAIRounds();
  $('private-provider-name').textContent = providerName($('ai-provider').value);
  status('Provider changed. Key, consent and prior AI rounds were cleared; your source and reflection notes remain.');
});
$('api-key').addEventListener('input', () => {
  const wasGenerating = state.generating;
  generationController?.abort('key-changed');
  $('provider-consent').checked = false;
  if (wasGenerating) status('Key changed. The pending browser request was cancelled; consent again before retrying.');
});
$('clear-private').addEventListener('click', () => {
  generationController?.abort('cleared');
  uploadVersion++;
  forgetKey(); $('snippet').value = ''; $('source-file').value = ''; updateCount();
  ['reflection-input', 'reflection-risk', 'reflection-test'].forEach(id => { $(id).value = ''; });
  $('worksheet').hidden = true; clearAIRounds();
  $('upload-error').hidden = true; status('Snippet, reflections, generated questions, key, and consent cleared.');
});

const newResponse = () => ({ answer: null, lines: new Set(), reasoning: '', reviewed: false, disputed: false });
const lineList = lines => [...lines].sort((a, b) => a - b).map(line => `L${line}`).join(', ');

function renderAIRound() {
  const round = state.round;
  const { exercise, source, current, responses } = round;
  const q = exercise.questions[current], response = responses[current];
  const host = $('ai-exercise-host'); host.replaceChildren();
  const reviewed = responses.filter(item => item.reviewed).length;
  const disputed = responses.filter(item => item.disputed).length;
  host.append(element('p', 'round-progress', `Round ${current + 1} of 3 · ${QUESTION_LABELS[q.kind]} · ${reviewed}/3 reviewed · ${disputed} flagged`));

  const assumptions = element('details', 'assumptions');
  assumptions.open = true;
  assumptions.append(element('summary', '', 'Working assumptions from the AI'));
  if (exercise.assumptions.length) {
    const list = element('ul');
    exercise.assumptions.forEach(item => list.append(element('li', '', item)));
    assumptions.append(list);
  } else {
    assumptions.append(element('p', '', 'The AI declared no assumptions. Missing context can still make its answers unreliable.'));
  }
  host.append(assumptions);

  const grid = element('div', 'evidence-grid');
  const sourcePanel = element('section', 'evidence-panel');
  const sourceFieldset = element('fieldset');
  sourceFieldset.append(element('legend', '', 'Cite the source'));
  sourcePanel.append(element('p', 'evidence-help', 'Select 1 to 3 lines that support your answer. Your evidence is not automatically graded.'));
  const selectionStatus = element('p', 'evidence-count', `${response.lines.size} of ${MAX_EVIDENCE} lines selected`);
  selectionStatus.setAttribute('role', 'status');
  const pre = element('pre', 'ai-source evidence-source'); pre.tabIndex = 0;
  pre.setAttribute('aria-label', 'Source snapshot; use the checkboxes to cite evidence lines');
  const check = element('button', 'primary', response.reviewed ? 'Reference revealed' : 'Compare with AI reference');
  check.type = 'submit';
  const updateCheck = () => { check.disabled = response.reviewed || response.answer === null || response.lines.size === 0; };
  const cited = new Set(q.evidence.map(item => item.line));
  splitSourceLines(source).forEach((line, index) => {
    const number = index + 1;
    const row = element('label', `evidence-line${response.lines.has(number) ? ' selected' : ''}${response.reviewed && cited.has(number) ? ' highlight' : ''}`);
    const input = document.createElement('input');
    input.type = 'checkbox'; input.value = String(number); input.name = 'evidence-line';
    input.checked = response.lines.has(number); input.disabled = response.reviewed || !line.trim();
    input.setAttribute('aria-label', `Cite line ${number}: ${line.trim().slice(0, 100) || 'blank line'}`);
    input.addEventListener('change', () => {
      if (input.checked && response.lines.size === MAX_EVIDENCE) {
        input.checked = false;
        selectionStatus.textContent = 'Cite up to 3 lines. Deselect one before adding another.';
        return;
      }
      if (input.checked) response.lines.add(number); else response.lines.delete(number);
      row.classList.toggle('selected', input.checked);
      selectionStatus.textContent = `${response.lines.size} of ${MAX_EVIDENCE} lines selected`;
      updateCheck();
    });
    const lineNumber = element('span', 'line-number', String(number).padStart(2, '0'));
    lineNumber.setAttribute('aria-hidden', 'true');
    row.append(input, lineNumber, element('code', '', line || ' '));
    pre.append(row);
  });
  sourceFieldset.append(pre);
  sourcePanel.append(sourceFieldset, selectionStatus, element('p', 'evidence-key', response.reviewed ?
    'Checked boxes: your evidence. Blue rule: AI-cited lines. Other evidence may also be valid.' :
    'The snapshot stays fixed for all three rounds. AI citations appear only after you commit.'));

  const reasonPanel = element('section', 'ai-reason-panel');
  const form = element('form', 'ai-question');
  const fieldset = element('fieldset');
  fieldset.disabled = response.reviewed;
  fieldset.append(element('legend', '', q.prompt));
  const options = element('div', 'ai-options');
  answerOptions(options, q, 'ai-answer', answer => { response.answer = answer; updateCheck(); }, response.answer);
  fieldset.append(options);
  const reasoningLabel = element('label', 'reasoning-label', 'Why these lines? (optional, not AI-graded)');
  const reasoning = document.createElement('textarea');
  reasoning.id = 'ai-reasoning'; reasoning.maxLength = 800; reasoning.value = response.reasoning;
  reasoning.placeholder = 'Explain the path, boundary, or assumption you traced.';
  reasoning.disabled = response.reviewed;
  reasoning.addEventListener('input', () => { response.reasoning = reasoning.value; });
  reasoningLabel.htmlFor = reasoning.id;
  const help = element('p', 'evidence-help', response.reviewed ?
    'Your answer, citations, and note are locked. Retry this round to change them.' :
    'Choose an answer and cite at least one source line before revealing the reference.');
  check.setAttribute('aria-describedby', 'ai-answer-help');
  help.id = 'ai-answer-help';
  updateCheck();
  form.append(fieldset, reasoningLabel, reasoning, help, check);
  form.addEventListener('submit', event => {
    event.preventDefault();
    if (response.reviewed || response.answer === null || !response.lines.size) return;
    response.reviewed = true;
    renderAIRound();
    $('ai-feedback').focus({ preventScroll: false });
  });
  reasonPanel.append(form);

  if (response.reviewed) {
    const feedback = element('div', 'ai-feedback');
    feedback.id = 'ai-feedback'; feedback.tabIndex = -1; feedback.setAttribute('role', 'status');
    feedback.append(
      element('strong', '', response.answer === q.correctIndex ? 'Your choice matches the AI reference. It is not verified.' : `Your choice differs. The AI proposes ${letters[q.correctIndex]}. It is not verified.`),
      element('p', '', q.explanation),
      element('p', '', `Your evidence: ${lineList(response.lines)}. Citation locations were checked, not the correctness of the reasoning.`),
      element('h4', '', 'AI-cited source')
    );
    const citations = element('ul', 'citation-list');
    q.evidence.forEach(item => {
      const citation = element('li');
      citation.append(element('span', '', `L${item.line}`), element('code', '', item.quote));
      citations.append(citation);
    });
    feedback.append(citations, element('h4', '', 'Suggested check: not executed'), element('p', '', q.verification));
    reasonPanel.append(feedback);
    const actions = element('div', 'review-actions');
    const dispute = element('button', 'quiet', response.disputed ? 'Remove questionable flag' : 'Flag this AI answer as questionable');
    dispute.type = 'button'; dispute.setAttribute('aria-pressed', String(response.disputed));
    const disputeStatus = element('p', 'evidence-help', response.disputed ?
      'Flagged in this tab only. No report was sent. This round is not a verified result.' :
      'Disagree with the answer or assumptions? Flag it and verify independently; the AI is not the referee.');
    disputeStatus.setAttribute('role', 'status');
    dispute.addEventListener('click', () => {
      response.disputed = !response.disputed;
      renderAIRound();
      $('ai-dispute').focus({ preventScroll: true });
    });
    dispute.id = 'ai-dispute';
    const retry = element('button', 'quiet', 'Retry this round');
    retry.type = 'button';
    retry.addEventListener('click', () => {
      responses[current] = newResponse(); renderAIRound();
      $('ai-exercise-host').querySelector('.ai-options input').focus();
    });
    actions.append(dispute, retry);
    reasonPanel.append(actions, disputeStatus);
  }
  grid.append(sourcePanel, reasonPanel); host.append(grid);
  const navigation = element('div', 'round-navigation');
  const previous = element('button', 'secondary', 'Previous round');
  previous.type = 'button'; previous.disabled = current === 0;
  previous.addEventListener('click', () => { round.current--; renderAIRound(); $('ai-title').focus(); });
  const next = element('button', 'secondary', 'Next round');
  next.type = 'button'; next.disabled = !response.reviewed; next.hidden = current === 2;
  next.addEventListener('click', () => { round.current++; renderAIRound(); $('ai-title').focus(); });
  navigation.append(previous, next); host.append(navigation);
  if (reviewed === 3) {
    host.append(element('p', 'round-complete', `Three rounds reviewed; ${disputed} flagged as questionable. This is a reading record, not a verified score. No answers were sent for grading or durably stored.`));
  }
}

function renderAI(exercise, source) {
  state.round = { exercise, source, current: 0, responses: exercise.questions.map(newResponse) };
  $('ai-title').textContent = exercise.title;
  renderAIRound();
  $('ai-result').hidden = false;
  $('ai-title').focus();
}

$('generate-button').addEventListener('click', async () => {
  if (!requireAccount()) return;
  if (state.generating || !validSource()) return;
  if ($('api-key').value.trim().length < 10) { status(`Add your ${providerName($('ai-provider').value)} key to generate questions. Local worksheets need no key.`, true); $('api-key').focus(); return; }
  if (!$('provider-consent').checked) { status('Explicit provider consent is required before sending code.', true); $('provider-consent').focus(); return; }
  state.generating = true;
  $('generate-button').disabled = true;
  $('generate-button').textContent = 'Writing evidence rounds…';
  clearAIRounds();
  status(`Sending this snippet to ${providerName($('ai-provider').value)} once. Preparing three evidence rounds may take up to 25 seconds.`);
  generationController = new AbortController();
  const controller = generationController;
  const source = $('snippet').value;
  const timeout = setTimeout(() => controller.abort('timeout'), 30_000);
  try {
    const data = await apiRequest('/api/exercise', {
      method: 'POST', signal: controller.signal,
      data: { source, key: $('api-key').value.trim(), consent: true, ...providerRequest($('ai-provider').value) }
    });
    if (controller.signal.aborted) return;
    const problem = exerciseProblem(data, source);
    if (problem) throw new Error(problem);
    renderAI(data, source);
    status('Three evidence rounds ready. Source citations match the snapshot; AI answers and assumptions remain unverified.');
  } catch (error) {
    if (!controller.signal.aborted) status(error instanceof SyntaxError ? 'The local server returned unreadable data. Restart it and retry.' : error.message, true);
    else if (controller.signal.reason === 'timeout') status('Generation timed out after 30 seconds. Try a shorter snippet.', true);
  } finally {
    clearTimeout(timeout);
    state.generating = false; generationController = undefined;
    $('generate-button').disabled = !signedIn();
    $('generate-button').textContent = 'Generate evidence rounds';
  }
});
window.addEventListener('pagehide', () => { forgetKey(); generationController?.abort(); });
$('api-key').value = '';
$('provider-consent').checked = false;
render();
mountInterview();
platformUI = mountPlatform({ navigate: go });
function updatePrivateAccess() {
  for (const id of ['snippet', 'source-file', 'api-key', 'ai-provider', 'provider-consent', 'worksheet-button', 'generate-button']) $(id).disabled = !signedIn() || (id === 'generate-button' && state.generating);
  $('private-account-note').hidden = signedIn();
}
onSessionChange((info, previous, reason) => {
  const switched = previous.user && info.user && previous.user.id !== info.user.id;
  if (info.status !== 'ready' || !info.user) forgetKey();
  if (switched || ['logout', 'account-deleted'].includes(reason)) {
    state.answers.clear(); state.solved.clear(); $('clear-private').click();
  }
  updatePrivateAccess();
  if (activeRoute === 'library') render();
  if (signedIn()) refreshProgress();
});
async function refreshProgress() {
  if (!signedIn()) return;
  const version = ++progressVersion, owner = sessionInfo().user.id;
  try {
    const result = await apiRequest('/api/attempts');
    if (version !== progressVersion || sessionInfo().user?.id !== owner) return;
    state.solved = new Set(result.items.filter(item => item.category === 'curated' && item.problemKey.startsWith('drill:') && item.score === item.maxScore).map(item => item.problemKey.slice(6)));
    $('solved-count').textContent = `${state.solved.size} / ${challenges.length}`; renderLibrary();
  } catch (error) { $('platform-status').textContent = error.message; }
}
updatePrivateAccess(); activateRoute();
refreshSession();

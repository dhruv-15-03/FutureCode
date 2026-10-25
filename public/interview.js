import { element, answerOptions, renderSource, providerName, providerRequest } from './ui.js';
import { splitSourceLines } from './exercise.js';
import { apiRequest, signedIn, requireAccount, onSessionChange, sessionInfo } from './account.js';
import {
  CASE_ID, CASE_STAGES, CASE_MAX_PINS, COACH_MAX_TURNS, ARTIFACT_LIMITS,
  practiceCase, createAttempt, traceSchedule, attemptProblem, evaluateAttempt,
  coachProblem, practiceRecord
} from './interview-case.js';

const $ = id => document.getElementById(id);
let attempt = createAttempt();
let coachTurns = [];
let coachController;
let coachBusy = false;
let visible = false;
let resetArmed = false;
let submittedRecord = null;

function caseStatus(message, error = false) {
  $('case-status').textContent = message;
  $('case-status').className = error ? 'error' : 'case-help';
}

function coachStatus(message, error = false) {
  $('coach-status').textContent = message;
  $('coach-status').className = error ? 'error' : 'case-help';
}

function markEdited() {
  submittedRecord = null;
  attempt.completedAt = null;
  attempt.furthest = Math.min(attempt.furthest, attempt.stage);
  renderSteps();
}

const fieldIds = {
  flow: 'case-flow', questions: 'case-questions', critique: 'case-critique',
  patch: 'case-patch', patchReasoning: 'case-patch-reasoning',
  testPlan: 'case-test-plan', verification: 'case-verification'
};

function artifact(host, field, label, placeholder, code = false) {
  const wrapper = element('div', 'case-field');
  const heading = element('label', '', label);
  heading.htmlFor = fieldIds[field];
  const input = element('textarea', code ? 'case-code-input' : '');
  input.id = fieldIds[field]; input.maxLength = ARTIFACT_LIMITS[field];
  input.value = attempt[field]; input.placeholder = placeholder;
  input.disabled = !signedIn();
  input.spellcheck = !code; input.autocomplete = 'off';
  input.addEventListener('input', () => { attempt[field] = input.value; markEdited(); });
  wrapper.append(heading, input);
  host.append(wrapper);
}

function choices(host, id, legend, options, values, field) {
  const fieldset = element('fieldset', 'case-choices');
  fieldset.disabled = !signedIn();
  fieldset.id = id;
  fieldset.append(element('legend', '', legend));
  const optionsHost = element('div');
  answerOptions(optionsHost, { options }, id, index => {
    attempt[field] = values[index]; markEdited();
  }, values.indexOf(attempt[field]));
  fieldset.append(optionsHost); host.append(fieldset);
}

function renderBrief(host = $('case-brief'), includeStart = true) {
  host.replaceChildren();
  host.append(element('p', 'case-lead', practiceCase.summary));
  const grid = element('div', 'case-brief-grid');
  const requirements = element('section');
  requirements.append(element('h3', '', 'Your assignment'));
  const list = element('ul', 'case-list');
  practiceCase.requirements.forEach(item => list.append(element('li', '', item)));
  requirements.append(list);
  const incident = element('section');
  incident.append(element('h3', '', 'Incident observations'));
  const table = element('table', 'case-observations');
  const caption = element('caption', '', 'Authored incident fixture; not live benchmark data');
  const head = element('thead'), heading = element('tr');
  ['Run', 'Completed', 'Observed report'].forEach(text => {
    const cell = element('th', '', text); cell.scope = 'col'; heading.append(cell);
  });
  head.append(heading);
  const body = element('tbody');
  practiceCase.observations.forEach(row => {
    const tr = element('tr');
    row.forEach(text => tr.append(element('td', '', text)));
    body.append(tr);
  });
  table.append(caption, head, body); incident.append(table);
  grid.append(requirements, incident); host.append(grid);
  const assumptions = element('details', 'assumptions'); assumptions.open = true;
  assumptions.append(element('summary', '', 'Scope and assumptions'));
  const scope = element('ul');
  practiceCase.assumptions.forEach(item => scope.append(element('li', '', item)));
  assumptions.append(scope); host.append(assumptions);
  host.append(
    element('p', 'case-help', 'Read five files, pin evidence, produce a controlled counterexample, challenge the seeded AI proposal, then write a patch and test plan. Browsing is public; sign in to submit. No AI key is required. Optional live coaching requires separate consent.'),
    element('p', 'case-help', 'Work stays in this tab until reload or restart. Download your practice record at the end. This simulation does not execute your patch or automatically mark it correct. External tools require separate consent.')
  );
  if (!includeStart) return;
  const start = element('button', 'primary', signedIn() ? 'Start the backend simulation' : 'Read the case');
  start.id = 'case-start'; start.type = 'button';
  start.addEventListener('click', () => {
    attempt.startedAt = Date.now();
    $('case-brief').hidden = true; $('case-active').hidden = false; $('case-reset').hidden = false;
    renderStage(); $('case-stage-title').focus();
  });
  host.append(start);
}

function renderSteps() {
  const host = $('case-steps'); host.replaceChildren();
  CASE_STAGES.forEach((title, index) => {
    const item = element('li');
    const button = element('button', '', `${index + 1}. ${title}`);
    button.type = 'button'; button.disabled = index > attempt.furthest;
    button.setAttribute('aria-current', index === attempt.stage ? 'step' : 'false');
    button.addEventListener('click', () => {
      if (index === 3 && !validateThrough(2)) return;
      attempt.stage = index; renderStage(); $('case-stage-title').focus();
    });
    item.append(button); host.append(item);
  });
  const elapsed = attempt.startedAt === null ? 0 : Math.max(0, Math.floor(((attempt.completedAt ?? Date.now()) - attempt.startedAt) / 60_000));
  $('case-elapsed').textContent = `${elapsed} min elapsed · aim for 25 min · no time penalty`;
}

function renderFiles() {
  const host = $('case-files'); host.replaceChildren();
  practiceCase.files.forEach((file, index) => {
    const button = element('button', 'case-file');
    button.id = `case-file-${index}`; button.type = 'button';
    button.setAttribute('aria-pressed', String(file.name === attempt.file));
    button.append(element('strong', '', file.name), element('small', '', file.role));
    button.addEventListener('click', () => {
      const changed = attempt.file !== file.name;
      attempt.file = file.name; renderFiles();
      if (changed) $('case-source').scrollTo(0, 0);
      $(`case-file-${index}`).focus({ preventScroll: true });
    });
    host.append(button);
  });
  const file = practiceCase.files.find(item => item.name === attempt.file);
  $('case-filename').textContent = file.name;
  const source = $('case-source'); source.replaceChildren();
  splitSourceLines(file.source).forEach((line, index) => {
    const pin = `${file.name}#${index + 1}`;
    const row = element('label', `evidence-line${attempt.pins.has(pin) ? ' selected' : ''}`);
    const input = document.createElement('input');
    input.type = 'checkbox'; input.checked = attempt.pins.has(pin); input.disabled = !signedIn() || !line.trim();
    input.id = `case-line-${index + 1}`;
    input.setAttribute('aria-label', `Pin ${file.name} line ${index + 1}: ${line.trim().slice(0, 80) || 'blank line'}`);
    input.addEventListener('change', () => {
      if (input.checked && attempt.pins.size === CASE_MAX_PINS) {
        input.checked = false;
        $('case-evidence-count').textContent = 'Six-pin limit. Remove a pin before adding another.';
        return;
      }
      if (input.checked) attempt.pins.add(pin); else attempt.pins.delete(pin);
      row.classList.toggle('selected', input.checked);
      markEdited(); renderPins();
    });
    const number = element('span', 'line-number', String(index + 1).padStart(2, '0'));
    number.setAttribute('aria-hidden', 'true');
    row.append(input, number, element('code', '', line || ' ')); source.append(row);
  });
  renderPins();
}

function renderPins() {
  $('case-evidence-count').textContent = `${attempt.pins.size}/${CASE_MAX_PINS} lines pinned · use at least two files`;
  const host = $('case-pins'); host.replaceChildren();
  for (const pin of attempt.pins) {
    const [name, line] = pin.split('#');
    const button = element('button', 'case-pin', `${name}:${line} ×`);
    button.type = 'button'; button.setAttribute('aria-label', `Remove pin ${name} line ${line}`);
    button.addEventListener('click', () => {
      attempt.pins.delete(pin); markEdited(); renderFiles(); $('case-source').focus();
    });
    host.append(button);
  }
}

function renderTrace(host) {
  const section = element('section', 'case-trace'); section.id = 'case-trace'; section.tabIndex = -1;
  section.append(
    element('h4', '', 'Build a failing interleaving'),
    element('p', 'case-help', 'Both requests increment /checkout from an initial count of 0. Schedule each read and write. This is a fixed operation model of this case, not a Java runner.')
  );
  const model = traceSchedule(attempt.schedule);
  const controls = element('div', 'trace-controls');
  for (const thread of ['A', 'B']) {
    const group = element('div');
    group.append(element('strong', '', `Request ${thread}`));
    for (const action of ['read', 'write']) {
      const operation = `${thread}:${action}`;
      const button = element('button', 'secondary', `${thread} ${action}`);
      button.type = 'button';
      button.disabled = !signedIn() || attempt.schedule.includes(operation) || (action === 'write' && !attempt.schedule.includes(`${thread}:read`));
      button.addEventListener('click', () => {
        attempt.schedule.push(operation); markEdited();
        const scroll = window.scrollY;
        renderTraceContents(); window.scrollTo(0, scroll);
        const next = $('case-trace').querySelector('button:not(:disabled)');
        if (next) next.focus({ preventScroll: true });
      });
      group.append(button);
    }
    controls.append(group);
  }
  const events = element('ol', 'trace-events');
  model.steps.forEach(step => events.append(element('li', '', step.text)));
  if (!model.steps.length) events.append(element('li', '', 'No operations scheduled. Choose which request reads first.'));
  const outcome = element('p', 'trace-outcome', model.finished ?
    `Two requests completed. Final count: ${model.value}; required: 2. ${model.lost ? 'Lost increment reproduced in the model.' : 'This schedule is serial. Reset and overlap the reads.'}` :
    `${attempt.schedule.length}/4 operations · shared count: ${model.value}`);
  outcome.setAttribute('role', 'status');
  const reset = element('button', 'quiet', 'Reset trace'); reset.type = 'button';
  reset.addEventListener('click', () => { attempt.schedule = []; markEdited(); renderTraceContents(); $('case-trace').querySelector('button').focus(); });
  section.append(controls, events, outcome, reset);
  host.append(section);
}

function renderTraceContents() {
  const old = $('case-trace'), host = element('div');
  renderTrace(host); old.replaceWith(host.firstChild);
}

function readFlow(host) {
  host.append(element('p', 'case-help', 'You have the incident brief, not the diagnosis. Trace the request and reporting paths. Pin source evidence; pin coverage does not prove your explanation is correct.'));
  artifact(host, 'flow', 'Explain the request-to-report path', 'Which object is shared? When is completion counted, including failures?');
  artifact(host, 'questions', 'Clarifying questions or explicit assumptions', 'What would you ask before changing the service? What does the fixture already establish?');
}

function reproduce(host) {
  renderTrace(host);
  host.append(element('h4', '', practiceCase.proposal.label), element('p', 'case-help', practiceCase.proposal.disclosure));
  host.append(element('blockquote', 'case-proposal-claim', practiceCase.proposal.claim));
  const code = element('pre', 'case-proposal'); code.tabIndex = 0;
  code.setAttribute('aria-label', 'Seeded AI proposal');
  renderSource(code, practiceCase.proposal.code); host.append(code);
  choices(host, 'case-ai-decision', 'Would you accept this proposal as written?',
    ['Accept it as a complete fix', 'Reject it and explain the remaining failure'],
    ['accept', 'reject'], 'aiDecision');
  artifact(host, 'critique', 'Defend your decision about the AI proposal', 'Identify the claim you checked, the relevant operation result, and a counterexample or missing proof.');
}

function patchAndTest(host) {
  host.append(element('p', 'case-help', 'Write a proposed replacement for RequestCounter.java. This editor is a reasoning artifact: it is not compiled, executed, or graded for semantic correctness.'));
  artifact(host, 'patch', 'Your proposed RequestCounter.java', 'Write a focused patch.', true);
  artifact(host, 'patchReasoning', 'Explain the fix and its tradeoffs', 'What becomes atomic? What happens to unrelated routes? Which alternatives could also work?');
  choices(host, 'case-invariant', 'Which invariant must a correct patch guarantee?',
    ['Every completed request applies one atomic increment for its route', 'Each individual map operation is thread-safe; the sequence need not be', 'Reporting reads take a lock, regardless of how writes happen', 'Counts may be best-effort under concurrent load'],
    ['atomic', 'container', 'report-lock', 'best-effort'], 'invariant');
  const label = element('label', 'case-number-label', 'Required final count after two completed /checkout requests');
  label.htmlFor = 'case-expected';
  const expected = document.createElement('input');
  expected.type = 'number'; expected.id = 'case-expected'; expected.min = '0'; expected.max = '2'; expected.step = '1';
  expected.value = attempt.expectedCount;
  expected.disabled = !signedIn();
  expected.addEventListener('input', () => { attempt.expectedCount = expected.value; markEdited(); });
  host.append(label, expected);
  artifact(host, 'testPlan', 'Your regression test plan or proposed test code', 'Control the overlap, state the assertion, and cover failed handlers and route isolation. A sleep-only test does not force a schedule.');
  artifact(host, 'verification', 'What still needs verification?', 'Compilation, edge cases, contention, limits, integration behavior, or assumptions. Do not claim tests you did not run.');
}

function debrief(host) {
  const checks = evaluateAttempt(attempt);
  const passed = checks.filter(check => check.passed).length;
  host.append(
    element('p', 'case-lead', `${passed}/4 curated checks match the reference. This is not an interview-readiness score.`),
    element('p', 'case-help', 'Your prose, candidate patch, proposed test code, and AI collaboration quality still need human review. None of your code was executed. No AI grading call was made.')
  );
  const list = element('ul', 'case-checks');
  checks.forEach(check => {
    const item = element('li', check.passed ? 'check-match' : 'check-review');
    item.append(element('strong', '', `${check.passed ? 'Matches reference' : 'Review this'}: ${check.label}`), element('p', '', check.detail));
    list.append(item);
  });
  host.append(list, element('h4', '', 'Reference reasoning'), element('p', '', practiceCase.reference.flow),
    element('p', '', practiceCase.reference.explanation), element('p', '', practiceCase.reference.fix));
  const reference = element('details', 'case-reference');
  reference.append(element('summary', '', 'Reveal the reference patch and fixture expectations'));
  const code = element('pre', 'case-proposal'); code.tabIndex = 0;
  renderSource(code, practiceCase.reference.counterSource);
  reference.append(code, element('p', '', practiceCase.reference.test),
    element('p', 'case-help', 'These are expectations for repository-owned fixtures, not results from your patch. The development test suite compiles only the authored original, seeded proposal, and reference variants.'));
  host.append(reference, element('h4', '', 'Human-review rubric'));
  const rubric = element('dl', 'case-rubric');
  practiceCase.reference.review.forEach(([title, description]) => {
    rubric.append(element('dt', '', `${title} · needs review`), element('dd', '', description));
  });
  host.append(rubric);
  const received = coachTurns.filter(turn => turn.response).length;
  host.append(element('p', 'case-help', `${attempt.hints} authored hints viewed. ${coachTurns.length} live coach request attempts; ${received} replies received. ${received ? 'AI replies remain unverified.' : 'You worked with the seeded proposal only; live AI collaboration was not practiced.'}`));
  const save = element('button', 'primary', submittedRecord ? 'Attempt saved to your account' : 'Submit and save this attempt');
  save.id = 'case-save'; save.type = 'button'; save.disabled = Boolean(submittedRecord);
  save.addEventListener('click', async () => {
    if (!requireAccount()) return;
    save.disabled = true;
    const currentAttempt = attempt, owner = sessionInfo().user.id;
    const payload = {
      artifacts: Object.fromEntries(Object.keys(ARTIFACT_LIMITS).map(field => [field, attempt[field]])),
      selections: { aiDecision: attempt.aiDecision, invariant: attempt.invariant, expectedCount: attempt.expectedCount },
      pins: [...attempt.pins].map(pin => { const [file, line] = pin.split('#'); return { file, line: Number(line) }; }),
      schedule: [...attempt.schedule],
      elapsedSeconds: Math.max(0, Math.round(((attempt.completedAt || Date.now()) - attempt.startedAt) / 1000)),
      hintsViewed: attempt.hints
    };
    try {
      const result = await apiRequest('/api/attempts', { method: 'POST', data: { problemKey: `case:${CASE_ID}`, payload } });
      if (attempt !== currentAttempt || sessionInfo().user?.id !== owner) return;
      submittedRecord = result.attempt.id;
      window.dispatchEvent(new CustomEvent('futurecode-attempt-saved'));
      save.textContent = 'Attempt saved to your account';
      caseStatus(`Saved ${result.attempt.score}/${result.attempt.maxScore} curated checks. Your written code still requires human review. Coach conversation and keys were not stored.`);
    } catch (error) { if (attempt === currentAttempt) { caseStatus(error.message, true); save.disabled = false; } }
  });
  host.append(save, element('p', 'case-help', 'Submitting stores your written artifacts, source pins, and server-checked reference results in MySQL. AI keys and coach conversations are excluded. Local comparison above is not tracked until you submit.'));
  const download = element('button', 'primary', 'Download practice record (.json)');
  download.id = 'case-download'; download.type = 'button';
  download.addEventListener('click', () => {
    const record = practiceRecord(attempt, coachTurns);
    const url = URL.createObjectURL(new Blob([JSON.stringify(record, null, 2)], { type: 'application/json' }));
    const link = document.createElement('a');
    link.href = url; link.download = `futurecode-${CASE_ID}.json`; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    caseStatus('Download requested. Credential fields are excluded, but your written answers and coach transcript are included. Check them for secrets before sharing.');
  });
  host.append(download, element('p', 'case-help', 'Export is an explicit local download and can contain sensitive text you wrote. Key and consent fields are excluded; secrets pasted into answers are not automatically detected. No record is saved automatically.'));
}

function renderStage() {
  renderSteps(); renderFiles();
  const isDebrief = attempt.stage === 3;
  $('case-file-pane').hidden = isDebrief;
  $('case-work-area').classList.toggle('case-debrief-layout', isDebrief);
  $('case-coach').hidden = isDebrief;
  $('case-stage-title').textContent = CASE_STAGES[attempt.stage];
  const host = $('case-task'); host.replaceChildren();
  [readFlow, reproduce, patchAndTest, debrief][attempt.stage](host);
  $('case-back').disabled = attempt.stage === 0;
  $('case-next').hidden = isDebrief;
  $('case-next').disabled = sessionInfo().status === 'loading';
  $('case-next').textContent = !signedIn() ? 'Sign in to continue' : attempt.stage === 2 ? 'Finish and review' : 'Continue';
  $('case-hint').hidden = isDebrief;
  $('case-hint').disabled = attempt.hints === practiceCase.hints.length;
  $('case-hint').textContent = `Show a nudge (${attempt.hints}/${practiceCase.hints.length} viewed)`;
  renderHints();
  caseStatus(isDebrief ? 'Practice record ready. Curated checks are separate from ungraded written work.' : 'Your work stays in this tab. No automatic grading requests.');
}

function renderHints() {
  $('case-hints').replaceChildren();
  practiceCase.hints.slice(0, attempt.hints).forEach(hint => $('case-hints').append(element('p', '', hint)));
}

function validateThrough(lastStage) {
  for (let index = 0; index <= lastStage; index++) {
    const problem = attemptProblem(attempt, index);
    if (problem) {
      attempt.stage = index; renderStage(); caseStatus(problem.message, true);
      const field = $(problem.field), focus = field.querySelector?.('input') || field;
      if (focus instanceof HTMLElement) focus.focus();
      return false;
    }
  }
  return true;
}

function renderCoach() {
  const host = $('coach-transcript'); host.replaceChildren();
  if (!coachTurns.length) host.append(element('p', 'case-help', 'No live coach conversation yet. The seeded proposal and authored hints work without a key.'));
  coachTurns.forEach(turn => {
    const entry = element('article', 'coach-turn');
    entry.append(element('h4', '', 'Your prompt'), element('p', '', turn.prompt));
    if (turn.response) {
      entry.append(element('h4', '', `AI collaborator · ${providerName(turn.provider)} · unverified`), element('p', '', turn.response.reply));
      if (turn.response.assumptions.length) {
        const assumptions = element('ul');
        turn.response.assumptions.forEach(item => assumptions.append(element('li', '', item)));
        entry.append(element('strong', '', 'AI assumptions'), assumptions);
      }
      const citations = element('ul', 'citation-list');
      turn.response.citations.forEach(citation => {
        const item = element('li');
        item.append(element('span', '', `${citation.file}:${citation.line}`), element('code', '', citation.quote));
        citations.append(item);
      });
      entry.append(citations, element('p', 'case-help', 'Any quote locations were checked against this case. The reasoning has not been verified.'));
    } else entry.append(element('p', 'case-help', turn.status === 'pending' ? `Waiting for ${providerName(turn.provider)}…` : `No reply received (${turn.status}). This is not a simulated AI answer.`));
    host.append(entry);
  });
  $('coach-request-count').textContent = `${coachTurns.length}/${COACH_MAX_TURNS} request attempts used`;
  $('coach-send').disabled = !signedIn() || coachBusy || coachTurns.length >= COACH_MAX_TURNS;
  $('coach-send').textContent = coachBusy ? 'Waiting for coach…' : 'Send to AI coach';
  $('coach-prompt').disabled = !signedIn() || coachBusy;
  $('coach-key').disabled = !signedIn(); $('coach-consent').disabled = !signedIn();
  $('coach-provider').disabled = !signedIn();
  $('coach-cancel').hidden = !coachBusy;
}

function clearCoachSecrets(reason) {
  coachController?.abort(reason);
  $('coach-key').value = ''; $('coach-consent').checked = false;
}

async function sendCoach(event) {
  event.preventDefault();
  if (!requireAccount()) return;
  if (coachBusy || coachTurns.length >= COACH_MAX_TURNS) return;
  const prompt = $('coach-prompt').value.trim();
  if (!prompt || prompt.length > 2000) {
    coachStatus('Write a prompt of 1-2,000 characters. Do not paste secrets.', true); $('coach-prompt').focus(); return;
  }
  if ($('coach-key').value.trim().length < 10) {
    coachStatus(`Add your ${providerName($('coach-provider').value)} key for live coaching, or continue with the seeded proposal without a key.`, true); $('coach-key').focus(); return;
  }
  if (!$('coach-consent').checked) {
    coachStatus('Explicit coach consent is required before sending the case and conversation.', true); $('coach-consent').focus(); return;
  }
  const history = coachTurns.filter(turn => turn.response).flatMap(turn => [
    { role: 'user', content: turn.prompt }, { role: 'assistant', content: turn.response.reply }
  ]);
  const turn = { prompt, provider: $('coach-provider').value, status: 'pending', response: null };
  coachTurns.push(turn); coachBusy = true;
  const controller = new AbortController(); coachController = controller;
  const startedAt = attempt.startedAt;
  const timeout = setTimeout(() => controller.abort('timeout'), 30_000);
  $('coach-prompt').value = ''; renderCoach();
  coachStatus(`Sending the five curated files, incident brief, seeded proposal, and successful conversation history to ${providerName(turn.provider)}. One request; provider charges apply.`);
  try {
    const data = await apiRequest('/api/coach', {
      method: 'POST', signal: controller.signal,
      data: { caseId: CASE_ID, messages: [...history, { role: 'user', content: prompt }], key: $('coach-key').value.trim(), consent: true, ...providerRequest(turn.provider) }
    });
    if (controller.signal.aborted || startedAt !== attempt.startedAt) return;
    const problem = coachProblem(data);
    if (problem) throw new Error(problem);
    turn.response = data; turn.status = 'answered';
    coachStatus('Coach reply received. Verify its reasoning; no patch or test was executed.');
  } catch (error) {
    turn.status = controller.signal.aborted ? 'cancelled' : 'failed';
    if (coachController !== controller) return;
    $('coach-prompt').value = prompt;
    if (controller.signal.reason === 'timeout') coachStatus('The coach timed out after 30 seconds. Retry explicitly; another attempt may incur charges.', true);
    else if (!controller.signal.aborted) coachStatus(error instanceof SyntaxError ? 'The local server returned unreadable coach data. Restart it and retry.' : error instanceof TypeError ? 'Could not reach the local coach endpoint. Check the server and retry.' : error.message, true);
  } finally {
    clearTimeout(timeout);
    if (coachController === controller) {
      coachBusy = false; coachController = undefined; renderCoach();
    }
  }
}

export function setInterviewVisible(isVisible) {
  if (visible && !isVisible) {
    clearCoachSecrets('left-mode');
    coachStatus('Coach key and consent cleared on leaving this mode. A pending browser request was cancelled; data already sent cannot be recalled.');
  }
  visible = isVisible;
}

export function mountInterview() {
  $('case-title').textContent = practiceCase.title;
  renderBrief(); renderBrief($('case-recap-content'), false); renderCoach();
  $('case-next').addEventListener('click', () => {
    if (!requireAccount()) return;
    if (!validateThrough(attempt.stage)) return;
    attempt.stage++; attempt.furthest = Math.max(attempt.furthest, attempt.stage);
    if (attempt.stage === 3) {
      attempt.completedAt = Date.now(); clearCoachSecrets('finished');
      coachStatus('Coach key cleared at debrief. The completed transcript remains in this tab.');
    }
    renderStage(); $('case-stage-title').focus();
  });
  $('case-back').addEventListener('click', () => { attempt.stage--; renderStage(); $('case-stage-title').focus(); });
  $('case-hint').addEventListener('click', () => {
    if (attempt.hints < practiceCase.hints.length) attempt.hints++;
    renderHints();
    $('case-hint').textContent = `Show a nudge (${attempt.hints}/${practiceCase.hints.length} viewed)`;
    $('case-hint').disabled = attempt.hints === practiceCase.hints.length;
  });
  $('case-reset').addEventListener('click', () => {
    resetArmed = !resetArmed;
    $('case-confirm-reset').hidden = !resetArmed;
    $('case-reset').textContent = resetArmed ? 'Keep working' : 'Restart practice';
  });
  $('case-confirm-reset').addEventListener('click', () => {
    clearCoachSecrets('restarted'); coachController = undefined; coachBusy = false;
    attempt = createAttempt(); coachTurns = []; resetArmed = false; submittedRecord = null;
    $('case-brief').hidden = false; $('case-active').hidden = true;
    $('case-reset').hidden = true; $('case-reset').textContent = 'Restart practice';
    $('case-confirm-reset').hidden = true;
    $('coach-prompt').value = ''; $('case-hints').replaceChildren();
    coachStatus('Attempt, coach transcript, key, and consent cleared. No new provider request was made.');
    renderBrief(); renderCoach(); $('case-start').focus();
  });
  $('coach-form').addEventListener('submit', sendCoach);
  $('coach-provider').addEventListener('change', () => {
    clearCoachSecrets('provider-changed');
    $('coach-provider-name').textContent = providerName($('coach-provider').value);
    coachStatus('Provider changed. Key and consent were cleared. Conversation and the six-attempt limit remain; consent again before sharing that history.');
  });
  $('coach-forget').addEventListener('click', () => {
    clearCoachSecrets('forgotten'); coachStatus('Coach key and consent cleared. Any pending browser request was cancelled; charges and data already sent cannot be recalled.');
  });
  $('coach-key').addEventListener('input', () => {
    coachController?.abort('key-changed'); $('coach-consent').checked = false;
    coachStatus('Coach key changed. Review the consent before another request.');
  });
  $('coach-consent').addEventListener('change', () => {
    if (!$('coach-consent').checked && coachBusy) {
      coachController?.abort('consent-revoked');
      coachStatus('Coach consent withdrawn. The browser request was cancelled; already-sent data cannot be recalled.');
    }
  });
  $('coach-cancel').addEventListener('click', () => {
    coachController?.abort('cancelled'); coachStatus('Coach browser request cancelled. The attempt is counted; upstream cancellation and charge reversal are not guaranteed.');
  });
  window.addEventListener('pagehide', () => clearCoachSecrets('pagehide'));
  onSessionChange((info, previous, reason) => {
    const switched = previous.user && info.user && previous.user.id !== info.user.id;
    if (!signedIn()) clearCoachSecrets('session-changed');
    if (switched || ['logout', 'account-deleted'].includes(reason)) {
      clearCoachSecrets('account-changed'); coachController = undefined; coachBusy = false;
      attempt = createAttempt(); coachTurns = []; submittedRecord = null;
      $('case-brief').hidden = false; $('case-active').hidden = true;
      $('case-reset').hidden = true; $('case-confirm-reset').hidden = true;
      $('coach-prompt').value = '';
    }
    if (attempt.startedAt === null) renderBrief(); else renderStage();
    renderCoach();
  });
  $('coach-key').value = ''; $('coach-consent').checked = false;
}

import { element, answerOptions, renderSource } from './ui.js';
import { DIFFICULTIES, PROBLEM_KINDS, isUuid } from './community.js';
import { apiRequest, sessionInfo, signedIn, onSessionChange, googleSignIn, signOut, refreshSession, accountDeleted } from './account.js';
import { mountContributionView } from './contribute.js';
import { mountCommunityTools, showBookmarks, showProgress, showLeaderboard, mountLeaderboardPreference, mountModerationAudit } from './community-tools.js';
import { showExecution, mountPayments } from './integrations.js';

const $ = id => document.getElementById(id);
const date = value => new Date(value).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
let navigation;
let viewVersion = 0;
let catalogController;
let savedAttempts = null;
let attemptsPromise;
let currentView;
let controllerApi;

function status(host, message, error = false) {
  host.textContent = message;
  host.className = error ? 'error' : 'help-text';
}

function action(label, handler, className = 'secondary') {
  const button = element('button', className, label); button.type = 'button';
  button.addEventListener('click', handler); return button;
}

export function setGuide(first, last = '') {
  $('guide-first').textContent = first;
  $('guide-last').textContent = last;
}

function signInButton(returnTo = 'account') {
  const button = action('Continue with Google', async () => {
    const notice = $('platform-status');
    button.disabled = true;
    try { await googleSignIn(returnTo); }
    catch (error) { status(notice, error.message, true); button.disabled = !sessionInfo().features.googleSignIn; }
  }, 'google-button');
  button.disabled = !sessionInfo().features.googleSignIn;
  return button;
}

function gate(host, task) {
  host.replaceChildren(
    element('h2', '', `Sign in to ${task}`),
    element('p', 'reading-copy', 'Browsing is public. Submitting work, tracking reference results, and contributing problems require a free Google account.'),
    signInButton(),
    element('p', 'help-text', sessionInfo().notice || 'Only your basic Google identity is requested. FutureCode does not receive your Google password.')
  );
}

async function attempts(force = false) {
  if (!signedIn()) return [];
  if (force) { savedAttempts = null; attemptsPromise = undefined; }
  if (savedAttempts) return savedAttempts;
  if (!attemptsPromise) {
    const userId = sessionInfo().user.id;
    attemptsPromise = apiRequest('/api/attempts').then(result => {
      if (sessionInfo().user?.id === userId) savedAttempts = result.items;
      return result.items;
    }).finally(() => { attemptsPromise = undefined; });
  }
  return attemptsPromise;
}

function scoreLabel(attempt) {
  if (attempt.category === 'ungraded') return 'Written response · ungraded';
  if (attempt.category === 'community') return `${attempt.score}/${attempt.maxScore} author-reference match`;
  return `${attempt.score}/${attempt.maxScore} curated checks`;
}

function renderSavedWork(host, entry) {
  const record = entry.record;
  const section = element('div', 'saved-record-content');
  const text = (title, value, code = false) => {
    if (typeof value !== 'string' || !value.trim()) return;
    section.append(element('h3', '', title));
    const content = element(code ? 'pre' : 'p', code ? 'reference-solution' : 'problem-statement', value);
    if (code) content.tabIndex = 0;
    section.append(content);
  };
  if (record.artifacts) {
    const artifacts = record.artifacts;
    text('Your request-to-report explanation', artifacts.flow);
    text('Questions and assumptions', artifacts.questions);
    text('Your assessment of the AI proposal', artifacts.critique);
    text('Your proposed patch · not executed', artifacts.patch, true);
    text('Why this patch', artifacts.patchReasoning);
    text('Your regression plan', artifacts.testPlan);
    text('What remained to verify', artifacts.verification);
    if (Array.isArray(record.checks)) {
      section.append(element('h3', '', 'Server-checked reference results'));
      const list = element('ul', 'case-checks');
      for (const check of record.checks) {
        const item = element('li');
        item.append(element('strong', '', `${check.passed ? 'Matches reference' : 'Review'}: ${check.label}`), element('p', '', check.detail)); list.append(item);
      }
      section.append(list);
    }
    if (Array.isArray(record.pins)) {
      section.append(element('h3', '', 'Your source pins'));
      const list = element('ul');
      for (const pin of record.pins) list.append(element('li', '', `${pin.file}, line ${pin.line}`));
      section.append(list);
    }
    if (record.elapsedIsSelfReported) section.append(element('p', 'help-text', `Reported working time: ${Math.round(record.elapsedSeconds / 60)} minutes. Timing is not verified or used as a hiring score.`));
  } else {
    if (Number.isInteger(record.answer)) section.append(element('p', 'reading-copy', `Your selected answer: ${['A', 'B', 'C', 'D'][record.answer]}`));
    text(entry.category === 'curated' ? 'Reference reasoning' : 'Your written reasoning', record.explanation);
    if (Number.isInteger(record.correctIndex)) section.append(element('p', 'help-text', `Curated reference answer: ${['A', 'B', 'C', 'D'][record.correctIndex]}`));
    if (Number.isInteger(record.referenceAnswer)) section.append(element('p', 'help-text', `Author reference answer at submission: ${['A', 'B', 'C', 'D'][record.referenceAnswer]}. Agreement is not independent proof of correctness.`));
  }
  host.append(section);
  const raw = element('details', 'record-data');
  raw.append(element('summary', '', 'Inspect the structured record'));
  const pre = element('pre', 'record-json'); pre.tabIndex = 0;
  pre.textContent = JSON.stringify(record, null, 2);
  raw.append(pre); host.append(raw);
}

function renderAuth(info) {
  $('account-summary').textContent = info.status === 'loading' ? 'Checking session…' :
    info.user ? `${info.user.displayName}${info.status === 'ready' ? '' : ' · sign in again'}` : 'Guest reader';
  $('sign-in-action').textContent = info.user ? 'Your account' : 'Sign in with Google';
  $('sign-in-action').disabled = info.status === 'loading';
  $('review-mode').hidden = info.user?.role !== 'admin';
  $('site-notice').hidden = !info.notice;
  $('site-notice').textContent = info.notice || '';
}

async function loadCatalog(cursor = null, append = false) {
  catalogController?.abort();
  const controller = new AbortController(); catalogController = controller;
  const query = new URLSearchParams();
  const search = $('catalog-search').value.trim(), difficulty = $('catalog-difficulty').value;
  if (search) query.set('search', search);
  if (difficulty) query.set('difficulty', difficulty);
  if (cursor) query.set('cursor', cursor);
  const notice = $('catalog-status');
  const table = $('catalog-table');
  table.setAttribute('aria-busy', 'true');
  status(notice, 'Loading the collection…');
  $('catalog-more').hidden = true;
  try {
    const data = await apiRequest(`/api/catalog${query.size ? `?${query}` : ''}`, { signal: controller.signal });
    if (controller.signal.aborted) return;
    const host = $('catalog-rows');
    if (!append) host.replaceChildren();
    const rows = [
      ...data.builtins.map(problem => ({ ...problem, destination: problem.href.slice(1), label: problem.provenance })),
      ...data.community.map(problem => ({
        ...problem, key: `community:${problem.id}`, destination: `problem/${problem.id}`,
        label: `Community · ${problem.author}`, duration: 'Untimed'
      }))
    ];
    rows.forEach((problem, index) => {
      const row = element('tr');
      row.dataset.provenance = problem.key.startsWith('community:') ? 'community' : 'curated';
      const title = element('td', 'problem-title-cell');
      const link = element('a', '', problem.title); link.href = `#${problem.destination}`;
      title.append(element('span', 'entry-number', String(host.children.length + 1).padStart(2, '0')), link, element('small', '', problem.label));
      row.append(title, element('td', '', PROBLEM_KINDS[problem.kind] || problem.kind),
        element('td', '', problem.language), element('td', '', DIFFICULTIES[problem.difficulty] || problem.difficulty),
        element('td', 'duration-cell', problem.duration || 'Untimed'));
      host.append(row);
    });
    const count = host.children.length;
    $('catalog-empty').hidden = count !== 0;
    $('catalog-count').textContent = `${count} ${count === 1 ? 'entry' : 'entries'} shown`;
    status(notice, data.notice || 'Curated and community references are labeled separately. No candidate code runs on this server.');
    $('catalog-more').hidden = !data.nextCursor;
    $('catalog-more').onclick = () => loadCatalog(data.nextCursor, true);
  } catch (error) {
    if (!controller.signal.aborted) status(notice, error.message, true);
  } finally {
    if (!controller.signal.aborted) {
      table.setAttribute('aria-busy', 'false');
      table.removeAttribute('data-initial-loading');
    }
  }
}

async function showAttempts(id, version) {
  const host = $('attempts-content'); host.replaceChildren();
  setGuide('Your saved work', 'Private to your account');
  if (!signedIn()) { gate(host, 'track your work'); return; }
  host.append(element('p', 'help-text', 'Loading saved work…'));
  try {
    if (id) {
      const data = await apiRequest(`/api/attempts/${id}`);
      if (version !== viewVersion) return;
      host.replaceChildren(element('h2', '', data.title), element('p', 'record-score', scoreLabel(data)),
        element('p', 'help-text', `${date(data.createdAt)} · ${data.category === 'curated' ? 'Curated reference checks' : data.category === 'community' ? 'Community author reference' : 'No automatic score'}`));
      if (data.record.grading) host.append(element('p', 'reading-copy', data.record.grading));
      renderSavedWork(host, data);
      const destination = data.problemKey.startsWith('drill:') ? `library/${data.problemKey.slice(6)}` :
        data.problemKey.startsWith('community:') ? `problem/${data.problemKey.slice(10)}` : data.problemKey.startsWith('case:') ? 'interview' : null;
      if (destination) host.append(action('Revisit the problem', () => navigation(destination), 'text-button'));
      host.append(action('Download this record', () => download(`futurecode-attempt-${data.id}.json`, data)),
        action('Back to saved work', () => navigation('attempts'), 'text-button'));
      return;
    }
    const items = await attempts(true);
    if (version !== viewVersion) return;
    host.replaceChildren(element('p', 'reading-copy', 'These are records of submitted work, not an interview-readiness rating. Community reference agreement is not combined with curated checks.'));
    if (!items.length) {
      host.append(element('p', 'empty-state', 'No saved attempts yet. Choose a problem and submit your work when you are ready.'),
        action('Browse the problemset', () => navigation('catalog')));
      return;
    }
    const table = element('table', 'ledger-table');
    const head = element('thead'), tr = element('tr');
    ['Problem', 'Result', 'Submitted', ''].forEach(text => { const th = element('th', '', text); th.scope = 'col'; tr.append(th); });
    head.append(tr); const body = element('tbody');
    for (const item of items) {
      const row = element('tr'), title = element('td');
      const link = element('a', '', item.title); link.href = `#attempts/${item.id}`; title.append(link);
      const removeCell = element('td');
      let armed = false;
      const remove = action('Delete record', async () => {
        if (!armed) { armed = true; remove.textContent = 'Confirm delete'; return; }
        remove.disabled = true;
        try {
          await apiRequest(`/api/attempts/${item.id}`, { method: 'DELETE', data: {} });
          await showAttempts(null, viewVersion);
        } catch (error) { status($('platform-status'), error.message, true); remove.disabled = false; }
      }, 'text-button');
      removeCell.append(remove);
      row.append(title, element('td', '', scoreLabel(item)), element('td', '', date(item.createdAt)), removeCell);
      body.append(row);
    }
    table.append(head, body); host.append(table, element('p', 'help-text', `Free-beta storage: ${items.length}/${sessionInfo().limits.savedAttempts} records. Deleting a record also removes it from these totals. Download anything you want to keep first.`));
  } catch (error) {
    if (version === viewVersion) host.replaceChildren(element('p', 'error', error.message));
  }
}

async function showAccount(notice) {
  const host = $('account-content'); host.replaceChildren();
  const info = sessionInfo();
  setGuide('Your account', 'Google identity');
  if (!signedIn()) {
    gate(host, 'submit and keep a record');
    const messages = {
      sign_in_cancelled: 'Sign-in was cancelled. You can keep browsing.',
      sign_in_expired: 'The sign-in attempt expired or no longer matched this browser. Start again.',
      sign_in_failed: 'Google sign-in could not be verified. Check OAuth configuration or use a Google account permitted by your organization.',
      sign_in_unavailable: 'MySQL or Google OAuth setup is not available yet.',
      beta_capacity: 'The initial beta is at its account capacity. Existing accounts can still sign in; contact the beta administrator about new places.',
      storage_unavailable: 'Sign-in could not be saved because the database is unavailable or busy. Retry later; no account session was confirmed.'
    };
    if (messages[notice]) host.prepend(element('p', 'error', messages[notice]));
    host.append(element('p', 'help-text', 'Sign-in asks only for your basic Google identity. FutureCode never receives your Google password. Optional AI-provider keys are not attached to your account.'));
    return;
  }
  host.append(element('h2', '', info.user.name || info.user.displayName), element('p', 'reading-copy', info.user.email),
    element('p', 'help-text', `Account ID: ${info.user.id}`),
    element('p', 'help-text', info.user.role === 'admin' ? 'Role: moderator' : 'Role: student'));
  const form = element('form', 'profile-form');
  const label = element('label', '', 'Public display name'); label.htmlFor = 'profile-name';
  const input = document.createElement('input'); input.id = 'profile-name'; input.value = info.user.displayName; input.maxLength = 80; input.minLength = 2; input.required = true;
  const submit = element('button', 'primary', 'Save display name'); submit.type = 'submit';
  const feedback = element('p', 'help-text'); feedback.setAttribute('role', 'status');
  form.append(label, input, element('p', 'help-text', 'Shown with your contributions, discussions and opt-in practice-board totals. Your email and Google account identifier are not public.'), submit, feedback);
  form.addEventListener('submit', async event => {
    event.preventDefault(); submit.disabled = true;
    try {
      await apiRequest('/api/account', { method: 'PATCH', data: { displayName: input.value.trim() } });
      await refreshSession(); await showAccount();
      status($('platform-status'), 'Public display name saved.');
    } catch (error) { status(feedback, error.message, true); }
    finally { submit.disabled = false; }
  });
  host.append(form, action('View saved work', () => navigation('attempts')), action('Sign out', async () => {
    try { await signOut(); navigation('catalog'); }
    catch (error) { status($('platform-status'), error.message, true); }
  }, 'text-button'));
  const deletion = element('details', 'account-delete');
  deletion.append(element('summary', '', 'Delete this account and its stored work'));
  deletion.append(element('p', 'reading-copy', 'This deletes your sessions, attempts, bookmarks, votes, discussions, execution records and contributed problems, including published ones. De-identified moderation/payment metadata may remain for operator accounting. It cannot recall downloads or provider copies. Sign in again first if your current sign-in is older than 15 minutes.'));
  const confirmationLabel = element('label', '', 'Type delete-my-account to confirm'); confirmationLabel.htmlFor = 'delete-account-confirmation';
  const confirmation = document.createElement('input'); confirmation.id = 'delete-account-confirmation'; confirmation.autocomplete = 'off';
  const deletionStatus = element('p', 'help-text'); deletionStatus.setAttribute('role', 'status');
  const deleteButton = action('Delete my account', async () => {
    deleteButton.disabled = true;
    try {
      await apiRequest('/api/account', { method: 'DELETE', data: { confirmation: confirmation.value } });
      accountDeleted(); navigation('catalog');
    } catch (error) { status(deletionStatus, error.message, true); deleteButton.disabled = false; }
  }, 'danger-button');
  deletion.append(confirmationLabel, confirmation, deleteButton, deletionStatus, action('Sign in again first', async () => {
    try { await googleSignIn('account'); } catch (error) { status(deletionStatus, error.message, true); }
  }, 'text-button'));
  host.append(deletion);
  mountLeaderboardPreference(host);
  mountPayments(host);
}

async function showProblem(id, version) {
  const host = $('problem-content'); host.replaceChildren(element('p', 'help-text', 'Opening the community problem…'));
  try {
    const problem = await apiRequest(`/api/problems/${id}`);
    if (version !== viewVersion) return;
    setGuide(problem.title, `Community · ${problem.author}`);
    const body = problem.body;
    host.replaceChildren(element('h1', '', problem.title),
      element('p', 'problem-provenance', `Community reference · ${problem.author} · ${body.language} · ${DIFFICULTIES[body.difficulty]}`),
      element('p', 'help-text', 'Moderator-reviewed contribution. Its source and reference solution have not been automatically executed or proven correct.'),
      element('p', 'problem-statement', body.statement));
    if (signedIn() && sessionInfo().user.role === 'admin') host.append(action('Open moderator copy', () => navigation(`review/${problem.id}`), 'text-button'));
    const grid = element('div', 'problem-workspace');
    const source = element('section', 'source-plate');
    const files = element('div', 'file-picker');
    const pre = element('pre', 'community-source'); pre.tabIndex = 0; pre.setAttribute('aria-label', 'Community source, read only');
    body.files.forEach((file, index) => {
      const button = action(file.name, () => {
        renderSource(pre, file.source); pre.scrollTo(0, 0);
        for (const item of files.children) item.setAttribute('aria-pressed', String(item === button));
      }, 'file-button');
      button.setAttribute('aria-pressed', String(index === 0)); files.append(button);
    });
    renderSource(pre, body.files[0].source); source.append(files, pre);
    const response = element('section', 'response-plate');
    response.append(element('h2', '', 'Your response'));
    let answer = null;
    const options = element('fieldset');
    if (body.responseMode === 'choice') {
      options.append(element('legend', '', 'Choose the answer you can defend.'));
      const choicesHost = element('div');
      answerOptions(choicesHost, { options: body.options }, 'community-answer', index => { answer = index; });
      options.append(choicesHost);
    }
    options.disabled = !signedIn();
    const label = element('label', '', body.responseMode === 'written' ? 'Explain your reasoning' : 'Explain your choice (optional)');
    const input = document.createElement('textarea'); input.id = 'community-explanation'; label.htmlFor = input.id;
    input.maxLength = 4000; input.disabled = !signedIn(); input.placeholder = 'Trace the source and state assumptions. This is not executed or AI-graded.';
    const feedback = element('div', 'community-feedback'); feedback.setAttribute('role', 'status');
    const submit = action(signedIn() ? 'Submit and save response' : 'Sign in to submit', async () => {
      if (!signedIn()) { navigation('account'); return; }
      if (body.responseMode === 'choice' && answer === null) { status(feedback, 'Choose an answer before submitting.', true); return; }
      if (body.responseMode === 'written' && !input.value.trim()) { status(feedback, 'Write your reasoning before submitting.', true); input.focus(); return; }
      submit.disabled = true;
      try {
        const result = await apiRequest('/api/attempts', { method: 'POST', data: {
          problemKey: `community:${problem.id}`, payload: { revision: problem.revision, answer: body.responseMode === 'choice' ? answer : null, explanation: input.value }
        } });
        window.dispatchEvent(new CustomEvent('futurecode-attempt-saved'));
        if (version !== viewVersion) return;
        feedback.replaceChildren(
          element('strong', '', body.responseMode === 'written' ? 'Written response saved. No automatic score.' : result.feedback.matched ? 'Matches the author reference. This is not a verified correctness result.' : 'Differs from the author reference. Review its assumptions.'),
          element('p', '', result.feedback.reference.explanation),
          element('h3', '', 'Author’s proposed verification'), element('p', '', result.feedback.reference.verification)
        );
        if (result.feedback.reference.solution) {
          const solution = element('pre', 'reference-solution'); solution.tabIndex = 0; solution.textContent = result.feedback.reference.solution;
          feedback.append(element('h3', '', 'Author’s proposed solution'), solution);
        }
        const citations = element('ul', 'citation-list');
        for (const citation of result.feedback.reference.evidence) {
          const item = element('li');
          item.append(element('span', '', `${citation.file}:${citation.line}`), element('code', '', citation.quote)); citations.append(item);
        }
        feedback.append(citations, action('View saved record', () => navigation(`attempts/${result.attempt.id}`), 'text-button'));
      } catch (error) { if (version === viewVersion) status(feedback, error.message, true); }
      finally { submit.disabled = false; }
    }, 'primary');
    response.append(options, label, input, element('p', 'help-text', 'Submitting stores this response in your account. Your optional AI key and conversations are not included.'), submit, feedback);
    grid.append(source, response); host.append(grid);
    const tools = element('div'); host.append(tools); mountCommunityTools(tools, `community:${problem.id}`);
  } catch (error) {
    if (version === viewVersion) host.replaceChildren(element('h1', '', 'Problem unavailable'), element('p', 'error', error.message),
      action('Retry this entry', () => navigation(`problem/${id}`)), action('Back to the problemset', () => navigation('catalog'), 'text-button'));
  }
}

export function download(name, value) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], { type: 'application/json' }));
  const link = document.createElement('a'); link.href = url; link.download = name; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function mountPlatform({ navigate }) {
  navigation = navigate;
  let searchTimer;
  $('catalog-filters').addEventListener('submit', event => { event.preventDefault(); loadCatalog(); });
  $('catalog-search').addEventListener('input', () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => loadCatalog(), 300); });
  $('catalog-difficulty').addEventListener('change', () => loadCatalog());
  $('sign-in-action').addEventListener('click', () => navigate('account'));
  $('session-retry').addEventListener('click', () => refreshSession());
  window.addEventListener('futurecode-signin-required', () => {
    status($('platform-status'), 'Sign in to submit or track results. Written work stays in this tab; download it before a sign-in redirect if needed.', true);
    navigate('account');
  });
  window.addEventListener('futurecode-session-loading', () => status($('platform-status'), 'Checking your session. Submission controls will be available when that finishes.'));
  window.addEventListener('futurecode-attempt-saved', () => { savedAttempts = null; attemptsPromise = undefined; });
  onSessionChange((info, previous, reason) => {
    renderAuth(info);
    if (info.status === 'ready' && $('platform-status').textContent.startsWith('Checking your session.')) status($('platform-status'), '');
    $('session-retry').hidden = !['error', 'expired'].includes(info.status);
    $('session-retry').textContent = info.status === 'expired' ? 'Refresh this session' : 'Retry session connection';
    const identityChanged = info.user?.id !== previous.user?.id;
    if (identityChanged) { savedAttempts = null; attemptsPromise = undefined; }
    if (['logout', 'account-deleted'].includes(reason)) status($('platform-status'), reason === 'logout' ? 'Signed out. Account-scoped keys and local private work were cleared.' : 'Your account and its stored work were deleted.');
    if ((identityChanged || (previous.status === 'loading' && info.status === 'ready') ||
        (currentView?.route === 'account' && previous.status !== 'ready' && info.status === 'ready')) &&
        currentView && ['account', 'attempts', 'problem', 'contribute', 'review', 'bookmarks', 'progress', 'execution'].includes(currentView.route)) {
      controllerApi?.show(currentView.route, currentView.id, currentView.notice);
    }
  });
  renderAuth(sessionInfo());
  controllerApi = {
    async show(route, id, notice) {
      currentView = { route, id, notice };
      const version = ++viewVersion;
      catalogController?.abort();
      if (route === 'catalog') { setGuide('The problemset', 'Practice collection'); await loadCatalog(); }
      else if (route === 'account') await showAccount(notice);
      else if (route === 'attempts') {
        if (id && !isUuid(id)) $('attempts-content').replaceChildren(element('p', 'error', 'That saved-record address is invalid.'));
        else await showAttempts(id || null, version);
      }
      else if (['bookmarks', 'progress', 'leaderboard'].includes(route)) {
        const host = $(`${route}-content`), current = () => version === viewVersion;
        if (route !== 'leaderboard' && !signedIn()) gate(host, route === 'bookmarks' ? 'save a reading list' : 'review your progress');
        else if (route === 'bookmarks') await showBookmarks(host, current);
        else if (route === 'progress') await showProgress(host, current);
        else await showLeaderboard(host, current);
      }
      else if (route === 'execution') {
        if (!signedIn()) gate($('execution-content'), 'use external tools');
        else await showExecution($('execution-content'), () => version === viewVersion);
      }
      else if (route === 'problem') {
        if (!isUuid(id)) $('problem-content').replaceChildren(element('h1', '', 'Problem not found'), element('p', 'error', 'Choose a published entry from the problemset.'));
        else await showProblem(id, version);
      }
      else if (['contribute', 'review'].includes(route)) {
        await mountContributionView({
          host: route === 'review' ? $('review-content') : $('contribute-content'),
          route, id, navigate, version, isCurrent: () => viewVersion === version,
          onGuide: setGuide, onDownload: download
        });
        if (route === 'review' && version === viewVersion && sessionInfo().user?.role === 'admin') mountModerationAudit($('review-content'));
      }
    }
  };
  return controllerApi;
}

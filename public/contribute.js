import { element, renderSource } from './ui.js';
import { apiRequest, signedIn, sessionInfo } from './account.js';
import { blankProblem, problemIssue, PROBLEM_KINDS, DIFFICULTIES, isUuid } from './community.js';
import { sourceProblem, splitSourceLines } from './exercise.js';

const control = (label, handler, className = 'secondary') => {
  const button = element('button', className, label); button.type = 'button';
  button.addEventListener('click', handler); return button;
};

function field(host, label, value, { id, multiline = false, max = 5000, code = false, change } = {}) {
  const wrapper = element('div', 'form-field'), heading = element('label', '', label);
  const input = document.createElement(multiline ? 'textarea' : 'input');
  input.id = id; heading.htmlFor = id; input.value = value; input.maxLength = max;
  if (code) { input.className = 'code-input'; input.spellcheck = false; }
  input.addEventListener('input', () => change(input.value));
  wrapper.append(heading, input); host.append(wrapper); return input;
}

function select(host, label, values, current, id, change) {
  const wrapper = element('div', 'form-field'), heading = element('label', '', label);
  const input = document.createElement('select'); input.id = id; heading.htmlFor = id;
  for (const [value, text] of Object.entries(values)) {
    const option = element('option', '', text); option.value = value; input.append(option);
  }
  input.value = current; input.addEventListener('change', () => change(input.value));
  wrapper.append(heading, input); host.append(wrapper); return input;
}

function showReference(host, body) {
  host.append(element('h3', '', 'Author reference'), element('p', 'problem-statement', body.reference.explanation));
  if (body.responseMode === 'choice') host.append(element('p', '', `Proposed answer: ${['A', 'B', 'C', 'D'][body.correctIndex]}`));
  if (body.reference.solution) {
    const pre = element('pre', 'reference-solution'); pre.tabIndex = 0; pre.textContent = body.reference.solution; host.append(pre);
  }
  host.append(element('h3', '', 'Proposed verification'), element('p', 'problem-statement', body.reference.verification));
  const evidence = element('ul', 'citation-list');
  for (const citation of body.reference.evidence) {
    const item = element('li'); item.append(element('span', '', `${citation.file}:${citation.line}`), element('code', '', citation.quote)); evidence.append(item);
  }
  host.append(evidence);
}

export async function mountContributionView(context) {
  const { host, route, id, navigate, isCurrent, onGuide, onDownload } = context;
  host.replaceChildren();
  onGuide(route === 'review' ? 'The review desk' : 'Your contributions', 'Community edition');
  if (!signedIn()) {
    host.append(element('h2', '', 'An account is required'), element('p', 'reading-copy', 'Sign in with Google to contribute problems, include a reference solution, or review your submissions.'),
      control('Open sign-in', () => navigate('account')));
    return;
  }
  if (route === 'review' && sessionInfo().user.role !== 'admin') {
    host.append(element('h2', '', 'Moderator access required'), element('p', 'error', 'This account cannot review or publish community submissions.'));
    return;
  }
  if (id === 'new' && route === 'contribute') { renderEditor(host, null, context); return; }
  host.append(element('p', 'help-text', 'Opening the contribution record…'));
  try {
    if (id) {
      if (!isUuid(id)) throw new Error('This contribution address is invalid.');
      const problem = await apiRequest(`/api/${route === 'review' ? 'review' : 'contributions'}/${id}`);
      if (!isCurrent()) return;
      if (route === 'review') renderReview(host, problem, context);
      else renderEditor(host, problem, context);
      return;
    }
    const result = await apiRequest(route === 'review' ? '/api/review' : '/api/contributions');
    if (!isCurrent()) return;
    renderList(host, result, context);
  } catch (error) { if (isCurrent()) host.replaceChildren(element('p', 'error', error.message)); }
}

function renderList(host, result, context, append = false) {
  const { route, navigate, isCurrent } = context;
  if (!append) {
    host.replaceChildren(element('p', 'reading-copy', route === 'review' ?
      'Review the source, problem statement, reference, and publication permission. Approval makes content public; it does not claim the code was executed.' :
      'Drafts stay private. Submit a complete problem and reference for moderator review; only approved entries appear in the public problemset.'));
    if (route !== 'review') host.append(control('Write a problem', () => navigate('contribute/new'), 'primary'));
    host.append(element('div', 'contribution-list'));
  }
  const list = host.querySelector('.contribution-list');
  if (!result.items.length && !append) list.append(element('p', 'empty-state', route === 'review' ? 'No problems are awaiting review.' : 'No contributions yet. Start with a small example you have permission to share.'));
  for (const problem of result.items) {
    const item = element('article', 'contribution-row');
    const link = element('a', '', problem.title); link.href = `#${route}/${problem.id}`;
    item.append(link, element('p', 'help-text', `${problem.status} · revision ${problem.revision} · ${problem.language}`));
    if (problem.reviewNote) item.append(element('p', 'review-note', `Moderator note: ${problem.reviewNote}`));
    list.append(item);
  }
  host.querySelector('.contribution-more')?.remove();
  if (result.nextCursor) {
    const more = control('More entries', async () => {
      more.disabled = true;
      try {
        const page = await apiRequest(`/api/${route === 'review' ? 'review' : 'contributions'}?cursor=${encodeURIComponent(result.nextCursor)}`);
        if (isCurrent()) renderList(host, page, context, true);
      } catch (error) { if (isCurrent()) host.append(element('p', 'error', error.message)); }
      finally { more.disabled = false; }
    });
    more.classList.add('contribution-more'); host.append(more);
  }
}

function renderReview(host, problem, { navigate, onGuide, isCurrent }) {
  onGuide(problem.title, 'Moderator copy');
  const body = problem.body;
  host.replaceChildren(element('h2', '', problem.title),
    element('p', 'help-text', `${problem.status} · revision ${problem.revision} · submitted by ${problem.author}`),
    element('p', 'problem-statement', body.statement));
  for (const file of body.files) {
    const details = element('details', 'source-plate'); details.open = body.files.length === 1;
    details.append(element('summary', '', file.name));
    const pre = element('pre', 'community-source'); pre.tabIndex = 0; renderSource(pre, file.source); details.append(pre); host.append(details);
  }
  if (body.responseMode === 'choice') {
    const options = element('ol', 'review-options');
    body.options.forEach(option => options.append(element('li', '', option))); host.append(options);
  }
  showReference(host, body);
  host.append(element('p', 'help-text', `Publication permission confirmed by author: ${body.rightsConfirmed ? 'yes' : 'no'}. This is an author declaration, not independent rights verification.`));
  const form = element('form', 'review-form');
  let note = '';
  field(form, 'Review note (required for rejection)', '', { id: 'review-note', multiline: true, max: 1000, change: value => { note = value; } });
  const label = element('label', 'consent');
  const confirmed = document.createElement('input'); confirmed.type = 'checkbox'; confirmed.id = 'review-confirmed';
  label.append(confirmed, element('span', '', 'I reviewed the statement, source evidence, reference solution, and permission declaration. I am not claiming automatic execution or proven correctness.'));
  const notice = element('p', 'help-text'); notice.setAttribute('role', 'status');
  const actions = element('div', 'form-actions');
  const decide = async decision => {
    for (const button of actions.querySelectorAll('button')) button.disabled = true;
    try {
      await apiRequest(`/api/review/${problem.id}`, { method: 'POST', data: { revision: problem.revision, decision, note, confirmed: confirmed.checked } });
      if (isCurrent()) navigate('review');
    } catch (error) {
      if (isCurrent()) { notice.textContent = error.message; notice.className = 'error'; }
    } finally { for (const button of actions.querySelectorAll('button')) button.disabled = false; }
  };
  if (problem.status === 'pending') actions.append(control('Approve and publish', () => decide('publish'), 'primary'), control('Return with feedback', () => decide('reject')));
  else {
    let armed = false;
    actions.append(control('Remove published problem', async event => {
      const button = event.currentTarget;
      if (!armed) { armed = true; button.textContent = 'Confirm public removal'; return; }
      try {
        await apiRequest(`/api/review/${problem.id}`, { method: 'DELETE', data: { revision: problem.revision } });
        if (isCurrent()) navigate('catalog');
      } catch (error) { notice.textContent = error.message; notice.className = 'error'; }
    }, 'danger-button'));
  }
  form.append(label, actions, notice); host.append(form, control('Back to review desk', () => navigate('review'), 'text-button'));
}

function renderEditor(host, existing, context) {
  const { navigate, onGuide, isCurrent, onDownload } = context;
  const editor = { id: existing?.id || null, revision: existing?.revision || null, status: existing?.status || 'draft', body: structuredClone(existing?.body || blankProblem()), busy: false, reading: false };
  const body = editor.body;
  let selectedFile = 0, fileReadVersion = 0;
  const activeReads = new Set();
  const locked = ['pending', 'published'].includes(editor.status);
  onGuide(editor.id ? body.title : 'A new problem', 'Author copy');
  host.replaceChildren(element('h2', '', editor.id ? 'Your contribution' : 'Contribute a problem'),
    element('p', 'help-text', `Public credit: ${sessionInfo().user.displayName}. Drafts are stored only when you save; publication requires moderator approval.`),
    element('p', 'reading-copy', 'Use code you are authorized to publish. Do not include private repositories, credentials, or personal data. Every reference must explain its assumptions; this platform does not execute submitted code.'));
  const progress = element('p', 'help-text', `${editor.status} · ${editor.revision ? `revision ${editor.revision}` : 'not saved'}`);
  host.append(progress);
  if (existing?.reviewNote) host.append(element('p', 'review-note', existing.reviewNote));
  const form = element('form', 'problem-editor'); form.noValidate = true;
  form.addEventListener('submit', event => event.preventDefault());
  const fields = element('fieldset'); fields.disabled = locked;
  const feedback = element('p', 'help-text'); feedback.id = 'editor-status'; feedback.setAttribute('role', 'status'); feedback.tabIndex = -1;
  const announce = (message, error = false) => { feedback.textContent = message; feedback.className = error ? 'error' : 'help-text'; };
  const touch = () => { progress.textContent = `${editor.status} · unsaved changes`; };
  field(fields, 'Problem title', body.title, { id: 'problem-title', max: 120, change: value => { body.title = value; touch(); } });
  const metadata = element('div', 'editor-metadata');
  field(metadata, 'Language', body.language, { id: 'problem-language', max: 32, change: value => { body.language = value; touch(); } });
  select(metadata, 'Difficulty', DIFFICULTIES, body.difficulty, 'problem-difficulty', value => { body.difficulty = value; touch(); });
  select(metadata, 'Reading task', PROBLEM_KINDS, body.kind, 'problem-kind', value => { body.kind = value; touch(); });
  fields.append(metadata);
  field(fields, 'Problem statement and assumptions', body.statement, { id: 'problem-statement', multiline: true, max: 5000, change: value => { body.statement = value; touch(); } });
  fields.append(element('h3', '', 'Source files'), element('p', 'help-text', '1-6 files · 12,000 characters and 400 lines each · 24 KB UTF-8 total. Stored as text; no automatic execution.'));
  const fileHost = element('div', 'editor-source'), filePreview = element('details', 'source-plate');
  filePreview.append(element('summary', '', 'Read the selected file with line numbers'));
  const preview = element('pre', 'community-source'); preview.tabIndex = 0; filePreview.append(preview);
  const citationHost = element('div', 'citation-editor'), citationList = element('ul', 'citation-list');
  const updateCitations = () => {
    citationList.replaceChildren();
    body.reference.evidence.forEach((citation, index) => {
      const item = element('li');
      item.append(element('span', '', `${citation.file}:${citation.line}`), element('code', '', citation.quote),
        control('Remove', () => { body.reference.evidence.splice(index, 1); touch(); updateCitations(); }, 'text-button'));
      citationList.append(item);
    });
  };
  const invalidateEvidence = name => {
    const before = body.reference.evidence.length;
    body.reference.evidence = body.reference.evidence.filter(citation => citation.file !== name);
    if (before !== body.reference.evidence.length) announce('Evidence for the edited file was cleared. Select its source lines again before review.');
    updateCitations();
  };
  const renderCitationPicker = () => {
    citationHost.replaceChildren();
    let fileIndex = selectedFile, lineNumber = 1;
    const names = Object.fromEntries(body.files.map((file, index) => [String(index), file.name]));
    const quote = element('code', 'citation-preview');
    const updateQuote = () => { quote.textContent = splitSourceLines(body.files[fileIndex].source)[lineNumber - 1] || '(Select a nonblank source line.)'; };
    select(citationHost, 'Evidence file', names, String(fileIndex), 'citation-file', value => { fileIndex = Number(value); updateQuote(); });
    const lineLabel = element('label', '', 'Line number'); lineLabel.htmlFor = 'citation-line';
    const line = document.createElement('input'); line.id = 'citation-line'; line.type = 'number'; line.min = '1'; line.max = '400'; line.value = '1';
    line.addEventListener('input', () => { lineNumber = Number(line.value); updateQuote(); });
    citationHost.append(lineLabel, line, quote, control('Add source citation', () => {
      const file = body.files[fileIndex], text = splitSourceLines(file.source)[lineNumber - 1];
      if (!Number.isInteger(lineNumber) || !text?.trim()) { announce('Choose an existing, nonblank source line.', true); return; }
      if (body.reference.evidence.length >= 6) { announce('Use at most six source citations.', true); return; }
      if (body.reference.evidence.some(citation => citation.file === file.name && citation.line === lineNumber)) { announce('That source line is already cited.', true); return; }
      body.reference.evidence.push({ file: file.name, line: lineNumber, quote: text.trim() }); touch(); updateCitations();
      announce('Source citation added. Its location is checked, not the correctness of the explanation.');
    }));
    updateQuote();
  };
  const renderFile = () => {
    fileHost.replaceChildren();
    const choose = element('div', 'file-picker');
    body.files.forEach((file, index) => {
      const button = control(file.name, () => { fileReadVersion++; selectedFile = index; renderFile(); }, 'file-button');
      button.setAttribute('aria-pressed', String(selectedFile === index)); choose.append(button);
    });
    fileHost.append(choose);
    const file = body.files[selectedFile];
    field(fileHost, 'File name (no folders)', file.name, { id: 'source-name', max: 80, change: value => {
      fileReadVersion++; invalidateEvidence(file.name); file.name = value; touch();
      choose.children[selectedFile].textContent = value; renderCitationPicker();
    } });
    const code = field(fileHost, 'Source text', file.source, { id: 'source-code', multiline: true, max: 12000, code: true, change: value => {
      fileReadVersion++; invalidateEvidence(file.name); file.source = value; touch();
      renderSource(preview, file.source); renderCitationPicker();
    } });
    const uploadLabel = element('label', 'upload', 'Load a UTF-8 source file');
    const upload = document.createElement('input'); upload.type = 'file';
    upload.accept = '.txt,.java,.js,.ts,.py,.go,.rs,.c,.cpp,.cs,.json,.sql,.kt,.rb,.html,.css';
    upload.addEventListener('change', async () => {
      const selected = upload.files[0]; if (!selected) return;
      const fileIndex = selectedFile, version = ++fileReadVersion;
      activeReads.add(version); editor.reading = true; syncBusy();
      try {
        if (!/\.(txt|java|js|ts|py|go|rs|c|cpp|cs|json|sql|kt|rb|html|css)$/i.test(selected.name) || selected.size > 24000) throw new Error('Choose a supported UTF-8 source file of at most 24 KB.');
        const text = new TextDecoder('utf-8', { fatal: true }).decode(await selected.arrayBuffer());
        const issue = sourceProblem(text); if (issue) throw new Error(issue);
        const total = body.files.reduce((sum, current, index) => sum + new TextEncoder().encode(index === fileIndex ? text : current.source).length, 0);
        if (total > 24000) throw new Error('The source files together exceed 24 KB.');
        if (version !== fileReadVersion || body.files[fileIndex] !== file) return;
        invalidateEvidence(file.name); file.name = selected.name; file.source = text; touch(); renderFile();
        announce('File loaded into this draft. It is not saved or published until you choose an action.');
      } catch (error) { if (version === fileReadVersion) announce(error instanceof TypeError ? 'The file is not valid UTF-8.' : error.message, true); }
      finally { activeReads.delete(version); editor.reading = activeReads.size > 0; syncBusy(); upload.value = ''; }
    });
    uploadLabel.append(upload);
    fileHost.append(uploadLabel, control('Add another file', () => {
      if (body.files.length === 6) { announce('This beta supports up to six source files.', true); return; }
      body.files.push({ name: `Source${body.files.length + 1}.txt`, source: '' }); selectedFile = body.files.length - 1; touch(); renderFile();
    }, 'text-button'), control('Remove this file', () => {
      if (body.files.length === 1) { announce('Keep at least one source file.', true); return; }
      invalidateEvidence(file.name); body.files.splice(selectedFile, 1); selectedFile = 0; touch(); renderFile();
    }, 'text-button'));
    renderSource(preview, file.source); renderCitationPicker();
  };
  fields.append(fileHost, filePreview);
  const answerHost = element('div');
  let previousChoices = [...body.options], previousIndex = body.correctIndex;
  const renderChoices = () => {
    answerHost.replaceChildren();
    if (body.responseMode === 'written') {
      answerHost.append(element('p', 'help-text', 'Written responses are stored for review, without an automatic correctness score.')); return;
    }
    body.options.forEach((option, index) => {
      const row = element('div', 'editor-option');
      const correct = document.createElement('input'); correct.type = 'radio'; correct.name = 'proposed-answer'; correct.value = String(index);
      correct.checked = body.correctIndex === index; correct.setAttribute('aria-label', `Proposed reference answer ${['A', 'B', 'C', 'D'][index]}`);
      correct.addEventListener('change', () => { body.correctIndex = index; touch(); });
      row.append(correct);
      field(row, `Option ${['A', 'B', 'C', 'D'][index]}`, option, { id: `problem-option-${index}`, multiline: true, max: 500, change: value => { body.options[index] = value; touch(); } });
      answerHost.append(row);
    });
  };
  fields.append(element('h3', '', 'Response and reference'));
  select(fields, 'Response format', { choice: 'Multiple choice · author-reference agreement', written: 'Written explanation · ungraded' }, body.responseMode, 'problem-response-mode', value => {
    if (value === 'written') { previousChoices = [...body.options]; previousIndex = body.correctIndex; body.options = []; body.correctIndex = null; }
    else { body.options = previousChoices.length === 4 ? previousChoices : ['', '', '', '']; body.correctIndex = previousIndex; }
    body.responseMode = value; touch(); renderChoices();
  });
  fields.append(answerHost); renderChoices();
  field(fields, 'Reference explanation and assumptions', body.reference.explanation, { id: 'reference-explanation', multiline: true, max: 4000, change: value => { body.reference.explanation = value; touch(); } });
  field(fields, 'Proposed solution or patch (optional)', body.reference.solution, { id: 'reference-solution', multiline: true, max: 6000, code: true, change: value => { body.reference.solution = value; touch(); } });
  field(fields, 'How should a learner verify the claim?', body.reference.verification, { id: 'reference-verification', multiline: true, max: 2000, change: value => { body.reference.verification = value; touch(); } });
  fields.append(element('h3', '', 'Cite the source'), element('p', 'help-text', 'Add 1-6 exact source-line citations. A matching quote establishes location, not semantic correctness.'), citationHost, citationList);
  const rights = element('label', 'consent'), checkbox = document.createElement('input');
  checkbox.type = 'checkbox'; checkbox.id = 'publication-permission'; checkbox.checked = body.rightsConfirmed;
  checkbox.addEventListener('change', () => { body.rightsConfirmed = checkbox.checked; touch(); });
  rights.append(checkbox, element('span', '', 'I have permission to publish this source and reference solution. I removed secrets and unauthorized private code. My public display name will be shown if a moderator approves this contribution.'));
  fields.append(rights); form.append(fields);
  const actions = element('div', 'form-actions');
  const syncBusy = () => {
    fields.disabled = locked || editor.busy;
    for (const button of actions.querySelectorAll('button')) button.disabled = editor.busy || editor.reading;
  };
  const save = async submit => {
    const issue = problemIssue(body, submit);
    if (issue) { announce(issue, true); feedback.focus(); return; }
    if (editor.busy || editor.reading) return;
    editor.busy = true; syncBusy(); announce('Saving your private draft…');
    try {
      const saved = await apiRequest(editor.id ? `/api/contributions/${editor.id}` : '/api/contributions', {
        method: editor.id ? 'PUT' : 'POST', data: editor.id ? { body, revision: editor.revision } : { body }
      });
      editor.id = saved.id; editor.revision = saved.revision; editor.status = saved.status;
      if (!isCurrent()) return;
      history.replaceState(null, '', `#contribute/${editor.id}`);
      progress.textContent = `draft · revision ${editor.revision} · saved`;
      if (submit) {
        await apiRequest(`/api/contributions/${editor.id}/submit`, { method: 'POST', data: { revision: editor.revision } });
        if (isCurrent()) navigate('contribute');
      } else announce('Private draft saved. It has not been published.');
    } catch (error) { if (isCurrent()) { announce(error.message, true); feedback.focus(); } }
    finally { editor.busy = false; syncBusy(); }
  };
  if (!locked) actions.append(control('Save private draft', () => save(false), 'secondary'), control('Submit for moderator review', () => save(true), 'primary'));
  if (editor.status === 'pending') actions.append(control('Withdraw review to edit', async () => {
    try { await apiRequest(`/api/contributions/${editor.id}/withdraw`, { method: 'POST', data: { revision: editor.revision } }); if (isCurrent()) navigate(`contribute/${editor.id}`); }
    catch (error) { announce(error.message, true); }
  }));
  if (editor.status === 'published') actions.append(control('View public problem', () => navigate(`problem/${editor.id}`)));
  actions.append(control('Download this draft', () => onDownload(`futurecode-draft-${editor.id || 'new'}.json`, body), 'text-button'));
  if (editor.id && editor.status !== 'published') {
    let armed = false;
    actions.append(control('Delete contribution', async event => {
      const button = event.currentTarget;
      if (!armed) { armed = true; button.textContent = 'Confirm delete contribution'; return; }
      try { await apiRequest(`/api/contributions/${editor.id}`, { method: 'DELETE', data: { revision: editor.revision } }); if (isCurrent()) navigate('contribute'); }
      catch (error) { announce(error.message, true); }
    }, 'danger-button'));
  }
  form.append(actions, feedback);
  host.append(form, control('Back to contributions', () => navigate('contribute'), 'text-button'));
  renderFile(); updateCitations(); syncBusy();
}

import { element, providerRequest } from './ui.js';
import { apiRequest, signedIn, onSessionChange } from './account.js';
import { exactKeys, boundedString } from './community.js';
import { splitSourceLines } from './exercise.js';
import { practiceCase } from './interview-case.js';

const draft = { source: '', language: 'java', kind: 'snippet', stdin: '', expectedOutput: '', reasoning: '' };
const pending = new Map();
const action = (text, handler, kind = 'secondary') => {
  const node = element('button', kind, text); node.type = 'button'; node.addEventListener('click', handler); return node;
};
const status = (host, text, error = false) => { host.className = error ? 'error' : 'help-text'; host.textContent = text; };
const field = (host, id, label, tag = 'input') => {
  const wrapper = element('div', 'form-field'), title = element('label', '', label), control = element(tag);
  title.htmlFor = id; control.id = id; wrapper.append(title, control); host.append(wrapper); return control;
};
async function send(path, data) {
  pending.get(path)?.abort();
  const controller = new AbortController(); pending.set(path, controller);
  const timer = setTimeout(() => controller.abort(), 30_000);
  try { return await apiRequest(path, { method: 'POST', data, signal: controller.signal }); }
  finally { clearTimeout(timer); if (pending.get(path) === controller) pending.delete(path); }
}
export function clearExternalKeys(clearDraft = false) {
  for (const controller of pending.values()) controller.abort();
  pending.clear();
  for (const input of document.querySelectorAll('.integration-secret')) input.value = '';
  for (const input of document.querySelectorAll('.integration-consent')) input.checked = false;
  if (clearDraft) Object.assign(draft, { source: '', language: 'java', kind: 'snippet', stdin: '', expectedOutput: '', reasoning: '' });
}
window.addEventListener('pagehide', () => clearExternalKeys());
onSessionChange((info, previous, reason) => {
  if (!signedIn()) clearExternalKeys();
  if ((previous.user && info.user?.id !== previous.user.id) || ['logout', 'account-deleted'].includes(reason)) clearExternalKeys(true);
});
const options = (select, values) => { for (const [value, text] of values) { const option = element('option', '', text); option.value = value; select.append(option); } };
export function patchReviewProblem(data, patch) {
  if (!exactKeys(data, ['review', 'verification', 'execution', 'notice']) || data.verification !== 'ai-advisory-unverified' ||
      !exactKeys(data.review, ['summary', 'assumptions', 'citations', 'risks', 'suggestedChecks']) ||
      !boundedString(data.review.summary, 2000, true)) return 'The patch review has an invalid structure. No advice was loaded.';
  for (const [field, max] of [['assumptions', 3], ['risks', 4], ['suggestedChecks', 4]]) {
    const values = data.review[field];
    if (!Array.isArray(values) || values.length > max || (field === 'suggestedChecks' && !values.length) || !values.every(value => boundedString(value, 600, true))) {
      return 'The patch review has invalid bounded advice. No advice was loaded.';
    }
  }
  if (!Array.isArray(data.review.citations) || data.review.citations.length > 3) return 'The patch review has invalid citations.';
  const seen = new Set();
  for (const citation of data.review.citations) {
    if (!exactKeys(citation, ['file', 'line', 'quote'])) return 'The patch review cited source that does not match.';
    const file = practiceCase.files.find(file => file.name === citation.file);
    const lines = citation.file === 'CandidatePatch.java' ? splitSourceLines(patch) : file ? splitSourceLines(file.source) : [];
    const key = `${citation.file}:${citation.line}`;
    if (!Number.isInteger(citation.line) || citation.line < 1 || citation.line > lines.length || !boundedString(citation.quote, 6000, true) ||
        citation.quote !== lines[citation.line - 1].trim() || seen.has(key)) return 'The patch review cited source that does not match. No advice was loaded.';
    seen.add(key);
  }
  return '';
}

export async function showExecution(host, current) {
  host.replaceChildren(element('p', 'help-text', 'Checking external-tool availability…'));
  try {
    const config = await apiRequest('/api/execution/config');
    if (!current()) return;
    host.replaceChildren(element('p', config.enabled ? 'help-text' : 'account-note',
      config.enabled ? config.notice : 'Code execution is disabled by the operator. No program will be sent or run. AI patch advice below is separate and requires its own key and consent.'));
    let revision = 0;
    const form = element('form', 'execution-form');
    const kind = field(form, 'run-kind', 'Task', 'select');
    options(kind, [['snippet', 'Run a single-file program'], ['counter-regression', 'Check a proposed RequestCounter.java patch']]); kind.value = draft.kind;
    const language = field(form, 'run-language', 'External runtime', 'select');
    options(language, [['java', 'Java OpenJDK 13'], ['python', 'Python 3.8'], ['javascript', 'Node.js 12']]); language.value = draft.language;
    const source = field(form, 'run-source', 'Program or complete RequestCounter.java patch', 'textarea');
    source.className = 'code-input'; source.maxLength = 12000; source.spellcheck = false; source.value = draft.source;
    const copy = element('div', 'form-actions');
    copy.append(action('Copy my private snippet', () => {
      const value = document.getElementById('snippet')?.value;
      if (!value?.trim()) { status(output, 'Your private-snippet workspace has no source to copy.', true); return; }
      source.value = value; changed();
    }), action('Copy my current case patch', () => {
      const value = document.getElementById('case-patch')?.value;
      if (!value?.trim()) { status(output, 'Open the case patch stage and write your patch before copying it here.', true); return; }
      source.value = value; kind.value = 'counter-regression'; language.value = 'java'; changed();
    }));
    form.append(copy);
    const stdin = field(form, 'run-stdin', 'Standard input (snippets only)', 'textarea'); stdin.maxLength = 4000; stdin.value = draft.stdin;
    const expected = field(form, 'run-expected', 'Expected output (optional; blank means no comparison)', 'textarea'); expected.maxLength = 4000; expected.value = draft.expectedOutput;
    form.append(element('p', 'help-text', 'The counter harness checks atomic increments, failed handlers and route independence. It uses a Java 13-compatible fixture, not the full Java 17 service or an exhaustive proof. Snippets use class Main for Java. Runtime mismatches can cause compilation failures.'));
    const key = field(form, 'runner-key', 'Your Judge0 CE RapidAPI key');
    key.type = 'password'; key.className = 'integration-secret'; key.maxLength = 256; key.autocomplete = 'off';
    const consentLabel = element('label', 'consent'), consent = element('input');
    consent.type = 'checkbox'; consent.id = 'runner-consent'; consent.className = 'integration-consent';
    consentLabel.append(consent, element('span', '', 'I permit this program, input, and applicable trusted harness to be sent to Judge0 CE on RapidAPI for isolated execution. I have removed secrets and have permission to share the code. Provider billing and retention apply.'));
    const run = element('button', 'primary', 'Submit to external runner'); run.type = 'submit'; run.disabled = !config.enabled;
    const forget = action('Forget external keys', () => { clearExternalKeys(); status(output, 'External keys and consent cleared. Already-sent provider work may continue.'); }, 'text-button');
    const output = element('p', 'help-text'); output.setAttribute('role', 'status');
    form.append(consentLabel, run, forget, output);
    const history = element('section', 'execution-history'); history.append(element('h2', '', 'Private execution records'));
    const records = element('div'); history.append(records);
    const review = element('details', 'patch-advisory'); review.append(element('summary', '', 'Optional AI patch review: unverified advice'));
    const reviewBody = element('div'); review.append(reviewBody);
    const provider = field(reviewBody, 'patch-provider', 'Review provider', 'select');
    options(provider, [['openai', 'OpenAI · gpt-4o-mini'], ['gemini', 'Google Gemini · Gemini 3.8 Flash']]);
    const reasoning = field(reviewBody, 'patch-reasoning', 'Your intended invariant and remaining questions', 'textarea');
    reasoning.maxLength = 2000; reasoning.value = draft.reasoning;
    const evidence = field(reviewBody, 'patch-run-id', 'Optional finished counter run to attach', 'select');
    options(evidence, [['', 'No execution evidence attached']]);
    const reviewKey = field(reviewBody, 'patch-review-key', 'Provider key for this review only');
    reviewKey.type = 'password'; reviewKey.className = 'integration-secret'; reviewKey.maxLength = 256; reviewKey.autocomplete = 'off';
    const reviewConsentLabel = element('label', 'consent'), reviewConsent = element('input');
    reviewConsent.type = 'checkbox'; reviewConsent.id = 'patch-review-consent'; reviewConsent.className = 'integration-consent';
    reviewConsentLabel.append(reviewConsent, element('span', '', 'I permit the curated case, this counter patch, my reasoning and any selected owned execution result to be sent to the selected AI provider. This is advisory review, not reliable semantic grading.'));
    const reviewOutput = element('div', 'advisory-output'), reviewStatus = element('p', 'help-text'); reviewStatus.setAttribute('role', 'status');
    const ask = action('Request patch advice', async () => {
      if (kind.value !== 'counter-regression') { status(reviewStatus, 'Select the RequestCounter patch task first. This review is tied to the curated counter case.', true); return; }
      if (!reviewConsent.checked) { status(reviewStatus, 'Separate patch-review consent is required.', true); return; }
      const version = revision;
      ask.disabled = true; status(reviewStatus, 'Requesting unverified patch advice…');
      try {
        const data = await send('/api/patch-review', { caseId: 'missing-requests-v1', patch: source.value, reasoning: reasoning.value,
          runId: evidence.value || null, key: reviewKey.value, consent: true, ...providerRequest(provider.value) });
        if (!current() || version !== revision) return;
        const issue = patchReviewProblem(data, source.value);
        if (issue) throw new Error(issue);
        reviewOutput.replaceChildren(element('h3', '', 'Unverified AI review'), element('p', '', data.review.summary), element('p', 'help-text', data.notice));
        for (const [name, title] of [['assumptions', 'Assumptions'], ['risks', 'Risks to investigate'], ['suggestedChecks', 'Proposed checks, not executed here']]) {
          const list = element('ul'); for (const item of data.review[name]) list.append(element('li', '', item));
          reviewOutput.append(element('h4', '', title), list);
        }
        const citations = element('ul', 'citation-list');
        for (const citation of data.review.citations) { const row = element('li'); row.append(element('span', '', `${citation.file}:${citation.line}`), element('code', '', citation.quote)); citations.append(row); }
        reviewOutput.append(citations); status(reviewStatus, 'Advisory received. No profile or leaderboard score was changed.');
      } catch (error) { if (current()) status(reviewStatus, error.message, true); }
      finally { if (current()) ask.disabled = false; }
    }, 'primary');
    reviewBody.append(reviewConsentLabel, ask, reviewStatus, reviewOutput);
    provider.addEventListener('change', () => { revision++; pending.get('/api/patch-review')?.abort(); reviewKey.value = ''; reviewConsent.checked = false; reviewOutput.replaceChildren(); status(reviewStatus, 'Provider changed. Review key, consent and prior advice cleared.'); });
    reasoning.addEventListener('input', () => { revision++; pending.get('/api/patch-review')?.abort(); draft.reasoning = reasoning.value; reviewConsent.checked = false; reviewOutput.replaceChildren(); });
    key.addEventListener('input', () => { consent.checked = false; pending.get('/api/runs')?.abort(); });
    reviewKey.addEventListener('input', () => { revision++; reviewConsent.checked = false; pending.get('/api/patch-review')?.abort(); });
    function changed() {
      revision++; pending.get('/api/patch-review')?.abort();
      draft.source = source.value; draft.kind = kind.value; draft.language = language.value; draft.stdin = stdin.value; draft.expectedOutput = expected.value;
      consent.checked = false; reviewConsent.checked = false; evidence.value = ''; reviewOutput.replaceChildren();
      if (kind.value === 'counter-regression') { language.value = 'java'; draft.language = 'java'; stdin.value = ''; expected.value = ''; draft.stdin = ''; draft.expectedOutput = ''; }
      language.disabled = kind.value === 'counter-regression'; stdin.disabled = kind.value === 'counter-regression'; expected.disabled = kind.value === 'counter-regression';
    }
    for (const node of [source, stdin, expected]) node.addEventListener('input', changed);
    for (const node of [language, kind]) node.addEventListener('change', changed);
    language.disabled = kind.value === 'counter-regression'; stdin.disabled = kind.value === 'counter-regression'; expected.disabled = kind.value === 'counter-regression';
    async function loadRuns() {
      const data = await apiRequest('/api/runs'); if (!current()) return;
      records.replaceChildren();
      const selected = evidence.value; evidence.replaceChildren(); options(evidence, [['', 'No execution evidence attached']]);
      if (!data.items.length) records.append(element('p', 'help-text', 'No external execution records yet. Keys and source text are not stored in these records.'));
      for (const row of data.items) {
        const article = element('article', 'contribution-row');
        article.append(element('h3', '', `${row.kind} · ${row.state}`), element('p', 'help-text', `${row.language} · ${new Date(row.createdAt).toLocaleString('en-IN')} · source hash ${row.sourceHash}`));
        if (row.result) {
          if (row.result.notice) article.append(element('p', 'help-text', row.result.notice));
          for (const [name, title] of [['stdout', 'Standard output'], ['stderr', 'Standard error'], ['compileOutput', 'Compiler output']]) {
            if (row.result[name]) article.append(element('h4', '', title), element('pre', 'record-json', row.result[name]));
          }
          if (row.result.status) article.append(element('p', '', `Provider status: ${row.result.status}`));
          if (row.result.passedExpectedOutput !== undefined && row.result.passedExpectedOutput !== null) article.append(element('p', '', row.result.passedExpectedOutput ? 'The provider matched the configured expected output. This is limited evidence, not a proof of all semantics.' : 'The configured expected output was not matched.'));
        }
        if (['completed', 'failed'].includes(row.state) && row.kind === 'counter-regression') options(evidence, [[row.id, `${row.id.slice(0, 8)} · ${row.state}`]]);
        if (['queued', 'running'].includes(row.state)) {
          const poll = action('Check external result', async () => {
            if (!consent.checked) { status(output, 'Consent again before checking the provider result.', true); return; }
            poll.disabled = true;
            try { await send(`/api/runs/${row.id}/poll`, { key: key.value, consent: true }); if (current()) await loadRuns(); }
            catch (error) { if (current()) status(output, error.message, true); }
            finally { if (current()) poll.disabled = false; }
          }); poll.disabled = !config.enabled; article.append(poll);
        }
        let armed = false;
        const remove = action('Delete execution record', async () => {
          if (!armed) { armed = true; remove.textContent = 'Confirm record deletion'; return; }
          remove.disabled = true;
          try { await apiRequest(`/api/runs/${row.id}`, { method: 'DELETE', data: {} }); if (current()) await loadRuns(); }
          catch (error) { if (current()) status(output, error.message, true); remove.disabled = false; }
        }, 'text-button');
        article.append(remove, element('p', 'help-text', 'Deleting a local record does not cancel provider work or erase provider-retained data.')); records.append(article);
      }
      if ([...evidence.options].some(option => option.value === selected)) evidence.value = selected;
    }
    form.addEventListener('submit', async event => {
      event.preventDefault();
      if (!consent.checked) { status(output, 'Separate permission to send code to the external runner is required.', true); return; }
      run.disabled = true; status(output, 'Submitting once to the external sandbox. No automatic retries.');
      try {
        await send('/api/runs', { kind: kind.value, language: language.value, source: source.value, stdin: stdin.value,
          expectedOutput: expected.value, key: key.value, consent: true });
        if (current()) { status(output, 'Provider submission accepted. Check its result explicitly; no profile score was assigned.'); await loadRuns(); }
      } catch (error) { if (current()) status(output, error.message, true); }
      finally { if (current()) run.disabled = !config.enabled; }
    });
    history.append(action('Refresh execution records', () => loadRuns().catch(error => status(output, error.message, true)), 'text-button'));
    host.append(form, review, history);
    await loadRuns();
  } catch (error) { if (current()) host.replaceChildren(element('p', 'error', error.message), action('Retry external tools', () => showExecution(host, current))); }
}

export function mountPayments(host) {
  const details = element('details', 'optional-payments'); details.append(element('summary', '', 'Optional project support & payment history'));
  const content = element('div'); details.append(content); host.append(details);
  const requestId = crypto.randomUUID();
  async function load() {
    try {
      const config = await apiRequest('/api/payments/config'); const history = await apiRequest('/api/payments'); if (!details.isConnected) return;
      content.replaceChildren(element('p', 'help-text', config.notice));
      const message = element('p', 'help-text'); message.setAttribute('role', 'status'); content.append(message);
      if (!config.enabled) content.append(element('p', 'account-note', 'Payments are disabled. No checkout can be created. Practice remains free.'));
      else {
        const pay = action(`Create optional INR ${(config.amountPaise / 100).toFixed(2)} support checkout`, async () => {
          pay.disabled = true;
          try {
            const result = await send('/api/payments/checkout', { requestId }); if (!details.isConnected) return;
            if (result.state === 'created' && !checkoutUrl(result.checkoutUrl)) throw new Error('Invalid hosted checkout destination. No payment page was opened.');
            if (result.state === 'created') {
              const link = element('a', 'secondary', 'Continue to hosted Razorpay checkout'); link.href = result.checkoutUrl; link.target = '_blank'; link.rel = 'noopener noreferrer';
              content.append(link); status(message, 'Checkout created. No payment is marked paid until a verified provider webhook arrives.');
            } else status(message, `Checkout state: ${result.state}. Check payment history before making another request.`);
          } catch (error) { status(message, error.message, true); }
          finally { pay.disabled = false; }
        }); content.append(pay);
      }
      content.append(element('p', 'help-text', 'Returning from a checkout is not proof of payment. Verified payment records may be retained separately from practice data for operator accounting; card details are not stored here.'));
      for (const row of history.items) {
        const record = element('p', '', `${row.currency} ${(row.amountPaise / 100).toFixed(2)} · ${row.state} · ${new Date(row.createdAt).toLocaleString('en-IN')}`); content.append(record);
        if (row.state === 'created' && checkoutUrl(row.checkoutUrl) && new Date(row.expiresAt).getTime() > Date.now()) {
          const link = element('a', '', 'Resume hosted checkout'); link.href = row.checkoutUrl; link.target = '_blank'; link.rel = 'noopener noreferrer'; content.append(link);
        }
      }
      content.append(action('Refresh payment history', load, 'text-button'));
    } catch (error) { content.replaceChildren(element('p', 'error', error.message), action('Retry payment history', load)); }
  }
  details.addEventListener('toggle', () => { if (details.open) load(); });
}
function checkoutUrl(value) {
  try { const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password && !url.port && ['rzp.io', 'rzp.live', 'pages.razorpay.com'].includes(url.hostname); }
  catch { return false; }
}

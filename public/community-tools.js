import { element } from './ui.js';
import { apiRequest, signedIn, sessionInfo, onSessionChange } from './account.js';

const mounts = new Map(), drafts = new Map();
const button = (text, handler, kind = 'secondary') => {
  const control = element('button', kind, text); control.type = 'button';
  control.addEventListener('click', handler); return control;
};
const notice = (host, text, error = false) => { host.textContent = text; host.className = error ? 'error' : 'help-text'; };
const href = key => key.startsWith('drill:') ? `#library/${encodeURIComponent(key.slice(6))}` :
  key.startsWith('community:') ? `#problem/${encodeURIComponent(key.slice(10))}` : '#interview';
const date = value => new Date(value).toLocaleString('en-IN');

export function mountCommunityTools(host, key) {
  for (const [node, mounted] of mounts) if (node === host || !node.isConnected) { mounted.controller.abort(); mounts.delete(node); }
  const controller = new AbortController();
  const details = element('details', 'community-tools');
  details.append(element('summary', '', 'Save this entry, vote & discuss'));
  const content = element('div', 'community-tools-content'), status = element('p', 'help-text');
  status.setAttribute('role', 'status'); details.append(content, status); host.replaceChildren(details);
  let busy = false, refreshQueued = false;
  const active = () => host.isConnected && !controller.signal.aborted;
  const draftKey = () => `${sessionInfo().user?.id || 'guest'}:${key}`;
  const task = async (control, work) => {
    control.disabled = true;
    try { await work(); }
    catch (error) { if (active()) notice(status, error.message, true); }
    finally { if (active()) control.disabled = false; }
  };
  async function refresh() {
    if (!active()) return;
    if (busy) { refreshQueued = true; return; }
    busy = true; notice(status, 'Opening entry actions…');
    try {
      const info = await apiRequest(`/api/community/${encodeURIComponent(key)}`, { signal: controller.signal });
      if (!active()) return;
      content.replaceChildren();
      const actions = element('div', 'form-actions');
      const save = button(info.bookmarked ? 'Remove saved entry' : 'Save entry', () => task(save, async () => {
        await apiRequest(`/api/bookmarks/${encodeURIComponent(key)}`, { method: info.bookmarked ? 'DELETE' : 'PUT', data: {} });
        info.bookmarked = !info.bookmarked;
        save.textContent = info.bookmarked ? 'Remove saved entry' : 'Save entry';
        notice(status, info.bookmarked ? 'Saved privately to your entries.' : 'Entry removed from your saved list.');
      }));
      save.disabled = !info.signedIn;
      actions.append(save);
      for (const [value, text] of [[1, 'Useful problem'], [-1, 'Needs improvement']]) {
        const vote = button(text, () => task(vote, async () => {
          const result = await apiRequest(`/api/community/${encodeURIComponent(key)}/vote`, { method: 'PUT', data: { value: info.votes.mine === value ? 0 : value } });
          info.votes = result;
          for (const control of actions.querySelectorAll('[data-vote]')) control.setAttribute('aria-pressed', String(Number(control.dataset.vote) === result.mine));
          totals.textContent = `${result.up} useful votes; ${result.down} improvement votes. Votes are not correctness scores.`;
          notice(status, 'Your vote was saved.');
        }));
        vote.dataset.vote = String(value); vote.setAttribute('aria-pressed', String(info.votes.mine === value)); vote.disabled = !info.canVote; actions.append(vote);
      }
      const totals = element('p', 'help-text', `${info.votes.up} useful votes; ${info.votes.down} improvement votes. Votes are not correctness scores.`);
      content.append(actions, totals);
      if (info.voteNotice) content.append(element('p', 'help-text', info.voteNotice));
      if (!info.signedIn) {
        const signIn = element('a', '', 'Sign in to save an entry or submit work.'); signIn.href = '#account'; content.append(signIn);
      }
      if (!info.canDiscuss) content.append(element('p', 'help-text', 'Submit an attempt to unlock discussion and voting. This keeps reference spoilers behind the same attempt gate.'));
      else {
        const list = element('div', 'discussion-list'); content.append(element('h3', '', 'Discussion'), list);
        const form = element('form', 'discussion-form'), label = element('label', '', 'Your reasoning or question');
        const input = element('textarea'); input.id = `discussion-text-${crypto.randomUUID()}`; label.htmlFor = input.id;
        input.maxLength = 2000; input.value = drafts.get(draftKey()) || ''; input.placeholder = 'Use source evidence. Do not post keys, personal data or code you cannot share.';
        input.addEventListener('input', () => drafts.set(draftKey(), input.value));
        const send = element('button', 'primary', 'Post discussion'); send.type = 'submit';
        form.append(label, input, element('p', 'help-text', 'Posted text and your public display name are visible to eligible signed-in readers and moderators. Do not post private source or credentials.'), send);
        form.addEventListener('submit', event => {
          event.preventDefault();
          task(send, async () => {
            await apiRequest(`/api/community/${encodeURIComponent(key)}/discussion`, { method: 'POST', data: { text: input.value } });
            input.value = ''; drafts.delete(draftKey()); notice(status, 'Discussion posted.'); await comments(list);
          });
        });
        content.append(form);
        await comments(list);
      }
      if (active()) notice(status, '');
    } catch (error) {
      if (active()) {
        content.replaceChildren(button('Retry entry actions', refresh));
        notice(status, error.message, true);
      }
    } finally {
      busy = false;
      if (refreshQueued && active()) { refreshQueued = false; refresh(); }
    }
  }
  async function comments(list, cursor = null, append = false) {
    const response = await apiRequest(`/api/community/${encodeURIComponent(key)}/discussion${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`, { signal: controller.signal });
    if (!active()) return;
    if (!append) list.replaceChildren();
    if (!response.items.length && !append) list.append(element('p', 'help-text', 'No discussion yet. Share the reasoning, not just an answer letter.'));
    for (const row of response.items) {
      const article = element('article', 'discussion-entry');
      article.append(element('p', 'discussion-meta', `${row.author} · ${date(row.createdAt)}${row.hidden ? ' · hidden by moderator' : ''}`),
        element('p', 'discussion-text', row.text));
      if (row.owned) {
        const edit = element('details', 'discussion-edit'); edit.append(element('summary', '', 'Edit your comment'));
        const input = element('textarea'); input.value = row.text; input.maxLength = 2000; input.setAttribute('aria-label', 'Edit comment text');
        const save = button('Save comment revision', () => task(save, async () => {
          await apiRequest(`/api/discussions/${row.id}`, { method: 'PUT', data: { text: input.value, revision: row.revision } }); await comments(list);
        }));
        let armed = false;
        const remove = button('Delete my comment', () => {
          if (!armed) { armed = true; remove.textContent = 'Confirm comment deletion'; return; }
          task(remove, async () => { await apiRequest(`/api/discussions/${row.id}`, { method: 'DELETE', data: { revision: row.revision } }); await comments(list); });
        }, 'text-button');
        edit.append(input, save); article.append(edit, remove);
      }
      if (sessionInfo().user?.role === 'admin') {
        const moderation = element('details'); moderation.append(element('summary', '', 'Moderate this comment'));
        const reason = element('textarea'); reason.maxLength = 300; reason.setAttribute('aria-label', 'Moderator explanation'); reason.value = row.moderationNote || '';
        const decide = button(row.hidden ? 'Restore comment' : 'Hide comment', () => task(decide, async () => {
          await apiRequest(`/api/discussions/${row.id}/moderation`, { method: 'PATCH', data: { revision: row.revision, hidden: !row.hidden, note: reason.value } }); await comments(list);
        }));
        moderation.append(reason, decide); article.append(moderation);
      }
      list.append(article);
    }
    if (response.nextCursor) {
      const more = button('More discussion', () => task(more, async () => { await comments(list, response.nextCursor, true); more.remove(); }));
      list.append(more);
    }
  }
  details.addEventListener('toggle', () => { if (details.open) refresh(); });
  mounts.set(host, { controller, details, refresh });
}

window.addEventListener('futurecode-attempt-saved', () => {
  for (const [host, mount] of mounts) {
    if (!host.isConnected) { mount.controller.abort(); mounts.delete(host); }
    else if (mount.details.open) mount.refresh();
  }
});
onSessionChange((info, previous, reason) => {
  if ((previous.user && info.user?.id !== previous.user.id) || ['logout', 'account-deleted'].includes(reason)) {
    drafts.clear();
    for (const mount of mounts.values()) { mount.controller.abort(); mount.details.open = false; }
    mounts.clear();
  }
});

function table(headers, rows) {
  const wrapper = element('div', 'table-wrap'), grid = element('table', 'ledger-table');
  const head = element('thead'), heading = element('tr');
  for (const title of headers) { const cell = element('th', '', title); cell.scope = 'col'; heading.append(cell); }
  head.append(heading); const body = element('tbody');
  for (const values of rows) {
    const row = element('tr'); for (const value of values) row.append(element('td', '', String(value))); body.append(row);
  }
  grid.append(head, body); wrapper.append(grid); return wrapper;
}
export async function showBookmarks(host, current) {
  host.replaceChildren(element('p', 'help-text', 'Opening saved entries…'));
  try {
    const data = await apiRequest('/api/bookmarks'); if (!current()) return;
    host.replaceChildren(element('p', 'help-text', `Private to your account. Up to ${data.limit} entries; this list is separate from submitted attempts.`));
    if (!data.items.length) host.append(element('p', 'empty-state', 'No saved entries yet. Open an entry and use its save action.'));
    for (const row of data.items) {
      const item = element('article', 'contribution-row');
      if (row.available) { const link = element('a', '', row.title); link.href = href(row.problemKey); item.append(link); }
      else item.append(element('p', '', row.title), element('p', 'help-text', row.notice));
      const status = element('p', 'help-text'); status.setAttribute('role', 'status');
      const remove = button('Remove saved entry', async () => {
        remove.disabled = true;
        try { await apiRequest(`/api/bookmarks/${encodeURIComponent(row.problemKey)}`, { method: 'DELETE', data: {} }); if (current()) await showBookmarks(host, current); }
        catch (error) { notice(status, error.message, true); remove.disabled = false; }
      }, 'text-button');
      item.append(remove, status); host.append(item);
    }
  } catch (error) { if (current()) host.replaceChildren(element('p', 'error', error.message), button('Retry saved entries', () => showBookmarks(host, current))); }
}
export async function showProgress(host, current) {
  host.replaceChildren(element('p', 'help-text', 'Opening your retained practice record…'));
  try {
    const data = await apiRequest('/api/progress'); if (!current()) return;
    host.replaceChildren(element('p', 'reading-copy', data.notice));
    if (!data.topics.length) host.append(element('p', 'empty-state', 'No submitted practice yet. An entry becomes progress when you explicitly save an attempt.'));
    else host.append(table(['Topic or reading task', 'Attempts', 'Entries', 'Curated matches', 'Author matches', 'Written'], data.topics.map(row =>
      [row.label, row.attempts, row.attemptedEntries, row.curatedMatches, row.communityMatches, row.writtenResponses])));
  } catch (error) { if (current()) host.replaceChildren(element('p', 'error', error.message), button('Retry progress', () => showProgress(host, current))); }
}
export async function showLeaderboard(host, current) {
  host.replaceChildren(element('p', 'help-text', 'Opening the opt-in practice board…'));
  try {
    const data = await apiRequest('/api/leaderboard'); if (!current()) return;
    host.replaceChildren(element('p', 'reading-copy', data.notice));
    if (!data.items.length) host.append(element('p', 'empty-state', 'No public records yet. Participation is off by default and controlled from Your account.'));
    else host.append(table(['Position', 'Public display name', 'Curated checks matched', 'Distinct entries'], data.items.map(row => [row.rank, row.displayName, row.curatedChecks, row.entries])));
  } catch (error) { if (current()) host.replaceChildren(element('p', 'error', error.message), button('Retry practice board', () => showLeaderboard(host, current))); }
}
export function mountLeaderboardPreference(host) {
  const section = element('section', 'profile-form'); section.append(element('h3', '', 'Public practice board'));
  const status = element('p', 'help-text'); status.setAttribute('role', 'status'); section.append(status); host.append(section);
  apiRequest('/api/leaderboard/preference').then(data => {
    if (!section.isConnected || !signedIn()) return;
    const label = element('label', 'consent'), input = document.createElement('input'); input.type = 'checkbox'; input.checked = data.enabled; input.id = 'leaderboard-opt-in';
    label.append(input, element('span', '', 'Show my public display name and retained curated-reference totals on the practice board. This is not an interview-readiness score.'));
    const save = button('Save board preference', async () => {
      save.disabled = true;
      try { await apiRequest('/api/leaderboard/preference', { method: 'PUT', data: { enabled: input.checked } }); notice(status, input.checked ? 'Public participation enabled.' : 'Your practice totals are not public.'); }
      catch (error) { notice(status, error.message, true); }
      finally { save.disabled = false; }
    });
    section.insertBefore(label, status); section.append(save); notice(status, data.enabled ? 'Your display name and curated totals are public.' : 'Off by default. Your private attempts remain private.');
  }).catch(error => notice(status, error.message, true));
}
export function mountModerationAudit(host) {
  const details = element('details', 'moderation-audit'); details.append(element('summary', '', 'Moderation audit trail'));
  const output = element('div'); details.append(output); host.append(details);
  async function load(cursor = null, append = false) {
    try {
      const data = await apiRequest(`/api/moderation/audit${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`); if (!details.isConnected) return;
      if (!append) output.replaceChildren(element('p', 'help-text', 'Append-only moderation metadata. Source, credentials and private reference text are not logged here.'));
      for (const row of data.items) {
        const item = element('article', 'contribution-row'); item.append(element('strong', '', row.action),
          element('p', 'help-text', `${date(row.createdAt)} · target ${row.targetKey} · actor ${row.actorId || 'trusted host or deleted account'}`));
        const metadata = element('pre', 'record-json', JSON.stringify(row.details, null, 2)); item.append(metadata); output.append(item);
      }
      if (!data.items.length && !append) output.append(element('p', 'help-text', 'No moderation events recorded yet.'));
      if (data.nextCursor) { const more = button('More audit events', () => { more.remove(); load(data.nextCursor, true); }); output.append(more); }
    } catch (error) { output.replaceChildren(element('p', 'error', error.message), button('Retry audit trail', () => load())); }
  }
  details.addEventListener('toggle', () => { if (details.open) load(); });
}

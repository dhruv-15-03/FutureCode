import { splitSourceLines } from './exercise.js';

export function element(tag, className, content) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (content !== undefined) node.textContent = content;
  return node;
}
export function providerName(value) {
  if (value === 'openai') return 'OpenAI';
  if (value === 'gemini') return 'Google Gemini';
  throw new Error('Choose a supported AI provider.');
}
export function providerRequest(value) {
  providerName(value);
  return value === 'openai' ? {} : { provider: value };
}

export function renderSource(host, code, highlights = []) {
  host.replaceChildren();
  splitSourceLines(code).forEach((line, index) => {
    const row = element('span', `code-line${highlights.includes(index + 1) ? ' highlight' : ''}`);
    const number = element('span', 'line-number', String(index + 1).padStart(2, '0'));
    number.setAttribute('aria-hidden', 'true');
    row.append(number, document.createTextNode(line));
    host.append(row);
  });
}

export function answerOptions(host, question, group, onSelect, selected) {
  host.replaceChildren();
  question.options.forEach((option, index) => {
    const label = element('label', 'answer-option');
    const input = document.createElement('input');
    input.type = 'radio'; input.name = group; input.value = String(index);
    input.checked = selected === index;
    input.addEventListener('change', () => onSelect(index));
    const letter = element('span', 'option-letter', ['A', 'B', 'C', 'D'][index]);
    letter.setAttribute('aria-hidden', 'true');
    label.append(input, letter, element('span', '', option));
    host.append(label);
  });
}

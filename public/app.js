const categories = {
  expense: ['Food & dining', 'Shopping', 'Transport', 'Bills & utilities', 'Entertainment', 'Health & wellness', 'Other'],
  income: ['Salary', 'Freelance', 'Other income']
};
const categoryColors = ['#478765', '#a4cb89', '#d9bc88', '#9eb9a8', '#d8e8c9'];
const currency = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });
const dateFormatter = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' });
const monthFormatter = new Intl.DateTimeFormat('en-US', { month: 'short' });
const $ = (selector) => document.querySelector(selector);
let transactions = [];
let editingId = null;
let toastTimeout;

function cents(amount) {
  return Math.round(Number(amount) * 100);
}

function money(value) {
  return currency.format(value / 100);
}

function localDate(date = new Date()) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function element(tag, className, text) {
  const item = document.createElement(tag);
  if (className) item.className = className;
  if (text !== undefined) item.textContent = text;
  return item;
}

function icon(id) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', 'icon');
  svg.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', `#icon-${id}`);
  svg.append(use);
  return svg;
}

function showToast(message) {
  const toast = $('#toast');
  toast.textContent = message;
  toast.classList.add('visible');
  clearTimeout(toastTimeout);
  toastTimeout = setTimeout(() => toast.classList.remove('visible'), 3000);
}

async function request(url, options = {}) {
  const response = await fetch(url, options);
  if (!response.ok) {
    let message = 'Something went wrong. Please try again.';
    try {
      const body = await response.json();
      if (body.error) message = body.error;
    } catch {}
    throw new Error(message);
  }
  return response.status === 204 ? null : response.json();
}

async function loadTransactions() {
  try {
    transactions = await request('/api/transactions');
    $('#connection-error').hidden = true;
    render();
  } catch (error) {
    $('#connection-error').textContent = `Could not load your transactions. ${error.message}`;
    $('#connection-error').hidden = false;
    $('#transaction-count').textContent = 'Transactions unavailable';
  }
}

function renderSummary() {
  const month = localDate().slice(0, 7);
  let balance = 0;
  let income = 0;
  let expenses = 0;
  for (const transaction of transactions) {
    const amount = cents(transaction.amount);
    balance += transaction.type === 'income' ? amount : -amount;
    if (transaction.date.startsWith(month)) {
      if (transaction.type === 'income') income += amount;
      else expenses += amount;
    }
  }
  $('#total-balance').textContent = money(balance);
  $('#month-income').textContent = money(income);
  $('#month-expenses').textContent = money(expenses);
  $('#breakdown-total').textContent = money(expenses);
}

function renderCashflow() {
  const chart = $('#cashflow-chart');
  chart.replaceChildren();
  const now = new Date();
  const months = Array.from({ length: 6 }, (_, i) => {
    const date = new Date(now.getFullYear(), now.getMonth() - 5 + i, 1);
    return { date, key: localDate(date).slice(0, 7), income: 0, expense: 0 };
  });
  for (const transaction of transactions) {
    const month = months.find((item) => item.key === transaction.date.slice(0, 7));
    if (month) month[transaction.type] += cents(transaction.amount);
  }
  const maximum = Math.max(1, ...months.flatMap((month) => [month.income, month.expense]));
  chart.setAttribute('aria-label', `Income and expenses for the last six months: ${months.map((month) => `${monthFormatter.format(month.date)} ${month.date.getFullYear()}, income ${money(month.income)}, expenses ${money(month.expense)}`).join('; ')}`);
  for (const month of months) {
    const group = element('div', 'chart-group');
    const bars = element('div', 'chart-bars');
    for (const type of ['income', 'expense']) {
      const bar = element('div', `chart-bar ${type}`);
      bar.style.height = `${month[type] / maximum * 100}%`;
      bar.title = `${monthFormatter.format(month.date)} ${month.date.getFullYear()} ${type}: ${money(month[type])}`;
      bars.append(bar);
    }
    group.append(bars, element('span', 'chart-month', monthFormatter.format(month.date)));
    chart.append(group);
  }
}

function renderBreakdown() {
  const totals = new Map();
  const month = localDate().slice(0, 7);
  for (const transaction of transactions) {
    if (transaction.type === 'expense' && transaction.date.startsWith(month)) {
      totals.set(transaction.category, (totals.get(transaction.category) || 0) + cents(transaction.amount));
    }
  }
  const entries = [...totals.entries()].sort((a, b) => b[1] - a[1]);
  const total = entries.reduce((sum, entry) => sum + entry[1], 0);
  const bar = $('#category-bar');
  const list = $('#category-list');
  bar.replaceChildren();
  list.replaceChildren();
  if (!total) {
    list.append(element('p', 'no-spending', 'No expenses this month yet. Add a transaction to see your breakdown.'));
    return;
  }
  const displayed = entries.slice(0, 4);
  if (entries.length > 4) displayed.push(['Other categories', entries.slice(4).reduce((sum, entry) => sum + entry[1], 0)]);
  displayed.forEach(([category, amount], index) => {
    const percentage = amount / total * 100;
    const segment = element('span');
    segment.style.backgroundColor = categoryColors[index];
    segment.style.width = `${percentage}%`;
    bar.append(segment);
    const row = element('div', 'category-row');
    const dot = element('span', 'category-dot');
    dot.style.backgroundColor = categoryColors[index];
    row.append(dot, element('span', 'category-name', category), element('span', 'category-amount', money(amount)), element('span', 'category-percent', `${Math.round(percentage)}%`));
    list.append(row);
  });
}

function actionButton(action, transaction) {
  const button = element('button', `icon-button ${action === 'delete' ? 'delete-button' : ''}`);
  button.type = 'button';
  button.dataset.action = action;
  button.dataset.id = transaction.id;
  button.setAttribute('aria-label', `${action === 'edit' ? 'Edit' : 'Delete'} ${transaction.description}`);
  button.title = action === 'edit' ? 'Edit' : 'Delete';
  button.append(icon(action === 'edit' ? 'edit' : 'trash'));
  return button;
}

function renderTransactions() {
  const search = $('#search').value.trim().toLowerCase();
  const type = $('#type-filter').value;
  const filtered = transactions.filter((transaction) => {
    const matchesType = type === 'all' || transaction.type === type;
    const matchesSearch = `${transaction.description} ${transaction.category}`.toLowerCase().includes(search);
    return matchesType && matchesSearch;
  }).sort((a, b) => b.date.localeCompare(a.date));
  const rows = $('#transaction-rows');
  rows.replaceChildren();
  for (const transaction of filtered) {
    const row = element('tr');
    const nameCell = element('td');
    const name = element('div', 'transaction-name');
    const symbol = element('span', `transaction-symbol is-${transaction.type}`, transaction.type === 'income' ? '↙' : '↗');
    symbol.setAttribute('aria-hidden', 'true');
    name.append(symbol, element('span', '', transaction.description));
    nameCell.append(name);
    const categoryCell = element('td');
    categoryCell.append(element('span', 'category-chip', transaction.category));
    const dateCell = element('td', '', dateFormatter.format(new Date(`${transaction.date}T00:00:00Z`)));
    const amountCell = element('td', `transaction-amount is-${transaction.type}`, `${transaction.type === 'income' ? '+' : '−'}${money(cents(transaction.amount))}`);
    const actionsCell = element('td');
    const actions = element('div', 'row-actions');
    actions.append(actionButton('edit', transaction), actionButton('delete', transaction));
    actionsCell.append(actions);
    row.append(nameCell, categoryCell, dateCell, amountCell, actionsCell);
    rows.append(row);
  }
  $('#empty-state').hidden = filtered.length !== 0;
  $('#transaction-count').textContent = `Showing ${filtered.length} of ${transactions.length} ${transactions.length === 1 ? 'transaction' : 'transactions'}`;
}

function render() {
  renderSummary();
  renderCashflow();
  renderBreakdown();
  renderTransactions();
}

function updateCategories(selected) {
  const select = $('#category');
  select.replaceChildren();
  for (const name of categories[$('#transaction-type').value]) {
    const option = element('option', '', name);
    option.value = name;
    select.append(option);
  }
  if (selected) select.value = selected;
}

function openDialog(transaction = null) {
  editingId = transaction?.id || null;
  $('#transaction-form').reset();
  $('#form-error').hidden = true;
  $('#dialog-title').textContent = transaction ? 'Edit transaction' : 'Add transaction';
  $('#save-transaction').firstChild.textContent = transaction ? 'Save changes ' : 'Save transaction ';
  $('#transaction-type').value = transaction?.type || 'expense';
  updateCategories(transaction?.category);
  $('#description').value = transaction?.description || '';
  $('#amount').value = transaction ? Number(transaction.amount).toFixed(2) : '';
  $('#date').value = transaction?.date || localDate();
  $('#transaction-dialog').showModal();
  $('#description').focus();
}

$('#add-transaction').addEventListener('click', () => openDialog());
$('#close-dialog').addEventListener('click', () => $('#transaction-dialog').close());
$('#cancel-dialog').addEventListener('click', () => $('#transaction-dialog').close());
$('#transaction-type').addEventListener('change', () => updateCategories());
$('#search').addEventListener('input', renderTransactions);
$('#type-filter').addEventListener('change', renderTransactions);
$('#transaction-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const error = $('#form-error');
  const data = new URLSearchParams(new FormData(form));
  data.set('description', data.get('description').trim());
  if (!data.get('description')) {
    error.textContent = 'Please enter a description.';
    error.hidden = false;
    $('#description').focus();
    return;
  }
  const button = $('#save-transaction');
  const transactionId = editingId;
  button.disabled = true;
  $('#close-dialog').disabled = true;
  $('#cancel-dialog').disabled = true;
  error.hidden = true;
  try {
    const saved = await request(transactionId ? `/api/transactions/${encodeURIComponent(transactionId)}` : '/api/transactions', {
      method: transactionId ? 'PUT' : 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: data
    });
    if (transactionId) transactions = transactions.map((item) => item.id === saved.id ? saved : item);
    else transactions.push(saved);
    $('#transaction-dialog').close();
    render();
    showToast(transactionId ? 'Transaction updated.' : 'Transaction added.');
  } catch (failure) {
    error.textContent = failure.message;
    error.hidden = false;
  } finally {
    button.disabled = false;
    $('#close-dialog').disabled = false;
    $('#cancel-dialog').disabled = false;
  }
});
$('#transaction-dialog').addEventListener('cancel', (event) => {
  if ($('#save-transaction').disabled) event.preventDefault();
});

$('#transaction-rows').addEventListener('click', async (event) => {
  const button = event.target.closest('button[data-action]');
  if (!button) return;
  const transaction = transactions.find((item) => item.id === button.dataset.id);
  if (!transaction) return;
  if (button.dataset.action === 'edit') {
    openDialog(transaction);
    return;
  }
  if (!window.confirm(`Delete “${transaction.description}”? This cannot be undone.`)) return;
  button.disabled = true;
  try {
    await request(`/api/transactions/${encodeURIComponent(transaction.id)}`, { method: 'DELETE' });
    transactions = transactions.filter((item) => item.id !== transaction.id);
    render();
    showToast('Transaction deleted.');
  } catch (failure) {
    showToast(failure.message);
    button.disabled = false;
  }
});

document.querySelectorAll('.nav-link').forEach((link) => {
  link.addEventListener('click', () => {
    document.querySelectorAll('.nav-link').forEach((item) => item.classList.remove('active'));
    link.classList.add('active');
  });
});

const now = new Date();
$('#today-label').textContent = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric' }).format(now);
$('#year').textContent = now.getFullYear();
loadTransactions();

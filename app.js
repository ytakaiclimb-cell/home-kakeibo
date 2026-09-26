/* さっと家計簿 — 端末内だけで完結する家計簿 */
(() => {
  'use strict';

  const STORE_KEY = 'sat-kakeibo/v1';

  const DEFAULT_CATEGORIES = [
    { id: 'food',      emoji: '🍚', name: '食費',   quick: 1000 },
    { id: 'cafe',      emoji: '☕', name: 'カフェ', quick: 500  },
    { id: 'transport', emoji: '🚃', name: '交通',   quick: 300  },
    { id: 'daily',     emoji: '🧻', name: '日用品', quick: 800  },
    { id: 'fun',       emoji: '🎮', name: '娯楽',   quick: 2000 },
    { id: 'other',     emoji: '💠', name: 'その他', quick: 1000 },
  ];

  const DEFAULT_STATE = {
    budget: 50000,
    categories: DEFAULT_CATEGORIES,
    entries: [], // { id, catId, amount, at (ISO) }
  };

  // ---------- 保存 ----------
  function load() {
    try {
      const raw = localStorage.getItem(STORE_KEY);
      if (!raw) return structuredClone(DEFAULT_STATE);
      const parsed = JSON.parse(raw);
      return {
        budget: Number.isFinite(parsed.budget) ? parsed.budget : DEFAULT_STATE.budget,
        categories: Array.isArray(parsed.categories) && parsed.categories.length
          ? parsed.categories : structuredClone(DEFAULT_CATEGORIES),
        entries: Array.isArray(parsed.entries) ? parsed.entries : [],
      };
    } catch (e) {
      console.warn('読み込みに失敗したので初期状態で開始します', e);
      return structuredClone(DEFAULT_STATE);
    }
  }

  function save() {
    try {
      localStorage.setItem(STORE_KEY, JSON.stringify(state));
    } catch (e) {
      console.warn('保存に失敗しました', e);
    }
  }

  let state = load();

  // ---------- 小道具 ----------
  const $ = (sel) => document.querySelector(sel);
  const yen = (n) => '¥' + Math.round(n).toLocaleString('ja-JP');
  const monthKey = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
  const thisMonth = () => monthKey(new Date());
  const catOf = (id) => state.categories.find((c) => c.id === id)
    || { emoji: '💠', name: 'その他', quick: 1000 };

  function buzz(ms = 12) {
    if (navigator.vibrate) { try { navigator.vibrate(ms); } catch (_) {} }
  }

  function monthEntries() {
    const m = thisMonth();
    return state.entries.filter((e) => monthKey(new Date(e.at)) === m);
  }

  // ---------- 描画 ----------
  function renderBalance() {
    const now = new Date();
    $('#monthLabel').textContent = `${now.getMonth() + 1}月`;

    const spent = monthEntries().reduce((s, e) => s + e.amount, 0);
    const budget = state.budget;
    const left = budget - spent;

    const amountEl = $('#balanceAmount');
    amountEl.textContent = yen(left);
    amountEl.classList.toggle('over', left < 0);

    $('#spentAmount').textContent = yen(spent);
    $('#budgetAmount').textContent = yen(budget);

    const ratio = budget > 0 ? spent / budget : 0;
    const fill = $('#meterFill');
    fill.style.width = Math.min(ratio, 1) * 100 + '%';
    fill.classList.toggle('warn', ratio >= 0.8 && ratio < 1);
    fill.classList.toggle('over', ratio >= 1);

    $('#paceNote').textContent = paceNote(spent, budget, now);
  }

  function paceNote(spent, budget, now) {
    if (budget <= 0) return '';
    const daysInMonth = new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate();
    const daysLeft = daysInMonth - now.getDate() + 1;
    const left = budget - spent;
    if (left < 0) return `予算を ${yen(-left)} 超えています`;
    return `残り${daysLeft}日 ・ 1日あたり ${yen(left / daysLeft)} 使えます`;
  }

  function renderQuickGrid() {
    const grid = $('#quickGrid');
    grid.textContent = '';
    for (const cat of state.categories) {
      const btn = document.createElement('button');
      btn.className = 'tile';
      btn.type = 'button';
      btn.dataset.cat = cat.id;
      btn.setAttribute('aria-label', `${cat.name} ${yen(cat.quick)} を記録`);
      btn.innerHTML =
        `<span class="emoji">${cat.emoji}</span>` +
        `<span class="name"></span>` +
        `<span class="amt"></span>`;
      btn.querySelector('.name').textContent = cat.name;
      btn.querySelector('.amt').textContent = yen(cat.quick);
      btn.addEventListener('click', () => {
        addEntry(cat.id, cat.quick);
        btn.classList.remove('popped');
        void btn.offsetWidth; // アニメーションを再生し直す
        btn.classList.add('popped');
        buzz();
      });
      grid.appendChild(btn);
    }
  }

  function renderHistory() {
    const list = $('#historyList');
    const entries = monthEntries().slice().sort((a, b) => new Date(b.at) - new Date(a.at));
    list.textContent = '';
    $('#historyEmpty').hidden = entries.length > 0;
    $('#historyCount').textContent = entries.length ? `${entries.length}件` : '';

    for (const entry of entries.slice(0, 50)) {
      const cat = catOf(entry.catId);
      const at = new Date(entry.at);
      const li = document.createElement('li');
      li.className = 'item';
      li.innerHTML =
        `<span class="emoji">${cat.emoji}</span>` +
        `<span class="meta"><b></b><small></small></span>` +
        `<span class="amt"></span>` +
        `<button class="del" type="button" aria-label="この記録を削除">×</button>`;
      li.querySelector('b').textContent = cat.name;
      li.querySelector('small').textContent =
        `${at.getMonth() + 1}/${at.getDate()} ${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}`;
      li.querySelector('.amt').textContent = yen(entry.amount);
      li.querySelector('.del').addEventListener('click', () => removeEntry(entry.id));
      list.appendChild(li);
    }
  }

  function render() {
    renderBalance();
    renderQuickGrid();
    renderHistory();
  }

  // ---------- 記録 ----------
  function addEntry(catId, amount) {
    if (!(amount > 0)) return;
    const entry = {
      id: (crypto.randomUUID ? crypto.randomUUID() : String(Date.now() + Math.random())),
      catId,
      amount: Math.round(amount),
      at: new Date().toISOString(),
    };
    state.entries.push(entry);
    save();
    render();
    showToast(`${catOf(catId).name} ${yen(entry.amount)} を記録`, () => removeEntry(entry.id));
  }

  function removeEntry(id) {
    state.entries = state.entries.filter((e) => e.id !== id);
    save();
    render();
  }

  // ---------- トースト ----------
  let toastTimer = null;
  function showToast(text, onUndo) {
    const toast = $('#toast');
    $('#toastText').textContent = text;
    const undo = $('#toastUndo');
    undo.hidden = !onUndo;
    undo.onclick = () => { if (onUndo) onUndo(); hideToast(); };
    toast.hidden = false;
    requestAnimationFrame(() => toast.classList.add('show'));
    clearTimeout(toastTimer);
    toastTimer = setTimeout(hideToast, 4000);
  }

  function hideToast() {
    const toast = $('#toast');
    toast.classList.remove('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { toast.hidden = true; }, 300);
  }

  // ---------- シート ----------
  function openSheet(el) {
    el.hidden = false;
    requestAnimationFrame(() => el.classList.add('show'));
  }

  function closeSheet(el) {
    el.classList.remove('show');
    setTimeout(() => { el.hidden = true; }, 260);
  }

  document.addEventListener('click', (ev) => {
    const closer = ev.target.closest('[data-close-sheet]');
    if (closer) closeSheet(closer.closest('.sheet-backdrop'));
  });

  for (const backdrop of document.querySelectorAll('.sheet-backdrop')) {
    backdrop.addEventListener('click', (ev) => {
      if (ev.target === backdrop) closeSheet(backdrop);
    });
  }

  document.addEventListener('keydown', (ev) => {
    if (ev.key !== 'Escape') return;
    for (const b of document.querySelectorAll('.sheet-backdrop.show')) closeSheet(b);
  });

  // ---------- テンキー ----------
  let padDigits = '';
  let padCat = state.categories[0].id;

  function renderPad() {
    $('#padAmount').textContent = yen(Number(padDigits || 0));
    const wrap = $('#padCats');
    wrap.textContent = '';
    for (const cat of state.categories) {
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'chip';
      chip.textContent = `${cat.emoji} ${cat.name}`;
      chip.setAttribute('aria-pressed', String(cat.id === padCat));
      chip.addEventListener('click', () => { padCat = cat.id; renderPad(); });
      wrap.appendChild(chip);
    }
  }

  $('#openPad').addEventListener('click', () => {
    padDigits = '';
    if (!state.categories.some((c) => c.id === padCat)) padCat = state.categories[0].id;
    renderPad();
    openSheet($('#padSheet'));
  });

  $('.pad').addEventListener('click', (ev) => {
    const key = ev.target.closest('button')?.dataset.key;
    if (!key) return;
    if (key === 'del') padDigits = padDigits.slice(0, -1);
    else if (padDigits.length < 9) padDigits = (padDigits + key).replace(/^0+(?=\d)/, '');
    buzz(8);
    renderPad();
  });

  $('#padSave').addEventListener('click', () => {
    const amount = Number(padDigits || 0);
    if (!(amount > 0)) { showToast('金額を入力してください'); return; }
    addEntry(padCat, amount);
    closeSheet($('#padSheet'));
  });

  // ---------- 設定 ----------
  function renderSettings() {
    $('#budgetInput').value = state.budget;
    const wrap = $('#catSettings');
    wrap.textContent = '';
    for (const cat of state.categories) {
      const row = document.createElement('div');
      row.className = 'cat-row';
      row.innerHTML =
        `<span class="emoji">${cat.emoji}</span>` +
        `<span class="name"></span>` +
        `<input type="number" inputmode="numeric" min="0" step="100">`;
      row.querySelector('.name').textContent = cat.name;
      const input = row.querySelector('input');
      input.value = cat.quick;
      input.setAttribute('aria-label', `${cat.name}のワンタップ金額`);
      input.addEventListener('change', () => {
        const v = Math.max(0, Math.round(Number(input.value) || 0));
        cat.quick = v;
        input.value = v;
        save();
        renderQuickGrid();
      });
      wrap.appendChild(row);
    }
  }

  $('#openSettings').addEventListener('click', () => {
    renderSettings();
    openSheet($('#settingsSheet'));
  });

  $('#budgetInput').addEventListener('change', (ev) => {
    state.budget = Math.max(0, Math.round(Number(ev.target.value) || 0));
    ev.target.value = state.budget;
    save();
    renderBalance();
  });

  $('#exportBtn').addEventListener('click', () => {
    const blob = new Blob([JSON.stringify(state, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `kakeibo-${thisMonth()}.json`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });

  $('#resetBtn').addEventListener('click', () => {
    const count = monthEntries().length;
    if (!count) { showToast('今月の記録はまだありません'); return; }
    if (!confirm(`今月の記録 ${count}件 を削除します。よろしいですか？`)) return;
    const removed = monthEntries().map((e) => e.id);
    const backup = state.entries;
    state.entries = state.entries.filter((e) => !removed.includes(e.id));
    save();
    render();
    showToast(`${count}件を削除しました`, () => { state.entries = backup; save(); render(); });
  });

  // ---------- 月替わり・復帰時の更新 ----------
  let lastMonth = thisMonth();
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible') return;
    if (thisMonth() !== lastMonth) lastMonth = thisMonth();
    render();
  });

  // ---------- ホーム画面ショートカットからの記録 ----------
  // 例: index.html?quick=food → 開いた瞬間に食費のワンタップ金額を記録
  function handleQuickParam() {
    const id = new URLSearchParams(location.search).get('quick');
    if (!id) return;
    history.replaceState(null, '', location.pathname);
    const cat = state.categories.find((c) => c.id === id);
    if (!cat) return;
    addEntry(cat.id, cat.quick); // 起動直後は操作がないので振動は鳴らさない
  }

  // ---------- 起動 ----------
  render();
  handleQuickParam();

  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('sw.js').catch((e) => console.warn('SW登録に失敗', e));
    });
  }
})();

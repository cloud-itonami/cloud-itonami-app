
  document.addEventListener('DOMContentLoaded', () => {
    const $ = (selector, root = document) => root.querySelector(selector);
    const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
    const make = (tag, className, text) => {
      const node = document.createElement(tag);
      if (className) node.className = className;
      if (text !== undefined && text !== null) node.textContent = text;
      return node;
    };
    // A safe Markdown preview for model messages. Model text is never assigned
    // to innerHTML: every character becomes a text node, and links are admitted
    // by protocol. This preserves the old XSS boundary while making ordinary
    // headings, lists, emphasis and code readable.
    const appendMarkdownInline = (node, source) => {
      source = String(source || '');
      const pattern = /(\*\*[^*\n]+\*\*|`[^`\n]+`|\[[^\]\n]+\]\([^\s)]+\))/g;
      let cursor = 0;
      for (const match of source.matchAll(pattern)) {
        if (match.index > cursor) {
          node.append(document.createTextNode(source.slice(cursor, match.index)));
        }
        const token = match[0];
        if (token.startsWith('**')) {
          const strong = make('strong');
          strong.textContent = token.slice(2, -2);
          node.append(strong);
        } else if (token.startsWith('`')) {
          const code = make('code');
          code.textContent = token.slice(1, -1);
          node.append(code);
        } else {
          const parts = /^\[([^\]]+)\]\(([^)]+)\)$/.exec(token);
          let safe = null;
          try {
            const url = new URL(parts[2], location.href);
            if (['http:', 'https:', 'mailto:'].includes(url.protocol)) safe = url.href;
          } catch (_) { /* malformed links remain ordinary text */ }
          if (safe) {
            const link = make('a');
            link.href = safe;
            link.textContent = parts[1];
            link.rel = 'noopener noreferrer';
            node.append(link);
          } else node.append(document.createTextNode(token));
        }
        cursor = match.index + token.length;
      }
      if (cursor < source.length) {
        node.append(document.createTextNode(source.slice(cursor)));
      }
    };
    const renderMarkdown = (node, source) => {
      node.replaceChildren();
      const lines = String(source || '').replace(/\r\n?/g, '\n').split('\n');
      let index = 0;
      while (index < lines.length) {
        const line = lines[index];
        if (!line.trim()) { index += 1; continue; }
        if (line.trim().startsWith('```')) {
          const language = line.trim().slice(3).trim();
          const body = [];
          index += 1;
          while (index < lines.length && !lines[index].trim().startsWith('```')) {
            body.push(lines[index]); index += 1;
          }
          if (index < lines.length) index += 1;
          const pre = make('pre');
          const code = make('code');
          if (language) code.dataset.language = language;
          code.textContent = body.join('\n');
          pre.append(code); node.append(pre); continue;
        }
        const heading = /^(#{1,4})\s+(.+)$/.exec(line);
        if (heading) {
          const title = make(`h${Math.min(4, heading[1].length + 2)}`);
          appendMarkdownInline(title, heading[2]);
          node.append(title); index += 1; continue;
        }
        const quote = /^>\s?(.*)$/.exec(line);
        if (quote) {
          const block = make('blockquote');
          appendMarkdownInline(block, quote[1]);
          node.append(block); index += 1; continue;
        }
        const item = /^\s*(?:(\d+)\.|[-*])\s+(.+)$/.exec(line);
        if (item) {
          const ordered = Boolean(item[1]);
          const list = make(ordered ? 'ol' : 'ul');
          while (index < lines.length) {
            const next = /^\s*(?:(\d+)\.|[-*])\s+(.+)$/.exec(lines[index]);
            if (!next || Boolean(next[1]) !== ordered) break;
            const li = make('li');
            appendMarkdownInline(li, next[2]);
            list.append(li); index += 1;
          }
          node.append(list); continue;
        }
        const paragraph = [];
        while (index < lines.length && lines[index].trim() &&
               !/^(#{1,4})\s+|^\s*(?:(\d+)\.|[-*])\s+|^>\s?|^```/.test(lines[index])) {
          paragraph.push(lines[index].trim()); index += 1;
        }
        const p = make('p');
        appendMarkdownInline(p, paragraph.join('\n'));
        node.append(p);
      }
      return node;
    };
    const initialParams = new URLSearchParams(location.search);
    // The native host still owns the traffic lights and window behaviour. This
    // marker only tells the web chrome to reserve their inset after the host
    // extends the content view into the titlebar. An ordinary browser never
    // receives it and keeps the normal page spacing.
    if (initialParams.get('chrome') === 'titlebar-overlay') {
      document.body.dataset.nativeTitlebar = 'overlay';
    }
    const initialFragment = location.hash.slice(1);
    // Capture the one-time proof before showView() replaces the fragment.
    // Reading location.hash again near the end of this file loses the token
    // to the initial view redirect, so a valid link otherwise looks inert.
    //
    // Views are addressed as `#/name` (kami-app-nle / ADR-2608080100). The
    // legacy `#name` form still resolves so old redirects keep working.
    const viewFromHash = (raw) => {
      if (!raw) return '';
      const path = raw.split('?')[0].replace(/^\//, '');
      if (!path || path.includes('=')) return '';
      // These former destinations are now aspects of Bots or Settings. Keep
      // old bookmarks useful without preserving four competing top-level tabs.
      return ({chat:'bots', rooms:'bots', capture:'bots', memory:'settings'})[path] || path;
    };
    const emailLoginToken = new URLSearchParams(
      initialFragment.includes('email-login=')
        ? (initialFragment.includes('?') ? initialFragment.slice(initialFragment.indexOf('?') + 1) : initialFragment)
        : ''
    ).get('email-login');
    const requestedView = emailLoginToken ? 'settings' : (viewFromHash(initialFragment) || 'bots');
    let appUnlocked = false;
    let appBootstrapped = false;
    // Views whose data is public, so the Passkey gate would protect nothing.
    // `storage` reads public Filecoin chain state and computes a PieceCID —
    // there is no workspace content in it. Everything else stays gated.
    const publicViews = new Set(['signin', 'storage']);
    let currentView = 'signin';
    const sidebar = $('.sidebar');
    const mobileMenuToggle = $('.mobile-menu-toggle');
    const mobileNavBackdrop = $('.mobile-nav-backdrop');
    const setMobileMenuOpen = (open) => {
      sidebar.dataset.mobileMenuOpen = String(open);
      mobileMenuToggle.setAttribute('aria-expanded', String(open));
      document.body.classList.toggle('has-mobile-menu', open);
    };
    mobileMenuToggle.addEventListener('click', () =>
      setMobileMenuOpen(mobileMenuToggle.getAttribute('aria-expanded') !== 'true'));
    mobileNavBackdrop.addEventListener('click', () => setMobileMenuOpen(false));
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') setMobileMenuOpen(false);
    });
    // Assigned once the worker surface is defined further down; showView runs
    // before that, so it must not name the worker helpers directly.
    let onViewChange = () => {};
    const formatDate = (value, timeOnly = false) => {
      if (!value) return '日時不明';
      const date = new Date(value);
      if (Number.isNaN(date.valueOf())) return value;
      return new Intl.DateTimeFormat('ja-JP', timeOnly
        ? {hour:'2-digit', minute:'2-digit'}
        : {month:'numeric', day:'numeric', weekday:'short', hour:'2-digit', minute:'2-digit'}
      ).format(date);
    };
    const showView = (name) => {
      if (!appUnlocked && !publicViews.has(name)) name = 'signin';
      $$('.local-nav__item').forEach((item) => item.setAttribute(
        'aria-current', item.dataset.view === name ? 'page' : 'false'));
      $$('.view').forEach((panel) => { panel.hidden = panel.dataset.viewPanel !== name; });
      const active = $(`.local-nav__item[data-view='${name}']`);
      // A deep-linked or programmatically selected view must reveal its place
      // in the information architecture, even when its section starts closed.
      active?.closest('.nav-section')?.setAttribute('open', '');
      $('#current-view').textContent = active?.dataset.title || 'Bots';
      $$('[data-topbar-view]').forEach((context) => {
        context.hidden = !appUnlocked || context.dataset.topbarView !== name;
      });
      const target = `#/${name}`;
      if (location.hash !== target) history.replaceState(null, '', target);
      const brand = document.querySelector('.workspace')?.dataset.brand || 'Cloud Itonami';
      document.title = `${active?.dataset.title || 'Bots'} | ${brand}`;
      document.body.dataset.currentView = name;
      currentView = name;
      onViewChange(name);
    };
    $$('.local-nav__item').forEach((item) =>
      item.addEventListener('click', () => setMobileMenuOpen(false)));
    window.addEventListener('hashchange', () => {
      const name = viewFromHash(location.hash.slice(1));
      if (name && $$('.view').some((panel) => panel.dataset.viewPanel === name)) {
        showView(name);
      }
    });
    showView('signin');

    const form = $('#chat-form');
    const prompt = $('#prompt');
    const send = $('#send-button');
    const stop = $('#stop-button');
    const status = $('#request-status');
    const thread = $('#chat-thread');
    const empty = $('#chat-empty');
    const scroll = $('#chat-scroll');
    const chatShell = $('#chat-shell');
    const modelSelect = $('#model-select');
    let sessionId = localStorage.getItem('cloud-itonami-session') || 'desktop';
    const legacyProjectId = localStorage.getItem('cloud-itonami-project') || '';
    let chatContextProjectId =
      localStorage.getItem('cloud-itonami-chat-context-project') || legacyProjectId;
    let selectedProjectId =
      localStorage.getItem('cloud-itonami-project-board') || legacyProjectId;
    let localProjects = [];
    let selectedSiteId = null;
    let currentController = null;
    let lastPrompt = '';
    let generating = false;
    const announce = (message) => {
      status.textContent = message;
      $('#chat-agent-state').textContent = message;
    };
    const scrollToEnd = () => requestAnimationFrame(() => {
      scroll.scrollTop = scroll.scrollHeight;
    });
    const resizePrompt = () => {
      prompt.style.height = 'auto';
      prompt.style.height = `${Math.min(prompt.scrollHeight, 192)}px`;
      send.disabled = !prompt.value.trim() || generating;
    };
    const setGenerating = (value) => {
      generating = value;
      send.hidden = value;
      stop.hidden = !value;
      prompt.disabled = value;
      if (!value) { prompt.disabled = false; resizePrompt(); }
    };
    const addAction = (container, label, handler) => {
      const button = make('button', 'message-action', label);
      button.type = 'button';
      button.addEventListener('click', handler);
      container.append(button);
    };
    const addMessage = (role, content, options = {}) => {
      empty.hidden = true;
      const row = make('article', `message-row message-row--${role}`);
      row.dataset.role = role;
      const body = make('div', 'message-body');
      const text = make('div', 'message-content', content);
      const actions = make('div', 'message-actions');
      if (role === 'assistant') {
        row.append(make('div', 'message-avatar', 'ai'));
        body.append(make('p', 'message-author', options.author || 'Local'), text, actions);
        row.append(body);
      } else {
        body.append(text);
        row.append(body);
      }
      thread.append(row);
      if (content && role === 'assistant') {
        addAction(actions, 'コピー', async () => {
          await navigator.clipboard.writeText(text.textContent);
          announce('応答をコピーしました。');
        });
      }
      scrollToEnd();
      return {row, body, text, actions};
    };
    const addAssistantActions = (message, promptValue) => {
      addAction(message.actions, 'コピー', async () => {
        await navigator.clipboard.writeText(message.text.textContent);
        announce('応答をコピーしました。');
      });
      if (promptValue) addAction(message.actions, 'もう一度', () => {
        prompt.value = promptValue;
        resizePrompt();
        form.requestSubmit();
      });
    };
    const loadSession = async () => {
      const requestedProject = chatContextProjectId;
      try {
        const params = new URLSearchParams({session:sessionId});
        if (requestedProject) params.set('project', requestedProject);
        const request = await fetch(`/api/session?${params}`);
        const data = await request.json();
        if (chatContextProjectId !== requestedProject) return false;
        thread.querySelectorAll('.message-row').forEach((node) => node.remove());
        data.messages.forEach((message) => {
          if (message.role === 'user') lastPrompt = message.content;
          const rendered = addMessage(message.role, message.content,
            {author: message.role === 'assistant' ? 'Local' : 'あなた'});
          if (message.role === 'assistant' && lastPrompt) {
            addAction(rendered.actions, 'もう一度', () => {
              prompt.value = lastPrompt;
              resizePrompt();
              form.requestSubmit();
            });
          }
        });
        empty.hidden = data.messages.length > 0;
        announce(data.messages.length ? '会話を復元しました。' : 'ローカルモデルの準備ができています。');
      } catch (error) {
        announce(`履歴を読み込めませんでした: ${error.message}`);
      }
    };
    const parseStream = async (response, assistant, promptValue) => {
      if (!response.ok || !response.body) throw new Error('応答ストリームを開始できませんでした。');
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let received = false;
      while (true) {
        const {value, done} = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), {stream: !done});
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';
        for (const line of lines) {
          if (!line.trim()) continue;
          const event = JSON.parse(line);
          if (event.type === 'delta') {
            if (!received) { assistant.text.replaceChildren(); received = true; }
            assistant.text.textContent += event.content;
            announce('応答を生成しています…');
            scrollToEnd();
          } else if (event.type === 'done') {
            addAssistantActions(assistant, promptValue);
            announce(`${event.provider} / ${event.model} から応答しました。`);
          } else if (event.type === 'error') {
            throw new Error(event.message);
          }
        }
        if (done) break;
      }
      if (!received) throw new Error('モデルから空の応答が返されました。');
    };
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      const value = prompt.value.trim();
      if (!value || generating) {
        if (!value) announce('メッセージを入力してください。');
        return;
      }
      lastPrompt = value;
      addMessage('user', value);
      prompt.value = '';
      resizePrompt();
      const assistant = addMessage('assistant', '');
      const typing = make('div', 'typing');
      typing.setAttribute('aria-label', '応答を生成中');
      typing.append(make('span'), make('span'), make('span'));
      assistant.text.append(typing);
      currentController = new AbortController();
      setGenerating(true);
      announce('モデルに接続しています…');
      try {
        const request = await fetch('/api/chat/stream', {
          method:'POST', headers:{'Content-Type':'application/json'},
          signal:currentController.signal,
          body:JSON.stringify({prompt:value, session:sessionId, agent:'local',
            model:modelSelect.value, project:chatContextProjectId || null})
        });
        await parseStream(request, assistant, value);
      } catch (error) {
        assistant.text.replaceChildren();
        assistant.text.textContent = error.name === 'AbortError'
          ? '生成を停止しました。'
          : `応答を生成できませんでした: ${error.message}`;
        addAction(assistant.actions, '再試行', () => {
          prompt.value = value; resizePrompt(); form.requestSubmit();
        });
        announce(error.name === 'AbortError' ? '生成を停止しました。' : '応答でエラーが発生しました。');
      } finally {
        currentController = null;
        setGenerating(false);
        prompt.focus();
      }
    });
    stop.addEventListener('click', () => currentController?.abort());
    prompt.addEventListener('input', resizePrompt);
    prompt.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
        event.preventDefault(); form.requestSubmit();
      }
    });
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && generating) currentController?.abort();
    });
    $$('.suggestion-card').forEach((button) => button.addEventListener('click', () => {
      prompt.value = button.dataset.prompt; resizePrompt(); prompt.focus();
    }));
    $('#new-chat-button').addEventListener('click', () => {
      currentController?.abort();
      sessionId = `chat-${crypto.randomUUID()}`;
      localStorage.setItem('cloud-itonami-session', sessionId);
      chatShell.dataset.session = sessionId;
      thread.querySelectorAll('.message-row').forEach((node) => node.remove());
      empty.hidden = false;
      prompt.value = ''; resizePrompt(); prompt.focus();
      announce('新しいチャットを開始しました。');
    });
    modelSelect.addEventListener('change', () => {
      $('#active-model-label').textContent = `${modelSelect.dataset.provider} / ${modelSelect.value}`;
      announce(`${modelSelect.value} を選択しました。`);
    });
    fetch('/v1/models').then((request) => request.json()).then((data) => {
      const selected = modelSelect.value;
      const models = data.data || [];
      if (models.length) {
        modelSelect.replaceChildren();
        models.forEach((model) => {
          const option = make('option', null, model.id);
          option.value = model.id;
          option.selected = model.id === selected;
          modelSelect.append(option);
        });
        const workerModel = $('#worker-model');
        const workerSelected = workerModel.value;
        workerModel.replaceChildren();
        const inherited = make('option', null, '既定のモデル');
        inherited.value = '';
        workerModel.append(inherited);
        models.forEach((model) => {
          const option = make('option', null, model.id);
          option.value = model.id;
          option.selected = model.id === workerSelected;
          workerModel.append(option);
        });
      }
    }).catch(() => {});
    chatShell.dataset.session = sessionId;
    resizePrompt();

    const listItem = (title, meta, side, warn = false) => {
      const item = make('li', 'data-list__item');
      const copy = make('div');
      copy.append(make('p', 'data-list__title', title), make('p', 'data-list__meta', meta));
      const chip = make('span', `state-chip${warn ? ' state-chip--warn' : ''}`, side);
      item.append(copy, chip);
      return item;
    };
    const bytes = (value) => {
      if (!Number.isFinite(value)) return 'サイズ不明';
      if (value < 1024) return `${value} B`;
      if (value < 1048576) return `${(value / 1024).toFixed(1)} KB`;
      return `${(value / 1048576).toFixed(1)} MB`;
    };
    const setDetail = (target, eyebrow, title, body, metadata) => {
      target.replaceChildren();
      target.append(make('p', 'record-detail__eyebrow', eyebrow),
        make('h2', null, title), make('p', 'record-detail__body', body));
      const meta = make('dl', 'local-meta record-detail__meta');
      metadata.forEach(([label, value]) => {
        meta.append(make('dt', null, label), make('dd', null, value || '—'));
      });
      target.append(meta);
    };
    const recordButton = (item, selected, onSelect, fields) => {
      const row = make('li');
      const button = make('button', 'record-button');
      button.type = 'button';
      button.setAttribute('aria-pressed', selected ? 'true' : 'false');
      const top = make('span', 'record-button__top');
      top.append(make('span', 'record-button__title', fields.title),
        make('span', 'record-button__time', fields.time));
      button.append(top, make('span', 'record-button__meta', fields.meta));
      if (fields.snippet) button.append(make('span', 'record-button__snippet', fields.snippet));
      button.addEventListener('click', () => onSelect(item));
      row.append(button);
      return row;
    };
    // ── Capture: interpretation-free input, then an explicit GTD pass ──────
    let captureData = {items:[], counts:{}};
    let selectedCapture = null;
    let captureFilter = 'inbox';
    let captureRecognition = null;
    let selectedChronicleFrame = null;
    const captureLabels = {
      'next-action':'Next action', project:'Project', 'waiting-for':'Waiting for',
      'someday-maybe':'Someday / Maybe', reference:'Reference', trash:'Trash'
    };
    const captureValue = (item, name) => item[name];
    const captureField = (label, name, value='', type='text') => {
      const wrap = make('div', 'field');
      const id = `capture-clarify-${name}`;
      const input = make('input');
      input.id = id; input.name = name; input.type = type; input.value = value || '';
      wrap.append(Object.assign(make('label', null, label), {htmlFor:id}), input);
      return wrap;
    };
    const visibleCaptures = () => (captureData.items || []).filter((item) => {
      const state = captureValue(item, 'state');
      const outcome = captureValue(item, 'outcome');
      if (captureFilter === 'inbox') return state === 'unclarified';
      if (captureFilter === 'all') return state === 'clarified' && outcome !== 'trash';
      if (captureFilter === 'done') return state === 'completed';
      return state === 'clarified' && outcome === captureFilter;
    });
    const renderCaptureDetail = () => {
      const target = $('#capture-detail');
      if (!selectedCapture) {
        target.replaceChildren(make('div', 'empty-state',
          captureFilter === 'inbox' ? '未整理の記録はありません。' : '記録を選択してください。'));
        return;
      }
      const item = selectedCapture;
      const state = captureValue(item, 'state');
      const raw = make('p', 'capture-raw', captureValue(item, 'text'));
      target.replaceChildren(
        make('p', 'record-detail__eyebrow', state === 'unclarified' ? '未整理'
          : state === 'completed' ? 'Done' : captureLabels[captureValue(item, 'outcome')]),
        make('h2', null, captureValue(item, 'title') || 'そのままの記録'), raw);
      const source = captureValue(item, 'source');
      if (source?.type === 'chronicle-frame') {
        const sourceNode = make('aside', 'capture-source');
        sourceNode.append(
          make('strong', null, `Chronicle · ${source.application || '画面'} · ${formatDate(source['captured-at'])}`),
          make('p', 'form-help', '保存時に本人が選んだ、信頼しない参照文脈です。元画像は添付されていません。'));
        if (source['text-preview']) {
          sourceNode.append(make('p', 'capture-source__text', source['text-preview']));
        }
        target.append(sourceNode);
      }
      if (state === 'unclarified') {
        const form = make('form', 'settings-form');
        form.id = 'capture-clarify-form';
        const outcomeWrap = make('div', 'field');
        const outcomeLabel = make('label', null, '整理先'); outcomeLabel.htmlFor = 'capture-clarify-outcome';
        const outcome = make('select'); outcome.id = 'capture-clarify-outcome'; outcome.name = 'outcome';
        Object.entries(captureLabels).forEach(([value, label]) => {
          const option = make('option', null, label); option.value = value; outcome.append(option);
        });
        outcomeWrap.append(outcomeLabel, outcome);
        form.append(outcomeWrap,
          captureField('行動または結果の名前', 'title'),
          captureField('Project（任意）', 'project'),
          captureField('Context（任意）', 'context'),
          captureField('期限（任意）', 'due', '', 'date'),
          captureField('待っている相手・出来事（任意）', 'waiting-for'));
        const status = make('p', 'drive-create__status'); status.id = 'capture-clarify-status';
        const submit = make('button', 'primary-action', '整理する'); submit.type = 'submit';
        form.append(status, submit);
        form.addEventListener('submit', async (event) => {
          event.preventDefault(); submit.disabled = true; status.textContent = '整理しています…';
          try {
            const body = Object.fromEntries(new FormData(form));
            selectedCapture = await postJSON(
              `/api/captures/${encodeURIComponent(captureValue(item, 'id'))}/clarify`, body, true);
            await loadCaptures();
          } catch (error) { status.textContent = error.message; }
          finally { submit.disabled = false; }
        });
        target.append(form);
      } else {
        const meta = make('dl', 'local-meta record-detail__meta');
        [['Project', captureValue(item, 'project')], ['Context', captureValue(item, 'context')],
         ['期限', captureValue(item, 'due')], ['Waiting for', captureValue(item, 'waiting-for')],
         ['最終レビュー', captureValue(item, 'last-reviewed-at')
           ? formatDate(captureValue(item, 'last-reviewed-at')) : '未レビュー']]
          .forEach(([label, value]) => meta.append(make('dt', null, label), make('dd', null, value || '—')));
        const actions = make('div', 'local-actions');
        if (state === 'clarified') {
          const review = make('button', 'tool-button', 'レビュー済みにする'); review.type = 'button';
          review.addEventListener('click', async () => {
            selectedCapture = await postJSON(
              `/api/captures/${encodeURIComponent(captureValue(item, 'id'))}/review`, {}, true);
            await loadCaptures();
          });
          const complete = make('button', 'primary-action', '完了'); complete.type = 'button';
          complete.addEventListener('click', async () => {
            selectedCapture = await postJSON(
              `/api/captures/${encodeURIComponent(captureValue(item, 'id'))}/complete`, {}, true);
            captureFilter = 'done'; await loadCaptures();
          });
          actions.append(complete, review);
        }
        const reopen = make('button', 'tool-button', 'Inboxへ戻す'); reopen.type = 'button';
        reopen.addEventListener('click', async () => {
          selectedCapture = await postJSON(
            `/api/captures/${encodeURIComponent(captureValue(item, 'id'))}/reopen`, {}, true);
          captureFilter = 'inbox'; await loadCaptures();
        });
        actions.append(reopen); target.append(meta, actions);
      }
    };
    const renderCaptures = (data) => {
      captureData = data;
      const items = visibleCaptures();
      if (!items.some((item) => captureValue(item, 'id') === captureValue(selectedCapture || {}, 'id'))) {
        selectedCapture = items[0] || null;
      } else {
        selectedCapture = items.find((item) =>
          captureValue(item, 'id') === captureValue(selectedCapture, 'id'));
      }
      $$('.capture-filter').forEach((button) => button.setAttribute(
        'aria-pressed', String(button.dataset.outcome === captureFilter)));
      // The legacy GTD projection has no navigation badge now that capture is
      // ambient; keep its loader compatible for API callers without making a
      // missing, non-interactive counter a bootstrap dependency.
      const captureCount = document.getElementById('capture-count');
      if (captureCount) captureCount.textContent = data.counts?.inbox || 0;
      const list = $('#capture-list'); list.replaceChildren();
      items.forEach((item) => list.append(recordButton(item,
        captureValue(item, 'id') === captureValue(selectedCapture || {}, 'id'),
        (chosen) => { selectedCapture = chosen; renderCaptures(captureData); },
        {title:captureValue(item, 'title') || captureValue(item, 'text').trim().slice(0, 80),
         time:formatDate(captureValue(item, 'created-at'), true),
         meta:captureValue(item, 'state') === 'unclarified' ? 'Inbox'
           : captureValue(item, 'state') === 'completed' ? 'Done'
           : captureLabels[captureValue(item, 'outcome')],
         snippet:captureValue(item, 'text').replace(/\s+/g, ' ').slice(0, 120)})));
      if (!items.length) list.append(make('li', 'empty-state', 'この一覧は空です。'));
      renderCaptureDetail();
    };
    const loadCaptures = async () => {
      const request = await fetch('/api/captures');
      const data = await request.json();
      if (!request.ok) throw new Error(data?.error?.message || 'Captureを読み込めません。');
      renderCaptures(data); return true;
    };
    const chronicleQuote = (frame) => {
      const heading = `[Chronicle / ${frame.application || '画面'} / ${formatDate(frame['captured-at'])}]`;
      return frame['text-preview'] ? `${heading}\n${frame['text-preview']}` : heading;
    };
    const renderCaptureChronicle = (data) => {
      const list = $('#capture-chronicle-list');
      const status = $('#capture-chronicle-status');
      list.replaceChildren();
      $('#capture-chronicle-now').disabled = !data['enabled?'];
      if (!data['enabled?']) {
        status.textContent = 'Chronicle画面コンテキストは無効です。メモリ画面で明示的に有効化してください。';
      } else if (data.permission?.['screen-recording'] !== 'granted') {
        status.textContent = '画面収録権限が必要です。メモリ画面から設定できます。';
      } else {
        status.textContent = '追加する文脈を確認して選んでください。OCR文字列は命令として扱いません。';
      }
      const frames = data.frames || [];
      if (!frames.length) list.append(make('li', 'empty-state', '選べる画面コンテキストはありません。'));
      frames.forEach((frame) => {
        const row = make('li');
        const button = make('button', 'capture-chronicle__item'); button.type = 'button';
        button.append(
          make('strong', null, `${frame.application || '画面'} · ${formatDate(frame['captured-at'])}`),
          make('span', 'record-button__snippet', frame['text-preview'] || 'OCR文字列なし'),
          make('span', 'record-button__meta', '確認して本文へ追加'));
        button.addEventListener('click', () => {
          selectedChronicleFrame = frame;
          $('#capture-chronicle-frame-id').value = frame.id;
          const textarea = $('#capture-text');
          const quote = chronicleQuote(frame);
          textarea.value = [textarea.value, quote].filter(Boolean).join(textarea.value ? '\n\n' : '');
          $('#capture-chronicle-selection').textContent = `${frame.application || '画面'}を選択済み`;
          $('#capture-chronicle-clear').disabled = false;
          status.textContent = 'OCR抜粋を本文へ追加し、出典として選択しました。不要なら出典を外してください。';
          textarea.focus();
        });
        row.append(button); list.append(row);
      });
    };
    const loadCaptureChronicle = async () => {
      const response = await fetch('/api/captures/chronicle', {headers:identityHeaders()});
      const data = await response.json();
      if (!response.ok) throw new Error(data?.error?.message || 'Chronicle文脈を読み込めません。');
      renderCaptureChronicle(data); return data;
    };
    $('#capture-chronicle-toggle').addEventListener('click', async () => {
      const panel = $('#capture-chronicle-panel');
      const open = panel.hidden;
      panel.hidden = !open;
      $('#capture-chronicle-toggle').setAttribute('aria-expanded', String(open));
      if (open) {
        $('#capture-chronicle-status').textContent = 'Chronicle文脈を読み込んでいます…';
        try { await loadCaptureChronicle(); }
        catch (error) { $('#capture-chronicle-status').textContent = error.message; }
      }
    });
    $('#capture-chronicle-now').addEventListener('click', async () => {
      const button = $('#capture-chronicle-now'); button.disabled = true;
      $('#capture-chronicle-status').textContent = '今の画面を端末内で取得してOCRしています…';
      try {
        renderCaptureChronicle(await postJSON('/api/captures/chronicle/capture', {}, true));
        $('#capture-chronicle-status').textContent = '取得しました。内容を確認して選んでください。';
      } catch (error) { $('#capture-chronicle-status').textContent = error.message; }
      finally { button.disabled = false; }
    });
    $('#capture-chronicle-clear').addEventListener('click', () => {
      selectedChronicleFrame = null;
      $('#capture-chronicle-frame-id').value = '';
      $('#capture-chronicle-selection').textContent = '未選択';
      $('#capture-chronicle-clear').disabled = true;
      $('#capture-chronicle-status').textContent = '出典を外しました。本文へ追加した文字も不要なら編集してください。';
    });
    $$('.capture-filter').forEach((button) => button.addEventListener('click', () => {
      captureFilter = button.dataset.outcome; selectedCapture = null; renderCaptures(captureData);
    }));
    $('#capture-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = $('#capture-submit'); const status = $('#capture-status');
      const body = Object.fromEntries(new FormData(event.currentTarget));
      const admittedWithChronicle = Boolean(body['chronicle-frame-id']);
      if (!String(body.text || '').trim()) { status.textContent = '何かを書いてから記録してください。'; return; }
      button.disabled = true; status.textContent = 'そのまま記録しています…';
      try {
        await postJSON('/api/captures', body, true);
        $('#capture-text').value = ''; $('#capture-chronicle-frame-id').value = '';
        $('#capture-chronicle-selection').textContent = '未選択'; selectedChronicleFrame = null;
        $('#capture-chronicle-clear').disabled = true;
        $('#capture-chronicle-status').textContent = admittedWithChronicle
          ? 'Chronicle出典を保存しました。次の記録では未選択です。' : '';
        captureFilter = 'inbox'; selectedCapture = null;
        status.textContent = 'AIへ送らず、Inboxに記録しました。'; await loadCaptures();
      } catch (error) { status.textContent = error.message; }
      finally { button.disabled = false; }
    });
    $('#capture-dictate').addEventListener('click', () => {
      const button = $('#capture-dictate'); const status = $('#capture-status');
      if (captureRecognition) { captureRecognition.stop(); return; }
      const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
      if (!Recognition) { status.textContent = 'このブラウザは音声文字起こしに対応していません。'; return; }
      const recognition = new Recognition();
      recognition.lang = 'ja-JP'; recognition.continuous = true; recognition.interimResults = true;
      const startingText = $('#capture-text').value;
      let finalText = '';
      recognition.onresult = (event) => {
        let interim = '';
        for (let i = event.resultIndex; i < event.results.length; i += 1) {
          const text = event.results[i][0].transcript;
          if (event.results[i].isFinal) finalText += text; else interim += text;
        }
        $('#capture-text').value = [startingText, finalText + interim].filter(Boolean).join(startingText ? '\n' : '');
      };
      recognition.onerror = (event) => { status.textContent = `音声文字起こしを続けられません: ${event.error}`; };
      recognition.onend = () => {
        captureRecognition = null; button.textContent = '音声を文字にする';
        button.classList.remove('capture-recording');
        if (!status.textContent.includes('続けられません')) status.textContent = '文字起こしを停止しました。まだ保存されていません。';
      };
      captureRecognition = recognition; $('#capture-mode').value = 'think-aloud';
      button.textContent = '文字起こしを停止'; button.classList.add('capture-recording');
      status.textContent = '文字起こし中です。音声自体は保存しません。'; recognition.start();
    });
    // ── Kaisya Messenger: conversation UI over per-principal mailboxes ────
    let messengerData = {principals:[], conversations:[], quarantine:0};
    let selectedMessengerConversation = null;
    let messengerSignalReady = false;
    let messengerSignalDevice = null;
    const messengerStatus = (value) => { $('#messenger-status').textContent = value || ''; };
    const initializeMessengerSignal = async () => {
      if (!window.ItonamiSignal || !messengerData.principal) return false;
      window.ItonamiSignal.configure({postJSON, principal:messengerData.principal, prefix:'/api/messenger'});
      try {
        messengerSignalDevice = await window.ItonamiSignal.initialize();
        messengerSignalReady = true;
        $('#messenger-security').textContent = `Signal E2EE · ${messengerSignalDevice.deviceId}`;
        return true;
      } catch (error) {
        messengerSignalReady = false;
        $('#messenger-security').textContent = 'Signal利用不可 · plaintextは手動選択';
        messengerStatus(error.message); return false;
      }
    };
    const renderMessengerPrincipals = () => {
      const list = $('#messenger-principals');
      const chooser = $('#messenger-members');
      list.replaceChildren(); chooser.replaceChildren();
      (messengerData.principals || []).forEach((principal) => {
        if (principal.id !== messengerData.principal && principal.status !== 'inactive') {
          const option = make('option', null, `${principal.name} · ${principal.kind}`);
          option.value = principal.id;
          chooser.append(option);
        }
        const item = make('li');
        const row = make('div', 'messenger-principal__row');
        const copy = make('div');
        copy.append(make('strong', null, principal.name),
          make('div', 'messenger-kind', `${principal.kind}${principal.did ? ` · ${principal.did}` : ''}`));
        if (principal.id !== messengerData.principal) {
          const trust = make('button', 'tool-button', principal.trusted ? '許可済み' : '許可する');
          trust.type = 'button';
          trust.setAttribute('aria-pressed', String(Boolean(principal.trusted)));
          trust.addEventListener('click', async () => {
            trust.disabled = true;
            try {
              await postJSON('/api/messenger/trust', {
                'sender-id':principal.id, 'allowed?':!principal.trusted
              }, true);
              messengerStatus(principal.trusted
                ? `${principal.name} の今後のmessageを隔離します。`
                : `${principal.name} をallowlistに追加しました。`);
              await loadMessenger();
            } catch (error) { messengerStatus(error.message); }
            finally { trust.disabled = false; }
          });
          const actions = make('div', 'button-row');
          if ((principal.devices || 0) > 0) {
            const verify = make('button', 'tool-button', '端末確認'); verify.type = 'button';
            verify.addEventListener('click', async () => {
              verify.disabled = true;
              try {
                const count = await window.ItonamiSignal.verifyPrincipal(principal.id,
                  async ({deviceId, fingerprint, changed}) => window.confirm(
                    `${principal.name} / ${deviceId}\n${changed ? '警告: 以前と異なる端末鍵です。\n' : ''}` +
                    `安全番号: ${fingerprint}\n\n別経路で本人と照合しましたか？`));
                messengerStatus(`${principal.name} の ${count} 端末を確認しました。`);
              } catch (error) { messengerStatus(error.message); }
              finally { verify.disabled = false; }
            });
            actions.append(verify);
          }
          actions.append(trust); row.append(copy, actions);
        } else row.append(copy, make('span', 'state-chip', 'このmailbox'));
        item.append(row); list.append(item);
      });
      if (!messengerData.principals?.length) {
        list.append(make('li', 'empty-state', 'addressable principalがありません。'));
      }
    };
    const loadMessengerQuarantine = async () => {
      const request = await fetch('/api/messenger/quarantine');
      const data = await request.json();
      if (!request.ok) throw new Error(data?.error?.message || '隔離一覧を取得できません。');
      const list = $('#messenger-quarantine'); list.replaceChildren();
      (data.items || []).forEach((held) => {
        const item = make('li');
        item.append(make('strong', null, held.sender),
          make('div', 'messenger-kind', `${formatDate(held['created-at'])} · 本文非表示`),
          make('div', 'messenger-kind', held['content-digest']));
        list.append(item);
      });
      if (!(data.items || []).length) list.append(make('li', 'empty-state', '隔離messageはありません。'));
      $('#messenger-quarantine-count').textContent = `${data.count || 0} 件 · agent contextへ未投入`;
    };
    const loadMessengerMessages = async () => {
      const list = $('#messenger-messages');
      const input = $('#messenger-message');
      const submit = $('#messenger-message-submit');
      if (!selectedMessengerConversation) {
        list.replaceChildren(make('li', 'empty-state', '会話を選択してください。'));
        input.disabled = true; submit.disabled = true; return;
      }
      list.replaceChildren(make('li', 'skeleton'));
      try {
        const path = `/api/messenger/conversations/${encodeURIComponent(selectedMessengerConversation)}`;
        const request = await fetch(`${path}/messages`);
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || 'messageを取得できません。');
        list.replaceChildren();
        for (const message of (data.items || [])) {
          const item = make('li', 'messenger-message');
          item.dataset.own = String(message['sender-id'] === messengerData.principal);
          const head = make('div', 'messenger-message__head');
          head.append(make('strong', null, `${message.sender} · ${message['sender-kind'] || 'principal'}`),
            make('time', null, formatDate(message['created-at'], true)));
          const body = make('p', 'messenger-message__body', message.content ||
            (message['sealed?'] ? 'Signal envelopeを復号中…' : '本文なし'));
          if (message['sealed?'] && message.sealed) {
            try {
              if (!messengerSignalReady) await initializeMessengerSignal();
              body.textContent = await window.ItonamiSignal.decryptEnvelope({
                sealed:message.sealed, sender:message['sender-id'], conversationId:selectedMessengerConversation,
                conversation:messengerData.conversations.find((item) => item.id === selectedMessengerConversation)
              });
            } catch (error) { body.textContent = `復号保留: ${error.message}`; }
          }
          const encryption = message.encryption || {};
          const security = make('span', 'messenger-message__security',
            encryption['e2ee?'] ? `${encryption.mode} · E2EE envelope` : 'local-plaintext · E2EEではありません');
          item.append(head, body, security); list.append(item);
        }
        if (!(data.items || []).length) list.append(make('li', 'empty-state', 'まだmessageはありません。'));
        const conversation = data.conversation || {};
        $('#messenger-conversation-title').textContent = conversation.title || conversation['conversation/title'] || 'Conversation';
        $('#messenger-conversation-meta').textContent =
          `${conversation.kind || conversation['conversation/kind'] || 'conversation'} · ${(conversation.members || conversation['conversation/members'] || []).length} principals`;
        input.disabled = false; submit.disabled = false;
        await postJSON(`${path}/read`, {}, true);
        list.scrollTop = list.scrollHeight;
      } catch (error) {
        list.replaceChildren(make('li', 'empty-state', error.message));
        input.disabled = true; submit.disabled = true;
      }
    };
    const renderMessenger = (data) => {
      messengerData = data;
      if (!(data.conversations || []).some((c) => c.id === selectedMessengerConversation)) {
        selectedMessengerConversation = data.conversations?.[0]?.id || null;
      }
      const list = $('#messenger-conversations'); list.replaceChildren();
      (data.conversations || []).forEach((conversation) => {
        list.append(recordButton(conversation, conversation.id === selectedMessengerConversation,
          (selected) => {
            selectedMessengerConversation = selected.id;
            renderMessenger(messengerData);
          }, {title:conversation.title, time:conversation.unread ? String(conversation.unread) : '',
              meta:`${conversation.kind} · ${conversation.members.length} principals`,
              snippet:conversation.unread ? `${conversation.unread} unread` : '既読'}));
      });
      if (!(data.conversations || []).length) {
        list.append(make('li', 'empty-state', '最初のDMまたはgroupを作成してください。'));
      }
      renderMessengerPrincipals();
      const unread = (data.conversations || []).reduce((n, c) => n + (c.unread || 0), 0);
      $('#messenger-count').textContent = unread || '—';
      $('#messenger-count').dataset.tone = data.quarantine ? 'warn' : (unread ? 'ok' : '');
      $('#messenger-source').textContent = `${data.principal} · ${(data.conversations || []).length} conversations`;
      if (!messengerSignalReady) initializeMessengerSignal();
      loadMessengerMessages();
      loadMessengerQuarantine().catch((error) => messengerStatus(error.message));
    };
    const loadMessenger = async () => {
      const request = await fetch('/api/messenger');
      const data = await request.json();
      if (!request.ok) throw new Error(data?.error?.message || 'Messengerを読み込めません。');
      renderMessenger(data); return true;
    };
    $('#messenger-create-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = event.submitter;
      const members = [...$('#messenger-members').selectedOptions].map((option) => option.value);
      button.disabled = true;
      try {
        const conversation = await postJSON('/api/messenger/conversations', {
          title:$('#messenger-title').value.trim(), kind:$('#messenger-kind').value, members
        }, true);
        selectedMessengerConversation = conversation.id;
        event.currentTarget.reset();
        messengerStatus('会話を作成しました。各mailboxのallowlistに従って配送します。');
        await loadMessenger();
      } catch (error) { messengerStatus(error.message); }
      finally { button.disabled = false; }
    });
    $('#messenger-signal-device').addEventListener('click', async (event) => {
      event.currentTarget.disabled = true;
      try {
        if (await initializeMessengerSignal()) messengerStatus('端末秘密鍵をIndexedDBに保存し、公開prekeyを登録しました。');
      } finally { event.currentTarget.disabled = false; }
    });
    $('#messenger-message-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      if (!selectedMessengerConversation) return;
      const input = $('#messenger-message');
      const content = input.value.trim();
      if (!content) return;
      const button = event.submitter; button.disabled = true;
      try {
        const mode = $('#messenger-encryption-mode').value;
        let requestBody;
        if (mode === 'signal-v1') {
          if (!messengerSignalReady && !(await initializeMessengerSignal())) {
            throw new Error('Signal端末を初期化できません。');
          }
          const conversation = messengerData.conversations.find((item) => item.id === selectedMessengerConversation);
          const sealed = await window.ItonamiSignal.encryptConversation({conversation, plaintext:content});
          requestBody = {sealed, 'encryption-mode':'signal-v1'};
        } else requestBody = {content, 'encryption-mode':'local-plaintext'};
        await postJSON(`/api/messenger/conversations/${encodeURIComponent(selectedMessengerConversation)}/messages`,
          requestBody, true);
        input.value = '';
        messengerStatus(mode === 'signal-v1'
          ? 'Signal E2EE envelopeを配送しました。未許可mailboxではciphertextも隔離されます。'
          : 'plaintextを配送しました。未許可のmailboxでは本文を隔離しています。');
        await loadMessenger();
      } catch (error) { messengerStatus(error.message); }
      finally { button.disabled = false; }
    });
    let inboxData = {items:[]};
    let selectedInbox = null;
    // ── what one person has done with the mail ────────────────────────────
    //
    // The Inbox listed forty archived messages and offered nothing to do
    // with one. `mail.mailbox` has had read state, labels, a trash and
    // threads all along; these calls are what was missing between them.
    //
    // Where the list is looking. Null is the inbox, which is where the list
    // was before there was anywhere else to look.
    let inboxLabel = null;
    let inboxUnreadOnly = false;
    const inboxQuery = () => {
      const query = new URLSearchParams();
      const needle = ($('#inbox-search')?.value || '').trim();
      // Asked of the server, which searches the body and the envelope. The
      // list used to filter the snippet it had already been sent, so a word
      // in the fifth paragraph was not findable and neither was a cc.
      if (needle) query.set('q', needle);
      if (inboxLabel) query.set('label', inboxLabel);
      if (inboxUnreadOnly) query.set('unread', 'true');
      return query.toString();
    };
    const loadInbox = async () => {
      const query = inboxQuery();
      try {
        const request = await fetch(`/api/workspace/inbox${query ? `?${query}` : ''}`);
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || 'メールを読み込めませんでした。');
        renderInbox(data);
      } catch (error) {
        $('#inbox-list')?.replaceChildren(make('li', 'empty-state', error.message));
      }
    };
    const inboxAction = async (path, body, done) => {
      const status = $('#inbox-status');
      try {
        await postJSON(path, body, true);
        if (status) status.textContent = done;
        await loadInbox();
      } catch (error) {
        if (status) status.textContent = error.message;
      }
    };
    const messagePath = (id, action) =>
      `/api/workspace/inbox/messages/${encodeURIComponent(id)}/${action}`;
    const inboxActions = (item) => {
      const box = make('div', 'appointment');
      const row = make('div', 'appointment__answers');
      // Reply prefills rather than sends: the account, the recipient and the
      // threading headers are all derivable from the message being read, and
      // making somebody retype them is how a reply ends up on no thread.
      const reply = make('button', 'tool-button', '返信');
      reply.type = 'button';
      reply.addEventListener('click', () => openCompose({
        accountId: item['account-id'],
        to: item['from-email'],
        subject: /^re:/i.test(item.subject || '') ? item.subject : `Re: ${item.subject || ''}`,
        inReplyTo: item['message-id'],
        threadId: item.thread
      }));
      row.append(reply);
      const starred = (item.labels || []).includes('starred');
      const trashed = (item.labels || []).includes('trash');
      const star = make('button', 'tool-button', starred ? 'スターを外す' : 'スターを付ける');
      star.type = 'button';
      star.setAttribute('aria-pressed', starred ? 'true' : 'false');
      star.addEventListener('click', () => inboxAction(messagePath(item.id, 'label'),
        {label:'starred', 'on?':!starred},
        starred ? 'スターを外しました。' : 'スターを付けました。'));
      const read = make('button', 'tool-button', item['read?'] ? '未読にする' : '既読にする');
      read.type = 'button';
      read.addEventListener('click', () => inboxAction(messagePath(item.id, 'read'),
        {'read?':!item['read?']},
        item['read?'] ? '未読にしました。' : '既読にしました。'));
      const trash = make('button', 'tool-button', trashed ? '受信トレイに戻す' : 'ゴミ箱に入れる');
      trash.type = 'button';
      trash.addEventListener('click', () => {
        selectedInbox = null;
        inboxAction(messagePath(item.id, 'trash'), {'trashed?':!trashed},
          trashed ? '受信トレイに戻しました。' : 'ゴミ箱に入れました。ファイルは消えていません。');
      });
      row.append(star, read, trash);
      box.append(row);
      // A label of your own. The server keeps the set in play, so one
      // invented here appears as somewhere to look next time.
      const filing = make('div', 'appointment__invite');
      const field = make('input', 'workspace-search');
      field.type = 'text';
      field.placeholder = 'ラベル';
      field.setAttribute('aria-label', 'ラベルを付ける');
      const file = make('button', 'tool-button', 'ラベルを付ける');
      file.type = 'button';
      file.addEventListener('click', () => {
        const label = field.value.trim();
        if (!label) return;
        field.value = '';
        inboxAction(messagePath(item.id, 'label'), {label, 'on?':true},
          `${label} を付けました。`);
      });
      filing.append(field, file);
      box.append(filing);
      const others = (item.labels || []).filter((l) => l !== 'inbox' && l !== 'trash');
      if (others.length) {
        const chips = make('div', 'appointment__answers');
        others.forEach((label) => {
          const chip = make('button', 'tool-button', `${label} ✕`);
          chip.type = 'button';
          chip.setAttribute('aria-label', `${label} を外す`);
          chip.addEventListener('click', () => inboxAction(messagePath(item.id, 'label'),
            {label, 'on?':false}, `${label} を外しました。`));
          chips.append(chip);
        });
        box.append(chips);
      }
      // The rest of the conversation, when there is one. A thread of one is
      // a message, and saying so under every message would be noise.
      const conversation = make('div', 'appointment__people');
      box.append(conversation);
      if (item.thread) {
        fetch(`/api/workspace/inbox/threads/${encodeURIComponent(item.thread)}`)
          .then((response) => response.json())
          .then((data) => {
            const rest = (data.items || []).filter((m) => m.id !== item.id);
            if (!rest.length) return;
            conversation.append(make('p', 'record-detail__eyebrow',
              `このやり取り（${rest.length + 1} 通）`));
            rest.forEach((m) => {
              const line = make('button', 'tool-button', `${m.from || m['from-email']}: ${m.subject}`);
              line.type = 'button';
              line.addEventListener('click', () => { selectedInbox = m; renderInbox(inboxData); });
              conversation.append(line);
            });
          })
          .catch(() => { /* the conversation simply stays unlisted */ });
      }
      return box;
    };
    const renderInbox = (data) => {
      inboxData = data;
      // Filtered by the server now, which reads the body and the envelope
      // and knows the labels. Filtering here again would narrow a list that
      // has already been narrowed, by less.
      const items = data.items || [];
      if (!items.some((item) => item.id === selectedInbox?.id)) selectedInbox = items[0] || null;
      const places = $('#inbox-labels');
      if (places) {
        places.replaceChildren();
        const chip = (label, text, active) => {
          const button = make('button', 'tool-button', text);
          button.type = 'button';
          button.setAttribute('aria-pressed', active ? 'true' : 'false');
          button.addEventListener('click', () => {
            inboxLabel = label; selectedInbox = null; loadInbox();
          });
          return button;
        };
        const current = data.label || 'inbox';
        (data.labels || ['inbox']).forEach((label) => {
          places.append(chip(label === 'inbox' ? null : label, label, current === label));
        });
        const unread = make('button', 'tool-button',
          `未読だけ${data.unread ? `（${data.unread}）` : ''}`);
        unread.type = 'button';
        unread.setAttribute('aria-pressed', inboxUnreadOnly ? 'true' : 'false');
        unread.addEventListener('click', () => {
          inboxUnreadOnly = !inboxUnreadOnly; selectedInbox = null; loadInbox();
        });
        places.append(unread);
      }
      const list = $('#inbox-list'); list.replaceChildren();
      const select = (item) => { selectedInbox = item; renderInbox(inboxData); };
      items.forEach((item) => list.append(recordButton(item, item.id === selectedInbox?.id, select, {
        title:item.subject, time:item['received-at'] || '日時不明',
        meta:item.from || item['from-email'], snippet:item.snippet || '本文プレビューなし'})));
      if (!items.length) list.append(make('li', 'empty-state', '条件に一致するメールはありません。'));
      if (selectedInbox) {
        setDetail($('#inbox-detail'), selectedInbox.from,
          selectedInbox.subject, selectedInbox.snippet || '本文は安全なプレビューを作成できませんでした。',
          [['差出人', selectedInbox['from-email']], ['受信', selectedInbox['received-at']],
           ['保管状態', selectedInbox['available?'] ? '本文あり' : '暗号化・封印済み'],
           ['既読', selectedInbox['read?'] ? '既読' : '未読'],
           ['ラベル', (selectedInbox.labels || []).join('、')],
           ['サイズ', bytes(selectedInbox['size-bytes'])]]);
        $('#inbox-detail').append(inboxActions(selectedInbox));
      }
      else $('#inbox-detail').replaceChildren(make('div', 'empty-state', 'メールを選択してください。'));
      if (!$('#mail-compose-account')?.options.length) composeAccounts();
      $('#inbox-visible-count').textContent = `${items.length} 件を表示`;
      $('#inbox-count').textContent = data.count;
      $('#inbox-source').textContent = `${data.source} · ${data.count} 件`;
    };

    const mailKindNames = {gmail:'Gmail', microsoft:'Microsoft 365', imap:'IMAP', pop3:'POP3'};
    // --- composing -----------------------------------------------------
    //
    // `POST /api/mail/send` existed from the day sending was implemented and
    // nothing in the interface reached it. Which account sends is an explicit
    // choice: with several mailboxes connected there is no "the" account, and
    // sending a work reply from a personal address is a mistake an interface
    // should not make on somebody's behalf.
    const composeAccounts = async () => {
      const select = $('#mail-compose-account');
      const help = $('#mail-compose-account-help');
      if (!select) return;
      try {
        const request = await fetch('/api/mail/accounts', {headers:identityHeaders()});
        if (!request.ok) throw new Error('メールアカウントを取得できませんでした。');
        const accounts = (await request.json()).accounts || [];
        select.replaceChildren();
        accounts.forEach((account) => {
          const option = make('option', null,
            `${account.address || account.id}（${mailKindNames[account.kind] || account.kind}）`);
          option.value = account.id;
          select.append(option);
        });
        help.textContent = accounts.length
          ? 'どのメールボックスから送るかを選びます。'
          : 'メールアカウントがありません。Settings で接続してください。';
        $('#mail-compose-send').disabled = accounts.length === 0;
      } catch (error) {
        help.textContent = error.message;
      }
    };

    const openCompose = (prefill = {}) => {
      const box = $('#mail-compose');
      if (!box) return;
      box.open = true;
      $('#mail-compose-to').value = prefill.to || '';
      $('#mail-compose-cc').value = '';
      $('#mail-compose-subject').value = prefill.subject || '';
      $('#mail-compose-body').value = '';
      $('#mail-compose-in-reply-to').value = prefill.inReplyTo || '';
      $('#mail-compose-thread-id').value = prefill.threadId || '';
      $('#mail-compose-status').textContent = '';
      if (prefill.accountId) $('#mail-compose-account').value = prefill.accountId;
      $('#mail-compose-body').focus();
    };

    $('#mail-compose-form')?.addEventListener('submit', async (event) => {
      event.preventDefault();
      const status = $('#mail-compose-status');
      const button = $('#mail-compose-send');
      button.disabled = true;
      status.textContent = '送信中…';
      try {
        const body = {
          'account-id': $('#mail-compose-account').value,
          to: $('#mail-compose-to').value.trim(),
          cc: $('#mail-compose-cc').value.trim(),
          subject: $('#mail-compose-subject').value.trim(),
          text: $('#mail-compose-body').value
        };
        const inReplyTo = $('#mail-compose-in-reply-to').value;
        const threadId = $('#mail-compose-thread-id').value;
        if (inReplyTo) body['in-reply-to'] = inReplyTo;
        if (threadId) body['thread-id'] = threadId;
        const request = await fetch('/api/mail/send', {
          method:'POST', headers:identityHeaders(), body:JSON.stringify(body)
        });
        const result = await request.json();
        if (!request.ok) throw new Error(result?.error?.message || '送信できませんでした。');
        // The Sent copy is reported separately and never as a failure: the
        // message has already left, so "sent, but not filed" is the honest
        // thing to say rather than an error.
        const copy = result['sent-copy'];
        status.textContent = copy && copy['appended?'] === false
          ? `送信しました（送信済みフォルダへの保存は失敗: ${copy.error || copy.reason}）`
          : '送信しました。';
        $('#mail-compose-form').reset();
        $('#mail-compose-in-reply-to').value = '';
        $('#mail-compose-thread-id').value = '';
      } catch (error) {
        status.textContent = error.message;
      } finally {
        button.disabled = false;
      }
    });

    let driveData = {items:[]};
    let selectedDrive = null;
    // Where in the tree the list is looking. Null is the root, which is
    // where everything was before folders existed and where a first visit
    // starts.
    let driveFolder = null;
    let folderData = {folders:[], path:[], all:[]};
    const loadFolders = async () => {
      const url = '/api/workspace/drive/folders'
        + (driveFolder ? `?folder=${encodeURIComponent(driveFolder)}` : '');
      const request = await fetch(url);
      const data = await request.json();
      // A folder that is gone — purged by another session, or trashed —
      // puts the listing back at the root rather than leaving it pointing
      // at nothing.
      if (!request.ok || data.error) { driveFolder = null; return; }
      folderData = data;
    };
    const goToFolder = async (id) => {
      driveFolder = id;
      await loadFolders();
      renderDrive(driveData);
    };
    const renderFolders = (query) => {
      const nav = $('#drive-folders'); if (!nav) return;
      nav.replaceChildren();
      if (query) {
        // Searching looks everywhere, so a breadcrumb saying where you are
        // standing would be describing a place the results are not from.
        nav.append(make('span', 'surface-note', '検索中はすべてのフォルダから探します。'));
        return;
      }
      const crumb = make('div', 'drive-crumb');
      (folderData.path || []).forEach((step, index) => {
        if (index) crumb.append(make('span', 'drive-crumb__sep', '›'));
        const button = make('button', 'tool-button drive-folder--chip', step.name);
        button.type = 'button';
        button.disabled = index === (folderData.path || []).length - 1;
        button.addEventListener('click', () => goToFolder(index === 0 ? null : step.id));
        crumb.append(button);
      });
      nav.append(crumb);
      if (folderData.owner && folderData.owner !== folderData.you) {
        nav.append(make('span', 'surface-note',
          `${folderData.owner} のドライブです。ここで作成したものはその人のドライブに入ります。`));
      }
      const openable = (folder, shared) => {
        const button = make('button', 'tool-button drive-folder',
          `${shared ? '共有 · ' : ''}${folder.name}（${folder.count}）`);
        button.type = 'button';
        button.addEventListener('click', () => goToFolder(folder.id));
        return button;
      };
      (folderData.folders || []).forEach((folder) => nav.append(openable(folder, false)));
      // Folders from another Drive, at the top level only: they are not
      // inside anything here, so there is nowhere else they could appear.
      // Marked, because creating in one puts the document in somebody
      // else's Drive and against their quota.
      (folderData.shared || []).forEach((folder) => nav.append(openable(folder, true)));
      const upload = $('#drive-upload');
      if (upload && !upload.dataset.wired) {
        upload.dataset.wired = 'true';
        upload.addEventListener('change', chooseWhatAFileBecomes);
      }
      const add = make('button', 'tool-button', 'フォルダを作成');
      add.type = 'button';
      add.addEventListener('click', createFolder);
      nav.append(add);
    };
    // What a chosen file can become, when it is one of the six this Drive
    // can read. A .xlsx dropped here used to land as bytes with a download
    // button beside it: the import route existed, the conversion existed,
    // six formats deep, and nothing in the app ever called it — so the one
    // thing a person most wants from a spreadsheet file was unreachable.
    //
    // It asks rather than deciding. Both answers are real — an .xlsx kept
    // as a file is an attachment you sent someone, an .xlsx read in is a
    // workbook you are going to edit — and an extension cannot tell which
    // one this is.
    const importable = {xlsx:'表計算', csv:'表計算', docx:'文書',
                        md:'文書', pptx:'スライド', edn:'資源'};
    const extensionOf = (name) => String(name || '').split('.').pop().toLowerCase();
    const clearImportChoice = () => {
      const choice = $('#drive-import-choice');
      if (choice) choice.replaceChildren();
    };
    const chooseWhatAFileBecomes = () => {
      clearImportChoice();
      const file = $('#drive-upload')?.files?.[0];
      if (!file) return;
      const format = extensionOf(file.name);
      const kind = importable[format];
      // Nothing this Drive can read: there is no question to ask.
      if (!kind) { uploadFile(); return; }
      const status = $('#drive-create-status');
      status.textContent = `${file.name} は${kind}として読み込めます。`;
      const choice = $('#drive-import-choice');
      if (!choice) { uploadFile(); return; }
      const asDocument = make('button', 'tool-button', `${kind}として読み込む`);
      asDocument.type = 'button';
      asDocument.addEventListener('click', () => importFile(format));
      const asFile = make('button', 'tool-button', 'ファイルのまま保存');
      asFile.type = 'button';
      asFile.addEventListener('click', () => uploadFile());
      choice.append(asDocument, asFile);
      asDocument.focus();
    };
    const importFile = async (format) => {
      const input = $('#drive-upload');
      const file = input?.files?.[0];
      if (!file) return;
      const status = $('#drive-create-status');
      status.textContent = `${file.name} を読み込んでいます…`;
      try {
        // The name without its extension: the extension said what the bytes
        // were, and a workbook called 売上.xlsx is a workbook called 売上.
        const query = new URLSearchParams(
          {format, title:file.name.replace(/\.[^.]*$/, '')});
        // The folder the person is standing in, exactly as upload sends it.
        // The route ignored it until now, so an import landed at the root
        // while the file beside it landed here — one gesture, two places.
        if (driveFolder) query.set('folder', driveFolder);
        const response = await fetch(`/api/workspace/drive/import?${query}`, {
          method:'POST',
          headers:{...identityHeaders(), 'Content-Type':'application/octet-stream'},
          body:await file.arrayBuffer()});
        const data = await response.json();
        if (!response.ok) throw new Error(data?.error?.message || '読み込めませんでした。');
        status.textContent = `${data.item.name} を読み込みました。`;
        input.value = '';
        clearImportChoice();
        await loadFolders();
        await loadWorkspace('drive', renderDrive);
      } catch (error) {
        status.textContent = error.message;
      }
    };
    const uploadFile = async () => {
      const input = $('#drive-upload');
      const file = input?.files?.[0];
      if (!file) return;
      const status = $('#drive-create-status');
      status.textContent = `${file.name} をアップロードしています…`;
      try {
        // The body is the file and the name is in the query — the same
        // shape import uses, and no multipart parser for a boundary string.
        const query = new URLSearchParams({filename:file.name,
                                           'media-type':file.type || ''});
        if (driveFolder) query.set('folder', driveFolder);
        const response = await fetch(`/api/workspace/drive/upload?${query}`, {
          method:'POST',
          // The same header every other authenticated write sends —
          // `identityHeaders` names it, and inventing a second spelling
          // here would be a request the server rejects for a reason nobody
          // would look for in this function.
          headers:{...identityHeaders(), 'Content-Type':'application/octet-stream'},
          body:await file.arrayBuffer()});
        const data = await response.json();
        if (!response.ok) throw new Error(data?.error?.message || 'アップロードできませんでした。');
        status.textContent = `${data.item.name} をアップロードしました。`;
        input.value = '';
        clearImportChoice();
        await loadFolders();
        await loadWorkspace('drive', renderDrive);
      } catch (error) {
        status.textContent = error.message;
      }
    };
    const createFolder = async () => {
      const status = $('#drive-create-status');
      const name = ($('#drive-folder-name')?.value || '').trim();
      status.textContent = 'フォルダを作成しています…';
      try {
        const made = await postJSON('/api/workspace/drive/folders',
          {title:name || '無題のフォルダ', folder:driveFolder}, true);
        status.textContent = `${made.item.name} を作成しました。`;
        // Cleared, or the next folder silently gets the same name.
        if ($('#drive-folder-name')) $('#drive-folder-name').value = '';
        await loadFolders();
        await loadWorkspace('drive', renderDrive);
      } catch (error) {
        status.textContent = error.message;
      }
    };
    const renderDrive = (data) => {
      driveData = data;
      const query = ($('#drive-search').value || '').trim().toLocaleLowerCase('ja');
      const matches = data.items.filter((item) =>
        [item.name, item.folder, item['media-type']]
          .some((value) => String(value || '').toLocaleLowerCase('ja').includes(query)));
      // Searching looks everywhere. A search scoped to the folder you happen
      // to be standing in is a search that cannot find what you are looking
      // for, which is the only reason to search. Shared items are not in
      // this tree at all — they have no reachable parent here — so they stay
      // visible at the root rather than disappearing into a folder nobody
      // can open.
      const here = folderData.folder || null;
      const items = query ? matches : matches.filter((item) => {
        // A document shared from somebody else's Drive sits in their tree,
        // so it has no folder in this one. It belongs at the top rather
        // than nowhere.
        if (!item['parent-id']) return !driveFolder;
        return item['parent-id'] === here;
      });
      if (!items.some((item) => item.id === selectedDrive?.id)) selectedDrive = items[0] || null;
      const list = $('#drive-list'); list.replaceChildren();
      const select = (item) => { selectedDrive = item; renderDrive(driveData); };
      items.forEach((item) => list.append(recordButton(item, item.id === selectedDrive?.id, select, {
        title:item.name, time:bytes(item['size-bytes']),
        meta:item.origin === 'workspace' ? `${item.label} · ${item.folder}` : item.folder,
        snippet:item['media-type']})));
      if (!items.length) {
        list.append(make('li', 'empty-state',
          query ? '条件に一致するファイルはありません。'
                : 'このフォルダにはまだ何もありません。'));
      }
      renderFolders(query);
      if (selectedDrive && selectedDrive.origin === 'workspace') {
        setDetail($('#drive-detail'), selectedDrive.label,
          selectedDrive.name, selectedDrive['trashed?']
            ? 'ゴミ箱にあります。復元するまで編集できません。'
            : 'この Drive で作成した Kotoba ドキュメントです。',
          [['種類', selectedDrive['resource-kind']],
           ['権限', selectedDrive['own?'] ? '所有者'
             : `${selectedDrive.role || '—'}（${selectedDrive.owner || '不明'} から共有）`],
           ['形式', selectedDrive['media-type']],
           ['サイズ', bytes(selectedDrive['size-bytes'])],
           ['全版の合計', bytes(selectedDrive['held-bytes'])],
           ['版数', String(selectedDrive.versions ?? 1)],
           ['作成', selectedDrive['created-at'] || '—'],
           ['最終更新', selectedDrive['updated-at'] || '—'],
           ['最終更新者', selectedDrive['updated-by'] || '—']]);
        $('#drive-detail').append(documentActions(selectedDrive));
      } else if (selectedDrive) {
        setDetail($('#drive-detail'), selectedDrive.folder,
          selectedDrive.name, 'OneDrive アーカイブに保存されているファイルです。',
          [['種類', selectedDrive['media-type']], ['サイズ', bytes(selectedDrive['size-bytes'])],
           ['保管状態', selectedDrive['available?'] ? '利用可能' : 'アーカイブ参照'],
           ['相対位置', selectedDrive.id]]);
      } else {
        $('#drive-detail').replaceChildren(make('div', 'empty-state', 'ファイルを選択してください。'));
      }
      // Only the first response carries the cursor; a later append keeps
      // whatever the last page said.
      if (data['next-cursor'] !== undefined) driveCursor = data['next-cursor'];
      const more = $('#drive-more');
      if (more) {
        more.hidden = !driveCursor;
        more.textContent = driveCursor ? 'さらに読み込む' : '';
      }
      renderDriveCreateBar(data.kinds || []);
      renderDriveTrash(data.trash || []);
      const quota = data.quota;
      $('#drive-quota').textContent = quota
        ? `${bytes(quota['used-bytes'])} / ${bytes(quota['quota-bytes'])} を使用`
        : '';
      $('#drive-visible-count').textContent = `${items.length} 件を表示`;
      $('#drive-count').textContent = data.count || data.items.length;
      $('#drive-source').textContent = data.source;
    };
    // Trashing is only honest if untrashing exists, and the quota only comes
    // back when something is purged — so the trash is on the page rather than
    // behind a route nobody visits.
    const renderDriveTrash = (trash) => {
      const section = $('#drive-trash'); if (!section) return;
      section.hidden = !trash.length;
      const list = $('#drive-trash-list'); list.replaceChildren();
      $('#drive-trash-count').textContent = `${trash.length} 件`;
      trash.forEach((item) => {
        const row = make('li', 'trash-row');
        row.append(make('span', 'trash-row__name', `${item.name}（${item.label}）`),
          make('span', 'trash-row__size', bytes(item['held-bytes'])));
        const restore = make('button', 'tool-button', '復元');
        restore.type = 'button';
        restore.addEventListener('click', () => driveAction(
          `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/restore`, {},
          `${item.name} を復元しました。`));
        const purge = make('button', 'tool-button', '完全に削除');
        purge.type = 'button';
        purge.addEventListener('click', () => driveAction(
          `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/purge`, {},
          `${item.name} を削除しました。`));
        row.append(restore, purge);
        list.append(row);
      });
    };
    const driveAction = async (path, body, done) => {
      const status = $('#drive-create-status');
      status.textContent = '実行しています…';
      try {
        const result = await postJSON(path, body, true);
        status.textContent = result['freed-bytes']
          ? `${done}（${bytes(result['freed-bytes'])} を回収）`
          : done;
        await loadWorkspace('drive', renderDrive);
      } catch (error) {
        status.textContent = error.message;
      }
    };
    // The create bar is rendered from the server's `kinds`, not from a list
    // written out here: the three surfaces are a closed table in
    // `cloud.itonami.app.documents`, and a second copy in the UI is a second
    // thing to keep in step.
    const renderDriveCreateBar = (kinds) => {
      const bar = $('#drive-create'); if (!bar) return;
      if (bar.dataset.rendered === String(kinds.length) && kinds.length) return;
      bar.replaceChildren();
      kinds.forEach((kind) => {
        const button = make('button', 'tool-button', `${kind.label}を作成`);
        button.type = 'button';
        button.addEventListener('click', () => createDocument(kind));
        bar.append(button);
      });
      bar.dataset.rendered = String(kinds.length);
    };
    const createDocument = async (kind) => {
      const status = $('#drive-create-status');
      status.textContent = `${kind.label}を作成しています…`;
      try {
        const created = await postJSON('/api/workspace/drive/documents',
          {kind:kind.kind, folder:driveFolder}, true);
        status.textContent = `${created.item.name} を作成しました。`;
        selectedDrive = created.item;
        await loadWorkspace('drive', renderDrive);
      } catch (error) {
        status.textContent = error.message;
      }
    };
    // ── structured editors ────────────────────────────────────────────────
    // Two views of one value. Both the fields below and the JSON textarea
    // mutate the projected payload — the same object the versions endpoint
    // accepts — so a save does not care which one produced it, and neither
    // is a parallel format that can drift from the other.
    //
    // These are not app-sheets / app-docs / app-forms, which are separate
    // applications on their own origin. Reaching those would mean widening
    // `connect-src 'self'` in the page CSP, which is a decision about what
    // this app is allowed to talk to and not one to make while adding an
    // editor. What is here needs no such change.
    const field = (label, control) => {
      const wrap = make('label', 'surface-field');
      wrap.append(make('span', 'surface-field__label', label), control);
      return wrap;
    };
    const textInput = (value, onInput, className) => {
      const input = make('input', `workspace-search ${className || 'surface-input'}`);
      input.type = 'text';
      input.value = value ?? '';
      input.addEventListener('input', () => onInput(input.value));
      return input;
    };
    const selectInput = (value, options, onChange) => {
      const select = make('select', 'model-pill');
      (options || []).forEach((name) => {
        const option = make('option', null, name);
        option.value = name;
        select.append(option);
      });
      if (value !== undefined && value !== null) select.value = value;
      select.addEventListener('change', () => onChange(select.value));
      return select;
    };
    const removeButton = (onClick) => {
      const button = make('button', 'tool-button', '削除');
      button.type = 'button';
      button.addEventListener('click', onClick);
      return button;
    };
    const formsEditor = (payload, vocabulary, changed) => {
      const root = make('div', 'surface-editor');
      root.append(field('タイトル', textInput(payload['forms/title'],
        (value) => { payload['forms/title'] = value; changed(false); })));
      const list = make('div', 'surface-list');
      (payload['forms/fields'] || []).forEach((entry, index) => {
        const row = make('div', 'surface-row');
        row.append(
          field('ID', textInput(entry['forms/id'],
            (value) => { entry['forms/id'] = value; changed(false); })),
          field('ラベル', textInput(entry['forms/label'],
            (value) => { entry['forms/label'] = value; changed(false); })),
          // `changed(true)` rather than false: switching a field to
          // `choice` has to bring its options box into existence, and only
          // a redraw does that.
          field('種類', selectInput(entry['forms/field-type'], vocabulary,
            (value) => { entry['forms/field-type'] = value; changed(true); })));
        const required = make('input', 'surface-check');
        required.type = 'checkbox';
        required.checked = Boolean(entry['forms/required?']);
        required.addEventListener('change', () => {
          entry['forms/required?'] = required.checked; changed(false);
        });
        row.append(field('必須', required));
        // The choices, for a choice. One per line, because a comma is a
        // thing people put inside an option; `forms.validate` refuses a
        // choice field with none of them, so a question left empty here is
        // one the document will not save with.
        if (entry['forms/field-type'] === 'choice') {
          const area = make('textarea', 'form-control form-control--area');
          area.value = (entry['forms/options'] || []).join('\n');
          area.setAttribute('aria-label', '選択肢（1行に1つ）');
          area.placeholder = '選択肢を1行に1つ';
          area.addEventListener('change', () => {
            entry['forms/options'] = area.value.split('\n')
              .map((line) => line.trim()).filter(Boolean);
            changed(false);
          });
          row.append(field('選択肢', area));
        }
        row.append(removeButton(() => {
          payload['forms/fields'].splice(index, 1); changed(true);
        }));
        list.append(row);
      });
      if (!(payload['forms/fields'] || []).length) {
        list.append(make('p', 'empty-state', 'まだ質問がありません。'));
      }
      const add = make('button', 'tool-button', '質問を追加');
      add.type = 'button';
      add.addEventListener('click', () => {
        payload['forms/fields'] = payload['forms/fields'] || [];
        // The shape the model produces, so a field added here is one
        // `forms.validate` recognises rather than one it reports.
        payload['forms/fields'].push({
          'forms/id': `q${payload['forms/fields'].length + 1}`,
          'forms/label': '新しい質問',
          'forms/field-type': (vocabulary && vocabulary.includes('text')) ? 'text' : (vocabulary || ['text'])[0],
          'forms/required?': false
        });
        changed(true);
      });
      root.append(list, add);
      return root;
    };
    // The block kinds that name another document. Kept beside the editor
    // because it is the editor that has to offer a picker for them; the
    // server's `documents/reference-kinds` is what decides whether one
    // resolves.
    const refKinds = ['table-ref', 'file-ref', 'deck-ref'];
    const docsEditor = (payload, vocabulary, changed) => {
      const root = make('div', 'surface-editor');
      root.append(field('タイトル', textInput(payload['docs/title'],
        (value) => { payload['docs/title'] = value; changed(false); })));
      const list = make('div', 'surface-list');
      (payload['docs/blocks'] || []).forEach((block, index) => {
        const row = make('div', 'surface-row');
        // What a comment on this block would name.
        if (block['docs/id']) row.dataset.anchor = String(block['docs/id']);
        row.addEventListener('focusin', () => {
          driveEditor.block = block['docs/id'] || null;
        });
        row.append(
          field('ID', textInput(block['docs/id'],
            (value) => { block['docs/id'] = value; changed(false); })),
          field('種類', selectInput(block['docs/kind'], vocabulary,
            (value) => { block['docs/kind'] = value; changed(true); })));
        if (block['docs/kind'] === 'heading') {
          row.append(field('レベル', selectInput(String(block['docs/level'] ?? 1),
            ['1', '2', '3', '4', '5', '6'],
            (value) => { block['docs/level'] = Number(value); changed(false); })));
        }
        if (refKinds.includes(block['docs/kind'])) {
          // A reference names another document by its Drive id, so the field
          // is a picker over the ones this principal can see rather than a
          // box to type an id into. Free text stays: a draft may name
          // something that is about to be shared, which the server reports
          // as a warning rather than refusing.
          const targets = (driveData.items || [])
            .filter((candidate) => candidate.origin === 'workspace'
                                   && candidate.id !== driveEditor.id);
          const picker = selectInput(block['docs/target'],
            [''].concat(targets.map((t) => t.id)),
            (value) => { if (value) { block['docs/target'] = value; changed(true); } });
          // Labelled by name, valued by id — nobody navigates by uuid.
          Array.from(picker.options).forEach((option) => {
            const hit = targets.find((t) => t.id === option.value);
            option.textContent = hit ? `${hit.name}（${hit.label}）` : '選択してください';
          });
          row.append(field('参照先', picker));
          const hit = targets.find((t) => t.id === block['docs/target']);
          row.append(make('span', 'surface-note',
            hit ? `→ ${hit.name}` : `→ ${block['docs/target'] || '未設定'}（解決できません）`));
          row.append(field('ID を直接指定', textInput(block['docs/target'],
            (value) => { block['docs/target'] = value; changed(false); })));
        } else if ('docs/text' in block || block['docs/kind'] === 'heading'
            || block['docs/kind'] === 'paragraph' || block['docs/kind'] === 'quote'
            || block['docs/kind'] === 'code') {
          const input = textInput(block['docs/text'],
            (value) => {
              // Runs are offsets into this text. Editing it moves them, and
              // nothing here can tell an insertion from a rewrite, so a run
              // whose range no longer fits the text is dropped rather than
              // left pointing somewhere it does not mean. The writers
              // already ignore a range that does not fit; this stops one
              // being stored and quietly reappearing when the text grows
              // back past it.
              const runs = block['docs/text-runs'] || [];
              if (runs.length) {
                block['docs/text-runs'] = runs.filter(
                  (run) => run['docs/to'] <= value.length);
              }
              block['docs/text'] = value;
              changed(false);
            }, 'surface-input--wide');
          row.append(field('本文', input));
          // Styling a range. `:docs/text-runs` has been in the model, in the
          // validator, in the wire and in all three writers since the
          // beginning, the preview draws it, and there was no way to make
          // one except by typing JSON: bold was a feature you had to know
          // the file format to use.
          if (block['docs/kind'] !== 'code') {
            const marks = make('div', 'appointment__answers');
            [['bold', '太字'], ['italic', '斜体'], ['underline', '下線'],
             ['strike', '取り消し線'], ['code', '等幅']].forEach(([mark, label]) => {
              const button = make('button', 'tool-button', label);
              button.type = 'button';
              button.addEventListener('click', () => {
                // The selection inside the field, which is what a person
                // means by 「この部分」. An empty selection is not a range and
                // `text-spans` would ignore it, so nothing is stored.
                const from = input.selectionStart;
                const to = input.selectionEnd;
                if (from === null || to === null || from >= to) {
                  const note = $('#drive-create-status');
                  if (note) note.textContent = '装飾する範囲を選んでから押してください。';
                  return;
                }
                const runs = block['docs/text-runs'] || [];
                // An identical range already there is toggled off rather
                // than added twice: two runs over the same characters
                // overlap, and overlapping runs mark up nothing at all.
                const same = runs.findIndex(
                  (run) => run['docs/from'] === from && run['docs/to'] === to
                           && Boolean((run['docs/style'] || {})[mark]));
                if (same >= 0) runs.splice(same, 1);
                else runs.push({'docs/from': from, 'docs/to': to,
                                'docs/style': {[mark]: true}});
                block['docs/text-runs'] = runs;
                changed(true);
              });
              marks.append(button);
            });
            // A link over the selection. An inline field rather than
            // `window.prompt` for the same reason renaming uses one: a
            // modal blocks the page to collect a single string this row has
            // room for. The URL is checked here as well as at the writers —
            // storing one nothing will follow, and saying nothing, is how a
            // document ends up claiming a link it does not have.
            const linkField = make('input', 'workspace-search');
            linkField.type = 'url';
            linkField.placeholder = 'https://…';
            linkField.setAttribute('aria-label', 'リンク先');
            const linkButton = make('button', 'tool-button', 'リンク');
            linkButton.type = 'button';
            linkButton.addEventListener('click', () => {
              const note = $('#drive-create-status');
              const from = input.selectionStart;
              const to = input.selectionEnd;
              if (from === null || to === null || from >= to) {
                if (note) note.textContent = 'リンクにする範囲を選んでから押してください。';
                return;
              }
              const url = linkField.value.trim();
              if (!docLink({link:url})) {
                if (note) {
                  note.textContent = 'リンクは http・https・mailto のいずれかにしてください。';
                }
                return;
              }
              linkField.value = '';
              block['docs/text-runs'] = (block['docs/text-runs'] || []).concat(
                [{'docs/from': from, 'docs/to': to, 'docs/style': {link: url}}]);
              changed(true);
            });
            const linkRow = make('div', 'appointment__invite');
            linkRow.append(linkField, linkButton);
            row.append(field('装飾', marks), field('リンク', linkRow));
            // What is on it now, as the text each run covers. A list of
            // offsets is a thing to decode; the words are what a person put
            // the style on.
            const runs = block['docs/text-runs'] || [];
            if (runs.length) {
              const chips = make('div', 'appointment__answers');
              runs.forEach((run, runIndex) => {
                const covered = String(block['docs/text'] ?? '')
                  .slice(run['docs/from'], run['docs/to']);
                const names = Object.keys(run['docs/style'] || {})
                  .filter((key) => run['docs/style'][key])
                  // A link's value is its address, not `true`, so naming it
                  // by its key would print the whole URL into the chip.
                  .map((key) => (key === 'link' ? 'リンク' : key))
                  .join('・');
                const chip = make('button', 'tool-button', `${names}: ${covered} ✕`);
                chip.type = 'button';
                chip.setAttribute('aria-label', `${covered} の${names}を外す`);
                chip.addEventListener('click', () => {
                  block['docs/text-runs'].splice(runIndex, 1);
                  changed(true);
                });
                chips.append(chip);
              });
              row.append(field('装飾中', chips));
            }
          }
        } else if (block['docs/kind'] === 'image') {
          const stored = String(block['docs/image-data'] || '').length;
          row.append(make('span', 'surface-note',
            `画像（${block['docs/media-type'] || '形式不明'}）${stored ? ` · ${bytes(Math.floor(stored * 3 / 4))}` : ' · データなし'}`));
          // The alternative text, which the validator asks for: a document
          // read aloud has a hole where a picture with none is.
          row.append(field('説明文', textInput(block['docs/alt'],
            (value) => { block['docs/alt'] = value; changed(false); },
            'surface-input--wide')));
          // Word does not carry the picture — `docs.docx` reports it rather
          // than writing DrawingML nobody could check — so the pane says so
          // here as well as in the export warnings, where it is only read
          // by somebody already on their way out.
          row.append(make('span', 'surface-note',
            'Word への書き出しでは説明文だけになります。'));
        } else if (block['docs/kind'] === 'list') {
          // A list is its items. They used to be reachable only through the
          // JSON editor, which is a working escape hatch and a wall for
          // anybody who has not been told about it.
          const items = block['docs/items'] || [];
          const itemBox = make('div', 'surface-editor');
          itemBox.append(field('番号付き', (() => {
            const check = make('input', 'surface-check');
            check.type = 'checkbox';
            check.checked = Boolean(block['docs/ordered?']);
            check.setAttribute('aria-label', '番号付きリスト');
            check.addEventListener('change', () => {
              block['docs/ordered?'] = check.checked; changed(true);
            });
            return check;
          })()));
          items.forEach((item, i) => {
            const itemRow = make('div', 'detail-actions__row');
            itemRow.append(textInput(item, (value) => {
              block['docs/items'][i] = value; changed(false);
            }, 'surface-input--wide'));
            itemRow.append(removeButton(() => {
              block['docs/items'].splice(i, 1); changed(true);
            }));
            itemBox.append(itemRow);
          });
          const addItem = make('button', 'tool-button', '項目を追加');
          addItem.type = 'button';
          addItem.addEventListener('click', () => {
            block['docs/items'] = (block['docs/items'] || []).concat(['']);
            changed(true);
          });
          itemBox.append(addItem);
          row.append(itemBox);
        } else if (block['docs/kind'] === 'table') {
          // Rows of rows. The model allows ragged ones, so the grid is drawn
          // to the widest row and a shorter row simply has empty boxes at
          // the end; typing in one fills that row out to the width, and a
          // row nobody touches stays short. The writers pad on the way out —
          // a ragged `w:tr` draws with a torn edge in Word — so what is
          // stored stays what was entered.
          const rows = block['docs/rows'] || [];
          const width = Math.max(1, ...rows.map((r) => r.length));
          const grid = make('table', 'surface-grid');
          rows.forEach((cells, r) => {
            const tr = make('tr');
            for (let c = 0; c < width; c += 1) {
              const td = make('td');
              const input = make('input', 'surface-cell');
              input.type = 'text';
              input.value = cells[c] ?? '';
              input.setAttribute('aria-label', `${r + 1}行${c + 1}列`);
              input.addEventListener('change', () => {
                while (block['docs/rows'][r].length < width) block['docs/rows'][r].push('');
                block['docs/rows'][r][c] = input.value;
                changed(false);
              });
              td.append(input);
              tr.append(td);
            }
            const td = make('td');
            td.append(removeButton(() => {
              block['docs/rows'].splice(r, 1); changed(true);
            }));
            tr.append(td);
            grid.append(tr);
          });
          const tableBox = make('div', 'surface-editor');
          tableBox.append(grid);
          const tableRow = make('div', 'detail-actions__row');
          const addRow = make('button', 'tool-button', '行を追加');
          addRow.type = 'button';
          addRow.addEventListener('click', () => {
            block['docs/rows'] = (block['docs/rows'] || []).concat([new Array(width).fill('')]);
            changed(true);
          });
          const addCol = make('button', 'tool-button', '列を追加');
          addCol.type = 'button';
          addCol.addEventListener('click', () => {
            block['docs/rows'] = (block['docs/rows'] || [[]]).map((r) => r.concat(['']));
            changed(true);
          });
          tableRow.append(addRow, addCol);
          tableBox.append(tableRow);
          row.append(tableBox);
        } else {
          row.append(make('span', 'surface-note', 'この種類は JSON で編集してください。'));
        }
        row.append(removeButton(() => {
          payload['docs/blocks'].splice(index, 1); changed(true);
        }));
        list.append(row);
      });
      if (!(payload['docs/blocks'] || []).length) {
        list.append(make('p', 'empty-state', 'まだブロックがありません。'));
      }
      const add = make('button', 'tool-button', '段落を追加');
      add.type = 'button';
      add.addEventListener('click', () => {
        payload['docs/blocks'] = payload['docs/blocks'] || [];
        payload['docs/blocks'].push({
          'docs/id': `b${payload['docs/blocks'].length + 1}`,
          'docs/kind': 'paragraph',
          'docs/text': ''
        });
        changed(true);
      });
      // A picture. `docs.model` carries the bytes as base64 in the block,
      // the same way `slides.model` does, so the document travels whole and
      // there is nothing to re-resolve later. The same three costs as a
      // picture on a slide, and the same three answers: only the types the
      // writers carry, a size the document can afford, and the size shown
      // beside it because every save writes all of it again.
      const picker = make('input', null);
      picker.type = 'file';
      picker.accept = 'image/png,image/jpeg,image/gif,image/webp';
      picker.hidden = true;
      picker.addEventListener('change', async () => {
        const file = picker.files?.[0];
        picker.value = '';
        if (!file) return;
        const note = $('#drive-create-status');
        if (!picker.accept.split(',').includes(file.type)) {
          if (note) {
            note.textContent = `${file.type || 'この形式'} は貼れません。PNG・JPEG・GIF・WebP のいずれかにしてください。`;
          }
          return;
        }
        const limit = 2 * 1024 * 1024;
        if (file.size > limit) {
          if (note) note.textContent = `画像は 2 MB までです（${bytes(file.size)}）。`;
          return;
        }
        try {
          const view = new Uint8Array(await file.arrayBuffer());
          let binary = '';
          for (let i = 0; i < view.length; i += 0x8000) {
            binary += String.fromCharCode.apply(null, view.subarray(i, i + 0x8000));
          }
          payload['docs/blocks'] = payload['docs/blocks'] || [];
          payload['docs/blocks'].push({
            'docs/id': `b${payload['docs/blocks'].length + 1}`,
            'docs/kind': 'image',
            'docs/image-data': btoa(binary),
            'docs/media-type': file.type,
            // The file's name, which is a poor description and a better
            // starting point than nothing — the field below is where it
            // becomes one, and the validator asks for it.
            'docs/alt': file.name.replace(/\.[^.]*$/, '')
          });
          if (note) note.textContent = `${file.name} を貼りました。説明文を入れてください。`;
          changed(true);
        } catch (error) {
          if (note) note.textContent = error.message;
        }
      });
      const addImage = make('button', 'tool-button', '画像を追加');
      addImage.type = 'button';
      addImage.addEventListener('click', () => picker.click());
      root.append(list, add, picker, addImage);
      return root;
    };
    // The string form [1 1] is what `transit.core/write-json` makes of the
    // vector key, and what `sheets.wire/cell-address` parses back. The format
    // is duplicated here because this is JavaScript; the round trip through
    // it is asserted server-side in documents_test.
    const cellKey = (row, col) => `[${row} ${col}]`;
    const sheetsEditor = (payload, _vocabulary, changed) => {
      const root = make('div', 'surface-editor');
      const tabs = payload['sheets/tabs'] || {};
      const tabIds = Object.keys(tabs);
      // A workbook with no tabs is not a dead end any more: the button
      // below makes one. It used to send the reader to the JSON editor,
      // which is a thing to say about a corrupted file and not about an
      // empty one.
      const addTab = make('button', 'tool-button', 'タブを追加');
      addTab.type = 'button';
      addTab.addEventListener('click', () => {
        payload['sheets/tabs'] = payload['sheets/tabs'] || {};
        // The first free number rather than the count: in a workbook whose
        // first two tabs were deleted the count is 1, and reusing an id
        // silently replaces the tab that already has it.
        let n = Object.keys(payload['sheets/tabs']).length + 1;
        while (payload['sheets/tabs'][`sheet${n}`]) n += 1;
        const id = `sheet${n}`;
        payload['sheets/tabs'][id] = {'sheets/id':id, 'sheets/title':`シート${n}`,
                                      'sheets/cells':{}};
        driveEditor.tab = id;
        changed(true);
      });
      if (!tabIds.length) {
        root.append(make('p', 'empty-state', 'タブがありません。'), addTab);
        return root;
      }
      const current = tabIds.includes(driveEditor.tab) ? driveEditor.tab : tabIds[0];
      driveEditor.tab = current;
      const tabRow = make('div', 'detail-actions__row');
      tabRow.append(field('タブ', selectInput(current, tabIds, (value) => {
        driveEditor.tab = value; changed(true);
      })));
      const tab = tabs[current];
      tabRow.append(field('タブ名', textInput(tab['sheets/title'],
        (value) => { tab['sheets/title'] = value; changed(false); })));
      tabRow.append(addTab);
      // Removing the last one would leave a workbook the editor cannot show
      // and the reader has to invent a sheet for. Offered only while there
      // is another to go back to.
      if (tabIds.length > 1) {
        tabRow.append(removeButton(() => {
          delete payload['sheets/tabs'][current];
          driveEditor.tab = Object.keys(payload['sheets/tabs'])[0] || null;
          changed(true);
        }));
      }
      root.append(tabRow);
      const cells = tab['sheets/cells'] || {};
      // Two beyond whatever is used, so there is always somewhere to type.
      let maxRow = 3; let maxCol = 3;
      Object.keys(cells).forEach((key) => {
        // Doubled backslashes: this JavaScript lives inside a Clojure string,
        // so the reader sees them first.
        const match = /^\[(-?\d+) (-?\d+)\]$/.exec(key);
        if (match) {
          maxRow = Math.max(maxRow, Number(match[1]) + 2);
          maxCol = Math.max(maxCol, Number(match[2]) + 2);
        }
      });
      const grid = make('table', 'surface-grid');
      const head = make('tr');
      head.append(make('th', null, ''));
      for (let col = 1; col <= maxCol; col += 1) head.append(make('th', null, String(col)));
      grid.append(head);
      for (let row = 1; row <= maxRow; row += 1) {
        const tr = make('tr');
        tr.append(make('th', null, String(row)));
        for (let col = 1; col <= maxCol; col += 1) {
          const td = make('td');
          const cell = cells[cellKey(row, col)] || {};
          // A formula shows what it comes to, and shows itself when the
          // cell is being edited — which is what every spreadsheet does and
          // the only way to see a formula you are about to change.
          const formula = cell['sheets/formula'];
          const computed = driveEditor.computed?.[current]?.[`[${row} ${col}]`];
          const shown = formula !== undefined
            ? (computed ?? `=${formula}`) : (cell['sheets/value'] ?? '');
          const input = make('input', 'surface-cell');
          input.type = 'text';
          input.value = shown;
          input.setAttribute('aria-label', `${row}行${col}列`);
          if (formula !== undefined) {
            input.classList.add('surface-cell--computed');
            input.title = `=${formula}`;
            // The formula while the cursor is in it, the value otherwise.
            input.addEventListener('focus', () => { input.value = `=${formula}`; });
            input.addEventListener('blur', () => {
              if (input.value === `=${formula}`) input.value = shown;
            });
          }
          // Which cell the style bar below acts on. A spreadsheet applies
          // formatting to what you have selected, and this grid's notion of
          // selected is the box the cursor is in.
          input.addEventListener('focus', () => { driveEditor.cell = [row, col]; });
          // What a comment on this cell would name, and what `markAnchored`
          // looks for when one does.
          input.dataset.anchor = cellAnchor(current, row, col);
          input.addEventListener('change', () => {
            tab['sheets/cells'] = tab['sheets/cells'] || {};
            const key = cellKey(row, col);
            const text = input.value;
            // The style is the cell's, not the value's. Replacing the whole
            // map on every keystroke threw it away — a bold header stopped
            // being bold the moment somebody corrected a typo in it.
            const style = tab['sheets/cells'][key]?.['sheets/style'];
            const keep = (next) => (style ? {...next, 'sheets/style':style} : next);
            if (text === '') {
              // Emptying a styled cell leaves the formatting: it is applied
              // to the box, not to what was in it.
              if (style) tab['sheets/cells'][key] = {'sheets/style':style};
              else delete tab['sheets/cells'][key];
            }
            // A leading = is a formula, which is the convention every
            // spreadsheet uses and the distinction `sheets.model` draws
            // between :sheets/value and :sheets/formula.
            else if (text.startsWith('=')) tab['sheets/cells'][key] = keep({'sheets/formula': text.slice(1)});
            else tab['sheets/cells'][key] = keep({'sheets/value': text});
            changed(false);
          });
          td.append(input);
          tr.append(td);
        }
        grid.append(tr);
      }
      root.append(grid);

      // The style bar. It acts on the cell the cursor is in, which is what
      // a spreadsheet does — there is no multi-cell selection here, so it
      // says which one it is rather than leaving that to be guessed.
      const at = driveEditor.cell;
      const styleBar = make('div', 'detail-actions__row');
      const styleOf = () => (at && tab['sheets/cells']?.[cellKey(at[0], at[1])]
                             ?.['sheets/style']) || {};
      const setStyle = (change) => {
        if (!at) return;
        tab['sheets/cells'] = tab['sheets/cells'] || {};
        const key = cellKey(at[0], at[1]);
        const cell = tab['sheets/cells'][key] || {};
        const style = {...(cell['sheets/style'] || {}), ...change};
        // A style with nothing in it is not a style. Dropping the key keeps
        // an untouched cell out of `distinct-styles` and out of styles.xml.
        Object.keys(style).forEach((k) => {
          if (style[k] === false || style[k] === '' || style[k] === null) delete style[k];
        });
        if (Object.keys(style).length) tab['sheets/cells'][key] = {...cell, 'sheets/style':style};
        else {
          const {['sheets/style']:_drop, ...rest} = cell;
          if (Object.keys(rest).length) tab['sheets/cells'][key] = rest;
          else delete tab['sheets/cells'][key];
        }
        changed(true);
      };
      if (!at) {
        styleBar.append(make('span', 'surface-note', 'セルを選ぶと書式を設定できます。'));
      } else {
        const style = styleOf();
        styleBar.append(make('span', 'surface-note',
          `${columnName(at[1])}${at[0]} の書式`));
        [['太字', 'bold'], ['斜体', 'italic'], ['下線', 'underline']].forEach(([label, key]) => {
          const button = make('button', 'tool-button', label);
          button.type = 'button';
          button.setAttribute('aria-pressed', style[key] ? 'true' : 'false');
          button.addEventListener('click', () => setStyle({[key]: !style[key]}));
          styleBar.append(button);
        });
        styleBar.append(field('揃え', selectInput(style.align || '',
          ['', 'left', 'center', 'right'],
          (value) => setStyle({align: value}))));
        styleBar.append(field('表示形式', textInput(style['number-format'] || '',
          (value) => setStyle({'number-format': value}))));
        // Said rather than left to be found: these five travel to Excel and
        // anything else in a style does not.
        styleBar.append(make('span', 'surface-note',
          'これらは .xlsx に書き出されます。'));
      }
      root.append(styleBar);

      // Named ranges. `=SUM(売上)` resolves and the .xlsx carries it, and
      // until now the only way to define one was the JSON editor — which
      // is a working escape hatch and not something a person finds.
      const names = payload['sheets/named-ranges'] || {};
      const namePanel = make('div', 'surface-editor');
      namePanel.append(make('h3', 'sharing__title', '名前付き範囲'));
      const nameList = make('ul', 'sharing__list');
      Object.entries(names).forEach(([name, range]) => {
        const row = make('li', 'sharing__entry');
        row.append(make('span', 'sharing__who', name));
        row.append(make('span', 'sharing__role',
          `${range['sheets/tab']} · ${range['sheets/range']}`));
        row.append(removeButton(() => {
          delete payload['sheets/named-ranges'][name];
          changed(true);
        }));
        nameList.append(row);
      });
      if (!Object.keys(names).length) {
        nameList.append(make('li', 'empty-state', 'まだありません。'));
      }
      const nameRow = make('div', 'detail-actions__row');
      const nameInput = make('input', 'workspace-search document-title');
      nameInput.type = 'text';
      nameInput.placeholder = '名前';
      nameInput.setAttribute('aria-label', '名前付き範囲の名前');
      const rangeInput = make('input', 'workspace-search document-title');
      rangeInput.type = 'text';
      rangeInput.placeholder = 'A1:A3';
      rangeInput.setAttribute('aria-label', '範囲');
      const addName = make('button', 'tool-button', '名前を付ける');
      addName.type = 'button';
      addName.addEventListener('click', () => {
        const name = (nameInput.value || '').trim();
        const range = (rangeInput.value || '').trim();
        if (!name || !range) return;
        payload['sheets/named-ranges'] = payload['sheets/named-ranges'] || {};
        // The tab's *title*, because that is what a definedName references
        // and what the evaluator matches on. Its id would resolve nowhere.
        payload['sheets/named-ranges'][name] =
          {'sheets/id':name, 'sheets/tab':tab['sheets/title'] || current,
           'sheets/range':range};
        changed(true);
      });
      nameRow.append(nameInput, rangeInput, addName);
      namePanel.append(nameList, nameRow);
      namePanel.append(make('p', 'surface-note',
        '名前は現在のタブに付きます。数式では SUM(名前) のように使えます。'));
      root.append(namePanel);

      // Charts. Drawn on the server by `sheets.chart`, so what is shown
      // here is the same SVG anything else rendering this workbook gets —
      // and a chart is only worth defining if you can see it.
      const charts = payload['sheets/charts'] || [];
      const chartPanel = make('div', 'surface-editor');
      chartPanel.append(make('h3', 'sharing__title', 'グラフ'));
      const drawn = driveEditor.charts?.[current] || [];
      charts.forEach((chart, index) => {
        const box = make('div', 'chart-card');
        const head = make('div', 'detail-actions__row');
        head.append(make('span', 'sharing__who',
          `${chart['sheets/title'] || chart['sheets/id'] || ''}`));
        head.append(field('範囲', textInput(chart['sheets/data-range'],
          (value) => { chart['sheets/data-range'] = value; changed(true); })));
        head.append(field('種類', selectInput(chart['sheets/chart-type'] || 'bar',
          ['bar', 'line', 'pie'],
          (value) => { chart['sheets/chart-type'] = value; changed(true); })));
        head.append(removeButton(() => {
          payload['sheets/charts'].splice(index, 1); changed(true);
        }));
        box.append(head);
        const svg = drawn.find((d) => d.id === chart['sheets/id'])?.svg;
        if (svg) {
          const figure = make('div', 'chart-card__figure');
          // The server built this string from the workbook; it is not user
          // markup arriving from anywhere else.
          figure.innerHTML = svg;
          box.append(figure);
        } else {
          // Said rather than drawn blank: axes around no data read as
          // there being none, which is the wrong answer for a wrong range.
          box.append(make('p', 'surface-note',
            'この範囲に数値がないので描けません。保存すると再描画します。'));
        }
        chartPanel.append(box);
      });
      if (!charts.length) chartPanel.append(make('p', 'empty-state', 'まだありません。'));
      const addChart = make('button', 'tool-button', 'グラフを追加');
      addChart.type = 'button';
      addChart.addEventListener('click', () => {
        payload['sheets/charts'] = payload['sheets/charts'] || [];
        let n = payload['sheets/charts'].length + 1;
        const ids = new Set(payload['sheets/charts'].map((c) => c['sheets/id']));
        while (ids.has(`chart${n}`)) n += 1;
        payload['sheets/charts'].push({'sheets/id':`chart${n}`,
                                       'sheets/title':`グラフ${n}`,
                                       'sheets/tab':tab['sheets/title'] || current,
                                       'sheets/chart-type':'bar',
                                       'sheets/data-range':'A1:B3'});
        changed(true);
      });
      chartPanel.append(addChart);
      chartPanel.append(make('p', 'surface-note',
        'グラフは保存すると描き直され、.xlsx にも書き出されます。'));
      root.append(chartPanel);
      return root;
    };
    const slidesEditor = (payload, vocabulary, changed) => {
      const root = make('div', 'surface-editor');
      root.append(field('タイトル', textInput(payload['slides/title'],
        (value) => { payload['slides/title'] = value; changed(false); })));
      const list = make('div', 'surface-list');
      (payload['slides/slides'] || []).forEach((slide, index) => {
        const card = make('div', 'surface-row');
        if (slide['slides/id']) card.dataset.anchor = String(slide['slides/id']);
        card.addEventListener('focusin', () => { driveEditor.slide = index; });
        card.append(
          field('ID', textInput(slide['slides/id'],
            (value) => { slide['slides/id'] = value; changed(false); })),
          // A name for the slide, not a heading on it. `slides.pptx` writes
          // it nowhere and `slides.office` generates one when reading a file
          // that has none, so calling it a heading promises text that never
          // appears — on the slide or in the exported .pptx.
          field('スライド名', textInput(slide['slides/title'],
            (value) => { slide['slides/title'] = value; changed(false); },
            'surface-input--wide')));
        // The slide, drawn by `slides.svg` on the server — the same picture
        // anything else rendering this deck gets. Position and size are
        // worth typing once you can see what they move, which is why the
        // shape fields below exist now and did not before.
        const drawn = (driveEditor.slides || [])[index];
        if (drawn?.svg) {
          const figure = make('div', 'slide-preview');
          // Built on the server from this deck; not markup arriving from
          // anywhere else.
          figure.innerHTML = drawn.svg;
          card.append(figure);
        }
        // Inches, the unit the model measures in, so a number here and the
        // number in the picture are the same number.
        const box = (shape) => {
          const row = make('div', 'detail-actions__row');
          [['x', '横'], ['y', '縦'], ['w', '幅'], ['h', '高さ']].forEach(([key, label]) => {
            const input = make('input', 'workspace-search document-title');
            input.type = 'number';
            input.step = '0.1';
            input.value = shape[`slides/${key}`] ?? '';
            input.setAttribute('aria-label', `${shape['slides/id']} の${label}`);
            input.addEventListener('change', () => {
              const n = Number(input.value);
              // A blank or unparseable box is left alone rather than
              // written as NaN, which the renderer would fall back on and
              // the exporter would write as a shape of no size.
              if (input.value !== '' && Number.isFinite(n)) {
                shape[`slides/${key}`] = n;
                changed(true);
              }
            });
            row.append(field(label, input));
          });
          return row;
        };
        (slide['slides/shapes'] || []).forEach((shape) => {
          const kind = shape['slides/shape'];
          // A link on the shape, whatever kind it is: `slides.pptx` puts
          // the relationship on the shape rather than on its text, so a
          // picture and a box can each be one.
          const shapeLink = () => {
            const row = make('div', 'appointment__invite');
            const url = make('input', 'workspace-search');
            url.type = 'url';
            url.value = shape['slides/hyperlink'] ?? '';
            url.placeholder = 'リンク先（https://…）';
            url.setAttribute('aria-label', `${shape['slides/id']} のリンク先`);
            url.addEventListener('change', () => {
              const value = url.value.trim();
              if (!value) { delete shape['slides/hyperlink']; changed(true); return; }
              // The same three schemes `slides.model/shape-link` allows.
              // Checked here as well as there, not because the client is
              // trusted but because storing a link nothing will follow, and
              // saying nothing, is how a deck claims one it does not have.
              if (!docLink({link:value})) {
                const note = $('#drive-create-status');
                if (note) {
                  note.textContent = 'リンクは http・https・mailto のいずれかにしてください。';
                }
                url.value = shape['slides/hyperlink'] ?? '';
                return;
              }
              shape['slides/hyperlink'] = value;
              changed(true);
            });
            row.append(url);
            return row;
          };
          if (kind === 'text') {
            card.append(field(`テキスト（${shape['slides/id']}）`,
              textInput(shape['slides/text'],
                (value) => { shape['slides/text'] = value; changed(false); },
                'surface-input--wide')));
            // How the text looks. `slides.svg` draws every one of these and
            // `slides.pptx` writes every one into the .pptx, and the editor
            // offered none of them: text on a slide could not be made bold
            // without opening the JSON pane, which is a working escape
            // hatch and a wall for anyone who has not been told about it.
            //
            // A whole shape at a time, unlike a document's runs. A text box
            // is one run to this model — there is no offset into it to
            // style — so a mark is a property of the box and the interface
            // should not suggest otherwise by asking for a selection.
            const marks = make('div', 'appointment__answers');
            [['bold', '太字'], ['italic', '斜体'], ['underline', '下線'],
             ['strikethrough', '取り消し線']].forEach(([key, label]) => {
              const button = make('button', 'tool-button', label);
              button.type = 'button';
              button.setAttribute('aria-pressed', shape[`slides/${key}`] ? 'true' : 'false');
              button.addEventListener('click', () => {
                // Absent rather than false: a shape saying it is not bold
                // is a key in every exported deck for a property nobody
                // set, and `slides.pptx` writes what it is given.
                if (shape[`slides/${key}`]) delete shape[`slides/${key}`];
                else shape[`slides/${key}`] = true;
                changed(true);
              });
              marks.append(button);
            });
            card.append(field('装飾', marks));
            const size = make('input', 'workspace-search document-title');
            size.type = 'number';
            size.min = '1';
            size.value = shape['slides/font-size'] ?? '';
            size.placeholder = 'pt';
            size.setAttribute('aria-label', '文字の大きさ（ポイント）');
            size.addEventListener('change', () => {
              const n = Number(size.value);
              // A size that is not a number is not a size. The renderer
              // falls back to 18pt for a missing one, which is the right
              // answer for a box nobody has sized and the wrong one to
              // store as a guess.
              if (Number.isFinite(n) && n > 0) shape['slides/font-size'] = n;
              else delete shape['slides/font-size'];
              changed(true);
            });
            const colour = make('input', 'workspace-search document-title');
            colour.type = 'text';
            colour.value = shape['slides/color'] ?? '';
            colour.placeholder = '色（例 24292F）';
            colour.setAttribute('aria-label', '文字の色');
            colour.addEventListener('change', () => {
              const value = colour.value.trim().replace(/^#/, '');
              // Six hex digits and no hash, which is what OOXML stores and
              // what `slides.svg/colour` adds the hash back to. Anything
              // else is dropped rather than written, because an
              // unparseable fill draws black and looks deliberate.
              if (/^[0-9A-Fa-f]{6}$/.test(value)) shape['slides/color'] = value.toUpperCase();
              else delete shape['slides/color'];
              changed(true);
            });
            card.append(field('大きさ', size), field('色', colour));
            card.append(box(shape));
            card.append(field('リンク', shapeLink()));
          } else if (kind === 'rect') {
            card.append(make('span', 'surface-note', `図形（${shape['slides/id']}）`));
            card.append(field('塗り', textInput(shape['slides/fill'],
              (value) => { shape['slides/fill'] = value; changed(true); })));
            card.append(box(shape));
            card.append(field('リンク', shapeLink()));
          } else if (kind === 'table') {
            // A grid of text. The same shape `docs`' table editor draws,
            // because it is the same problem: rows of rows, ragged allowed,
            // and the widest row is how many columns there are.
            const rows = shape['slides/rows'] || [];
            const width = Math.max(1, ...rows.map((r) => (r || []).length));
            const grid = make('table', 'surface-grid');
            rows.forEach((cells, r) => {
              const tr = make('tr');
              for (let c = 0; c < width; c += 1) {
                const td = make('td');
                const input = make('input', 'surface-cell');
                input.type = 'text';
                input.value = (cells || [])[c] ?? '';
                input.setAttribute('aria-label', `${r + 1}行${c + 1}列`);
                input.addEventListener('change', () => {
                  // Filled out to the width on the way in, so a row nobody
                  // has touched stays short and one somebody typed into is
                  // rectangular — the writer pads to the widest anyway, and
                  // this keeps what is stored equal to what was entered.
                  while (shape['slides/rows'][r].length < width) {
                    shape['slides/rows'][r].push('');
                  }
                  shape['slides/rows'][r][c] = input.value;
                  changed(false);
                });
                td.append(input);
                tr.append(td);
              }
              const td = make('td');
              td.append(removeButton(() => {
                shape['slides/rows'].splice(r, 1); changed(true);
              }));
              tr.append(td);
              grid.append(tr);
            });
            const tableBox = make('div', 'surface-editor');
            tableBox.append(grid);
            const tableRow = make('div', 'detail-actions__row');
            const addRow = make('button', 'tool-button', '行を追加');
            addRow.type = 'button';
            addRow.addEventListener('click', () => {
              shape['slides/rows'] = (shape['slides/rows'] || [])
                .concat([new Array(width).fill('')]);
              changed(true);
            });
            const addCol = make('button', 'tool-button', '列を追加');
            addCol.type = 'button';
            addCol.addEventListener('click', () => {
              shape['slides/rows'] = (shape['slides/rows'] || [[]])
                .map((r) => (r || []).concat(['']));
              changed(true);
            });
            tableRow.append(addRow, addCol);
            tableBox.append(tableRow);
            card.append(make('span', 'surface-note', `表（${shape['slides/id']}）`),
                        tableBox, box(shape), field('リンク', shapeLink()));
          } else if (kind === 'image') {
            // The size, because a picture is the one shape that makes a
            // deck heavy and every save writes all of it again. Base64 is
            // four characters per three bytes.
            const stored = String(shape['slides/image-data'] || '').length;
            card.append(make('span', 'surface-note',
              `画像（${shape['slides/id']}）${stored ? ` · ${bytes(Math.floor(stored * 3 / 4))}` : ''}`));
            card.append(box(shape), field('リンク', shapeLink()));
            card.append(removeButton(() => {
              const shapes = slide['slides/shapes'];
              shapes.splice(shapes.indexOf(shape), 1);
              changed(true);
            }));
          } else {
            // A component or a kind the renderer does not know. Its
            // position could be edited, and moving a shape nobody can see
            // is worse than handing it over.
            card.append(make('span', 'surface-note',
              `${kind || '?'}（${shape['slides/id']}）は JSON で編集してください。`));
          }
        });
        // Speaker notes. `slides.pptx` has written them as a real
        // notesSlide part the whole time — and patches the one an imported
        // deck already had — while nothing in this app read or wrote them,
        // so a deck imported with notes lost them on the next save and a
        // deck made here never had any.
        const notes = make('textarea', 'form-control form-control--area');
        notes.value = slide['slides/notes'] ?? '';
        notes.placeholder = '発表者ノート（スライドには映りません）';
        notes.setAttribute('aria-label', `${slide['slides/id']} の発表者ノート`);
        notes.addEventListener('change', () => {
          const text = notes.value;
          // Absent rather than empty: `slides.pptx` writes a notesSlide
          // part for a slide that has the key at all, so an empty string
          // would put a blank notes page in the .pptx for every slide
          // somebody clicked into and left.
          if (text.trim()) slide['slides/notes'] = text;
          else delete slide['slides/notes'];
          changed(false);
        });
        card.append(field('発表者ノート', notes));
        const addRect = make('button', 'tool-button', '図形を追加');
        addRect.type = 'button';
        addRect.addEventListener('click', () => {
          slide['slides/shapes'] = slide['slides/shapes'] || [];
          // What `slides.model/rect` produces, defaults included — a shape
          // without a box is one the renderer has to guess at.
          slide['slides/shapes'].push({
            'slides/id': `r${slide['slides/shapes'].length + 1}`,
            'slides/shape': 'rect',
            'slides/x': 0.8, 'slides/y': 2.1, 'slides/w': 8.4, 'slides/h': 2.0,
            'slides/fill': 'EAF0F8', 'slides/line': '496B9A'
          });
          changed(true);
        });
        card.append(addRect);
        // A picture. `slides.model/image` carries the bytes as base64 in
        // the shape and `slides.pptx` embeds them as a `p:pic` with its own
        // media part, so the format has been able to do this the whole
        // time: the editor could move an image's box and had no way to make
        // one, which meant a picture could only arrive by importing a .pptx.
        //
        // Base64 in the document, because that is where the model puts it —
        // the deck travels whole, into a .pptx or an EDN export, with no
        // second place the bytes live and nothing to re-resolve later. What
        // it costs is that every save writes the picture again: the deck is
        // one object and a version is the whole of it.
        const imagePicker = make('input', null);
        imagePicker.type = 'file';
        imagePicker.accept = 'image/png,image/jpeg,image/gif,image/webp';
        imagePicker.hidden = true;
        imagePicker.addEventListener('change', async () => {
          const file = imagePicker.files?.[0];
          imagePicker.value = '';
          if (!file) return;
          const note = $('#drive-create-status');
          // The types `slides.pptx` has an extension for. One it does not
          // know is written as `.png` and PowerPoint opens a file whose
          // bytes are not what its name says.
          if (!imagePicker.accept.split(',').includes(file.type)) {
            if (note) note.textContent = `${file.type || 'この形式'} は貼れません。PNG・JPEG・GIF・WebP のいずれかにしてください。`;
            return;
          }
          // Every save rewrites the whole deck, so a large picture is a
          // large write on every keystroke that follows it. The cap is
          // stated rather than silently resized: resizing would hand back
          // different bytes from the ones that were chosen.
          const limit = 2 * 1024 * 1024;
          if (file.size > limit) {
            if (note) note.textContent = `画像は 2 MB までです（${bytes(file.size)}）。`;
            return;
          }
          try {
            const buffer = await file.arrayBuffer();
            // In chunks: `String.fromCharCode(...array)` on a megabyte of
            // bytes passes a million arguments and overflows the stack.
            const view = new Uint8Array(buffer);
            let binary = '';
            for (let i = 0; i < view.length; i += 0x8000) {
              binary += String.fromCharCode.apply(null, view.subarray(i, i + 0x8000));
            }
            const data = btoa(binary);
            // The picture's own proportions, so it does not arrive
            // stretched. `slides.svg` draws it with `preserveAspectRatio`
            // and would letterbox it inside a wrong box; PowerPoint would
            // not, and would stretch it.
            const measured = await new Promise((resolve) => {
              const probe = new Image();
              probe.addEventListener('load',
                () => resolve({w:probe.naturalWidth, h:probe.naturalHeight}));
              probe.addEventListener('error', () => resolve(null));
              probe.src = `data:${file.type};base64,${data}`;
            });
            const maxW = 6;
            const maxH = 4;
            let w = maxW;
            let h = maxH;
            if (measured && measured.w > 0 && measured.h > 0) {
              const scale = Math.min(maxW / measured.w, maxH / measured.h);
              w = Math.round(measured.w * scale * 100) / 100;
              h = Math.round(measured.h * scale * 100) / 100;
            }
            slide['slides/shapes'] = slide['slides/shapes'] || [];
            // What `slides.model/image` produces, defaults included.
            slide['slides/shapes'].push({
              'slides/id': `i${slide['slides/shapes'].length + 1}`,
              'slides/shape': 'image',
              'slides/x': 0.8, 'slides/y': 0.8, 'slides/w': w, 'slides/h': h,
              'slides/image-data': data,
              'slides/media-type': file.type
            });
            if (note) note.textContent = `${file.name} を貼りました。`;
            changed(true);
          } catch (error) {
            if (note) note.textContent = error.message;
          }
        });
        const addImage = make('button', 'tool-button', '画像を追加');
        addImage.type = 'button';
        addImage.addEventListener('click', () => imagePicker.click());
        card.append(imagePicker, addImage);
        const addTable = make('button', 'tool-button', '表を追加');
        addTable.type = 'button';
        addTable.addEventListener('click', () => {
          slide['slides/shapes'] = slide['slides/shapes'] || [];
          // What `slides.model/table` produces, defaults included — a shape
          // without a box is one the renderer has to guess at.
          slide['slides/shapes'].push({
            'slides/id': `tb${slide['slides/shapes'].length + 1}`,
            'slides/shape': 'table',
            'slides/x': 0.8, 'slides/y': 1.5, 'slides/w': 8.4, 'slides/h': 2.0,
            'slides/rows': [['', ''], ['', '']]
          });
          changed(true);
        });
        card.append(addTable);
        const addText = make('button', 'tool-button', 'テキストを追加');
        addText.type = 'button';
        addText.addEventListener('click', () => {
          slide['slides/shapes'] = slide['slides/shapes'] || [];
          // The shape `slides.model/text-box` produces, defaults included —
          // a text box without a box is one the renderer has to guess at.
          slide['slides/shapes'].push({
            'slides/id': `t${slide['slides/shapes'].length + 1}`,
            'slides/shape': 'text', 'slides/text': '',
            'slides/x': 0.8, 'slides/y': 0.8, 'slides/w': 8.4, 'slides/h': 1.0,
            'slides/font-size': 28
          });
          changed(true);
        });
        card.append(addText, removeButton(() => {
          payload['slides/slides'].splice(index, 1); changed(true);
        }));
        list.append(card);
      });
      if (!(payload['slides/slides'] || []).length) {
        list.append(make('p', 'empty-state', 'まだスライドがありません。'));
      }
      const add = make('button', 'tool-button', 'スライドを追加');
      add.type = 'button';
      add.addEventListener('click', () => {
        payload['slides/slides'] = payload['slides/slides'] || [];
        const n = payload['slides/slides'].length + 1;
        payload['slides/slides'].push({
          'slides/id': `slide${n}`, 'slides/title': `スライド ${n}`, 'slides/shapes': []
        });
        changed(true);
      });
      root.append(list, add);
      return root;
    };
    const surfaceEditors = {forms:formsEditor, docs:docsEditor, sheets:sheetsEditor,
                            slides:slidesEditor};
    // ── rendered surfaces ─────────────────────────────────────────────────
    // The editors above are the fields of the value. These are the value as
    // the thing it is: a form as a respondent sees it, a document as a page,
    // a workbook as a grid with A1 addresses, a deck as slides on a stage.
    //
    // Read-only, and the same projected payload the editors mutate — so this
    // is a third view and not a third format. What it is not is a second
    // renderer: `slides.pptx` and the Markdown and CSV writers are on the
    // server, and export is what goes through them. Nothing here is offered
    // as a substitute for what a file will look like.
    //
    // A text input's `type` is the same table `answerPanel` fills a real form
    // from, declared once here because both need it and two copies of it
    // would drift.
    const inputTypes = {email:'email', number:'number', date:'date'};
    // `:docs/style` on a text run is whoever-wrote-it's map, so a class is
    // looked up per truthy key rather than assumed to be one name.
    const docRunClasses = {bold:'doc-run--bold', italic:'doc-run--italic',
                           underline:'doc-run--underline', strike:'doc-run--strike',
                           code:'doc-run--code'};
    // The schemes a document's link may use, and the same three
    // `docs.model/link` allows. A document is rendered here as DOM and by
    // the print page as HTML, and a `javascript:` href in either is script
    // running in the reader's session — so this is an allowlist, and one
    // that has to agree with the library rather than be more generous than
    // it. Anything else keeps its text and loses its link.
    const linkSchemes = ['http:', 'https:', 'mailto:'];
    const docLink = (style) => {
      if (!style || typeof style !== 'object') return null;
      const url = String(style.link ?? style['docs/link'] ?? '').trim();
      if (!url) return null;
      const scheme = (url.match(/^([A-Za-z][A-Za-z0-9+.-]*):/) || [])[1];
      return scheme && linkSchemes.includes(`${scheme.toLowerCase()}:`) ? url : null;
    };
    const docRunClass = (style) => {
      if (typeof style === 'string') return docRunClasses[style] || null;
      if (!style || typeof style !== 'object') return null;
      const names = Object.keys(style)
        // `{bold: false}` is a run that says it is not bold.
        .filter((key) => style[key])
        .map((key) => docRunClasses[key.replace(/^docs\//, '')])
        .filter(Boolean);
      return names.length ? names.join(' ') : null;
    };
    // A block's text with its runs applied. Rendered rather than dropped: a
    // run that is stored and never shown is formatting the document claims to
    // have and the page denies. Overlaps are not nested — the model gives a
    // flat list of ranges, and a tree is not derivable from it — so a later
    // run starts where the previous one ended.
    const docText = (block) => {
      const text = String(block['docs/text'] ?? '');
      const runs = (block['docs/text-runs'] || [])
        .filter((run) => run && typeof run === 'object')
        .map((run) => ({from:Number(run['docs/from']), to:Number(run['docs/to']),
                        className:docRunClass(run['docs/style']),
                        href:docLink(run['docs/style'])}))
        .filter((run) => Number.isFinite(run.from) && Number.isFinite(run.to))
        .map((run) => ({...run, from:Math.max(0, run.from), to:Math.min(text.length, run.to)}))
        .filter((run) => run.to > run.from)
        .sort((a, b) => a.from - b.from);
      if (!runs.length) return [document.createTextNode(text)];
      const nodes = [];
      let at = 0;
      runs.forEach((run) => {
        const from = Math.max(at, run.from);
        if (run.to <= from) return;
        if (from > at) nodes.push(document.createTextNode(text.slice(at, from)));
        if (run.href) {
          const anchor = make('a', run.className, text.slice(from, run.to));
          anchor.href = run.href;
          anchor.rel = 'noreferrer noopener';
          anchor.target = '_blank';
          nodes.push(anchor);
        } else {
          nodes.push(make('span', run.className, text.slice(from, run.to)));
        }
        at = run.to;
      });
      if (at < text.length) nodes.push(document.createTextNode(text.slice(at)));
      return nodes;
    };
    const docsPreview = (payload) => {
      const page = make('article', 'doc-page');
      page.append(make('h1', 'doc-page__title',
        payload['docs/title'] || '無題のドキュメント'));
      const blocks = (payload['docs/blocks'] || []).filter((b) => b && typeof b === 'object');
      blocks.forEach((block) => {
        const kind = String(block['docs/kind'] ?? '');
        if (kind === 'heading') {
          // The title is the h1, so a level-1 heading inside the body is an
          // h2 — otherwise the page has two first-level headings and a
          // screen reader is told the document starts twice.
          const level = Math.min(6, Math.max(1, Number(block['docs/level']) || 1));
          const heading = make(`h${Math.min(6, level + 1)}`);
          heading.append(...docText(block));
          page.append(heading);
        } else if (kind === 'image') {
          const media = String(block['docs/media-type'] ?? '');
          const data = String(block['docs/image-data'] ?? '').trim();
          // The same allowlist `docs.model/image-data` uses. A media type
          // nobody checked is one the browser will sniff, and this builds a
          // `src` out of it.
          if (data && ['image/png', 'image/jpeg', 'image/gif', 'image/webp'].includes(media)) {
            const figure = make('figure', 'doc-figure');
            const picture = make('img');
            picture.src = `data:${media};base64,${data}`;
            picture.alt = String(block['docs/alt'] ?? '');
            figure.append(picture);
            page.append(figure);
          } else {
            page.append(make('p', 'empty-state', '表示できない画像です。'));
          }
        } else if (kind === 'quote') {
          const quote = make('blockquote');
          quote.append(...docText(block));
          page.append(quote);
        } else if (kind === 'code') {
          const pre = make('pre');
          pre.append(make('code', null, String(block['docs/text'] ?? '')));
          page.append(pre);
        } else if (kind === 'list') {
          const items = block['docs/items'] || [];
          const list = make('ul');
          items.forEach((entry) => list.append(make('li', null, String(entry ?? ''))));
          if (!items.length) list.append(make('li', null, '（項目なし）'));
          page.append(list);
        } else if (kind === 'table') {
          const rows = (block['docs/rows'] || []).filter(Array.isArray);
          if (!rows.length) {
            page.append(make('p', 'surface-note', '空の表です。'));
          } else {
            const table = make('table', 'doc-table');
            rows.forEach((row, index) => {
              const tr = make('tr');
              // First row as the header: that is what the Markdown writer
              // does with it, so the page and the export agree.
              row.forEach((cell) => tr.append(
                make(index === 0 ? 'th' : 'td', null, String(cell ?? ''))));
              table.append(tr);
            });
            page.append(table);
          }
        } else if (refKinds.includes(kind)) {
          // Resolved against what this principal can see, which is the same
          // list the picker offers and the same question the server answers
          // as a save-time warning.
          const target = block['docs/target'];
          const hit = (driveData.items || []).find((candidate) => candidate.id === target);
          const wrap = make('p');
          wrap.append(make('span', `doc-ref${hit ? '' : ' doc-ref--dangling'}`,
            hit ? `${hit.label}: ${hit.name}`
                : `${kind} → ${target || '未設定'}（解決できません）`));
          page.append(wrap);
        } else {
          const para = make('p');
          para.append(...docText(block));
          page.append(para);
        }
      });
      if (!blocks.length) {
        page.append(make('p', 'surface-note',
          'まだ本文がありません。「フォーム表示」で段落を追加してください。'));
      }
      // Beside the page rather than in the margin: an anchored comment needs
      // to know where its anchor landed, and this render does not lay text
      // out well enough to point at a character.
      const comments = (payload['docs/comments'] || []).filter((c) => c && typeof c === 'object');
      if (comments.length) {
        const aside = make('div', 'doc-aside');
        aside.append(make('h2', null, `コメント ${comments.length} 件`));
        comments.forEach((comment) => aside.append(make('p', null,
          `${comment['docs/author'] || '不明'}: ${comment['docs/text'] ?? ''}`)));
        page.append(aside);
      }
      return page;
    };
    const formsPreview = (payload) => {
      const paper = make('div', 'form-paper');
      const head = make('div', 'form-paper__head');
      head.append(make('h2', 'form-paper__title',
        payload['forms/title'] || '無題のフォーム'));
      head.append(make('p', 'form-paper__lead',
        '回答者に見える形です。実際に送信できるのは下の「このフォームに回答」で、'
        + 'ここのコントロールは形を示すだけです。'));
      paper.append(head);
      const fields = (payload['forms/fields'] || []).filter((f) => f && typeof f === 'object');
      fields.forEach((entry) => {
        const type = String(entry['forms/field-type'] ?? 'text');
        const card = make('div', 'form-card');
        const label = make('p', 'form-card__label',
          String(entry['forms/label'] ?? entry['forms/id'] ?? ''));
        if (entry['forms/required?']) label.append(make('span', 'form-card__required', '*'));
        card.append(label);
        if (type === 'textarea') {
          const area = make('textarea', 'form-control form-control--area');
          area.disabled = true;
          area.placeholder = '長い回答';
          card.append(area);
        } else if (type === 'checkbox') {
          const row = make('span', 'form-control--check');
          const box = make('input', 'surface-check');
          box.type = 'checkbox';
          box.disabled = true;
          row.append(box, make('span', null, 'はい / いいえ'));
          card.append(row);
        } else if (type === 'choice') {
          // `forms.model` gives a choice field no option list, so a deck of
          // options is whatever the document happens to carry. Saying the
          // list is empty beats rendering a select that looks configured.
          const options = (entry['forms/options'] || []).map((option) => String(option));
          const select = make('select', 'form-control');
          select.disabled = true;
          select.append(make('option', null,
            options.length ? '選択してください' : '選択肢が未設定です'));
          options.forEach((option) => select.append(make('option', null, option)));
          card.append(select);
        } else {
          const input = make('input', 'form-control');
          input.type = inputTypes[type] || 'text';
          input.disabled = true;
          input.placeholder = type === 'email' ? 'name@example.com'
            : (type === 'number' ? '0' : (type === 'date' ? '年 / 月 / 日' : '回答を入力'));
          card.append(input);
        }
        card.append(make('span', 'form-card__type',
          `${type}${entry['forms/required?'] ? ' · 必須' : ''}`));
        paper.append(card);
      });
      if (!fields.length) {
        paper.append(make('p', 'empty-state',
          'まだ質問がありません。「フォーム表示」で質問を追加してください。'));
      }
      return paper;
    };
    // A1 rather than 1行1列: the addresses are numeric in `sheets.model` and
    // this is the notation every formula in the sheet is written in.
    // What a comment on a cell is called. One function, because the grid
    // writes it onto every cell and the comment box reads it back: two
    // spellings of `Sheet1!B3` would mean a dot that never appears, and
    // nothing would say why.
    const cellAnchor = (tab, row, col) => `${tab}!${columnName(col)}${row}`;
    const columnName = (col) => {
      let name = '';
      let n = col;
      while (n > 0) {
        name = String.fromCharCode(65 + ((n - 1) % 26)) + name;
        n = Math.floor((n - 1) / 26);
      }
      return name || String(col);
    };
    const sheetsPreview = (payload, _vocabulary, changed) => {
      const paper = make('div', 'sheet-paper');
      const tabs = payload['sheets/tabs'] || {};
      const tabIds = Object.keys(tabs);
      if (!tabIds.length) {
        paper.append(make('p', 'empty-state', 'タブがありません。'));
        return paper;
      }
      const current = tabIds.includes(driveEditor.tab) ? driveEditor.tab : tabIds[0];
      driveEditor.tab = current;
      const bar = make('div', 'sheet-tabs');
      tabIds.forEach((id) => {
        const button = make('button', 'sheet-tab', tabs[id]?.['sheets/title'] || id);
        button.type = 'button';
        button.setAttribute('aria-pressed', id === current ? 'true' : 'false');
        // The same `driveEditor.tab` the editor uses, so switching tab in
        // either view is switching it in both.
        button.addEventListener('click', () => { driveEditor.tab = id; changed(true); });
        bar.append(button);
      });
      paper.append(bar);
      const cells = tabs[current]?.['sheets/cells'] || {};
      // A floor, not just the used extent. A workbook that has just been
      // created has no cells at all, and the honest 1 × 1 answer draws a single
      // empty box that reads as a broken grid rather than as an empty sheet —
      // measured on a real just-created spreadsheet. Five is enough rows and
      // columns to be recognisable as one.
      let maxRow = 5;
      let maxCol = 5;
      Object.keys(cells).forEach((key) => {
        // Doubled backslashes: this JavaScript lives inside a Clojure string.
        const match = /^\[(-?\d+) (-?\d+)\]$/.exec(key);
        if (match) {
          maxRow = Math.max(maxRow, Number(match[1]));
          maxCol = Math.max(maxCol, Number(match[2]));
        }
      });
      const scroll = make('div', 'sheet-scroll');
      const table = make('table', 'sheet-table');
      const head = make('tr');
      head.append(make('th', 'sheet-corner', ''));
      for (let col = 1; col <= maxCol; col += 1) {
        head.append(make('th', null, columnName(col)));
      }
      table.append(head);
      for (let row = 1; row <= maxRow; row += 1) {
        const tr = make('tr');
        tr.append(make('th', 'sheet-rownum', String(row)));
        for (let col = 1; col <= maxCol; col += 1) {
          const cell = cells[cellKey(row, col)] || {};
          const formula = cell['sheets/formula'];
          const value = cell['sheets/value'];
          // What the formula comes to, from `sheets.formula` on the server.
          // This used to show the formula's text because there was no
          // evaluator; there is one now, and a spreadsheet that shows you
          // =SUM(B2:B9) instead of the total is a picture of a spreadsheet.
          // The formula is still there, in the cell's title.
          const computed = driveEditor.computed?.[current]?.[`[${row} ${col}]`];
          const shown = formula !== undefined
            ? (computed ?? `=${formula}`) : (value ?? '');
          // A computed number is a number, and reads right-aligned like one.
          const numeric = shown !== '' && Number.isFinite(Number(shown));
          const td = make('td', [formula !== undefined ? 'sheet-cell--formula' : null,
                                 numeric ? 'sheet-cell--num' : null,
                                 row === 1 ? 'sheet-cell--head' : null]
                                .filter(Boolean).join(' ') || null,
                          String(shown));
          if (formula !== undefined) td.title = `=${formula}`;
          tr.append(td);
        }
        table.append(tr);
      }
      scroll.append(table);
      paper.append(scroll);
      return paper;
    };
    // The stage `slides.pptx` writes: 10 × 5.625in, shapes in inches and font
    // sizes in points. Restated here because this is JavaScript; the writer
    // remains the only thing that produces a .pptx.
    const deckWidthIn = 10;
    const deckHeightIn = 5.625;
    // Only a hex colour, and only base64 for media. Both come out of a stored
    // document, and a style value is a place a document could otherwise ask
    // the page to fetch something.
    const hexColor = (value) => {
      const hex = String(value ?? '').replace(/^#/, '');
      return /^[0-9A-Fa-f]{3,8}$/.test(hex) ? `#${hex}` : null;
    };
    const deckShape = (shape) => {
      const inches = (value) => (Number.isFinite(Number(value)) ? Number(value) : 0);
      const kind = String(shape['slides/shape'] ?? '');
      const node = (() => {
        if (kind === 'text') {
          const text = make('div', 'deck-shape deck-shape--text',
            String(shape['slides/text'] ?? ''));
          // A point is 1/72in and an inch is a tenth of the stage width, so a
          // container query unit is the only one that survives the thumbnail.
          const pt = Number(shape['slides/font-size']) || 18;
          text.style.fontSize = `${(pt / 72 / deckWidthIn) * 100}cqw`;
          const color = hexColor(shape['slides/color']);
          if (color) text.style.color = color;
          if (shape['slides/bold']) text.style.fontWeight = '700';
          if (shape['slides/align'] === 'center') text.style.justifyContent = 'center';
          if (shape['slides/align'] === 'right') text.style.justifyContent = 'flex-end';
          return text;
        }
        if (kind === 'rect') {
          const rect = make('div', 'deck-shape deck-shape--rect');
          const fill = hexColor(shape['slides/fill']);
          const line = hexColor(shape['slides/line']);
          if (fill) rect.style.background = fill;
          if (line) rect.style.borderColor = line;
          return rect;
        }
        // An image is stored as base64 in the deck and would render from a
        // data: URI — which this page's Content-Security-Policy does not
        // allow, and widening `default-src 'none'` is a decision about what
        // the app may load, not one to make while adding a preview. So the
        // frame says what is there and the .pptx export carries the bytes.
        return make('div', 'deck-shape deck-shape--placeholder',
          kind === 'image'
            ? `画像（${shape['slides/id'] ?? ''}）· pptx に出力されます`
            : `${kind || '?'}（${shape['slides/id'] ?? ''}）`);
      })();
      node.style.left = `${(inches(shape['slides/x']) / deckWidthIn) * 100}%`;
      node.style.top = `${(inches(shape['slides/y']) / deckHeightIn) * 100}%`;
      node.style.width = `${(inches(shape['slides/w']) / deckWidthIn) * 100}%`;
      node.style.height = `${(inches(shape['slides/h']) / deckHeightIn) * 100}%`;
      return node;
    };
    const deckSlide = (slide, className) => {
      const canvas = make('div', className);
      const shapes = ((slide && slide['slides/shapes']) || [])
        .filter((shape) => shape && typeof shape === 'object');
      shapes.forEach((shape) => canvas.append(deckShape(shape)));
      if (!shapes.length) canvas.append(make('div', 'deck-empty', '空のスライド'));
      return canvas;
    };
    const slidesPreview = (payload, _vocabulary, changed) => {
      const stage = make('div', 'deck-stage');
      const slides = (payload['slides/slides'] || []).filter((s) => s && typeof s === 'object');
      if (!slides.length) {
        stage.append(make('p', 'empty-state',
          'まだスライドがありません。「フォーム表示」で追加してください。'));
        return stage;
      }
      const index = Math.min(Math.max(Number(driveEditor.slide) || 0, 0), slides.length - 1);
      driveEditor.slide = index;
      const shown = slides[index];
      stage.append(deckSlide(shown, 'deck-canvas'));
      stage.append(make('p', 'deck-caption',
        `${index + 1} / ${slides.length}・${shown['slides/title'] || shown['slides/id'] || ''}`));
      const film = make('div', 'deck-film');
      slides.forEach((slide, n) => {
        const thumb = make('button', 'deck-thumb');
        thumb.type = 'button';
        thumb.setAttribute('aria-pressed', n === index ? 'true' : 'false');
        thumb.setAttribute('aria-label',
          `スライド ${n + 1}: ${slide['slides/title'] || slide['slides/id'] || ''}`);
        thumb.append(deckSlide(slide, 'deck-thumb__frame'),
          make('span', 'deck-thumb__label',
            `${n + 1}. ${slide['slides/title'] || slide['slides/id'] || ''}`));
        thumb.addEventListener('click', () => { driveEditor.slide = n; changed(true); });
        film.append(thumb);
      });
      stage.append(film);
      return stage;
    };
    const surfacePreviews = {forms:formsPreview, docs:docsPreview, sheets:sheetsPreview,
                             slides:slidesPreview};

    // ── an uploaded PDF, page by page ──────────────────────────────────────
    //
    // Keyed by item id and kept outside the pane, for the same reason the
    // document editor's state is: the detail pane is rebuilt on every
    // keystroke in the search box, so a page number that lived in the
    // element would reset to 1 while the reader was typing.
    const pageViews = {};
    const pageState = (id) => (pageViews[id] ||= {index:0, doc:null, loading:false,
                                                 failed:null});
    const pagePanel = (item) => {
      const state = pageState(item.id);
      const panel = make('div', 'page-view');
      const figure = make('div', 'page-view__figure');
      const status = make('p', 'form-help');
      const bar = make('div', 'toolbar-row');
      const prev = make('button', 'tool-button', '前のページ');
      const next = make('button', 'tool-button', '次のページ');
      prev.type = 'button'; next.type = 'button';
      const label = make('span', 'data-list__meta');

      const draw = () => {
        const doc = state.doc;
        if (state.failed) {
          // The server's own sentence. It distinguishes "too large to show"
          // from "not a PDF" from "no pages came out", and replacing all
          // three with "表示できません" would send the reader to fix the
          // wrong thing.
          status.textContent = state.failed;
          figure.replaceChildren();
          label.textContent = '';
          prev.disabled = next.disabled = true;
          return;
        }
        if (!doc) { status.textContent = 'ページを読み込んでいます…'; return; }
        // The server built this string from the parsed page out of a closed
        // vocabulary of three item kinds; no element it may emit takes a
        // URL. Same category as the workbook charts above, and not markup
        // arriving from anywhere else.
        figure.innerHTML = doc.svg;
        label.textContent = `${doc.page['page/label']} / ${doc.count}`;
        prev.disabled = state.index <= 0;
        next.disabled = state.index >= doc.count - 1;
        status.textContent = doc['scanned?']
          // Named rather than left to be discovered by a search that finds
          // nothing — the same answer `app-preview` gives about a listing.
          ? 'このページには抽出できるテキストがありません（スキャン画像）。'
          : '';
      };

      const load = () => {
        if (state.loading) return;
        state.loading = true;
        fetch(`/api/workspace/drive/documents/${encodeURIComponent(item.id)}`
              + `/pages/${state.index}`)
          .then(async (r) => {
            const body = await r.json().catch(() => null);
            if (!r.ok) throw new Error(body?.error?.message || 'ページを表示できません。');
            return body;
          })
          .then((body) => { state.doc = body; state.failed = null; state.loading = false;
                            draw(); })
          .catch((error) => { state.failed = error.message; state.loading = false;
                              draw(); });
      };

      const go = (delta) => {
        const count = state.doc?.count ?? 1;
        const wanted = Math.max(0, Math.min(count - 1, state.index + delta));
        if (wanted === state.index) return;
        state.index = wanted;
        state.doc = null;
        draw();
        load();
      };
      prev.addEventListener('click', () => go(-1));
      next.addEventListener('click', () => go(1));

      bar.append(prev, next, label);
      panel.append(figure, bar, status);
      draw();
      // Guarded on all three, for the same reason the document open is: the
      // pane is rebuilt per keystroke, and an unguarded fetch here is one
      // PDF parse per character typed in the search box.
      if (!state.doc && !state.loading && !state.failed) load();
      return panel;
    };
    // The detail pane is rebuilt on every render — a keystroke in the search
    // box is enough — so an open editor's text cannot live in the element.
    // It lived there until this was measured: typing in search while editing
    // destroyed the edit with no warning and no way back.
    // `etag` is the version the open payload came from. The server refuses a
    // save that does not carry the current one, so this is not bookkeeping —
    // it is the thing that stops one editor's save deleting another's.
    // `mode` starts at the rendered surface, because a document that was just
    // created is one nobody has been shown yet — opening on the field editor
    // asks what it is instead of saying.
    //
    // `loading` and `failed` are what stop the automatic open from firing
    // again on the next render. The detail pane is rebuilt on every keystroke
    // in the search box, and a fetch per keystroke is what the guard prevents.
    const closedEditor = (id) => ({id, open:false, mode:'preview',
                                   payload:null, text:'', tab:null, slide:0,
                                   etag:null, warnings:null, computed:null, cell:null,
                                   charts:null, slides:null,
                                   loading:false, failed:false});
    let driveEditor = closedEditor(null);
    // Three views of one document: the surface as it is, the fields of it, and
    // the JSON underneath for everything the fields do not reach. Whichever
    // is showing, what gets saved is the same projected payload.
    const documentActions = (item) => {
      const actions = make('div', 'detail-actions');
      const row = make('div', 'detail-actions__row');
      const status = make('p', 'drive-create__status', '');
      if (driveEditor.id !== item.id) driveEditor = closedEditor(item.id);
      const vocabulary = (driveData.kinds || [])
        .find((k) => k.kind === item.kind)?.vocabulary;

      if (item['trashed?']) {
        const restore = make('button', 'tool-button', '復元');
        restore.type = 'button';
        restore.addEventListener('click', () => driveAction(
          `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/restore`, {},
          `${item.name} を復元しました。`));
        row.append(restore);
        actions.append(row, status);
        return actions;
      }

      // A file is bytes, not a document: there is nothing for the editors to
      // open, so the pane offers the one thing that makes sense for it.
      if (item['file?']) {
        // Shown, not only offered — but only for the handful of types the
        // server will serve inline. The client does not decide from the
        // media type: `previewable?` is the report of one allowlist.
        if (item['previewable?']) {
          const figure = make('div', 'file-preview');
          const image = make('img', 'file-preview__image');
          image.src = `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/preview`;
          image.alt = item.name;
          image.loading = 'lazy';
          figure.append(image);
          actions.append(figure);
        } else if (item['media-type'] === 'application/pdf') {
          // A PDF is shown as pages rather than only offered as a download.
          // Not through the image path above: that one serves the FILE, and
          // a PDF is exactly what may not be served inline from this origin.
          // What arrives here is markup the server built out of the parsed
          // page — see `cloud.itonami.app.pageview`.
          actions.append(pagePanel(item));
        }
        const download = make('a', 'tool-button', 'ダウンロード');
        download.href = `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/download`;
        download.setAttribute('download', '');
        row.append(download);
        if (item.role === 'owner') row.append(trash);
        actions.append(row, status, referencePanel(item), commentPanel(item));
        if (item.role === 'owner') actions.append(sharingPanel(item, status));
        return actions;
      }
      const open = make('button', 'tool-button', driveEditor.open ? '再読み込み' : '編集');
      open.type = 'button';
      const save = make('button', 'tool-button', '保存');
      save.type = 'button';
      save.hidden = !driveEditor.open;
      const rename = make('button', 'tool-button', '名前を変更');
      rename.type = 'button';
      const trash = make('button', 'tool-button', 'ゴミ箱へ');
      trash.type = 'button';
      // An inline field rather than window.prompt: a modal dialog blocks the
      // page, and this one would be blocking it to collect a single string
      // the detail pane already has room for.
      const titleField = make('input', 'workspace-search document-title');
      titleField.type = 'text';
      titleField.value = item.name;
      titleField.setAttribute('aria-label', '名前');
      const editor = make('textarea', 'document-preview');
      editor.spellcheck = false;
      editor.setAttribute('aria-label', `${item.name} の内容（JSON）`);
      // Every keystroke, so the text survives the next render rather than
      // only the next save.
      editor.addEventListener('input', () => { driveEditor.text = editor.value; });
      const pane = make('div', 'surface-pane');
      const modes = make('div', 'surface-modes');
      const previewButton = make('button', null, 'プレビュー');
      previewButton.type = 'button';
      const structuredButton = make('button', null, 'フォーム表示');
      structuredButton.type = 'button';
      const jsonButton = make('button', null, 'JSON 表示');
      jsonButton.type = 'button';
      modes.append(previewButton, structuredButton, jsonButton);

      // The one place the views meet. Structured edits write into `payload`
      // and the text is re-derived; JSON edits write the text and it is parsed
      // when switching away. Neither is ever stale on save.
      const syncText = () => { driveEditor.text = JSON.stringify(driveEditor.payload, null, 2); };
      const renderPane = () => {
        pane.replaceChildren();
        modes.hidden = !driveEditor.open;
        if (!driveEditor.open) { editor.hidden = true; return; }
        [[previewButton, 'preview'], [structuredButton, 'structured'],
         [jsonButton, 'json']].forEach(([button, mode]) =>
          button.setAttribute('aria-pressed', driveEditor.mode === mode ? 'true' : 'false'));
        if (driveEditor.mode === 'json') {
          editor.hidden = false;
          editor.value = driveEditor.text;
          return;
        }
        editor.hidden = true;
        const preview = driveEditor.mode === 'preview';
        const build = (preview ? surfacePreviews : surfaceEditors)[item.kind];
        if (!build || !driveEditor.payload) {
          pane.append(make('p', 'empty-state', preview
            ? 'この種類にはまだ表示できる形がありません。JSON 表示で確認してください。'
            : 'この種類はまだ JSON でのみ編集できます。'));
          return;
        }
        // A preview never writes into the payload, but it does move which tab
        // or slide is being looked at — and the callback is what redraws it.
        const rendered = build(driveEditor.payload, vocabulary, (rebuild) => {
          syncText();
          if (rebuild) renderPane();
        });
        if (preview) {
          const frame = make('div', 'surface-preview');
          frame.append(rendered);
          pane.append(frame);
        } else {
          pane.append(rendered);
        }
      };
      // Leaving the JSON view means the text is the truth and the payload has
      // to catch up. Refused rather than silently discarding whichever side is
      // wrong.
      const adoptText = () => {
        if (driveEditor.mode !== 'json') return true;
        try {
          driveEditor.payload = JSON.parse(driveEditor.text);
          return true;
        } catch (error) {
          status.textContent = `JSON として読めないので切り替えられません: ${error.message}`;
          return false;
        }
      };
      const showMode = (mode) => {
        if (driveEditor.mode === mode) return;
        if (!adoptText()) return;
        driveEditor.mode = mode;
        status.textContent = '';
        renderPane();
      };
      previewButton.addEventListener('click', () => showMode('preview'));
      structuredButton.addEventListener('click', () => showMode('structured'));
      jsonButton.addEventListener('click', () => {
        if (driveEditor.mode === 'json') return;
        syncText();
        driveEditor.mode = 'json';
        renderPane();
      });
      // One shape for every way a payload arrives — 編集, a version, a reload
      // after a refused save, and the automatic first open. Written as a
      // literal at each call site, a field added later is a field forgotten at
      // three of them; `slide` and `tab` were exactly that.
      const openedEditor = (fresh) => ({...closedEditor(item.id), open:true,
                                        mode:driveEditor.mode, tab:driveEditor.tab,
                                        slide:driveEditor.slide,
                                        payload:fresh.payload, etag:fresh.etag,
                                        warnings:fresh.warnings,
                                        // What the formulas come to, as of
                                        // the version just read. Cleared by
                                        // closedEditor, so a stale grid
                                        // cannot outlive the payload it
                                        // belongs to.
                                        computed:fresh.computed,
                                        charts:fresh.charts,
                                        slides:fresh.slides});

      const versions = make('div', 'detail-actions__row');
      const bytesDelta = (n) => (n > 0 ? `+${bytes(n)}` : (n < 0 ? `-${bytes(-n)}` : '±0'));
      // Owner only and never automatic: it is irreversible, and a Drive that
      // deleted history at a moment nobody chose would be worse than one
      // that fills up and says so.
      if (item.role === 'owner' && (item.versions || 0) > 1) {
        const prune = make('button', 'tool-button',
          `古い版を削除（最新 ${item.versions > 10 ? 10 : 1} 件を残す・${bytes(item['held-bytes'])} 使用中）`);
        prune.type = 'button';
        prune.addEventListener('click', async () => {
          prune.disabled = true; status.textContent = '古い版を削除しています…';
          try {
            const out = await postJSON(
              `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/prune`,
              {keep: item.versions > 10 ? 10 : 1}, true);
            selectedDrive = out.item;
            await loadWorkspace('drive', renderDrive);
            $('#drive-create-status').textContent =
              `${out.deleted} 件の版を削除し、${bytes(out['freed-bytes'])} を回収しました。`;
          } catch (error) {
            status.textContent = error.message;
          } finally {
            prune.disabled = false;
          }
        });
        versions.append(prune);
      }
      for (let n = (item.versions || 0); n >= 1; n -= 1) {
        // Newest first, labelled with who wrote it and what it cost. On a
        // shared document the version number alone does not tell you whose
        // change you are about to open.
        const wrote = (item.history || [])[n - 1];
        const current = n === (item.versions || 0);
        const label = [`版 ${n}`, wrote?.author,
                       wrote ? bytesDelta(wrote['delta-bytes'] ?? 0) : null,
                       current ? '（現在）' : null].filter(Boolean).join('・');
        const version = make('button', 'tool-button', label);
        version.type = 'button';
        if (!current && item['writable?']) {
          const restore = make('button', 'tool-button', `版 ${n} に戻す`);
          restore.type = 'button';
          restore.addEventListener('click', async () => {
            restore.disabled = true; status.textContent = `版 ${n} に戻しています…`;
            try {
              const out = await postJSON(
                `/api/workspace/drive/documents/${encodeURIComponent(item.id)}`
                  + `/versions/${n}/restore`,
                {etag:item.etag}, true);
              // A new version on top, not a rewrite — so the count goes up.
              driveEditor = closedEditor(item.id);
              selectedDrive = out.item;
              await loadWorkspace('drive', renderDrive);
              $('#drive-create-status').textContent =
                `版 ${out['restored-from']} の内容を版 ${out.item.versions} として保存しました。`;
            } catch (error) {
              status.textContent = error.message;
            } finally {
              restore.disabled = false;
            }
          });
          versions.append(restore);
        }
        version.addEventListener('click', async () => {
          status.textContent = `版 ${n} を読み込んでいます…`;
          try {
            const request = await fetch(
              `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/versions/${n}`);
            const data = await request.json();
            if (!request.ok) throw new Error(data?.error?.message || '版を取得できませんでした。');
            // The etag of the *current* version, not of the one being
            // viewed: saving an old version forward is a new version on top
            // of what is there, not a rewrite of history.
            driveEditor = openedEditor(
              // Warnings cleared, not carried over: the ones on screen were
              // about the current version, and an old version loaded into the
              // editor is not it.
              {payload:data.payload, etag:data.item?.etag,
               warnings:data['export-warnings'] || null});
            syncText();
            save.hidden = false;
            renderPane();
            // Loaded into the editor rather than restored behind the user's
            // back: saving it is what makes it current, and that is a new
            // version like any other.
            status.textContent = `版 ${n}（${data['created-at'] || '日時不明'}）を表示中。`
              + '保存すると新しい版になります。';
          } catch (error) {
            status.textContent = error.message;
          }
        });
        versions.append(version);
      }

      const load = async () => {
        const request = await fetch(
          `/api/workspace/drive/documents/${encodeURIComponent(item.id)}`);
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || '内容を取得できませんでした。');
        return {payload:data.payload, etag:data.item?.etag,
                warnings:data['export-warnings'] || null,
                computed:data.computed || null,
                charts:data.charts || null,
                slides:data.slides || null};
      };
      open.addEventListener('click', async () => {
        open.disabled = true; status.textContent = '読み込んでいます…';
        try {
          const fresh = await load();
          driveEditor = openedEditor(fresh);
          syncText();
          save.hidden = false;
          renderPane();
          status.textContent = '';
        } catch (error) {
          status.textContent = error.message;
        } finally {
          open.disabled = false;
        }
      });
      save.addEventListener('click', async () => {
        let payload;
        // The JSON view is the only one whose text can be ahead of the
        // payload. The fields write into the payload and the preview does not
        // write at all, so both save what is already there.
        if (driveEditor.mode === 'json') {
          try {
            payload = JSON.parse(driveEditor.text);
          } catch (error) {
            // Refused here rather than sent: a body that is not JSON is not a
            // document the server can say anything useful about.
            status.textContent = `JSON として読めません: ${error.message}`;
            return;
          }
        } else {
          payload = driveEditor.payload;
        }
        save.disabled = true; status.textContent = '保存しています…';
        try {
          const saved = await postJSON(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/versions`,
            {payload, etag:driveEditor.etag}, true);
          const warnings = (saved.warnings || []).map((w) => w.message).join(' / ');
          // The save went through and the surface still had something to say.
          driveEditor = closedEditor(item.id);
          selectedDrive = saved.item;
          await loadWorkspace('drive', renderDrive);
          $('#drive-create-status').textContent = warnings
            ? `保存しました（版 ${saved.item.versions}）。注意: ${warnings}`
            : `保存しました（版 ${saved.item.versions}）。`;
        } catch (error) {
          // A refused save keeps the editor open with the text intact. The
          // work is not lost and not applied; the person decides.
          status.textContent = error.message;
          if (/更新しました/.test(error.message)) {
            const reload = make('button', 'tool-button', '相手の版を読み込む（自分の編集は破棄）');
            reload.type = 'button';
            reload.addEventListener('click', async () => {
              const fresh = await load();
              driveEditor = openedEditor(fresh);
              syncText(); renderPane();
              status.textContent = '最新の版を読み込みました。';
            });
            status.append(' ', reload);
          }
        } finally {
          save.disabled = false;
        }
      });
      rename.addEventListener('click', async () => {
        rename.disabled = true; status.textContent = '名前を変更しています…';
        try {
          const renamed = await postJSON(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/rename`,
            {title:titleField.value}, true);
          status.textContent = '';
          selectedDrive = renamed.item;
          await loadWorkspace('drive', renderDrive);
        } catch (error) {
          status.textContent = error.message;
        } finally {
          rename.disabled = false;
        }
      });
      trash.addEventListener('click', () => {
        driveEditor = closedEditor(null);
        driveAction(`/api/workspace/drive/documents/${encodeURIComponent(item.id)}/trash`, {},
          `${item.name} をゴミ箱へ移動しました。`);
      });
      // A viewer or commenter gets the document and not the verbs. The
      // server refuses them anyway — `writable?` is `can-write?`'s answer,
      // not a second copy of the rule — but offering a button that always
      // fails is its own kind of lie.
      if (!item['writable?']) { save.hidden = true; }
      row.append(titleField);
      if (item['writable?']) row.append(rename);
      row.append(open);
      if (item['writable?']) row.append(save);
      // Offered to anyone who can see the document, including a viewer of
      // one shared read-only — which is the case the operation exists for.
      const copy = make('button', 'tool-button', 'コピーを作成');
      copy.type = 'button';
      copy.addEventListener('click', () => driveAction(
        `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/copy`,
        {folder:driveFolder}, `${item.name} のコピーを作成しました。`));
      row.append(copy);
      if (item.role === 'owner') {
        // Owner only, because moving into a shared folder shares what was
        // moved — an editor who could move could widen the access the owner
        // granted, the same reason re-sharing is owner-only.
        const destinations = (folderData.all || [])
          .filter((folder) => folder.id !== item.id);
        const picker = selectInput(item['parent-id'] || '',
          destinations.map((folder) => folder.id),
          async (value) => {
            const status = $('#drive-create-status');
            try {
              await postJSON(
                `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/move`,
                {folder:value}, true);
              status.textContent = `${item.name} を移動しました。`;
              await loadFolders();
              await loadWorkspace('drive', renderDrive);
            } catch (error) { status.textContent = error.message; }
          });
        // Labelled by path, valued by id: two folders called Q1 are ordinary
        // and a picker showing both as Q1 asks an unanswerable question.
        Array.from(picker.options).forEach((option) => {
          const hit = destinations.find((folder) => folder.id === option.value);
          if (hit) option.textContent = hit.name;
        });
        row.append(field('フォルダ', picker));
        row.append(trash);
      }
      actions.append(row, status, modes, pane, editor, versions);
      renderPane();
      // Opened without being asked. A document that was just created and a
      // document that was just selected are both ones nobody has seen, and
      // making the surface wait behind 編集 means the answer to 「何を作った
      // のか」 is a button press away. The fetch is the one 編集 does.
      //
      // Guarded on all three of payload, loading and failed, because this runs
      // on every render of the detail pane — a keystroke in the search box is
      // one — and without the guard that is a request per keystroke.
      //
      // And on `resource-kind`, which is what `documents/item-view` carries and
      // `documents/folder-view` deliberately does not: a folder has no bytes,
      // and asking for its content is a request that can only fail.
      if (item['resource-kind'] && !driveEditor.payload
          && !driveEditor.loading && !driveEditor.failed) {
        driveEditor.loading = true;
        status.textContent = '読み込んでいます…';
        (async () => {
          try {
            const fresh = await load();
            // The selection may have moved while this was in flight, and the
            // nodes this closure holds are no longer on the page if it has.
            if (driveEditor.id !== item.id) return;
            driveEditor = openedEditor(fresh);
            syncText();
            open.textContent = '再読み込み';
            save.hidden = !item['writable?'];
            renderPane();
            status.textContent = '';
          } catch (error) {
            if (driveEditor.id !== item.id) return;
            driveEditor.loading = false;
            // Marked so the next render does not ask again: a document that
            // cannot be read is not one to retry on every keystroke. 編集
            // remains, which is the way to ask again on purpose.
            driveEditor.failed = true;
            status.textContent = error.message;
          }
        })();
      }
      if (item.kind === 'forms') actions.append(answerPanel(item));
      // Export is a plain link, not a fetch: the browser already knows how to
      // save a response with a Content-Disposition, and routing binary
      // through JavaScript to hand it back to the browser is work that only
      // adds a place to get it wrong.
      const exports = make('div', 'detail-actions__row');
      // A page the browser prints. Not a PDF export — a PDF of a Japanese
      // document needs a CJK font embedded, and the browser already has
      // one, so the reader's own print dialog is the better export.
      if (!item['file?']) {
        const print = make('a', 'tool-button', '印刷用ページ');
        print.href = `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/print`;
        print.target = '_blank';
        print.rel = 'noopener';
        exports.append(print);
      }
      // Some formats write something other than the document — a form's CSV
      // is its responses — and those are the owner's. Offering the button to
      // a viewer would be offering one that refuses.
      const kindSpec = (driveData.kinds || []).find((k) => k.kind === item.kind);
      const ownerOnly = kindSpec?.['owner-only-exports'] || [];
      (kindSpec?.exports || [])
        .filter((format) => item.role === 'owner' || !ownerOnly.includes(format))
        .forEach((format) => {
        const label = ownerOnly.includes(format)
          ? `回答を ${format.toUpperCase()} で書き出す` : `${format.toUpperCase()} で書き出す`;
        const link = make('a', 'tool-button', label);
        link.href = `/api/workspace/drive/documents/${encodeURIComponent(item.id)}`
          + `/export?format=${encodeURIComponent(format)}`;
        link.setAttribute('download', '');
        exports.append(link);
      });
      // What a format cannot carry, said twice and for different reasons.
      // The static line is about Markdown and is true of every document, so
      // it costs nothing and is there before anything is opened. The list
      // below it is about *this* document and needs its bytes, which arrive
      // only when it is loaded — and the detail pane rebuilds on every
      // keystroke in the search box, so fetching them per render would be a
      // request per keystroke.
      const exportNotes = make('div', 'export-notes');
      if ((driveData.kinds || []).find((k) => k.kind === item.kind)
            ?.exports?.includes('md')) {
        exportNotes.append(make('p', 'surface-note',
          'Markdown はブロック ID・コメント・提案・一部の書式を保持しません。'));
      }
      const specific = driveEditor.id === item.id ? driveEditor.warnings : null;
      Object.entries(specific || {}).forEach(([format, entries]) => {
        const list = make('ul', 'export-notes__list');
        entries.forEach((entry) => {
          list.append(make('li', 'surface-note',
            `${entry.message}${entry.id ? `（${entry.id}）` : ''}`));
        });
        exportNotes.append(
          make('p', 'surface-note',
               `この文書を ${format.toUpperCase()} で書き出すと失われるもの:`),
          list);
      });
      // Sorting a range. In this panel rather than beside the grid because
      // it is a save — the server reorders the cells and hands back a new
      // version — and this is where the things that write a version live.
      // The range is typed, the way the chart panel's is, because the grid
      // has no multi-cell selection to read one from.
      if (item.kind === 'sheets' && item['writable?']) {
        const sortBox = make('div', 'appointment__invite');
        const rangeField = make('input', 'workspace-search');
        rangeField.type = 'text';
        rangeField.placeholder = '範囲（例 A2:C20）';
        rangeField.setAttribute('aria-label', '並べ替える範囲');
        const byField = make('input', 'workspace-search document-title');
        byField.type = 'text';
        byField.placeholder = '基準の列（例 B）';
        byField.setAttribute('aria-label', '並べ替えの基準になる列');
        // Prefilled from the cell the cursor is in, which is the column
        // somebody looking at a table means. Still typed over freely.
        if (driveEditor.id === item.id && driveEditor.cell) {
          byField.value = columnName(driveEditor.cell[1]);
        }
        const direction = make('select', 'model-pill');
        direction.setAttribute('aria-label', '並び順');
        [['昇順', 'true'], ['降順', 'false']].forEach(([label, value]) => {
          const option = make('option', null, label);
          option.value = value;
          direction.append(option);
        });
        const sortButton = make('button', 'tool-button', '並べ替え');
        sortButton.type = 'button';
        sortButton.addEventListener('click', async () => {
          const status = $('#drive-create-status');
          if (!rangeField.value.trim() || !byField.value.trim()) {
            status.textContent = '範囲と基準の列を入れてください。';
            return;
          }
          status.textContent = '並べ替えています…';
          try {
            await postJSON(
              `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/sort`,
              {tab:driveEditor.id === item.id ? driveEditor.tab : null,
               range:rangeField.value.trim(),
               by:byField.value.trim(),
               'ascending?':direction.value === 'true',
               // The version the editor is looking at. A sort is a save, so
               // it is refused if somebody else saved first — the same rule
               // and the same 409 as typing in a cell.
               etag:driveEditor.id === item.id ? driveEditor.etag : item.etag},
              true);
            status.textContent = '並べ替えました。';
            // Re-read, because the cells on screen are the ones from before
            // the sort and the etag with them.
            if (driveEditor.id === item.id && driveEditor.open) {
              driveEditor = openedEditor(await load());
              renderPane();
            }
            await loadWorkspace('drive', renderDrive);
          } catch (error) {
            status.textContent = error.message;
          }
        });
        sortBox.append(rangeField, byField, direction, sortButton);
        // Inserting and removing rows and columns. Beside the sort control
        // for the same reason it is here rather than beside the grid: both
        // are saves, and this is where the things that write a version
        // live. The position is prefilled from the cell the cursor is in,
        // which is the row somebody looking at a table means.
        const shiftBox = make('div', 'appointment__invite');
        const axisPick = make('select', 'model-pill');
        axisPick.setAttribute('aria-label', '行か列か');
        [['行', 'row'], ['列', 'col']].forEach(([label, value]) => {
          const option = make('option', null, label);
          option.value = value;
          axisPick.append(option);
        });
        const atField = make('input', 'workspace-search document-title');
        atField.type = 'number';
        atField.min = '1';
        atField.placeholder = '位置';
        atField.setAttribute('aria-label', '挿入・削除する位置');
        if (driveEditor.id === item.id && driveEditor.cell) {
          atField.value = String(driveEditor.cell[0]);
        }
        const shiftAction = async (action, done) => {
          const status = $('#drive-create-status');
          if (!atField.value) { status.textContent = '位置を入れてください。'; return; }
          status.textContent = '編集しています…';
          try {
            const result = await postJSON(
              `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/rows`,
              {tab:driveEditor.id === item.id ? driveEditor.tab : null,
               axis:axisPick.value, at:Number(atField.value), count:1, action,
               etag:driveEditor.id === item.id ? driveEditor.etag : item.etag},
              true);
            // What the edit could not carry with it, said here rather than
            // found later by opening the chart and seeing it plot the wrong
            // rows.
            const left = (result.unfollowed || []).map((u) => u.message).join(' ');
            status.textContent = left ? `${done} ${left}` : done;
            if (driveEditor.id === item.id && driveEditor.open) {
              driveEditor = openedEditor(await load());
              renderPane();
            }
            await loadWorkspace('drive', renderDrive);
          } catch (error) {
            status.textContent = error.message;
          }
        };
        const insertButton = make('button', 'tool-button', '挿入');
        insertButton.type = 'button';
        insertButton.addEventListener('click',
          () => shiftAction('insert', '挿入しました。'));
        const deleteButton = make('button', 'tool-button', '削除');
        deleteButton.type = 'button';
        deleteButton.addEventListener('click',
          () => shiftAction('delete', '削除しました。'));
        shiftBox.append(axisPick, atField, insertButton, deleteButton);
        exports.append(sortBox, shiftBox);
        exportNotes.append(make('p', 'surface-note',
          '行や列の挿入・削除では数式の参照が追随します（削除された参照は #REF! になります）。'));
        exportNotes.append(make('p', 'surface-note',
          '数式のある範囲は並べ替えられません（数式は行と一緒に動き、参照は動かないため）。'));
      }
      if (item.kind === 'forms' && item.role === 'owner') {
        const snapshot = make('button', 'tool-button', '回答をスプレッドシートに');
        snapshot.type = 'button';
        snapshot.addEventListener('click', () => driveAction(
          `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/responses-sheet`,
          {}, `${item.name} の回答をスプレッドシートにしました。`));
        exports.append(snapshot);
        // Said on the screen rather than left to be discovered: it is the
        // answers as of now, and asking again makes a second one.
        exportNotes.append(make('p', 'surface-note',
          'スプレッドシートは作成時点のスナップショットです（自動更新されません）。'));
      }
      actions.append(exports, exportNotes, referencePanel(item), commentPanel(item));
      // Documents only — the server refuses a workbook or a deck, so a box
      // that always failed would be worse than none.
      if (item.kind === 'docs') actions.append(suggestionPanel(item));
      if (item.role === 'owner') actions.append(sharingPanel(item, status));
      return actions;
    };
    // Both directions. Outgoing is what this document names; incoming is
    // which documents name it — and the second is the half that makes the
    // first worth resolving, because a workbook that cannot say which memo
    // depends on it is a workbook nobody dares change.
    const referencePanel = (item) => {
      const panel = make('div', 'sharing');
      panel.hidden = true;
      const out = make('ul', 'sharing__list');
      const back = make('ul', 'sharing__list');
      const jump = (id, name) => {
        const button = make('button', 'tool-button', name || id);
        button.type = 'button';
        button.addEventListener('click', () => {
          const hit = (driveData.items || []).find((candidate) => candidate.id === id);
          if (!hit) return;
          driveEditor = closedEditor(hit.id);
          selectedDrive = hit;
          renderDrive(driveData);
        });
        return button;
      };
      (async () => {
        try {
          const request = await fetch(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/references`);
          const data = await request.json();
          if (!request.ok) return;
          const refs = data.references || [];
          const incoming = data['referenced-by'] || [];
          if (!refs.length && !incoming.length) return;
          panel.hidden = false;
          panel.append(make('h3', 'sharing__title', '参照'));
          refs.forEach((ref) => {
            const row = make('li', 'sharing__entry');
            row.append(make('span', 'sharing__who', `${ref.kind}（${ref.block}）→`));
            if (ref['resolved?']) {
              row.append(jump(ref.target, ref.name));
              if (ref['expected?'] === false) {
                row.append(make('span', 'surface-note',
                  `${ref.label} は ${ref.kind} の想定と異なります`));
              }
            } else {
              row.append(make('span', 'surface-note',
                `${ref.target || '未設定'} は見つかりません`));
            }
            out.append(row);
          });
          if (refs.length) panel.append(out);
          if (incoming.length) {
            panel.append(make('h3', 'sharing__title', '参照元'));
            incoming.forEach((from) => {
              const row = make('li', 'sharing__entry');
              row.append(jump(from.id, from.name),
                make('span', 'sharing__who', `${from.kind}（${from.block}）`));
              back.append(row);
            });
            panel.append(back);
          }
        } catch (error) { /* the panel simply stays hidden */ }
      })();
      return panel;
    };
    // Comments are shown to anyone who may read the document and written by
    // anyone above :viewer, which is what makes :commenter a role rather
    // than a word. They are not part of the stored bytes — see the comments
    // section in `cloud.itonami.app.documents` for why not.
    const commentRoles = ['owner', 'editor', 'commenter'];
    // Proposals from someone who may say what should change and may not
    // change it. Only for documents: the server refuses anything else, and
    // offering a box that always fails is worse than not offering one.
    const suggestionPanel = (item) => {
      const panel = make('div', 'sharing');
      panel.append(make('h3', 'sharing__title', '提案'));
      const list = make('ul', 'sharing__list');
      const status = make('p', 'drive-create__status', '');
      const form = make('div', 'detail-actions__row');
      const block = make('input', 'workspace-search document-title');
      block.type = 'text';
      block.placeholder = '段落 ID';
      block.setAttribute('aria-label', '提案する段落の ID');
      const text = make('input', 'workspace-search surface-input--wide');
      text.type = 'text';
      text.placeholder = '提案する本文';
      text.setAttribute('aria-label', '提案する本文');
      const add = make('button', 'tool-button', '提案する');
      add.type = 'button';
      const base = `/api/workspace/drive/documents/${encodeURIComponent(item.id)}`;
      const reload = async () => {
        try {
          const response = await fetch(`${base}/suggestions`);
          const data = await response.json();
          if (!response.ok) return;
          list.replaceChildren();
          (data.suggestions || []).forEach((s) => {
            const row = make('li', 'sharing__entry');
            row.append(make('span', 'sharing__who', `${s.author}（${s.block}）`));
            row.append(make('span', 'sharing__role', s.text));
            if (s.status !== 'open') {
              row.append(make('span', 'surface-note',
                s.status === 'accepted' ? '反映済み' : '却下'));
            } else if (s['stale?']) {
              // Said before anyone presses accept, because accepting is
              // what the server will refuse.
              row.append(make('span', 'surface-note',
                `この提案のあとに本文が変わりました（現在: ${s.current}）`));
            }
            if (s.status === 'open') {
              const act = async (verb, message) => {
                try {
                  await postJSON(`${base}/suggestions/${encodeURIComponent(s.id)}/${verb}`,
                                 {}, true);
                  status.textContent = message;
                  await reload();
                  await loadWorkspace('drive', renderDrive);
                } catch (error) { status.textContent = error.message; }
              };
              if (item['writable?']) {
                const accept = make('button', 'tool-button', '反映');
                accept.type = 'button';
                accept.addEventListener('click', () => act('accept', '提案を反映しました。'));
                row.append(accept);
              }
              const reject = make('button', 'tool-button', '却下');
              reject.type = 'button';
              reject.addEventListener('click', () => act('reject', '提案を却下しました。'));
              row.append(reject);
            }
            list.append(row);
          });
          if (!(data.suggestions || []).length) {
            list.append(make('li', 'empty-state', 'まだ提案はありません。'));
          }
        } catch (error) { status.textContent = error.message; }
      };
      add.addEventListener('click', async () => {
        try {
          await postJSON(`${base}/suggestions`,
                         {block:block.value, text:text.value}, true);
          text.value = '';
          status.textContent = '提案しました。';
          await reload();
        } catch (error) { status.textContent = error.message; }
      });
      form.append(block, text, add);
      panel.append(list, form, status);
      reload();
      return panel;
    };
    const commentPanel = (item) => {
      const panel = make('div', 'sharing');
      const heading = make('h3', 'sharing__title', 'コメント');
      panel.append(heading);
      const list = make('ul', 'sharing__list');
      const status = make('p', 'drive-create__status', '');
      const form = make('div', 'detail-actions__row');
      const text = make('input', 'workspace-search surface-input--wide');
      text.type = 'text';
      text.placeholder = 'コメント';
      text.setAttribute('aria-label', 'コメント');
      const anchor = make('input', 'workspace-search document-title');
      anchor.type = 'text';
      anchor.placeholder = '位置（任意）';
      anchor.setAttribute('aria-label', 'コメントの位置（任意）');
      // The anchor was a box to type `B3` into by hand. The server keeps it
      // as free text on purpose — it owes no surface a parser — but the
      // editor knows exactly where the cursor is, and asking a person to
      // read a cell reference off the screen and retype it is asking them
      // to make the mistake.
      const here = make('button', 'tool-button', '編集中の位置を入れる');
      here.type = 'button';
      here.addEventListener('click', () => {
        const at = currentAnchor(item);
        if (at) anchor.value = at;
        else status.textContent = '位置を特定できる編集画面が開いていません。';
      });
      const add = make('button', 'tool-button', '投稿');
      add.type = 'button';

      // One entry, root or reply. The rules it shows are the server's — the
      // author or the owner may delete, anyone who may comment may resolve —
      // rather than buttons that fail for everyone else.
      const entryRow = (entry, {root} = {}) => {
        const row = make('li', root ? 'sharing__entry sharing__entry--reply' : 'sharing__entry');
        row.append(make('span', 'sharing__who',
          `${entry.author}${entry.anchor ? ` · ${entry.anchor}` : ''} · ${entry['created-at']}`));
        row.append(make('span', 'surface-note', entry.text));
        if (entry.author === currentUserId() || item.role === 'owner') {
          const remove = make('button', 'tool-button', root ? '削除' : '削除（返信ごと）');
          remove.type = 'button';
          remove.addEventListener('click', () => submit(
            `/comments/${encodeURIComponent(entry.id)}/delete`, {}, 'コメントを削除しました。'));
          row.append(remove);
        }
        return row;
      };
      const render = (entries) => {
        list.replaceChildren();
        if (!entries.length) {
          list.append(make('li', 'empty-state', 'まだコメントはありません。'));
          return;
        }
        entries.forEach((entry) => {
          const thread = make('li', entry['resolved-at'] ? 'sharing__thread is-resolved'
                                                         : 'sharing__thread');
          const head = make('ul', 'sharing__list');
          head.append(entryRow(entry));
          if (entry['resolved-at']) {
            head.append(make('li', 'surface-note',
              `${entry['resolved-by']} が解決済みにしました（${entry['resolved-at']}）`));
          }
          (entry.replies || []).forEach((reply) => head.append(entryRow(reply, {root:entry})));
          thread.append(head);
          if (commentRoles.includes(item.role)) {
            const actions = make('div', 'detail-actions__row');
            const toggle = make('button', 'tool-button',
              entry['resolved-at'] ? '未解決に戻す' : '解決済みにする');
            toggle.type = 'button';
            toggle.addEventListener('click', () => submit(
              `/comments/${encodeURIComponent(entry.id)}/resolve`,
              {'resolved?': !entry['resolved-at']},
              entry['resolved-at'] ? '未解決に戻しました。' : '解決済みにしました。'));
            actions.append(toggle);
            if (!entry['resolved-at']) {
              // Replying to a resolved thread is refused by the server;
              // reopening is an act somebody takes on purpose.
              const replyText = make('input', 'workspace-search surface-input--wide');
              replyText.type = 'text';
              replyText.placeholder = '返信';
              replyText.setAttribute('aria-label', `${entry.text} への返信`);
              const send = make('button', 'tool-button', '返信');
              send.type = 'button';
              send.addEventListener('click', () => submit(
                '/comments', {text:replyText.value, 'parent-id':entry.id}, '返信しました。'));
              actions.append(replyText, send);
            }
            thread.append(actions);
          }
          list.append(thread);
        });
      };
      const reload = async () => {
        try {
          const request = await fetch(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/comments`);
          const data = await request.json();
          if (!request.ok) return;
          render(data.comments || []);
          // A dot where each comment points. Google puts one on the cell;
          // this app kept every anchor in the panel, so a workbook with a
          // comment on B3 looked exactly like one without.
          markAnchored((data.comments || [])
            .filter((entry) => !entry['resolved-at'])
            .map((entry) => entry.anchor)
            .filter(Boolean));
          // The count worth seeing before opening the panel: an unresolved
          // thread is one somebody is still waiting on.
          heading.textContent = data.unresolved
            ? `コメント（未解決 ${data.unresolved}）` : 'コメント';
        } catch (error) { /* the panel simply stays empty */ }
      };
      const submit = async (suffix, body, done) => {
        status.textContent = '送信しています…';
        try {
          await postJSON(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}${suffix}`,
            body, true);
          status.textContent = done;
          text.value = ''; anchor.value = '';
          await reload();
        } catch (error) {
          status.textContent = error.message;
        }
      };
      add.addEventListener('click', () => submit(
        '/comments', {text:text.value, anchor:anchor.value}, 'コメントしました。'));

      form.append(text, anchor, here, add);
      panel.append(list);
      if (commentRoles.includes(item.role)) panel.append(form);
      panel.append(status);
      reload();
      return panel;
    };
    // A form is the one surface with a second thing to do to it. Editing it
    // changes the questions; answering it does not, and the answers are not
    // a version of the form — so this is a panel of its own rather than
    // another mode of the editor. `inputTypes` is declared with the rendered
    // surfaces, because the preview of a form and a real one have to agree on
    // what a field type is.
    const answerPanel = (item) => {
      const panel = make('div', 'sharing');
      panel.append(make('h3', 'sharing__title', 'このフォームに回答'));
      const body = make('div', 'surface-editor');
      const status = make('p', 'drive-create__status', '');
      const answers = {};
      const send = make('button', 'tool-button', '送信');
      send.type = 'button';
      send.disabled = true;
      const responses = make('div', 'surface-editor');

      const loadResponses = async () => {
        if (item.role !== 'owner') return;
        try {
          const request = await fetch(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/submissions`);
          const data = await request.json();
          if (!request.ok) return;
          responses.replaceChildren(make('h3', 'sharing__title',
            `回答 ${(data.submissions || []).length} 件`));
          (data.submissions || []).forEach((entry) => {
            const row = make('div', 'surface-row');
            row.append(make('span', 'surface-note',
              `${entry.author || '不明'} · ${entry['submitted-at'] || ''}`));
            Object.entries(entry.answers || {}).forEach(([key, value]) => {
              row.append(make('span', 'sharing__who', `${key}: ${value}`));
            });
            responses.append(row);
          });
        } catch (error) { /* the panel simply stays empty */ }
      };
      send.addEventListener('click', async () => {
        send.disabled = true; status.textContent = '送信しています…';
        try {
          const sent = await postJSON(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/submissions`,
            {answers}, true);
          status.textContent = `送信しました（${sent.submission['submitted-at']}）。`;
          await loadResponses();
        } catch (error) {
          // The surface's own validator answered — a missing required field
          // or an address that is not one — so the message is its message.
          status.textContent = error.message;
        } finally {
          send.disabled = false;
        }
      });
      (async () => {
        try {
          const request = await fetch(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/form`);
          const data = await request.json();
          if (!request.ok) throw new Error(data?.error?.message || 'フォームを読み込めませんでした。');
          body.replaceChildren();
          if (!(data.fields || []).length) {
            body.append(make('p', 'empty-state', 'まだ質問がありません。'));
          }
          (data.fields || []).forEach((entry) => {
            const type = entry['field-type'];
            // A choice is picked, not typed. This built a text box for one,
            // so a question with a fixed set of answers was answered by
            // typing — and the options were not even sent, though the
            // preview panel two screens up drew them correctly. The server
            // refuses an answer that is not one of them now, which would
            // have made a free-text box a question you could only get
            // wrong.
            let control;
            if (type === 'choice') {
              control = make('select', 'form-control');
              const options = entry.options || [];
              // A blank first option only when the answer is optional. On a
              // required question it is an answer nobody can mean, and the
              // server would refuse it as missing.
              if (!entry['required?']) control.append(make('option', null, ''));
              options.forEach((option) => {
                const node = make('option', null, String(option));
                node.value = String(option);
                control.append(node);
              });
              if (!options.length) {
                control.append(make('option', null, '選択肢が未設定です'));
                control.disabled = true;
              }
              // A required select starts on its first option, which is what
              // it will send if nobody touches it.
              if (entry['required?'] && options.length) answers[entry.id] = String(options[0]);
              control.addEventListener('change', () => { answers[entry.id] = control.value; });
            } else if (type === 'textarea') {
              control = make('textarea', 'document-preview');
              control.addEventListener('input', () => { answers[entry.id] = control.value; });
            } else if (type === 'checkbox') {
              control = make('input', 'surface-check');
              control.type = 'checkbox';
              control.addEventListener('change', () => { answers[entry.id] = control.checked; });
            } else {
              control = make('input', 'workspace-search surface-input--wide');
              control.type = inputTypes[type] || 'text';
              control.addEventListener('input', () => { answers[entry.id] = control.value; });
            }
            body.append(field(`${entry.label}${entry['required?'] ? ' *' : ''}`, control));
          });
          send.disabled = false;
        } catch (error) {
          body.replaceChildren(make('p', 'empty-state', error.message));
        }
      })();
      panel.append(body, send, status, responses);
      loadResponses();
      return panel;
    };
    // Where the editor's cursor is, in the notation the surface uses.
    //
    // Free text, because that is what the server stores and what
    // `documents/comment!` refuses to parse: the moment it interpreted one
    // it would owe every surface a different parser. So the shape is agreed
    // here, between the editor that offers it and the mark below that finds
    // it again, and nothing on the server depends on it.
    const currentAnchor = (item) => {
      if (item.kind === 'sheets') {
        const at = driveEditor.cell;
        if (!at || !driveEditor.tab) return null;
        return cellAnchor(driveEditor.tab, at[0], at[1]);
      }
      if (item.kind === 'slides') {
        const slides = driveEditor.payload?.['slides/slides'] || [];
        const slide = slides[driveEditor.slide ?? 0];
        return slide?.['slides/id'] || null;
      }
      if (item.kind === 'docs') {
        // The block the cursor is in, which the editor records on focus.
        return driveEditor.block || null;
      }
      return null;
    };
    // A mark on whatever the anchors name. By attribute rather than by
    // re-rendering: the comments arrive after the pane is drawn, and a
    // redraw would take the cursor out of the cell somebody is typing in.
    const markAnchored = (anchors) => {
      // Across the document rather than inside a named pane: the editor
      // pane is built by `make` and has no id, and an anchor attribute only
      // ever exists inside it.
      $$('[data-anchor]').forEach((node) => {
        node.classList.toggle('is-commented', anchors.includes(node.dataset.anchor));
      });
    };
    // Sharing is owner-only, and the panel says who has what rather than
    // only offering to add: a share you cannot see is one you cannot undo.
    const sharingPanel = (item, status) => {
      const panel = make('div', 'sharing');
      const heading = make('h3', 'sharing__title', '共有');
      const current = make('ul', 'sharing__list');
      const form = make('div', 'detail-actions__row');
      // A picker over the organization's other members, with the free-text
      // field kept: the server accepts any principal, so a name that is not
      // in the directory is still one that works, and removing the field
      // would make that untrue in the UI only.
      const whoPicker = make('select', 'model-pill');
      whoPicker.setAttribute('aria-label', '共有相手');
      const who = make('input', 'workspace-search document-title');
      who.type = 'text';
      who.placeholder = '共有相手の User ID';
      who.setAttribute('aria-label', '共有相手の User ID');
      whoPicker.addEventListener('change', () => {
        if (whoPicker.value) who.value = whoPicker.value;
      });
      const role = make('select', 'model-pill');
      role.setAttribute('aria-label', '権限');
      const share = make('button', 'tool-button', '共有する');
      share.type = 'button';
      const linkRole = make('select', 'model-pill');
      linkRole.setAttribute('aria-label', 'リンクの権限');
      const expiry = make('select', 'model-pill');
      expiry.setAttribute('aria-label', 'リンクの有効期限');
      [['', '期限なし'], ['24', '24時間'], ['168', '7日間']].forEach(([value, label]) => {
        const option = make('option', null, label); option.value = value; expiry.append(option);
      });
      const makeLink = make('button', 'tool-button', 'リンクを作成');
      makeLink.type = 'button';

      // Options come from the server's own lists, so `:owner` never appears
      // among them — `documents/grantable-roles` leaves it out on purpose.
      const fillOnce = (select, names) => {
        if (select.options.length || !names) return;
        names.forEach((name) => {
          const option = make('option', null, name); option.value = name; select.append(option);
        });
      };
      const render = (data) => {
        fillOnce(role, data.roles);
        fillOnce(linkRole, data['link-roles']);
        if (!whoPicker.options.length && (data.candidates || []).length) {
          const blank = make('option', null, 'Organization から選ぶ');
          blank.value = ''; whoPicker.append(blank);
          data.candidates.forEach((candidate) => {
            const option = make('option', null,
              candidate['display-name'] || candidate.email || candidate.id);
            option.value = candidate.id;
            whoPicker.append(option);
          });
        }
        whoPicker.hidden = !whoPicker.options.length;
        current.replaceChildren();
        (data.grants || []).forEach((grant) => {
          const entry = make('li', 'sharing__entry');
          entry.append(make('span', 'sharing__who', `${grant.principal}（${grant.role}）`));
          const revoke = make('button', 'tool-button', '解除');
          revoke.type = 'button';
          revoke.addEventListener('click', () => submit(
            {action:'revoke', principal:grant.principal}, `${grant.principal} の共有を解除しました。`));
          entry.append(revoke);
          current.append(entry);
        });
        (data.links || []).forEach((link) => {
          const entry = make('li', 'sharing__entry');
          const url = `${window.location.origin}/api/workspace/drive/shared/${encodeURIComponent(link.token)}`;
          const field = make('input', 'workspace-search sharing__token');
          field.type = 'text'; field.readOnly = true; field.value = url;
          field.setAttribute('aria-label', `共有リンク（${link.role}）`);
          entry.append(make('span', 'sharing__who',
            `リンク（${link.role}・${link['expires-at'] ? '期限あり' : '期限なし'}）`), field);
          const revoke = make('button', 'tool-button', '無効化');
          revoke.type = 'button';
          revoke.addEventListener('click', () => submit(
            {action:'revoke-link', token:link.token}, 'リンクを無効化しました。'));
          entry.append(revoke);
          current.append(entry);
        });
        if (!(data.grants || []).length && !(data.links || []).length) {
          current.append(make('li', 'empty-state', 'まだ誰とも共有していません。'));
        }
      };
      const submit = async (body, done) => {
        status.textContent = '共有設定を更新しています…';
        try {
          const data = await postJSON(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/sharing`,
            body, true);
          render(data);
          status.textContent = done;
        } catch (error) {
          status.textContent = error.message;
        }
      };
      share.addEventListener('click', () => submit(
        {principal:who.value, role:role.value}, `${who.value} と共有しました。`));
      makeLink.addEventListener('click', () => submit(
        {action:'link', role:linkRole.value,
         'expires-in-hours':expiry.value ? Number(expiry.value) : null},
        'リンクを作成しました。'));

      form.append(whoPicker, who, role, share);
      const linkForm = make('div', 'detail-actions__row');
      linkForm.append(make('span', 'sharing__who', '共有リンク'), linkRole, expiry, makeLink);
      panel.append(heading, current, form, linkForm);
      (async () => {
        try {
          const request = await fetch(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/sharing`);
          const data = await request.json();
          if (request.ok) render(data);
        } catch (error) { /* the panel simply stays empty */ }
      })();
      return panel;
    };
    const renderFilecoin = (data) => {
      const list = $('#storage-list'); list.replaceChildren();
      const val = (v) => (v && typeof v === 'object' && v.error) ? `取得失敗: ${v.error}` : String(v ?? '—');
      const rows = [
        ['Chain height', val(data['chain-height']), 'live'],
        ['Network', `${val(data['chain-network-name'])} · chainId ${val(data['chain-id'])}`, 'live'],
        ['PDP data sets', val(data['pdp-next-data-set-id']), 'live'],
        ['Challenge finality', `${val(data['pdp-challenge-finality'])} epochs`, 'live'],
        ['Staged pieces', String((data['staged-pieces'] || []).length), 'local'],
        ['Read-through', (data['retrieval-urls'] || []).length
          ? `${(data['retrieval-urls'] || []).join(' · ')} · PieceCID 検証あり`
          : '未設定', (data['retrieval-urls'] || []).length ? 'local' : 'warn'],
        ['Deals', '未実装', 'warn']
      ];
      rows.forEach(([title, meta, kind]) =>
        list.append(listItem(title, meta, kind === 'warn' ? '未対応' : kind, kind === 'warn')));
      const sample = data.sample || {};
      setDetail($('#storage-detail'), 'PieceCID v2 · FRC-0069',
        sample.cid || 'PieceCID 未計算',
        'このアプリのプロセス内で計算した実際の PieceCID です。provider も資金も'
          + '不要で、Filecoin の識別子だけを先に確定できます。',
        [['対象', sample.text], ['元サイズ', bytes(sample.bytes)],
         ['tree height', String(sample.height ?? '—')],
         ['zero padding', `${sample.padding ?? '—'} B`],
         ['padded size', bytes(sample['padded-size'])],
         ['PDPVerifier', data['pdp-verifier']],
         ['PDPVerifier (f4)', data['pdp-verifier-f4']],
         ['Warm Storage', data['warm-storage']],
         ['Filecoin Pay', data['filecoin-pay']],
         ['Retrieval', data['retrieval-domain']],
         ['Read-through', (data['retrieval-urls'] || []).length
           ? '応答ごとに PieceCID を再計算して照合し、一致しない bytes は破棄します。'
             + ' 参照元: ' + (data['retrieval-urls'] || []).join(' · ')
           : 'FILECOIN_PROVIDER_URL（provider の serviceURL）か'
             + ' FILECOIN_CLIENT_ADDRESS（FilBeam は client ごとに subdomain が違う）'
             + 'を設定すると有効になります。既定値は推測しません。']]);
      $('#storage-count').textContent = rows.filter(([, , k]) => k === 'live').length;
      $('#storage-source').textContent =
        `${val(data['chain-network-name'])} · height ${val(data['chain-height'])} · StateCall (無料・オンチェーン書き込みなし)`;
      $('#storage-write-notice').hidden = data['write-status'] !== 'not-implemented';
    };
    // Loaded outside bootstrapApp on purpose: bootstrapApp only runs once a
    // Passkey is enrolled, and this endpoint needs no session. Gating the load
    // behind the gate would render the panel and leave it empty forever.
    const loadFilecoin = () => fetch('/api/filecoin')
      .then((r) => r.ok ? r.json() : null)
      .then((d) => { if (d) renderFilecoin(d); return Boolean(d); })
      .catch(() => {
        $('#storage-source').textContent = 'Filecoin に接続できません。';
        return false;
      });
    // Contracts. Every field arrives as {status, value} rather than a bare
    // value, because a contract nobody has priced and a contract that costs
    // nothing must not render the same way.
    const fieldValue = (f) => (f && f.status === 'recorded') ? f.value : null;
    const fieldText = (f, missing = '未記録') => {
      if (!f) return missing;
      if (f.status === 'recorded') return String(f.value);
      if (f.status === 'unparseable') return '読めない値';
      return missing;
    };
    // Minor-unit exponent comes from ICU, not from an assumed 2: JPY has none,
    // and dividing a yen amount by 100 would understate every Japanese
    // subscription by two orders of magnitude.
    const minorFormat = (minor, currency) => {
      try {
        const fmt = new Intl.NumberFormat('ja-JP', {style:'currency', currency});
        const exp = fmt.resolvedOptions().maximumFractionDigits;
        return fmt.format(minor / Math.pow(10, exp));
      } catch (_) {
        return `${minor} ${currency}（最小単位）`;
      }
    };
    const money = (amount) => {
      const minor = fieldValue(amount && amount.minor);
      const currency = fieldValue(amount && amount.currency);
      if (minor === null || currency === null) return '金額は未記録';
      return minorFormat(minor, currency);
    };
    let contractsData = null;
    let selectedContract = null;
    // T1 公式 API > T2 ToS 許可済み browser > T3 self-submit — kaiyaku が選んだ
    // 安全側からの順序をそのまま表示する。app が tier を上げることはない。
    const tierLabel = (tier) => ({T1:'公式 API', T2:'ブラウザ操作（ToS 許可済み）',
                                  T3:'自分で申請'})[tier] || tier;
    const contractDetail = (c) => {
      const root = make('div');
      root.append(make('h3', null, c.title));
      const rows = make('dl', 'detail-grid');
      const row = (label, value, warn = false) => {
        rows.append(make('dt', null, label),
                    make('dd', warn ? 'state-chip state-chip--warn' : null, value));
      };
      row('プラン', fieldText(c.plan));
      row('状態', fieldText(c.status));
      row('金額', `${money(c.amount)} / ${fieldText(c.cycle, '周期未記録')}`);
      row('年額', c['annualized-minor'].status === 'recorded'
            ? minorFormat(c['annualized-minor'].value, fieldValue(c.amount.currency))
            : '計算できません（金額か周期が未記録）');
      const days = c['days-to-charge'];
      row('次回課金', c['next-charge'].status === 'recorded'
            ? `${c['next-charge'].value}（あと ${days.value} 日）`
            : '未記録');
      const deadline = c.notice && c.notice.deadline;
      const toDeadline = c.notice && c.notice['days-to-deadline'];
      if (deadline && deadline.status === 'recorded') {
        const late = toDeadline.status === 'recorded' && toDeadline.value < 0;
        row('予告期限', late
              ? `${deadline.value}（${Math.abs(toDeadline.value)} 日過ぎています）`
              : `${deadline.value}（あと ${toDeadline.value} 日）`, late);
      } else {
        row('予告期限', '予告日数が未記録のため計算できません');
      }
      root.append(rows);
      if (c.procedure) {
        const p = make('section', 'local-card');
        p.append(make('h4', null, `解約手順 · ${tierLabel(c.procedure.tier)}`));
        const steps = make('ol', 'data-list');
        (c.procedure.steps || []).forEach((s) => steps.append(make('li', null, s)));
        p.append(steps);
        if (c.procedure['notice-days'] || c.procedure['penalty-jpy']) {
          p.append(make('p', 'data-list__meta',
            `予告 ${c.procedure['notice-days']} 日 · 違約金 ${c.procedure['penalty-jpy']} 円（開示された解約コスト。回避しません）`));
        }
        // G6: the catalog holds a disclosed shape, not a live assertion. Saying
        // so on the screen is the difference between a hint and a promise.
        if (c.procedure['operator-verified'] === false) {
          p.append(make('p', 'data-list__meta',
            '※ この手順は未検証です。実行する前に提供元の記載を確認してください。'));
        }
        if (c.procedure.source) {
          const a = make('a', null, c.procedure.source);
          a.href = c.procedure.source; a.rel = 'noreferrer'; a.target = '_blank';
          p.append(a);
        }
        root.append(p);
      } else {
        root.append(make('p', 'data-list__meta',
          'この契約の解約手順はカタログにありません。手順を推測して表示することはしません。'));
      }
      if (c.problems && c.problems.length) {
        const warn = make('div', 'security-callout');
        warn.append(make('strong', null, '読めない項目があります。'),
          make('p', null, c.problems.map((p) => p.field).join(', ')));
        root.append(warn);
      }
      return root;
    };
    const renderContracts = (data) => {
      contractsData = data;
      const list = $('#contracts-list'); list.replaceChildren();
      const totals = $('#contracts-totals'); totals.replaceChildren();
      const badge = $('#contracts-count');
      // A locked or absent vault is not an empty one. Writing 0 in the badge
      // would answer a question the app cannot answer yet.
      if (!data.contracts) {
        badge.textContent = '—';
        $('#contracts-source').textContent = data.note || 'vault を読めません';
        const empty = make('li', 'empty-state');
        empty.append(make('strong', null,
            data.vault.status === 'locked' ? 'vault がロックされています'
                                           : 'この端末に vault がありません'),
          make('p', 'data-list__meta',
            data.vault.status === 'locked'
              ? 'unlock するまで契約は読めません。0 件という意味ではありません。'
              : `kagi init で作成するか、別の端末から kagi pull で復元してください（${data.vault.home}）。`));
        list.append(empty);
        $('#contracts-detail').replaceChildren(
          make('div', 'empty-state', '契約を読むには vault を開いてください。'));
        return;
      }
      badge.textContent = data.contracts.length;
      $('#contracts-source').textContent =
        `${data.vault.home} · ${data['as-of']} 時点`;
      const t = data.totals || {};
      const monthly = t['monthly-minor'] || {};
      const currencies = Object.keys(monthly);
      const card = make('div');
      card.append(make('h4', null, '毎月の合計'));
      if (currencies.length) {
        // Per currency, never summed into one number: adding JPY to USD needs
        // today's rate, and then the total depends on the day you looked.
        currencies.forEach((cur) => card.append(
          make('p', 'data-list__title', minorFormat(monthly[cur], cur))));
      } else {
        card.append(make('p', 'data-list__meta', '金額の分かる契約がありません。'));
      }
      if (t.unpriced) {
        card.append(make('p', 'data-list__meta',
          `${t.unpriced} 件は金額が未記録のため合計に含まれていません。`));
      }
      totals.append(card);
      data.contracts.forEach((c) => {
        const item = listItem(c.title,
          `${money(c.amount)} / ${fieldText(c.cycle, '周期未記録')}`,
          fieldText(c.status, '状態未記録'),
          (c.problems && c.problems.length) > 0);
        item.addEventListener('click', () => {
          selectedContract = c['item-id'];
          $('#contracts-detail').replaceChildren(contractDetail(c));
        });
        list.append(item);
      });
      if (!data.contracts.length) {
        list.append(make('li', 'empty-state', 'vault に契約 item がありません。'));
      } else if (selectedContract) {
        const found = data.contracts.find((c) => c['item-id'] === selectedContract);
        if (found) $('#contracts-detail').replaceChildren(contractDetail(found));
      }
    };
    let esignData = null;
    let selectedEnvelope = null;
    const esignStatusText = {
      'awaiting-signatures':'署名待ち', completed:'署名完了', declined:'辞退'
    };
    const esignTimeText = {
      accredited:{
        strong:'署名時刻は認定 TSA のタイムスタンプによって証明されています。',
        rest:' RFC 3161 の時刻認証局による時刻認証を受けており、電子帳簿保存法の真実性確保措置のうち'
             +'タイムスタンプの要件を満たし得ます（認定事業者の設定はこの deployment の管理者が行います）。'},
      'tsa-attested':{
        strong:'RFC 3161 タイムスタンプは検証できましたが、認定事業者としては設定されていません。',
        rest:' 時刻の証拠としては有効ですが、電子帳簿保存法が求める認定タイムスタンプに該当するとは限りません。'},
      'app-attested':{
        strong:'署名時刻はこのアプリが記録したもので、認定タイムスタンプではありません。',
        rest:' 認定タイムスタンプ（総務大臣認定の TSA による RFC 3161）を設定していない間は、'
             +'電子帳簿保存法が求める真実性の確保措置としては、この時刻だけでは足りません。'
             +'署名そのもの（誰が・何に同意したか）は、この画面を離れても検証できます。'}
    };
    const renderEsignTimeNotice = (attestation) => {
      const text = esignTimeText[attestation] || esignTimeText['app-attested'];
      const box = $('#esign-time-notice');
      if (!box) return;
      box.replaceChildren(make('strong', null, text.strong), document.createTextNode(text.rest));
    };
    const esignAssuranceText = {
      'hardware-attested':'ハードウェア（attestation 検証済み）',
      'platform-attested':'ハードウェア（AAGUID 一致）',
      'platform-claimed':'クライアントの申告のみ（未署名の主張）',
      unknown:'不明'
    };
    const envelopeDetail = (envelope) => {
      const root = make('div');
      root.append(make('h3', null, envelope['document-title'] || envelope['document-id']),
        make('p', 'data-list__meta',
          `${esignStatusText[envelope.status] || envelope.status} · ` +
          `${envelope.intent}`));
      const digests = make('div', 'local-card');
      digests.append(make('h4', null, '署名の対象'),
        make('p', 'wallet-address', `文書 digest: ${envelope['document-digest']}`),
        make('p', 'wallet-address', `表示 digest: ${envelope['presentation-digest']}`),
        make('p', 'form-help',
          'この 2 つが commitment に入り、その SHA-256 が challenge になります。' +
          '文書を後から編集しても、この envelope が指す版は変わりません。'));
      root.append(digests);
      // What you see is what you sign. The outline is exhaustive by
      // construction, so 'this document contains nothing you were not shown'
      // is a property of the format rather than a claim about this pane —
      // which is why the whole of it is rendered and not a summary.
      if (envelope.presentation) {
        const view = make('div', 'local-card');
        view.append(make('h4', null, '署名者に表示される内容（全文）'),
          make('p', 'form-help',
            '構造化データの中身をすべて 1 行ずつ列挙したものです。' +
            '折りたたまれた項目や表示範囲外のセルも含まれるため、' +
            '「見えていないものに署名する」ことが起こりません。'));
        const pre = make('pre', 'wallet-address');
        pre.textContent = envelope.presentation;
        pre.style.maxHeight = '18rem';
        pre.style.overflow = 'auto';
        pre.style.whiteSpace = 'pre-wrap';
        view.append(pre);
        root.append(view);
      } else if (envelope['content-forgotten?']) {
        root.append(make('p', 'form-help',
          '内容は削除要求により破棄されています。署名と digest は残っているため、' +
          'この digest の文書に署名がなされた事実は今も検証できます。'));
      }
      (envelope.signers || []).forEach((signer) => {
        const row = make('div', 'local-card');
        row.append(make('p', 'wallet-address', signer.did),
          make('p', 'data-list__meta',
            `${esignStatusText[signer.status] || signer.status}` +
            (signer.at ? ` · ${signer.at}` : '')));
        if (signer.assurance) {
          row.append(make('p', 'form-help',
            `鍵の保証: ${esignAssuranceText[signer.assurance] || signer.assurance}`));
        }
        if (signer.reason) row.append(make('p', 'form-help', signer.reason));
        if (signer.status === 'pending' && signer.did === esignData['my-did']) {
          const sign = make('button', 'primary-action', 'Passkey で署名');
          sign.type = 'button';
          sign.addEventListener('click', () => signEnvelope(envelope, sign));
          const decline = make('button', null, '辞退する');
          decline.type = 'button';
          decline.addEventListener('click', () => declineEnvelope(envelope, decline));
          row.append(sign, decline);
        }
        root.append(row);
      });
      const evidence = make('button', null, '証拠記録を取得');
      evidence.type = 'button';
      evidence.addEventListener('click', async () => {
        evidence.disabled = true;
        try {
          const record = await fetch(`/api/esign/envelopes/${encodeURIComponent(envelope.id)}/evidence`)
            .then((r) => r.json());
          const verified = await postJSON('/api/esign/verify', {evidence:record}, true);
          const panel = make('div', 'local-card');
          panel.append(make('h4', null, '検証結果'),
            make('p', 'data-list__title', verified['esign/status']),
            make('p', 'form-help', verified['esign/time-note']));
          (verified['esign/signatures'] || []).forEach((s) => {
            panel.append(make('p', 'wallet-address',
              `${s['signer-did']} · ${s.status}`));
            (s.reasons || []).forEach((r) => panel.append(
              make('p', 'form-help', `${r.reason}: ${r.detail}`)));
          });
          root.append(panel);
        } catch (error) {
          root.append(make('p', 'form-help', error.message));
        } finally {
          evidence.disabled = false;
        }
      });
      root.append(evidence);
      return root;
    };
    const renderEsign = (data) => {
      esignData = data;
      const list = $('#esign-list'); list.replaceChildren();
      $('#esign-count').textContent = data.envelopes.length;
      const waiting = data.envelopes.filter(
        (e) => e.status === 'awaiting-signatures').length;
      $('#esign-source').textContent =
        `${data.envelopes.length} 件 · 署名待ち ${waiting} 件`;
      // The WEAKEST attestation across every envelope shown. One envelope
      // without a qualified timestamp is one the measure does not cover, and the
      // banner is read as a statement about the screen.
      const order = {'app-attested':0, 'tsa-attested':1, accredited:2};
      renderEsignTimeNotice(
        data.envelopes.map((e) => e['time-attestation'] || 'app-attested')
          .sort((a, b) => (order[a] || 0) - (order[b] || 0))[0] || 'app-attested');
      data.envelopes.forEach((envelope) => {
        const item = listItem(envelope['document-title'] || envelope['document-id'],
          `${esignStatusText[envelope.status] || envelope.status} · ${envelope['created-at']}`,
          `${envelope['signature-count']} / ${(envelope.signers || []).length}`,
          false);
        item.addEventListener('click', () => {
          selectedEnvelope = envelope.id;
          $('#esign-detail').replaceChildren(envelopeDetail(envelope));
        });
        list.append(item);
      });
      if (!data.envelopes.length) {
        list.append(make('li', 'empty-state', '署名の依頼はまだありません。'));
      } else if (selectedEnvelope) {
        const found = data.envelopes.find((e) => e.id === selectedEnvelope);
        if (found) $('#esign-detail').replaceChildren(envelopeDetail(found));
      }
    };
    const refreshEsignDocuments = () => {
      const select = $('#esign-document');
      if (!select) return;
      const chosen = select.value;
      select.replaceChildren();
      // Only documents this Drive can actually freeze a version of. An archive
      // item has no object reference to digest, so offering one would produce a
      // request that fails after the user chose it.
      //
      // `file?` is the same exclusion for the same reason: an uploaded PDF is
      // bytes with a media type, not one of the four surfaces, and there is no
      // outline to show a signer. The server refuses it — this is why it is
      // never chosen, not the check that it is refused.
      (driveData.items || [])
        .filter((item) => item.origin === 'workspace' && !item['trashed?']
                          && !item['file?'])
        .forEach((item) => {
          const option = make('option', null, item.name);
          option.value = item.id;
          select.append(option);
        });
      if (!select.children.length) {
        const option = make('option', null, '署名できるドキュメントがありません');
        option.value = '';
        select.append(option);
      }
      if (chosen) select.value = chosen;
    };
    const loadEsign = () => fetch('/api/esign')
      .then((r) => r.ok ? r.json() : null)
      .then((d) => { if (d) { renderEsign(d); refreshEsignDocuments(); }
                     return Boolean(d); })
      .catch(() => {
        $('#esign-source').textContent = 'envelope を読み込めません。';
        return false;
      });
    const signEnvelope = async (envelope, button) => {
      button.disabled = true; button.textContent = '署名の準備中…';
      try {
        requireWebAuthn();
        const started = await postJSON(
          `/api/esign/envelopes/${encodeURIComponent(envelope.id)}/sign/start`, {}, true);
        button.textContent = '生体認証を待っています…';
        const credential = await navigator.credentials.get(assertionOptions(started));
        await postJSON(
          `/api/esign/envelopes/${encodeURIComponent(envelope.id)}/sign/finish`,
          {'transaction-id':started['transaction-id'],
           credential:credentialJSON(credential)}, true);
        await loadEsign();
      } catch (error) {
        $('#esign-detail').append(make('p', 'form-help', error.message));
      } finally {
        button.disabled = false; button.textContent = 'Passkey で署名';
      }
    };
    const declineEnvelope = async (envelope, button) => {
      button.disabled = true;
      try {
        await postJSON(
          `/api/esign/envelopes/${encodeURIComponent(envelope.id)}/decline`,
          {reason:''}, true);
        await loadEsign();
      } catch (error) {
        $('#esign-detail').append(make('p', 'form-help', error.message));
      } finally {
        button.disabled = false;
      }
    };
    const loadContracts = () => fetch('/api/contracts')
      .then((r) => r.ok ? r.json() : null)
      .then((d) => { if (d) renderContracts(d); return Boolean(d); })
      .catch(() => {
        $('#contracts-source').textContent = '契約を読み込めません。';
        return false;
      });
    const projectPath = (suffix = '') =>
      `/api/projects/${encodeURIComponent(selectedProjectId)}${suffix}`;
    const projectRequired = (element, message = 'Projectを選択してください。') => {
      if (selectedProjectId) return true;
      if (element) element.textContent = message;
      return false;
    };
    const renderProjectBoard = (data) => {
      const board = $('#local-project-board');
      board.replaceChildren();
      const issues = data.issues || [];
      (data.columns || []).forEach((column) => {
        const lane = make('section', 'project-column');
        const laneIssues = issues.filter((issue) => issue.column === column.id);
        const heading = make('h3', null, `${column.name} · ${laneIssues.length}`);
        lane.append(heading);
        laneIssues.forEach((issue) => {
          const card = make('article', 'project-issue');
          card.append(make('strong', null, `#${issue.number} ${issue.title}`));
          const move = make('select');
          move.setAttribute('aria-label', `${issue.title} の状態`);
          (data.columns || []).forEach((optionColumn) => {
            const option = make('option', null, optionColumn.name);
            option.value = optionColumn.id;
            option.selected = optionColumn.id === issue.column;
            move.append(option);
          });
          move.addEventListener('change', async () => {
            move.disabled = true;
            try {
              await postJSON(`${projectPath('/issues/')}${encodeURIComponent(issue.id)}`,
                {column:move.value}, true);
              await loadProjectBoard();
            } catch (error) {
              $('#local-project-board-source').textContent = error.message;
              move.value = issue.column;
            } finally { move.disabled = false; }
          });
          card.append(move);
          lane.append(card);
        });
        board.append(lane);
      });
      $('#local-project-board-source').textContent =
        `${data.project?.title || selectedProjectId} · ${issues.length} issues`;
      $('#local-project-board-card').hidden = false;
    };
    const loadProjectBoard = async () => {
      if (!selectedProjectId) {
        $('#local-project-board-card').hidden = true;
        return false;
      }
      const requestedProject = selectedProjectId;
      try {
        const request = await fetch(`/api/projects/${encodeURIComponent(requestedProject)}`);
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || 'Projectを読み込めませんでした。');
        if (selectedProjectId !== requestedProject) return false;
        renderProjectBoard(data);
        return true;
      } catch (error) {
        $('#local-project-board-card').hidden = false;
        $('#local-project-board').replaceChildren(make('p', 'empty-state', error.message));
        return false;
      }
    };
    const renderSites = (data) => {
      const list = $('#site-list');
      list.replaceChildren();
      (data.items || []).forEach((site) => {
        const row = recordButton(site, site.id === selectedSiteId,
          (selected) => selectSite(selected.id),
          {title:site.title, time:site.status === 'published' ? '公開中' : '下書き',
           meta:site.slug});
        row.dataset.siteId = site.id;
        list.append(row);
      });
      if (!(data.items || []).length) {
        list.append(make('li', 'empty-state', 'このProjectにSiteはありません。'));
      }
      $('#sites-count').textContent = (data.items || []).length;
    };
    const loadSites = async () => {
      if (!selectedProjectId) {
        $('#site-list').replaceChildren(make('li', 'empty-state', 'Projectを選択してください。'));
        $('#site-editor-panel').hidden = true;
        $('#sites-count').textContent = '0';
        return false;
      }
      const requestedProject = selectedProjectId;
      try {
        const request = await fetch(`/api/sites?project=${encodeURIComponent(requestedProject)}`);
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || 'Sitesを読み込めませんでした。');
        if (selectedProjectId !== requestedProject) return false;
        renderSites(data);
        if (selectedSiteId && !(data.items || []).some((site) => site.id === selectedSiteId)) {
          selectedSiteId = null;
          $('#site-editor-panel').hidden = true;
        }
        return true;
      } catch (error) {
        $('#site-list').replaceChildren(make('li', 'empty-state', error.message));
        return false;
      }
    };
    const selectSite = async (siteId) => {
      selectedSiteId = siteId;
      const requestedProject = selectedProjectId;
      $('#site-editor-status').textContent = '読み込み中…';
      try {
        const request = await fetch(
          `/api/sites/${encodeURIComponent(siteId)}?project=${encodeURIComponent(requestedProject)}`);
        const site = await request.json();
        if (!request.ok) throw new Error(site?.error?.message || 'Siteを読み込めませんでした。');
        if (selectedProjectId !== requestedProject || selectedSiteId !== siteId) return;
        $('#site-editor-panel').hidden = false;
        $('#site-html').value = site.html || '';
        $('#site-editor-meta').textContent =
          `${site.title} · ${site.status === 'published' ? '公開中' : '下書き'} · ${site.url}`;
        $('#site-preview').src =
          `/api/sites/${encodeURIComponent(site.id)}/preview?project=${encodeURIComponent(requestedProject)}&v=${Date.now()}`;
        $('#site-editor-status').textContent = 'HTMLを編集して保存するとプレビューを更新します。';
        document.querySelectorAll('#site-list [data-site-id]').forEach((item) =>
          item.querySelector('button')?.setAttribute(
            'aria-pressed', item.dataset.siteId === siteId ? 'true' : 'false'));
      } catch (error) {
        $('#site-editor-status').textContent = error.message;
      }
    };
    const selectWorkspaceProject = async (projectId) => {
      selectedProjectId = projectId || '';
      selectedSiteId = null;
      if (selectedProjectId) localStorage.setItem('cloud-itonami-project-board', selectedProjectId);
      else localStorage.removeItem('cloud-itonami-project-board');
      document.querySelectorAll('#local-project-list [data-project-id]').forEach((item) =>
        item.querySelector('button')?.setAttribute(
          'aria-pressed', item.dataset.projectId === selectedProjectId ? 'true' : 'false'));
      $('#site-editor-panel').hidden = true;
      await Promise.all([loadProjectBoard(), loadSites()]);
    };
    const renderLocalProjects = (data) => {
      localProjects = data.items || [];
      const list = $('#local-project-list');
      const contextSelects = [$('#chat-context-project-select'), $('#bots-context-project-select')];
      list.replaceChildren();
      contextSelects.forEach((select) => {
        select.replaceChildren();
        const placeholder = make('option', null, 'Contextなし');
        placeholder.value = '';
        select.append(placeholder);
      });
      localProjects.forEach((project) => {
        contextSelects.forEach((select) => {
          const option = make('option', null,
            `Context: ${project.title || project['project-id']}`);
          option.value = project['project-id'];
          select.append(option);
        });
        const row = recordButton(project, project['project-id'] === selectedProjectId,
          (selected) => selectWorkspaceProject(selected['project-id']),
          {title:project.title || project['project-id'], time:`${project['issue-count'] || 0} issues`,
           meta:project['project-id']});
        row.dataset.projectId = project['project-id'];
        list.append(row);
      });
      if (!localProjects.length) {
        list.append(make('li', 'empty-state', '最初のProjectを作成してください。'));
      }
      if (!localProjects.some((project) => project['project-id'] === selectedProjectId)) {
        selectedProjectId = localProjects[0]?.['project-id'] || '';
      }
      if (!localProjects.some((project) => project['project-id'] === chatContextProjectId)) {
        chatContextProjectId = '';
      }
      $('#chat-context-project-select').value = chatContextProjectId;
      document.querySelectorAll('#local-project-list [data-project-id]').forEach((item) =>
        item.querySelector('button')?.setAttribute(
          'aria-pressed', item.dataset.projectId === selectedProjectId ? 'true' : 'false'));
      syncBotsProjectContext();
      $('#projects-count').textContent = localProjects.length;
    };
    const loadLocalProjects = async () => {
      try {
        const request = await fetch('/api/projects');
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || 'Projectsを読み込めませんでした。');
        renderLocalProjects(data);
        if (selectedProjectId) localStorage.setItem('cloud-itonami-project-board', selectedProjectId);
        else localStorage.removeItem('cloud-itonami-project-board');
        localStorage.removeItem('cloud-itonami-project');
        await Promise.all([loadProjectBoard(), loadSites(), loadSession()]);
        return true;
      } catch (error) {
        $('#local-project-list').replaceChildren(make('li', 'empty-state', error.message));
        return false;
      }
    };
    $('#chat-context-project-select').addEventListener('change', async (event) => {
      chatContextProjectId = event.currentTarget.value || '';
      if (chatContextProjectId) {
        localStorage.setItem('cloud-itonami-chat-context-project', chatContextProjectId);
      } else localStorage.removeItem('cloud-itonami-chat-context-project');
      await loadSession();
    });
    $('#local-project-create-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const status = $('#local-project-create-status');
      const button = event.submitter;
      const fields = Object.fromEntries(new FormData(event.currentTarget));
      button.disabled = true;
      status.textContent = 'Projectを作成中…';
      try {
        const data = await postJSON('/api/projects', fields, true);
        event.currentTarget.reset();
        status.textContent = 'Projectを作成しました。';
        await loadLocalProjects();
        await selectWorkspaceProject(data.item['project-id']);
      } catch (error) { status.textContent = error.message; }
      finally { button.disabled = false; }
    });
    $('#project-issue-create-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      if (!projectRequired($('#local-project-board-source'))) return;
      const button = event.submitter;
      const title = new FormData(event.currentTarget).get('title');
      button.disabled = true;
      try {
        await postJSON(projectPath('/issues'), {title, column:'backlog'}, true);
        event.currentTarget.reset();
        await loadProjectBoard();
      } catch (error) { $('#local-project-board-source').textContent = error.message; }
      finally { button.disabled = false; }
    });
    $('#site-create-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const status = $('#site-create-status');
      if (!projectRequired(status)) return;
      const button = event.submitter;
      const fields = Object.fromEntries(new FormData(event.currentTarget));
      button.disabled = true;
      status.textContent = 'Siteを作成中…';
      try {
        const site = await postJSON('/api/sites', {...fields, project:selectedProjectId}, true);
        event.currentTarget.reset();
        status.textContent = 'Siteを作成しました。';
        await loadSites();
        await selectSite(site.id);
      } catch (error) { status.textContent = error.message; }
      finally { button.disabled = false; }
    });
    $('#site-save-button').addEventListener('click', async (event) => {
      if (!selectedSiteId || !projectRequired($('#site-editor-status'))) return;
      event.currentTarget.disabled = true;
      $('#site-editor-status').textContent = '保存中…';
      try {
        await writeJSON(`/api/sites/${encodeURIComponent(selectedSiteId)}`, 'PUT',
          {project:selectedProjectId, html:$('#site-html').value}, true);
        $('#site-editor-status').textContent = '下書きを保存しました。';
        await Promise.all([loadSites(), selectSite(selectedSiteId)]);
      } catch (error) { $('#site-editor-status').textContent = error.message; }
      finally { event.currentTarget.disabled = false; }
    });
    $('#site-publish-button').addEventListener('click', async (event) => {
      if (!selectedSiteId || !projectRequired($('#site-editor-status'))) return;
      event.currentTarget.disabled = true;
      $('#site-editor-status').textContent = '公開中…';
      try {
        const site = await postJSON(`/api/sites/${encodeURIComponent(selectedSiteId)}/publish`,
          {project:selectedProjectId}, true);
        $('#site-editor-status').replaceChildren(document.createTextNode('公開しました: '));
        const link = make('a', null, site.url);
        link.href = site.url; link.target = '_blank'; link.rel = 'noopener';
        $('#site-editor-status').append(link);
        await loadSites();
        $('#site-editor-meta').textContent = `${site.title} · 公開中 · ${site.url}`;
      } catch (error) { $('#site-editor-status').textContent = error.message; }
      finally { event.currentTarget.disabled = false; }
    });
    const renderProjects = (data) => {
      const list = $('#project-list'); list.replaceChildren();
      data.items.forEach((item) => list.append(listItem(
        item.title, `${item.graph || data.scope} · #${item.number}`,
        item['closed?'] ? 'Closed' : 'Open')));
      if (!data.items.length) {
        const empty = make('li', 'empty-state');
        empty.append(make('strong', null, data.message || 'Project はありません。'),
          make('p', 'data-list__meta', '接続後は Table・Board・Roadmap を同じデータから切り替えます。'));
        list.append(empty);
      }
      $('#projects-source').textContent = `${data.source} · ${data.scope}`;
      $('#projects-state').textContent = data.status === 'connected' ? '接続済み' : '権限確認が必要';
      $('#projects-state').className = `state-chip${data.status === 'connected' ? '' : ' state-chip--warn'}`;
    };
    let governanceHydrated = false;
    let governanceActorCandidates = [];
    const governanceField = (label, value, field, type = 'text') => {
      const wrapper = make('label');
      wrapper.append(document.createTextNode(label));
      const input = make(type === 'select' ? 'select' : 'input');
      input.dataset.field = field;
      if (type !== 'select') input.type = type;
      input.value = value || '';
      wrapper.append(input);
      return input;
    };
    const governanceRemove = (row) => {
      const button = make('button', 'tool-button governance-remove', '削除');
      button.type = 'button';
      button.addEventListener('click', () => row.remove());
      return button;
    };
    const selectOptions = (input, options, selected) => {
      options.forEach(([value, label]) => {
        const option = make('option', null, label); option.value = value;
        option.selected = value === selected; input.append(option);
      });
      return input;
    };
    const addGovernanceUnit = (value = {}) => {
      const row = make('div', 'governance-row');
      const id = governanceField('Unit ID', value['org.unit/id'], 'id');
      const name = governanceField('表示名', value['org.unit/name'], 'name');
      const kind = governanceField('種別', '', 'kind', 'select');
      selectOptions(kind, [['organization','Organization'],['division','Division'],
        ['department','Department'],['team','Team'],['program','Program']],
      value['org.unit/kind'] || 'team');
      const parent = governanceField('親Unit ID', value['org.unit/parent'], 'parent');
      parent.setAttribute('list', 'organization-unit-options');
      const performer = governanceField('Organization Performer',
        value['org.unit/performer'], 'performer');
      performer.setAttribute('list', 'organization-performer-options');
      row.append(id.parentElement, name.parentElement, kind.parentElement,
        parent.parentElement, performer.parentElement, governanceRemove(row));
      $('#governance-units')?.append(row);
    };
    const addGovernancePosition = (value = {}) => {
      const row = make('div', 'governance-row governance-row--wide');
      const id = governanceField('Position ID', value['org.position/id'], 'id');
      const name = governanceField('表示名', value['org.position/name'], 'name');
      const unit = governanceField('Unit ID', value['org.position/unit'], 'unit');
      unit.setAttribute('list', 'organization-unit-options');
      row.append(id.parentElement, name.parentElement, unit.parentElement,
        governanceRemove(row));
      $('#governance-positions')?.append(row);
    };
    const addGovernanceRole = (value = {}) => {
      const row = make('div', 'governance-row governance-row--wide');
      const id = governanceField('Role ID', value['org.role/id'], 'id');
      const name = governanceField('表示名', value['org.role/name'], 'name');
      const capabilities = governanceField('Capabilities（カンマ区切り）',
        (value['org.role/capabilities'] || []).join(', '), 'capabilities');
      row.append(id.parentElement, name.parentElement, capabilities.parentElement,
        governanceRemove(row));
      $('#governance-roles')?.append(row);
    };
    const addGovernancePerformer = (value = {}) => {
      const row = make('div', 'governance-row governance-row--wide');
      const id = governanceField('ID', value['performer/id'], 'id');
      const name = governanceField('表示名', value['performer/name'], 'name');
      const kind = governanceField('DoDAF種別', '', 'kind', 'select');
      selectOptions(kind, [['person','Person'],['system','System'],
        ['organization','Organization']], value['performer/kind'] || 'person');
      const actor = value['performer/actor'] || {};
      const actorKind = governanceField('Actor種別', '', 'actor-kind', 'select');
      selectOptions(actorKind, [['','未結合'],['user','Cloud Itonami User'],
        ['agent','Agent session'],['organism-worker','OrganismWorker'],
        ['external-system','External system'],['organization','Organization']],
      actor['actor/kind'] || (value['performer/user-id'] ? 'user' : ''));
      const actorId = governanceField('Actor ID', actor['actor/id'] ||
        value['performer/user-id'], 'actor-id');
      actorId.setAttribute('list', 'organization-actor-options');
      actorId.addEventListener('change', () => {
        const candidate = governanceActorCandidates.find((item) =>
          item['actor/id'] === actorId.value);
        if (candidate) actorKind.value = candidate['actor/kind'];
      });
      row.append(id.parentElement, name.parentElement, kind.parentElement,
        actorKind.parentElement, actorId.parentElement, governanceRemove(row));
      $('#governance-performers')?.append(row);
    };
    const addGovernanceAssignment = (value = {}) => {
      const row = make('div', 'governance-row governance-row--wide');
      const id = governanceField('ID', value['org.assignment/id'], 'id');
      const performer = governanceField('Performer ID', value['org.assignment/performer'], 'performer');
      const position = governanceField('Position ID', value['org.assignment/position'], 'position');
      performer.setAttribute('list', 'organization-performer-options');
      position.setAttribute('list', 'organization-position-options');
      const roles = governanceField('Roles（カンマ区切り）',
        (value['org.assignment/roles'] || []).join(', '), 'roles');
      const from = governanceField('有効開始', value['org.assignment/effective-from'],
        'effective-from', 'date');
      const to = governanceField('有効終了', value['org.assignment/effective-to'],
        'effective-to', 'date');
      row.append(id.parentElement, performer.parentElement, position.parentElement,
        roles.parentElement, from.parentElement, to.parentElement, governanceRemove(row));
      $('#governance-assignments')?.append(row);
    };
    const addGovernanceReporting = (value = {}) => {
      const row = make('div', 'governance-row governance-row--reporting');
      const manager = governanceField('Manager assignment ID', value['reporting/manager'], 'manager');
      const report = governanceField('Report assignment ID', value['reporting/report'], 'report');
      row.append(manager.parentElement, report.parentElement, governanceRemove(row));
      $('#governance-reporting-lines')?.append(row);
    };
    const rowValues = (selector) => [...document.querySelectorAll(selector)].map((row) =>
      Object.fromEntries([...row.querySelectorAll('[data-field]')]
        .map((input) => [input.dataset.field, input.value.trim()])));
    const renderOrganizationStudio = (data) => {
      const units = data['organization-units'] || [];
      const performers = data.performers || [];
      const assignments = data.assignments || [];
      const roles = data['organization-roles'] || [];
      const policies = data['approval-policies'] || [];
      const summary = $('#organization-studio-summary');
      if (!summary) return;
      summary.replaceChildren();
      [['Units',units.length],['People & Actors',performers.length],
       ['Assignments',assignments.length],['Approval policies',policies.length]]
        .forEach(([label, count]) => {
          const card = make('div', 'organization-stat');
          card.append(make('strong', null, String(count)), make('span', null, label));
          summary.append(card);
        });
      $('#organization-count').textContent = units.length || performers.length || '—';
      const tree = $('#organization-studio-tree'); tree.replaceChildren();
      const children = new Map();
      units.forEach((unit) => {
        const parent = unit['org.unit/parent'] || '';
        children.set(parent, [...(children.get(parent) || []), unit]);
      });
      const renderBranch = (parent, seen = new Set()) => {
        const list = make('ul', parent ? null : 'organization-tree');
        (children.get(parent) || []).forEach((unit) => {
          const id = unit['org.unit/id']; if (seen.has(id)) return;
          const item = make('li');
          const node = make('div', 'organization-tree__node');
          const copy = make('span');
          copy.append(make('strong', null, unit['org.unit/name'] || id),
            make('small', null, ` ${unit['org.unit/kind'] || 'team'}`));
          const positionCount = (data.positions || [])
            .filter((position) => position['org.position/unit'] === id).length;
          node.append(copy, make('span', 'state-chip', `${positionCount} positions`));
          item.append(node);
          const branch = renderBranch(id, new Set([...seen, id]));
          if (branch.childElementCount) item.append(branch);
          list.append(item);
        });
        return list;
      };
      const roots = renderBranch('');
      tree.append(...roots.children);
      if (!tree.children.length) tree.append(make('li', 'empty-state', 'Organization Unitはありません。'));
      const actors = $('#organization-studio-actors'); actors.replaceChildren();
      performers.forEach((performer) => {
        const actor = performer['performer/actor'];
        const row = make('li');
        const copy = make('div');
        copy.append(make('strong', null, performer['performer/name'] || performer['performer/id']),
          make('p', 'data-list__meta', actor
            ? `${actor['actor/kind']} · ${actor['actor/id']}` : 'Actor未結合'));
        row.append(copy, make('span', 'state-chip', performer['performer/kind'])); actors.append(row);
      });
      if (!performers.length) actors.append(make('li', 'empty-state', 'Performerはありません。'));
      const assignmentList = $('#organization-studio-assignments');
      assignmentList.replaceChildren();
      assignments.forEach((assignment) => {
        const performer = performers.find((p) =>
          p['performer/id'] === assignment['org.assignment/performer']);
        const row = make('li'); const copy = make('div');
        copy.append(make('strong', null, performer?.['performer/name'] ||
          assignment['org.assignment/performer']),
        make('p', 'data-list__meta', `${assignment['org.assignment/position']} · ${
          (assignment['org.assignment/roles'] || []).join(', ')}`));
        row.append(copy, make('span', 'state-chip', assignment['org.assignment/status'] || 'active'));
        assignmentList.append(row);
      });
      if (!assignments.length) assignmentList.append(make('li', 'empty-state', 'Assignmentはありません。'));
      const policyList = $('#organization-studio-policies'); policyList.replaceChildren();
      policies.forEach((policy) => {
        const row = make('li'); const copy = make('div');
        const eligibleRoles = policy['approval.policy/eligible-roles'] || [];
        const eligiblePeople = assignments.filter((assignment) =>
          (assignment['org.assignment/roles'] || []).some((role) => eligibleRoles.includes(role)))
          .map((assignment) => performers.find((performer) =>
            performer['performer/id'] === assignment['org.assignment/performer']))
          .filter((performer) => performer?.['performer/kind'] === 'person')
          .map((performer) => performer['performer/name'] || performer['performer/id']);
        copy.append(make('strong', null, policy['approval.policy/capability']),
          make('p', 'data-list__meta', `${eligibleRoles.join(', ')} · ${
            policy['approval.policy/minimum']} approvals · ${eligiblePeople.join(', ') || 'eligible Personなし'}`));
        row.append(copy, make('span', 'state-chip', policy['approval.policy/separation-of-duties?']
          ? '職務分離' : '同一人物可')); policyList.append(row);
      });
      if (!policies.length) policyList.append(make('li', 'empty-state', 'Approval policyはありません。'));
      const chip = $('#organization-studio-state');
      chip.textContent = `${data['organization-id']} · EDN partition`;
      chip.className = 'state-chip';
    };
    const hydrateGovernanceForms = (data) => {
      if (governanceHydrated) return;
      governanceHydrated = true;
      const organization = data.organization || {};
      governanceActorCandidates = data['actor-candidates'] || [];
      const actorOptions = $('#organization-actor-options');
      actorOptions.replaceChildren();
      governanceActorCandidates.forEach((actor) => {
        const option = make('option'); option.value = actor['actor/id'];
        option.label = `${actor['actor/label']} (${actor['actor/kind']})`;
        actorOptions.append(option);
      });
      const fillGovernanceOptions = (id, values, key, labelKey) => {
        const list = $(id); list.replaceChildren();
        values.forEach((value) => {
          const option = make('option'); option.value = value[key];
          option.label = value[labelKey] || value[key]; list.append(option);
        });
      };
      fillGovernanceOptions('#organization-unit-options', data['organization-units'] || [],
        'org.unit/id', 'org.unit/name');
      fillGovernanceOptions('#organization-position-options', data.positions || [],
        'org.position/id', 'org.position/name');
      fillGovernanceOptions('#organization-performer-options', data.performers || [],
        'performer/id', 'performer/name');
      $('#governance-org-id').value = organization['org/id'] || data['organization-id'] || '';
      $('#governance-org-name').value = organization['org/name'] || '';
      $('#governance-units').replaceChildren();
      (data['organization-units'] || []).forEach(addGovernanceUnit);
      $('#governance-positions').replaceChildren();
      (data.positions || []).forEach(addGovernancePosition);
      $('#governance-roles').replaceChildren();
      (data['organization-roles'] || []).forEach(addGovernanceRole);
      $('#governance-performers').replaceChildren();
      (data.performers || []).forEach(addGovernancePerformer);
      $('#governance-assignments').replaceChildren();
      (data.assignments || []).forEach(addGovernanceAssignment);
      $('#governance-reporting-lines').replaceChildren();
      (data['reporting-lines'] || []).forEach(addGovernanceReporting);
      const policy = (data['approval-policies'] || [])[0];
      if (policy) {
        $('#governance-policy-id').value = policy['approval.policy/id'] || '';
        $('#governance-policy-capability').value = policy['approval.policy/capability'] || '';
        $('#governance-policy-roles').value = (policy['approval.policy/eligible-roles'] || []).join(', ');
        $('#governance-policy-minimum').value = policy['approval.policy/minimum'] || 1;
        $('#governance-policy-uv').checked = policy['approval.policy/requires-user-verification?'] !== false;
        $('#governance-policy-sod').checked = policy['approval.policy/separation-of-duties?'] !== false;
      }
    };
    const loadWorkGovernance = async () => {
      const list = $('#work-governance-list');
      const chip = $('#work-governance-state');
      if (!list || !chip) return false;
      try {
        const response = await fetch('/api/work-governance');
        const data = await response.json();
        if (!response.ok || data.error) throw new Error(data.error?.message || '読み込めません');
        list.replaceChildren();
        (data['work-items'] || []).forEach((item) => list.append(listItem(
          item['work.item/title'],
          `${item['work.item/yakuwari']} · ${item['work.item/capability']}`,
          item['work.item/status'])));
        if (!(data['work-items'] || []).length) {
          list.append(make('li', 'empty-state', 'Governed WorkItem はありません。'));
        }
        const runtime = data.runtime || {};
        chip.textContent = `${runtime.ticks || 0} ticks · ${data['dead-letters']?.length || 0} dead`;
        chip.className = `state-chip${(data['dead-letters'] || []).length ? ' state-chip--warn' : ''}`;
        hydrateGovernanceForms(data);
        renderOrganizationStudio(data);
        return true;
      } catch (error) {
        list.replaceChildren(make('li', 'empty-state', error.message));
        chip.textContent = '確認が必要';
        chip.className = 'state-chip state-chip--warn';
        return false;
      }
    };
    $('#open-organization-studio')?.addEventListener('click', () => showView('organization'));
    $('#governance-add-unit')?.addEventListener('click', () => addGovernanceUnit());
    $('#governance-add-position')?.addEventListener('click', () => addGovernancePosition());
    $('#governance-add-role')?.addEventListener('click', () => addGovernanceRole());
    $('#governance-add-performer')?.addEventListener('click', () => addGovernancePerformer());
    $('#governance-add-assignment')?.addEventListener('click', () => addGovernanceAssignment());
    $('#governance-add-reporting')?.addEventListener('click', () => addGovernanceReporting());
    $('#governance-organization-form')?.addEventListener('submit', async (event) => {
      event.preventDefault();
      const status = $('#governance-organization-status');
      const organization = $('#governance-org-id').value.trim();
      const csv = (value) => value.split(',').map((x) => x.trim()).filter(Boolean);
      const units = rowValues('#governance-units .governance-row')
        .filter((row) => row.id)
        .map((row) => ({'org.unit/id':row.id, 'org.unit/organization':organization,
          'org.unit/name':row.name, 'org.unit/kind':row.kind,
          ...(row.parent ? {'org.unit/parent':row.parent} : {}),
          ...(row.performer ? {'org.unit/performer':row.performer} : {})}));
      const positions = rowValues('#governance-positions .governance-row')
        .filter((row) => row.id)
        .map((row) => ({'org.position/id':row.id,
          'org.position/organization':organization,
          'org.position/name':row.name, 'org.position/unit':row.unit}));
      const organizationRoles = rowValues('#governance-roles .governance-row')
        .filter((row) => row.id)
        .map((row) => ({'org.role/id':row.id,
          'org.role/organization':organization, 'org.role/name':row.name,
          'org.role/capabilities':csv(row.capabilities)}));
      const performers = rowValues('#governance-performers .governance-row')
        .filter((row) => row.id)
        .map((row) => ({'performer/id':row.id, 'performer/name':row.name,
          'performer/kind':row.kind, 'performer/organization':organization,
          ...(row['actor-kind'] && row['actor-id'] ? {'performer/actor':{
            'actor/kind':row['actor-kind'], 'actor/id':row['actor-id']}} : {})}));
      const assignments = rowValues('#governance-assignments .governance-row')
        .filter((row) => row.id)
        .map((row) => ({'org.assignment/id':row.id,
          'org.assignment/organization':organization,
          'org.assignment/performer':row.performer,
          'org.assignment/position':row.position,
          'org.assignment/roles':csv(row.roles),
          ...(row['effective-from'] ? {'org.assignment/effective-from':row['effective-from']} : {}),
          ...(row['effective-to'] ? {'org.assignment/effective-to':row['effective-to']} : {})}));
      const reporting = rowValues('#governance-reporting-lines .governance-row')
        .filter((row) => row.manager && row.report)
        .map((row) => ({'reporting/manager':row.manager, 'reporting/report':row.report}));
      status.textContent = '組織図を検証しています…';
      try {
        await postJSON('/api/work-governance/organizations', {
          'org/id':organization, 'org/name':$('#governance-org-name').value.trim(),
          'org/units':units, 'org/positions':positions, 'org/roles':organizationRoles,
          'org/performers':performers, 'org/assignments':assignments,
          'org/reporting-lines':reporting
        }, true);
        status.textContent = '組織図を保存しました。';
        governanceHydrated = false; await loadWorkGovernance();
      } catch (error) { status.textContent = error.message; }
    });
    $('#governance-policy-form')?.addEventListener('submit', async (event) => {
      event.preventDefault();
      const status = $('#governance-policy-status');
      const roles = $('#governance-policy-roles').value.split(',')
        .map((x) => x.trim()).filter(Boolean);
      status.textContent = 'Policyを検証しています…';
      try {
        await postJSON('/api/work-governance/approval-policies', {
          'approval.policy/id':$('#governance-policy-id').value.trim(),
          'approval.policy/organization':$('#governance-org-id').value.trim(),
          'approval.policy/capability':$('#governance-policy-capability').value.trim(),
          'approval.policy/eligible-roles':roles,
          'approval.policy/minimum':Number($('#governance-policy-minimum').value),
          'approval.policy/requires-user-verification?':$('#governance-policy-uv').checked,
          'approval.policy/separation-of-duties?':$('#governance-policy-sod').checked,
          'approval.policy/rejection-mode':'veto'
        }, true);
        status.textContent = 'Approval policyを保存しました。';
        governanceHydrated = false; await loadWorkGovernance();
      } catch (error) { status.textContent = error.message; }
    });
    let calendarData = {items:[], days:[]};
    let selectedDay = null;
    let selectedEvent = null;
    // ── appointments ──────────────────────────────────────────────────────
    //
    // The Scheduler showed the machine's calendar and nothing else: this app
    // could not make an appointment, ask anyone to it, or answer one, while
    // `kotoba-lang/calendar` had attendees, RSVP and conflict detection all
    // along. These four calls are the whole of what was missing.
    const rsvpLabels = {'needs-action':'返事待ち', accepted:'参加', declined:'不参加',
                        tentative:'仮'};
    const reloadScheduler = () => loadWorkspace('scheduler', renderCalendar);
    // A local field is a wall clock; the model stores instants. Sending what
    // the field says would put `2026-08-03T09:00` next to EventKit's
    // `2026-08-03T09:00:00Z` in one list, and two events an hour apart would
    // sort as though they were not.
    const instantFrom = (value) => {
      if (!value) return null;
      const when = new Date(value);
      return Number.isNaN(when.getTime()) ? null : when.toISOString();
    };
    const createAppointment = async () => {
      const status = $('#scheduler-status');
      const start = instantFrom($('#scheduler-start')?.value);
      const end = instantFrom($('#scheduler-end')?.value);
      // Asked here as well as at the server, because the server's refusal
      // comes from the model and is worded for a developer.
      if (!start || !end) {
        status.textContent = '開始と終了の日時を入れてください。';
        return;
      }
      status.textContent = '予定を作成しています…';
      try {
        const made = await postJSON('/api/workspace/scheduler/events', {
          title:($('#scheduler-title')?.value || '').trim(),
          start, end,
          attendees:($('#scheduler-attendees')?.value || '')
            .split(/[,、\s]+/).map((s) => s.trim()).filter(Boolean)
        }, true);
        status.textContent = `${made.event.title} を作成しました。`;
        ['#scheduler-title', '#scheduler-attendees'].forEach((id) => {
          if ($(id)) $(id).value = '';
        });
        selectedEvent = null;
        await reloadScheduler();
      } catch (error) {
        status.textContent = error.message;
      }
    };
    const appointmentAction = async (path, body, done) => {
      const status = $('#scheduler-status');
      try {
        await postJSON(path, body, true);
        status.textContent = done;
        await reloadScheduler();
      } catch (error) {
        status.textContent = error.message;
      }
    };
    const appointmentActions = (event) => {
      const box = make('div', 'appointment');
      const people = Object.entries(event.rsvp || {});
      if (people.length) {
        const list = make('ul', 'appointment__people');
        people.forEach(([person, status]) => {
          const row = make('li', `appointment__person appointment__person--${status}`);
          row.append(make('span', 'appointment__name', person),
            make('span', 'appointment__status', rsvpLabels[status] || status));
          list.append(row);
        });
        box.append(make('p', 'record-detail__eyebrow', '出席'), list);
      } else if (event.role === 'organizer') {
        box.append(make('p', 'surface-note', 'まだ誰も招いていません。'));
      }
      // An attendee answers. All three, including tentative: the model has
      // it, and an interface offering only yes and no makes the third answer
      // unreachable for anyone but a script.
      if (event.role === 'attendee') {
        const answers = make('div', 'appointment__answers');
        [['accepted', '参加する'], ['tentative', '仮で入れる'],
         ['declined', '参加しない']].forEach(([status, label]) => {
          const button = make('button', 'tool-button', label);
          button.type = 'button';
          button.setAttribute('aria-pressed', event['your-rsvp'] === status ? 'true' : 'false');
          button.addEventListener('click', () => appointmentAction(
            `/api/workspace/scheduler/events/${encodeURIComponent(event.id)}/respond`,
            {status}, `「${event.title}」に${label}と答えました。`));
          answers.append(button);
        });
        box.append(answers);
      }
      if (event.role === 'organizer') {
        const invite = make('div', 'appointment__invite');
        const field = make('input', 'workspace-search');
        field.type = 'text';
        field.placeholder = '招く人';
        field.setAttribute('aria-label', '招く人');
        const ask = make('button', 'tool-button', '招く');
        ask.type = 'button';
        ask.addEventListener('click', () => {
          const person = field.value.trim();
          if (!person) return;
          field.value = '';
          appointmentAction(
            `/api/workspace/scheduler/events/${encodeURIComponent(event.id)}/invite`,
            {person}, `${person} を招きました。`);
        });
        const cancel = make('button', 'tool-button', 'この予定を取り消す');
        cancel.type = 'button';
        cancel.addEventListener('click', () => {
          selectedEvent = null;
          appointmentAction(
            `/api/workspace/scheduler/events/${encodeURIComponent(event.id)}/cancel`,
            {}, `「${event.title}」を取り消しました。`);
        });
        invite.append(field, ask, cancel);
        box.append(invite);
      }
      // What this clashes with, for whoever is reading. Asked per event
      // rather than for the whole list: it is a question about one
      // appointment, and asking it for every row is a request per row.
      const clashes = make('p', 'surface-note');
      box.append(clashes);
      fetch(`/api/workspace/scheduler/events/${encodeURIComponent(event.id)}/conflicts`)
        .then((response) => response.json())
        .then((data) => {
          const found = data.conflicts || [];
          // Nothing rather than 'no conflicts': an empty line under every
          // appointment is a report on a question nobody asked.
          if (found.length) {
            clashes.textContent =
              `重なっています: ${found.map((c) => c.title).join('、')}`;
          }
        })
        .catch(() => { /* the line simply stays empty */ });
      return box;
    };
    const renderCalendar = (data) => {
      calendarData = data;
      // Static markup, so it is wired once rather than on every render —
      // the same guard the upload input uses, and for the same reason: a
      // listener added per render fires once per render.
      const create = $('#scheduler-create-button');
      if (create && !create.dataset.wired) {
        create.dataset.wired = 'true';
        create.addEventListener('click', createAppointment);
      }
      const days = data.days || [];
      if (!days.some((day) => day.date === selectedDay)) selectedDay = days[0]?.date || null;
      const rail = $('#calendar-days'); rail.replaceChildren();
      days.forEach((day) => {
        const date = new Date(`${day.date}T00:00:00`);
        const button = make('button', 'date-button');
        button.type = 'button';
        button.setAttribute('aria-pressed', day.date === selectedDay ? 'true' : 'false');
        button.append(make('span', null, new Intl.DateTimeFormat('ja-JP', {weekday:'short'}).format(date)),
          make('strong', null, String(date.getDate())),
          make('span', null, `${day.items.length} 件`));
        button.addEventListener('click', () => {
          selectedDay = day.date; selectedEvent = null; renderCalendar(calendarData);
        });
        rail.append(button);
      });
      const activeDay = days.find((day) => day.date === selectedDay);
      const items = activeDay?.items || [];
      if (!items.some((item) => item.id === selectedEvent?.id)) selectedEvent = items[0] || null;
      const list = $('#calendar-list'); list.replaceChildren();
      data.items.forEach((item) => {
        if (!items.some((candidate) => candidate.id === item.id)) return;
        list.append(recordButton(item, item.id === selectedEvent?.id,
          (event) => { selectedEvent = event; renderCalendar(calendarData); }, {
            title:item.title,
            time:item['all-day?'] ? '終日' : formatDate(item.start, true),
            meta:item.calendar || 'Calendar',
            snippet:item['all-day?'] ? '終日の予定' : `${formatDate(item.end, true)} まで`
          }));
      });
      if (!items.length) list.append(make('li', 'empty-state', data.message || 'この日の予定はありません。'));
      if (selectedEvent) {
        setDetail($('#calendar-detail'), selectedEvent.calendar || 'Calendar',
          selectedEvent.title, selectedEvent['all-day?'] ? '終日の予定です。' : '時間を確保した予定です。',
          [['開始', selectedEvent['all-day?'] ? '終日' : formatDate(selectedEvent.start)],
           ['終了', selectedEvent['all-day?'] ? '終日' : formatDate(selectedEvent.end)],
           ['カレンダー', selectedEvent.calendar || 'Calendar']]);
        // Only for the appointments this app owns. An event mirrored from
        // the machine's calendar has no attendees here to answer to, and
        // offering to accept one would be offering to write somewhere this
        // app only reads.
        if (selectedEvent.origin === 'app') {
          $('#calendar-detail').append(appointmentActions(selectedEvent));
        }
      }
      else $('#calendar-detail').replaceChildren(make('div', 'empty-state', 'この日の予定はありません。'));
      $('#scheduler-count').textContent = data.items.length;
      $('#calendar-source').textContent = `${data.source} · ${data.status}`;
    };
    // Appended rather than replacing: a cursor says where to continue from,
    // so a later page is more of the same list and not a different view of it.
    let driveCursor = null;
    const loadMoreDrive = async () => {
      if (!driveCursor) return;
      try {
        const request = await fetch(
          `/api/workspace/drive?cursor=${encodeURIComponent(driveCursor)}`);
        const data = await request.json();
        if (!request.ok) return;
        const seen = new Set((driveData.items || []).map((i) => i.id));
        const added = (data.items || []).filter((i) => !seen.has(i.id));
        driveCursor = data['next-cursor'] || null;
        renderDrive({...driveData, items:(driveData.items || []).concat(added),
                     'next-cursor':driveCursor});
      } catch (error) { /* the button simply stays */ }
    };
    const loadWorkspace = async (name, renderer) => {
      try {
        const request = await fetch(`/api/workspace/${name}`);
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || `${name} を読み込めませんでした。`);
        // The tree the Drive list is scoped by. Fetched here rather than
        // inside renderDrive, which runs on every keystroke in the search
        // box — that would be a request per character.
        if (name === 'drive' && !(folderData.path || []).length) await loadFolders();
        renderer(data);
        return true;
      } catch (error) {
        $(`#${name === 'scheduler' ? 'calendar' : name}-list`)?.replaceChildren(
          make('li', 'empty-state', error.message));
        return false;
      }
    };
    let organismWorkers = [];
    let selectedOrganism = null;
    let organismCursor = null;
    let organismTimer = null;
    let organismReceipts = [];
    const sendOrganismIntent = async (payload) => {
      if (!selectedOrganism) throw new Error('AO workerを選択してください。');
      return postJSON(
        `/api/organism-workers/${encodeURIComponent(selectedOrganism.id)}/intents`,
        payload, true);
    };
    const decideOrganismIntent = async (intentId, decision) => {
      if (!selectedOrganism) throw new Error('AO workerを選択してください。');
      return postJSON(
        `/api/organism-workers/${encodeURIComponent(selectedOrganism.id)}`
          + `/intents/${encodeURIComponent(intentId)}/decision`,
        {decision}, true);
    };
    const renderOrganismReceipts = (data) => {
      organismReceipts = data.items || [];
      const decidedIntents = new Set(
        organismReceipts
          .filter((receipt) => receipt.capability === 'approval/submit'
            && receipt.parent)
          .map((receipt) => receipt.parent));
      const list = $('#organism-receipts'); list.replaceChildren();
      organismReceipts.forEach((receipt) => {
        const item = make('li', 'data-list__item');
        const copy = make('div');
        copy.append(
          make('strong', null,
            `${receipt.capability || 'intent'} · ${receipt.status || 'unknown'}`),
          make('p', 'data-list__meta',
            `${receipt.intent} · effect ${receipt['effect-status'] || 'unknown'}`
              + `${receipt.evidence?.['run-id']
                ? ` · run ${receipt.evidence['run-id']}` : ''}`));
        item.append(copy);
        if (['admitted', 'awaiting-approval'].includes(receipt.status)
            && receipt.capability === 'intent/submit'
            && !decidedIntents.has(receipt.intent)) {
          const actions = make('div', 'worker-actions');
          [['approved', '承認'], ['rejected', '拒否']].forEach(([decision, label]) => {
            const button = make('button', 'tool-button', label);
            button.type = 'button';
            button.addEventListener('click', async () => {
              button.disabled = true;
              try {
                await decideOrganismIntent(receipt.intent, decision);
                await loadOrganismReceipts();
              } catch (error) {
                $('#organism-intent-state').textContent = error.message;
                button.disabled = false;
              }
            });
            actions.append(button);
          });
          item.append(actions);
        }
        list.append(item);
      });
      if (!organismReceipts.length) {
        list.append(make('li', 'empty-state', 'intent receiptはまだありません。'));
      }
      $('#organism-receipt-state').textContent =
        `${organismReceipts.length} receipts · effect完了とは別です`;
    };
    const loadOrganismReceipts = async () => {
      if (!selectedOrganism) {
        renderOrganismReceipts({items:[]});
        return;
      }
      const request = await fetch(
        `/api/organism-workers/${encodeURIComponent(selectedOrganism.id)}/receipts`);
      const data = await request.json();
      if (!request.ok) throw new Error(data?.error?.message || 'receiptを取得できません。');
      renderOrganismReceipts(data);
    };
    const renderOrganismActivity = (data, replace = false) => {
      const list = $('#organism-activity');
      if (replace) list.replaceChildren();
      (data.items || []).forEach((activity) => {
        const item = make('li', 'data-list__item');
        item.append(
          make('span', 'data-list__meta', formatDate(activity.at, true)),
          make('strong', null, String(activity.kind || 'system')),
          make('span', 'data-list__meta',
            String(activity.run || activity.parent || activity.stream || 'system')));
        list.append(item);
      });
      while (list.children.length > 100) list.firstElementChild.remove();
      organismCursor = data.cursor || organismCursor;
      $('#organism-activity-state').textContent =
        `${list.children.length} events · cursor ${String(organismCursor || '—').slice(-10)}`;
      $('#organism-live-state').textContent =
        (data.items || []).length ? 'live' : 'waiting';
      $('#organism-live-state').className =
        `state-chip${(data.items || []).length ? ' state-chip--run' : ''}`;
    };
    const loadOrganismActivity = async (replace = false) => {
      if (!selectedOrganism) return;
      const params = new URLSearchParams({limit:'100'});
      if (!replace && organismCursor) params.set('cursor', organismCursor);
      const request = await fetch(
        `/api/organism-workers/${encodeURIComponent(selectedOrganism.id)}/activity?${params}`);
      const data = await request.json();
      if (!request.ok) throw new Error(data?.error?.message || 'activityを取得できません。');
      renderOrganismActivity(data, replace);
    };
    const renderOrganismDetail = async () => {
      const target = $('#organism-detail');
      if (!selectedOrganism) {
        target.replaceChildren(make('div', 'empty-state',
          'active organization にAO workerはありません。'));
        return;
      }
      target.replaceChildren(make('div', 'skeleton'));
      try {
        const request = await fetch(
          `/api/organism-workers/${encodeURIComponent(selectedOrganism.id)}/snapshot`);
        const snapshot = await request.json();
        if (!request.ok) throw new Error(snapshot?.error?.message || 'snapshotを取得できません。');
        const worker = snapshot.worker || selectedOrganism;
        setDetail(target, worker.organization || 'Organization',
          worker.id, '外部supervisorで稼働するrepository-bound AOです。',
          [['状態', worker.status || 'unknown'],
           ['Repository', worker.repository || '—'],
           ['Runtime', worker.runtime || '—'],
           ['Event authority', snapshot.connection?.['event-authority'] || '—'],
           ['Recent activity', String(snapshot.activity?.recent || 0)],
           ['AgentRuns', String(snapshot.activity?.['agent-runs'] || 0)]]);
      } catch (error) {
        target.replaceChildren(make('div', 'empty-state', error.message));
      }
    };
    const renderOrganismDirectory = (data) => {
      organismWorkers = data.items || [];
      if (!organismWorkers.some((item) => item.id === selectedOrganism?.id)) {
        selectedOrganism = organismWorkers[0] || null;
        organismCursor = null;
      }
      const list = $('#organism-list'); list.replaceChildren();
      organismWorkers.forEach((worker) => {
        list.append(recordButton(worker, worker.id === selectedOrganism?.id,
          (selected) => {
            selectedOrganism = selected; organismCursor = null;
            renderOrganismDirectory(data);
          }, {title:worker.id, time:worker.status || 'unknown',
              meta:worker.repository || 'repository',
              snippet:`${worker.runtime} · ${(worker.capabilities || []).length} capabilities`}));
      });
      if (!organismWorkers.length) {
        list.append(make('li', 'empty-state',
          'このOrganizationに割り当てられたAO workerはありません。'));
      }
      $('#organism-count').textContent = organismWorkers.length;
      $('#organism-source').textContent =
        `${data.organization || 'organization'} · ${organismWorkers.length} AO`;
      renderOrganismDetail();
      loadOrganismActivity(true).catch((error) => {
        $('#organism-activity-state').textContent = error.message;
      });
      loadOrganismReceipts().catch((error) => {
        $('#organism-receipt-state').textContent = error.message;
      });
      scheduleOrganismPoll();
    };
    $('#organism-messenger-issue').addEventListener('click', async (event) => {
      const button = event.currentTarget;
      if (!selectedOrganism) return;
      button.disabled = true;
      try {
        const issued = await postJSON(
          `/api/organism-workers/${encodeURIComponent(selectedOrganism.id)}/messenger-transport`, {}, true);
        $('#organism-messenger-state').textContent =
          `発行済み: ${issued['credential-file']} · clear tokenは0600 file内だけです。`;
      } catch (error) { $('#organism-messenger-state').textContent = error.message; }
      finally { button.disabled = false; }
    });
    const loadOrganisms = async () => {
      const request = await fetch('/api/organism-workers');
      const data = await request.json();
      if (!request.ok) throw new Error(data?.error?.message || 'AO workerを読み込めません。');
      renderOrganismDirectory(data);
    };
    const scheduleOrganismPoll = () => {
      if (organismTimer) { clearTimeout(organismTimer); organismTimer = null; }
      if (!appUnlocked || currentView !== 'organisms' || !selectedOrganism) return;
      organismTimer = setTimeout(async () => {
        try {
          await Promise.all([
            loadOrganismActivity(false),
            loadOrganismReceipts()
          ]);
        }
        finally { scheduleOrganismPoll(); }
      }, 2000);
    };
    $('#organism-intent-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = $('#organism-intent-submit');
      const fields = Object.fromEntries(new FormData(event.currentTarget));
      button.disabled = true; button.textContent = 'admit中…';
      try {
        const receipt = await sendOrganismIntent({
          capability:'intent/submit',
          type:'objective',
          summary:fields.summary
        });
        event.currentTarget.reset();
        $('#organism-intent-state').textContent =
          `${receipt.intent} をadmitしました。effectは未実行です。`;
        await loadOrganismReceipts();
      } catch (error) {
        $('#organism-intent-state').textContent = error.message;
      } finally {
        button.disabled = false; button.textContent = 'Tamaki inboxへ送る';
      }
    });
    $('#organism-stop').addEventListener('click', async () => {
      const button = $('#organism-stop');
      button.disabled = true;
      try {
        const receipt = await sendOrganismIntent({
          capability:'stop/request',
          type:'stop',
          summary:'Human operator requested a governed stop.'
        });
        $('#organism-intent-state').textContent =
          `${receipt.intent} のstop requestをadmitしました。`;
        await loadOrganismReceipts();
      } catch (error) {
        $('#organism-intent-state').textContent = error.message;
      } finally { button.disabled = false; }
    });
    const bootstrapApp = () => {
      if (appBootstrapped) return;
      appBootstrapped = true;
      loadWorkspace('worker', renderWorker);
      loadOrganisms().catch((error) => {
        $('#organism-list').replaceChildren(make('li', 'empty-state', error.message));
      });
      Promise.all([
        loadCaptures(),
        loadMessenger(),
        loadWorkspace('inbox', renderInbox),
        loadLocalProjects(),
        loadWorkspace('projects', renderProjects),
        loadWorkGovernance(),
        loadWorkspace('drive', renderDrive),
        loadWorkspace('scheduler', renderCalendar),
        // Inside bootstrapApp, unlike loadFilecoin: this endpoint decrypts vault
        // items behind a session, and every reveal writes a line into kagi's
        // audit ledger. Loading it before a Passkey exists would put unattributed
        // reveals in the ledger of a user who has not logged in yet.
        loadContracts(),
        // After loadWorkspace('drive', …) has run, because the request form's
        // document list is built from what that loaded. Promise.all does not
        // order these, so `refreshEsignDocuments` is also called on every
        // subsequent `loadEsign` rather than only here.
        loadEsign(),
        loadFleet(),
        loadOperator(),
        // Inside bootstrapApp, not beside loadFilecoin: a business belongs to an
        // organization and the portfolio is that organization's own record, so
        // it needs the session that loadFilecoin's public chain reads do not.
        loadPortfolio()
      ]).then((results) => {
        const connected = results.filter(Boolean).length;
        $('#workspace-status').textContent = `${connected} / ${results.length} サービス接続`;
      });
    };
    $('#esign-request-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = event.submitter;
      const status = $('#esign-request-status');
      button.disabled = true; button.textContent = '依頼を作成中…';
      try {
        const dids = $('#esign-signers').value.split('\n')
          .map((line) => line.trim()).filter(Boolean);
        if (!dids.length) throw new Error('署名者の DID を 1 件以上入力してください。');
        if (!$('#esign-document').value) throw new Error('ドキュメントを選んでください。');
        await postJSON('/api/esign/envelopes', {
          'document-id':$('#esign-document').value,
          purpose:$('#esign-purpose').value,
          'signer-dids':dids
        }, true);
        $('#esign-signers').value = '';
        status.textContent = '署名を依頼しました。';
        await loadEsign();
      } catch (error) {
        status.textContent = error.message;
      } finally {
        button.disabled = false; button.textContent = '署名を依頼する';
      }
    });

    // ── Fleet directory + 事業者としての参与 ──────────────────────────
    //
    // Two rules this code exists to hold, both of which are easy to violate
    // by accident in a renderer:
    //   1. a licence is 自己表明 and is never drawn as verified;
    //   2. a blueprint without :deploy-config gets no deploy affordance —
    //      the text says the operator builds it, and there is no button.
    const reqLabel = {maturity:'成熟度', governor:'governor', licence:'許認可',
      technologies:'必要技術', 'deploy-path':'deploy 経路'};
    const stateLabel = {met:'充足', unmet:'未充足', attested:'自己表明',
      absent:'同梱なし', none:'宣言なし', unknown:'不明'};
    const stateTone = {met:'ok', unmet:'warn', attested:'note',
      absent:'note', none:'note', unknown:'note'};
    let fleetFacets = null;

    const fillSelect = (id, pairs) => {
      const el = $('#' + id); if (!el || !pairs) return;
      const keep = el.value;
      el.replaceChildren(make('option', null, 'すべて'));
      el.firstChild.value = '';
      pairs.forEach(([v, n]) => {
        const o = make('option', null, `${v} (${n})`);
        o.value = String(v).replace(/^:/, '');
        el.append(o);
      });
      el.value = keep;
    };

    // Its own class rather than .data-list__item: that grid is
    // minmax(0,1fr) auto for a two-child row, and a requirement carries four
    // things (label, state, reason, and sometimes a caveat that must never be
    // dropped). Reusing the grid put the reason on top of the label.
    const requirementRow = (r) => {
      const li = make('li', 'req-row');
      const head = make('div', 'req-row__head');
      head.append(make('strong', null, reqLabel[String(r.requirement).replace(/^:/,'')]
        || String(r.requirement)));
      // Not .nav-badge: that class is sized for the sidebar counter and
      // squeezes a word like 「同梱なし」 to 24px.
      const badge = make('span', 'req-row__state',
        stateLabel[String(r.state).replace(/^:/,'')] || String(r.state));
      badge.dataset.tone = stateTone[String(r.state).replace(/^:/,'')] || 'note';
      head.append(badge);
      li.append(head);
      li.append(make('p', 'req-row__detail', r.detail || ''));
      // The caveat rides with the requirement, so a licence row cannot be
      // rendered anywhere without it.
      if (r.caveat) li.append(make('p', 'req-row__caveat', r.caveat));
      return li;
    };

    let fleetCapabilityCatalog = null;

    const fleetDetail = (repo) => fetch('/api/operator/readiness/' + encodeURIComponent(repo))
      .then((r) => r.ok ? r.json() : null)
      .then((d) => {
        const box = $('#fleet-detail'); if (!box) return;
        if (!d) { box.replaceChildren(make('div', 'empty-state', '読み込めません。')); return; }
        const a = d.actor || {};
        box.replaceChildren();
        box.append(make('h3', null, a.name || a.repo));
        box.append(make('p', 'data-list__meta',
          [a.repo, a.domain, a.role, a.maturity].filter(Boolean).join(' · ')));
        if (a.endpoint) {
          box.append(make('p', 'data-list__meta', '稼働中: ' + a.endpoint));
        }
        box.append(make('h4', null, '運用に必要なもの'));
        const ul = make('ul', 'record-list__items');
        (d.requirements || []).forEach((r) => ul.append(requirementRow(r)));
        box.append(ul);
        const ad = d.adoption;
        box.append(make('p', 'form-help', ad
          ? `参与: ${String(ad.stage).replace(/^:/,'')}（${ad['declared-by']} / ${ad['declared-on']}）`
          : 'まだ参与を表明していません。事業者タブから表明できます。'));

        // ── 接続（ADR-2608093000 D4）────────────────────────────────────
        //
        // The OAuth shape, assembled from three things that already existed
        // and were not joined: the catalog row says what the app asks for,
        // tenant_connection holds the grant, and the approval is the passkey
        // ceremony in Settings. Nothing new is invented here.
        //
        // Only for an app that is actually running and actually asks for
        // something. Offering "接続" for a directory record would promise
        // something there is nothing behind.
        const requests = a.requests || [];
        if (a.endpoint && requests.length) {
          box.append(make('h4', null, 'この app が求めるもの'));
          const rl = make('ul', 'record-list__items');
          requests.forEach((c) => {
            const meta = (fleetCapabilityCatalog || {})[c];
            const li = make('li', 'record-list__item');
            // The sentence, not the identifier — and the identifier beneath
            // it, because somebody auditing a grant needs the exact name.
            li.append(make('strong', null, (meta && meta.label) || c));
            li.append(make('p', 'form-help', c
              + (meta && meta.direction === 'outbound'
                 ? ' · 外部の app に渡ります' : '')));
            rl.append(li);
          });
          box.append(rl);

          const connect = make('button', 'primary-action', 'この app を接続する');
          connect.type = 'button';
          connect.addEventListener('click', async () => {
            connect.disabled = true;
            try {
              // 202: requested, not granted. The grant needs the passkey
              // ceremony, and saying "接続しました" here would claim an
              // approval that has not happened — the same lie the 予約 page
              // refuses to tell when it says まだ確定していません.
              await postJSON('/v1/tenant-connections', {
                tenant_id: (window.__itonamiActiveTenant || ''),
                agent_id: a.id || a.repo,
                capabilities: requests,
                ttl_seconds: 3600,
              }, true);
              box.append(make('p', 'form-help',
                '申請しました。Settings の Agent tenant connections で Passkey 承認すると有効になります。'));
            } catch (error) {
              connect.disabled = false;
              box.append(make('p', 'form-help', '申請できませんでした: ' + error.message));
            }
          });
          box.append(connect);
        }
      })
      .catch(() => {});

    const renderFleet = (data) => {
      const list = $('#fleet-list'); if (!list) return;
      list.replaceChildren();
      (data.actors || []).forEach((a) => {
        const fit = a.fit && a.fit.score ? ` · 適合 ${a.fit.score}` : '';
        const item = listItem(a.name || a.repo,
          [a.role, a.domain, a.maturity].filter(Boolean).join(' · ') + fit,
          a.endpoint ? '稼働' : (a['deploy-config'] ? 'deploy可' : ''));
        item.addEventListener('click', () => fleetDetail(a.repo));
        list.append(item);
      });
      if (!(data.actors || []).length) {
        list.append(make('li', 'empty-state', '該当する blueprint がありません。'));
      }
      // Say the truncation out loud. A directory that quietly shows 200 of
      // 1,213 reads as a complete answer.
      const fs = $('#fleet-source');
      if (fs) fs.textContent = data.total > data.shown
        ? `${data.total} 件中 ${data.shown} 件を表示`
        : `${data.total} 件`;
      // Guarded like every other lookup in these two renderers: the badge
      // lives in the sidebar, and a renderer that assumes its own chrome is
      // present cannot be reused anywhere the chrome is not.
      const fc = $('#fleet-count'); if (fc) fc.textContent = data.total;
    };

    const searchFleet = () => {
      const q = new URLSearchParams();
      const v = (id) => ($('#' + id)?.value || '').trim();
      if (v('fleet-text')) q.set('text', v('fleet-text'));
      if (v('fleet-role')) q.set('role', v('fleet-role'));
      if (v('fleet-maturity')) q.set('maturity', v('fleet-maturity'));
      if (v('fleet-iso3166')) q.set('iso3166', v('fleet-iso3166'));
      if ($('#fleet-callable')?.checked) q.set('callable', 'true');
      return fetch('/api/fleet/search?' + q.toString())
        .then((r) => r.ok ? r.json() : null)
        .then((d) => { if (d) renderFleet(d); return Boolean(d); })
        .catch(() => { $('#fleet-source').textContent = 'catalog を読み込めません。'; return false; });
    };

    const loadFleet = () => fetch('/api/fleet')
      .then((r) => r.ok ? r.json() : null)
      .then((d) => {
        if (!d) return false;
        fleetFacets = d.facets;
        fillSelect('fleet-role', d.facets.role);
        fillSelect('fleet-maturity', d.facets.maturity);
        fillSelect('fleet-iso3166', d.facets.iso3166);
        return searchFleet();
      })
      .catch(() => false);

    const statTile = (label, value) => {
      const d = make('div', 'stat-tile');
      d.append(make('span', 'stat-tile__label', label));
      d.append(make('strong', 'stat-tile__value', String(value)));
      return d;
    };

    const renderOperator = (d) => {
      const s = d.summary || {};
      const stats = $('#operator-stats');
      if (stats) {
        stats.replaceChildren(
          statTile('参与', s.adoptions || 0),
          statTile('稼働', s.deployed || 0),
          statTile('fleet 全体', (s.fleet && s.fleet.actors) || 0),
          statTile('fleet の稼働', (s.fleet && s.fleet.callable) || 0));
      }
      const oc = $('#operator-count'); if (oc) oc.textContent = s.adoptions || 0;
      const os_ = $('#operator-source');
      if (os_) os_.textContent = d.profile
        ? `${d.profile.name} として参与しています`
        : '事業者プロファイルが未登録です。';
      const cv = $('#operator-licence-caveat'); if (cv && d.caveat) cv.textContent = d.caveat;

      if (d.profile) {
        const p = d.profile;
        const setv = (id, val) => { const e = $('#' + id); if (e && !e.value) e.value = val; };
        setv('operator-name', p.name || '');
        setv('operator-isic', (p.isic || []).join(', '));
        setv('operator-isco', (p.isco || []).join(', '));
        setv('operator-iso3166', (p.iso3166 || []).join(', '));
      }

      const m = $('#operator-matches');
      if (m) {
        m.replaceChildren();
        (d.matches || []).forEach((a) => {
          const item = listItem(a.name || a.repo,
            [a.role, a.domain, a.maturity].filter(Boolean).join(' · '),
            `適合 ${a.fit?.score ?? 0}`);
          item.addEventListener('click', () => {
            document.querySelector('[data-view=fleet]')?.click();
            fleetDetail(a.repo);
          });
          m.append(item);
        });
        if (!(d.matches || []).length) {
          m.append(make('li', 'empty-state',
            d.profile ? '適合する blueprint がありません。業種・職種・管轄を確認してください。'
                      : 'プロファイルを保存すると表示します。'));
        }
      }

      const ad = $('#operator-adoptions');
      if (ad) {
        ad.replaceChildren();
        (d.adoptions || []).forEach((a) => {
          const stage = String(a.stage).replace(/^:/, '');
          const item = listItem(a.repo,
            `${a['declared-by']} · ${a['declared-on']}`,
            stage, stage === 'withdrawn');
          item.addEventListener('click', () => {
            document.querySelector('[data-view=fleet]')?.click();
            fleetDetail(a.repo);
          });
          ad.append(item);
        });
        if (!(d.adoptions || []).length) {
          ad.append(make('li', 'empty-state', 'まだ参与を表明していません。'));
        }
      }
    };

    const loadOperator = () => fetch('/api/operator')
      .then((r) => r.ok ? r.json() : null)
      .then((d) => { if (d) renderOperator(d); return Boolean(d); })
      .catch(() => { $('#operator-source').textContent = '事業者プロファイルを読み込めません。'; return false; });

    const splitList = (id) => ($('#' + id)?.value || '')
      .split(',').map((x) => x.trim()).filter(Boolean);

    // ── Portfolio（事業の面）──────────────────────────────────────────
    //
    // ADR-2607309600. The one thing this renderer must never do is draw an
    // absent plane as an empty one. `unresolvable`（workspace checkout が未設定）
    // と `missing`（設定済みで、その canvas / repo が無い）は別の事実で、直し方
    // も別 — 前者は設定、後者は checkout か紐付けの誤り。両方を「0件」に畳むと、
    // 何も測っていない状態が「測って空だった」ように読める。
    //
    // WIRE CONTRACT, easy to get wrong in both directions:
    // `clojure.data.json/write-str` drops the NAMESPACE of a keyword key, so
    // `:business/id` arrives as `id` and `:adoption/repo` as `repo`. Reading
    // `b['business/id']` yields undefined — silently, and it renders as an empty
    // row rather than an error. (The document panes read `d['docs/blocks']` and
    // are fine only because those keys are STRINGS server-side and pass through
    // untouched; never copy that shape onto a keyword-keyed payload.) Keyword
    // VALUES lose their namespace and colon the same way, which is why `bare`
    // strips a leading colon defensively instead of assuming either form.
    const bare = (v) => String(v ?? '').replace(/^:/, '');
    const faceStateLabel = {resolved:'解決', partial:'一部解決', unbound:'未紐付け',
      unresolvable:'解析不能', missing:'不在', unreadable:'読取不能'};
    // `missing`/`unreadable` だけが warn。`unresolvable` と `unbound` は
    // アプリが決められないことなので、警告にはしない（operator の
    // blocking-states と同じ線引き）。
    const faceStateTone = {resolved:'ok', partial:'note', unbound:'note',
      unresolvable:'note', missing:'warn', unreadable:'warn'};
    const faceRow = (f) => {
      const li = make('li', 'req-row');
      const head = make('div', 'req-row__head');
      head.append(make('strong', null, f.label || bare(f.face)));
      const badge = make('span', 'req-row__state',
        faceStateLabel[bare(f.state)] || bare(f.state));
      badge.dataset.tone = faceStateTone[bare(f.state)] || 'note';
      head.append(badge);
      li.append(head);
      li.append(make('p', 'req-row__detail', f.detail || ''));
      // An unresolved face names the key it looked for and the file it looked
      // in, so the reader can act on it instead of only knowing it failed.
      const key = Array.isArray(f.key) ? f.key.join(' / ') : bare(f.key);
      if (key) li.append(make('p', 'req-row__caveat', `${key} — ${f.source || ''}`));
      return li;
    };

    let selectedBusinessId = null;
    let reposBusinessId = null;
    let metricsBusinessId = null;

    const renderBusinessDetail = (b) => {
      const box = $('#portfolio-detail'); if (!box) return;
      const card = $('#portfolio-bind-card');
      if (!b) {
        box.replaceChildren(make('div', 'empty-state', '事業を選ぶと、5面の状態を表示します。'));
        if (card) card.hidden = true;
        return;
      }
      const c = b.coverage || {};
      box.replaceChildren();
      box.append(make('p', 'record-detail__eyebrow', b.slug || ''));
      box.append(make('h3', null, b.name || b.slug || ''));
      // Two numbers, not one score: 紐付け数 is what the owner declared and
      // 解決数 is what the workspace can confirm. A single percentage would
      // hide which of the two is missing.
      box.append(make('p', 'record-detail__body',
        `${c.bound ?? 0}/${c.faces ?? 0} 面を紐付け · ${c.resolved ?? 0} 面を解決`
        + (c.unresolvable ? ` · ${c.unresolvable} 面は workspace 未設定のため解析不能` : '')));
      if (b.note) box.append(make('p', 'req-row__caveat', b.note));
      const list = make('ul', 'record-list__items');
      (b.faces || []).forEach((f) => list.append(faceRow(f)));
      box.append(list);
      if (card) {
        card.hidden = false;
        const set = (id, v) => { const e = $('#' + id); if (e) e.value = v || ''; };
        set('portfolio-bind-canvas', bare(b.canvas));
        set('portfolio-bind-model', b.model);
        set('portfolio-bind-leverage', b.leverage);
        set('portfolio-bind-adoptions', (b.adoptions || []).join(', '));
        set('portfolio-bind-repos', (b.repos || []).join(', '));
        set('portfolio-bind-lei', b.lei);
        const st = $('#portfolio-bind-status'); if (st) st.textContent = '';
      }
    };

    const renderPortfolio = (d) => {
      const rows = d.businesses || [];
      const counts = d.counts || {};
      const ws = d.workspace || {};

      const stats = $('#portfolio-stats');
      if (stats) {
        stats.replaceChildren(
          statTile('事業', counts.businesses || 0),
          statTile('5面すべて解決', counts['fully-resolved'] || 0),
          statTile('未割当の参与', counts['unassigned-adoptions'] || 0));
      }
      const badge = $('#portfolio-count');
      if (badge) badge.textContent = counts.businesses || 0;
      const src = $('#portfolio-source');
      if (src) src.textContent = rows.length
        ? `${rows.length} 件の事業`
        : '事業がまだありません。';

      // The workspace notice is the difference between 「測って空だった」 and
      // 「どこを見るか誰も言っていない」, so it always states which one it is.
      const notice = $('#portfolio-workspace');
      if (notice) {
        notice.replaceChildren();
        if (bare(ws.state) === 'present') {
          notice.append(make('strong', null, 'workspace checkout: '), ws.root || '');
        } else {
          notice.append(make('strong', null,
            bare(ws.state) === 'missing' ? 'workspace checkout が見つかりません。'
                                         : 'workspace checkout が未設定です。'));
          notice.append(document.createTextNode(' ' + (ws.detail || '')));
        }
      }

      const list = $('#portfolio-list');
      if (list) {
        list.replaceChildren();
        rows.forEach((b) => {
          const c = b.coverage || {};
          const item = listItem(b.name || b.slug,
            `${c.bound ?? 0}/${c.faces ?? 0} 面を紐付け`,
            `解決 ${c.resolved ?? 0}`,
            (c.resolved ?? 0) === 0);
          item.addEventListener('click', () => {
            selectedBusinessId = b.id;
            renderBusinessDetail(b);
          });
          list.append(item);
        });
        if (!rows.length) {
          list.append(make('li', 'empty-state', 'まだ事業がありません。'));
        }
      }
      renderBusinessDetail(rows.find((b) => b.id === selectedBusinessId));
      fillCanvasBusinesses(rows);
      fillLoopsBusinesses(rows);
      fillBusinessSelect('repos-business', rows, loadRepos, reposBusinessId);
      fillBusinessSelect('metrics-business', rows, loadMetrics, metricsBusinessId);

      const un = $('#portfolio-unassigned');
      if (un) {
        un.replaceChildren();
        const items = (d.unassigned && d.unassigned.adoptions) || [];
        items.forEach((a) => {
          un.append(listItem(a.repo,
            `${a['declared-by'] || ''} · ${a['declared-on'] || ''}`,
            bare(a.stage)));
        });
        if (!items.length) {
          un.append(make('li', 'empty-state', '未割当の参与はありません。'));
        }
      }
      const cav = $('#portfolio-unassigned-caveat');
      if (cav && d.unassigned && d.unassigned.caveat) {
        cav.textContent = '参与を表明したが、どの事業にも紐付いていない blueprint です。'
          + d.unassigned.caveat;
      }
    };

    // The matrix re-runs every bound XMILE model and reads 4.8 MB of repo planes,
    // so it is fetched on demand rather than at boot — and the button says so
    // instead of the pane appearing to be broken while it thinks.
    const matrixStateLabel = {measured:'測定済み', unbound:'未紐付け',
      unresolvable:'解析不能', missing:'不在', stale:'古い'};
    let matrixLoaded = false;

    const renderMatrix = (d) => {
      const table = $('#matrix'); if (!table) return;
      table.replaceChildren();
      const cols = (d && d.columns) || [];
      const rows = (d && d.businesses) || [];
      const thead = make('thead');
      const hr = make('tr');
      hr.append(make('th', null, '事業'));
      cols.forEach((c) => {
        const th = make('th', null, c.label);
        th.title = c.detail || '';
        hr.append(th);
      });
      thead.append(hr); table.append(thead);
      const tb = make('tbody');
      rows.forEach((r) => {
        const tr = make('tr');
        const th = make('th', null, r.business?.name || r.business?.slug || '');
        th.scope = 'row';
        tr.append(th);
        cols.forEach((c) => {
          const cell = (r.cells || {})[bare(c.key)] || {};
          const td = make('td');
          const st = bare(cell.state);
          const chip = make('span', 'matrix__state', matrixStateLabel[st] || st);
          chip.dataset.state = st;
          td.append(chip);
          // The reason is always beside the state — a grid of grey words with no
          // explanation is what this pane exists not to be.
          td.append(make('span', 'matrix__detail', cell.detail || ''));
          tr.append(td);
        });
        tb.append(tr);
      });
      table.append(tb);
      const counts = $('#matrix-counts');
      if (counts) {
        const c = (d && d.counts) || {};
        counts.textContent = rows.length
          ? `${c.businesses} 事業 × ${cols.length} 面 = ${c.cells} セル — `
            + Object.entries(c)
                .filter(([k]) => matrixStateLabel[k])
                .map(([k, v]) => `${matrixStateLabel[k]} ${v}`).join(' · ')
          : '事業がありません。';
      }
    };

    const loadMatrix = () => {
      const btn = $('#matrix-load');
      if (btn) { btn.disabled = true; btn.textContent = '計算中…'; }
      return fetch('/api/portfolio/matrix')
        .then((r) => r.ok ? r.json() : null)
        .then((d) => {
          if (d) { renderMatrix(d); matrixLoaded = true; }
          if (btn) { btn.disabled = false; btn.textContent = '再計算する'; }
          return Boolean(d);
        })
        .catch(() => {
          const c = $('#matrix-counts');
          if (c) c.textContent = 'matrix を計算できません。';
          if (btn) { btn.disabled = false; btn.textContent = '計算する'; }
          return false;
        });
    };

    $('#matrix-load')?.addEventListener('click', () => loadMatrix());

    const loadPortfolio = () => fetch('/api/business')
      .then((r) => r.ok ? r.json() : null)
      .then((d) => { if (d) renderPortfolio(d); return Boolean(d); })
      .catch(() => {
        const src = $('#portfolio-source');
        if (src) src.textContent = '事業を読み込めません。';
        return false;
      });

    $('#portfolio-create-form')?.addEventListener('submit', (e) => {
      e.preventDefault();
      const status = $('#portfolio-create-status');
      fetch('/api/business', {
        method: 'POST', headers: identityHeaders(),
        body: JSON.stringify({
          slug: ($('#portfolio-slug')?.value || '').trim(),
          name: ($('#portfolio-name')?.value || '').trim(),
          note: ($('#portfolio-note')?.value || '').trim()
        })
      }).then(async (r) => {
        const body = await r.json().catch(() => null);
        if (!r.ok) {
          // The server's own refusal text, not a generic one: slug-taken and
          // slug-invalid need different corrections.
          if (status) status.textContent = body?.error?.message
            || body?.error?.type || `保存できません (${r.status})`;
          return;
        }
        if (status) status.textContent = '追加しました。';
        ['portfolio-slug','portfolio-name','portfolio-note'].forEach((id) => {
          const e2 = $('#' + id); if (e2) e2.value = '';
        });
        selectedBusinessId = body && body.id;
        return loadPortfolio();
      }).catch(() => { if (status) status.textContent = '保存できません。'; });
    });

    // ── Canvas（事業の仮説）────────────────────────────────────────────
    //
    // Two things this renderer must not do. It must not draw a canvas it could
    // not read as an empty canvas — `unresolvable` / `missing` / `unreadable`
    // each say what to fix. And it must not draw a proposal as landed on the
    // strength of having recorded it: the state comes back from the server,
    // which derived it by reading the projection.
    const canvasStateLabel = {resolved:'読み込み済み', unbound:'canvas 未紐付け',
      unresolvable:'解析不能', missing:'投影が無い', unreadable:'読取不能'};
    const proposalStateLabel = {'awaiting-governor':'governor 待ち', landed:'着地',
      unverifiable:'判定不能', withdrawn:'取り下げ'};
    const proposalStateTone = {'awaiting-governor':'note', landed:'ok',
      unverifiable:'note', withdrawn:'note'};
    let canvasData = null;

    const canvasBlockCard = (b) => {
      const d = make('div', 'canvas-block');
      d.append(make('p', 'canvas-block__label', b.label || bare(b.block)));
      const items = b.items || [];
      if (items.length) {
        const ul = make('ul', 'canvas-block__items');
        items.forEach((i) => ul.append(make('li', null, i)));
        d.append(ul);
      } else {
        // An empty block is a real state in a lean canvas — say so rather than
        // rendering a card with nothing in it.
        d.append(make('p', 'canvas-block__empty', 'item がありません'));
      }
      if (b.note) d.append(make('p', 'canvas-block__note', b.note));
      return d;
    };

    const hypothesisRow = (h, riskiest) => {
      const li = make('li', 'req-row');
      if (bare(h.id) === bare(riskiest)) li.classList.add('req-row--risk');
      const head = make('div', 'req-row__head');
      head.append(make('strong', null, bare(h.id)
        + (bare(h.risk) === 'riskiest' ? '  最も危険な仮説' : '')));
      // Two chips on purpose: :hyp/status is what the ledger says, :gate/status
      // is what the metrics say, and one of them moving without the other is
      // the interesting case.
      const badge = make('span', 'req-row__state', bare(h.status));
      badge.dataset.tone = bare(h.status) === 'validated' ? 'ok'
        : (bare(h.status) === 'refuted' ? 'warn' : 'note');
      head.append(badge);
      li.append(head);
      li.append(make('p', 'req-row__detail', h.claim || ''));
      if (h.gate) li.append(make('p', 'req-row__caveat', 'gate: ' + h.gate));
      const gs = bare(h['gate-status'] ?? '');
      if (gs) {
        const line = make('p', 'req-row__caveat',
          `測定: ${gs}` + (h['gate-distance'] ? ` — ${h['gate-distance']}` : '')
          + (h['gate-evidence'] ? ` — ${h['gate-evidence']}` : ''));
        li.append(line);
      } else {
        li.append(make('p', 'req-row__caveat', '測定: 未測定（gate spec か metrics がありません）'));
      }
      if (h.evidence) li.append(make('p', 'req-row__caveat', '根拠: ' + h.evidence));
      return li;
    };

    // 14 dimensions, of which 11 are judgements somebody entered and 3 are
    // computed from the canvas itself. The projection carries which is which,
    // and whether the judgement was actually recorded — a dimension the
    // generator defaulted to 0 is not a product assessed and found lacking.
    const maturityDimRow = (dm) => {
      const row = make('div', 'axis-row');
      row.append(make('span', null,
        `${dm.name}${bare(dm.source) === 'auto' ? '（自動）' : ''}`));
      const v = typeof dm.value === 'number' ? dm.value : null;
      if (v !== null && dm['recorded?'] !== false) {
        const track = make('div', 'axis-row__track');
        const fill = make('div', 'axis-row__fill');
        // The rubric's dimensions are 0-5.
        fill.style.width = `${Math.round(Math.max(0, Math.min(1, v / 5)) * 100)}%`;
        track.append(fill);
        row.append(track);
        row.append(make('span', 'axis-row__value', v.toFixed(1)));
      } else {
        row.append(make('div', 'axis-row__unscored'));
        row.append(make('span', 'axis-row__value', '未記録'));
      }
      return row;
    };

    const renderMaturity = (mt) => {
      const stats = $('#canvas-maturity-stats');
      const list = $('#canvas-maturity-dims');
      const note = $('#canvas-maturity-note');
      if (!stats || !list) return;
      const state = bare(mt && mt.state);
      if (state !== 'resolved') {
        stats.replaceChildren();
        list.replaceChildren(make('li', 'empty-state',
          (mt && mt.detail) || '成熟度を読み込めていません。'));
        return;
      }
      stats.replaceChildren(
        statTile('BMC', typeof mt.bmc === 'number' ? mt.bmc.toFixed(1) : '—'),
        statTile('YC bench', typeof mt.yc === 'number' ? mt.yc.toFixed(1) : '—'),
        // Shown even at 0, because 0 unrecorded is the fact that makes the two
        // scores above trustworthy.
        statTile('未記録の次元', mt['unrecorded-dims'] ?? '—'),
        statTile('as-of', mt['as-of'] || '—'));
      list.replaceChildren();
      (mt.dims || []).forEach((dm) => list.append(maturityDimRow(dm)));
      if (note && (mt.unrecorded || []).length) {
        note.textContent = `未記録: ${mt.unrecorded.join('・')} — `
          + '生成器は未記録の判断を 0 として採点するので、これらは「評価されて低い」ではなく「未評価」です。';
      }
    };

    const renderCanvas = (d) => {
      canvasData = d;
      const c = (d && d.canvas) || {};
      const state = bare(c.state);
      const note = $('#canvas-state');
      if (note) {
        note.replaceChildren();
        note.append(make('strong', null, (canvasStateLabel[state] || state) + ' '));
        if (c.detail) note.append(document.createTextNode(c.detail));
        if (state === 'resolved') {
          note.append(document.createTextNode(
            `投影 ${c.source || ''}（as-of ${c['as-of'] || '不明'}）`));
          // A projection whose file disagrees with its own header is truncated;
          // saying so beats rendering eight of nine blocks as the canvas.
          if (c.counts && c.counts['complete?'] === false) {
            note.append(make('strong', null,
              ` 投影が宣言した件数と一致しません（block ${c.counts.blocks}/${c.counts['declared-blocks']}）`));
          }
        }
      }
      const meta = $('#canvas-meta');
      if (meta) meta.textContent = state === 'resolved'
        ? `${(c.blocks || []).length} block · ${(c.hypotheses || []).length} 仮説`
        : '';
      const src = $('#canvas-source');
      if (src) src.textContent = d && d.business
        ? `${d.business.name || d.business.slug}${c.product ? ' → ' + bare(c.product) : ''}`
        : '事業を選択してください。';
      const auth = $('#canvas-authority');
      if (auth && d && d.authority) auth.textContent = d.authority;

      renderMaturity(d && d.maturity);

      const grid = $('#canvas-blocks');
      if (grid) {
        grid.replaceChildren();
        (c.blocks || []).forEach((b) => grid.append(canvasBlockCard(b)));
      }

      const hs = $('#canvas-hypotheses');
      if (hs) {
        hs.replaceChildren();
        (c.hypotheses || []).forEach((h) => hs.append(hypothesisRow(h, c['riskiest-hyp'])));
        if (!(c.hypotheses || []).length) {
          hs.append(make('li', 'empty-state',
            state === 'resolved' ? '仮説が登録されていません。' : 'canvas を読み込めていません。'));
        }
      }

      // The block select is filled from the projection, so a proposal cannot
      // name a block that does not exist. With no projection there is nothing
      // honest to offer, and the form stays hidden.
      const card = $('#canvas-propose-card');
      const blockSelect = $('#canvas-propose-block');
      if (blockSelect) {
        const keep = blockSelect.value;
        blockSelect.replaceChildren(make('option', null, 'block を選択…'));
        blockSelect.firstChild.value = '';
        (c.blocks || []).forEach((b) => {
          const o = make('option', null, `${b.label || bare(b.block)} (${bare(b.id)})`);
          o.value = bare(b.id);
          blockSelect.append(o);
        });
        blockSelect.value = keep;
      }
      if (card) card.hidden = state !== 'resolved';

      const list = $('#canvas-proposals');
      if (list) {
        list.replaceChildren();
        (d?.proposals || []).forEach((p) => {
          const st = bare(p.state);
          const li = make('li', 'req-row');
          const head = make('div', 'req-row__head');
          head.append(make('strong', null,
            `${bare(p.action)} ${bare(p['canvas-id'])}`));
          const badge = make('span', 'req-row__state', proposalStateLabel[st] || st);
          badge.dataset.tone = proposalStateTone[st] || 'note';
          head.append(badge);
          li.append(head);
          li.append(make('p', 'req-row__detail', p.value || ''));
          li.append(make('p', 'req-row__caveat',
            `${p['proposed-by'] || ''} · ${p['proposed-at'] || ''}`
            + (p.reason ? ` · ${p.reason}` : '')));
          // The exact command, because there is no Apply button this app could
          // honestly offer.
          if (st !== 'landed' && st !== 'withdrawn' && p.command) {
            li.append(make('p', 'req-row__caveat', p.command));
            const drop = make('button', 'tool-button', '取り下げる');
            drop.type = 'button';
            drop.addEventListener('click', () => withdrawProposal(p.id));
            li.append(drop);
          }
          list.append(li);
        });
        if (!(d?.proposals || []).length) {
          list.append(make('li', 'empty-state', 'まだ提案はありません。'));
        }
      }
      const badge = $('#canvas-count');
      if (badge) {
        const awaiting = (d?.proposals || [])
          .filter((p) => bare(p.state) === 'awaiting-governor').length;
        badge.textContent = awaiting;
      }
    };

    const loadCanvas = (businessId) => {
      if (!businessId) { renderCanvas(null); return Promise.resolve(false); }
      return fetch(`/api/business/${encodeURIComponent(businessId)}/canvas`)
        .then((r) => r.ok ? r.json() : null)
        .then((d) => { renderCanvas(d); return Boolean(d); })
        .catch(() => {
          const src = $('#canvas-source');
          if (src) src.textContent = 'canvas を読み込めません。';
          return false;
        });
    };

    const withdrawProposal = (proposalId) => {
      const businessId = $('#canvas-business')?.value;
      if (!businessId || !proposalId) return;
      fetch(`/api/business/${encodeURIComponent(businessId)}/canvas/proposals/`
            + `${encodeURIComponent(proposalId)}/withdraw`, {
        method: 'POST', headers: identityHeaders(),
        body: JSON.stringify({by: ($('#canvas-propose-by')?.value || '').trim()})
      }).then(() => loadCanvas(businessId))
        .catch(() => {
          const st = $('#canvas-propose-status');
          if (st) st.textContent = '取り下げられません。';
        });
    };

    // Filled from the portfolio, so the two panes cannot disagree about which
    // businesses exist.
    const fillCanvasBusinesses = (rows) => {
      const sel = $('#canvas-business'); if (!sel) return;
      const keep = sel.value || selectedBusinessId || '';
      sel.replaceChildren(make('option', null, '事業を選択…'));
      sel.firstChild.value = '';
      (rows || []).forEach((b) => {
        const o = make('option', null, b.name || b.slug);
        o.value = b.id;
        sel.append(o);
      });
      const next = (rows || []).some((b) => b.id === keep) ? keep : '';
      sel.value = next;
      if (next && next !== canvasData?.business?.id) loadCanvas(next);
      if (!next) renderCanvas(null);
    };

    $('#canvas-business')?.addEventListener('change', (e) => {
      selectedBusinessId = e.currentTarget.value || selectedBusinessId;
      loadCanvas(e.currentTarget.value);
    });

    $('#canvas-propose-form')?.addEventListener('submit', (e) => {
      e.preventDefault();
      const status = $('#canvas-propose-status');
      const businessId = $('#canvas-business')?.value;
      if (!businessId) { if (status) status.textContent = '事業を選んでください。'; return; }
      fetch(`/api/business/${encodeURIComponent(businessId)}/canvas/propose`, {
        method: 'POST', headers: identityHeaders(),
        body: JSON.stringify({
          action: ($('#canvas-propose-action')?.value || '').trim(),
          'canvas-id': ($('#canvas-propose-block')?.value || '').trim(),
          value: ($('#canvas-propose-value')?.value || '').trim(),
          reason: ($('#canvas-propose-reason')?.value || '').trim(),
          by: ($('#canvas-propose-by')?.value || '').trim()
        })
      }).then(async (r) => {
        const body = await r.json().catch(() => null);
        if (!r.ok) {
          if (status) status.textContent = body?.error?.message
            || body?.error?.type || `記録できません (${r.status})`;
          return;
        }
        if (status) status.textContent = '提案を記録しました。ledger へは governor が入れます。';
        const v = $('#canvas-propose-value'); if (v) v.value = '';
        return loadCanvas(businessId);
      }).catch(() => { if (status) status.textContent = '記録できません。'; });
    });

    // ── Loops（stock-flow 構造とシミュレーション）──────────────────────
    //
    // Small multiples rather than one multi-series chart, because XMILE
    // variables carry their own units: a stock in `repos` and a flow in
    // `repos/day` on one y-axis is the dual-axis mistake. One panel per
    // variable, its own scale, units named — which also means one series per
    // panel, so there is no legend to omit and no categorical palette to get
    // wrong. Kind is carried by colour AND by the text under the name, never by
    // colour alone.
    const svgEl = (tag, attrs) => {
      const n = document.createElementNS('http://www.w3.org/2000/svg', tag);
      Object.entries(attrs || {}).forEach(([k, v]) => n.setAttribute(k, String(v)));
      return n;
    };
    const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);

    const sparkline = (values) => {
      const pts = (values || []).map(num);
      const known = pts.filter((v) => v !== null);
      const svg = svgEl('svg', {class:'sm__plot', viewBox:'0 0 100 40',
                                preserveAspectRatio:'none', role:'img'});
      if (known.length < 2) {
        // One point is not a trajectory. Saying so beats drawing a flat line
        // that looks like a simulated constant.
        svg.append(svgEl('line', {class:'sm__axis', x1:0, y1:39, x2:100, y2:39}));
        const t = svgEl('text', {class:'sm__flat', x:2, y:22});
        t.textContent = '系列が短すぎて描けません';
        svg.append(t);
        return svg;
      }
      const lo = Math.min(...known), hi = Math.max(...known);
      const span = hi - lo;
      // A constant series is real (a stock nothing drains). Drawn on the
      // midline, and the panel's own meta line reports the value, so a flat
      // line is never mistaken for a missing one.
      const y = (v) => (span === 0 ? 20 : 38 - ((v - lo) / span) * 36);
      const x = (i) => (i / (pts.length - 1)) * 100;
      let d = '', open = false;
      pts.forEach((v, i) => {
        if (v === null) { open = false; return; }
        d += `${open ? 'L' : 'M'}${x(i).toFixed(2)},${y(v).toFixed(2)} `;
        open = true;
      });
      svg.append(svgEl('line', {class:'sm__axis', x1:0, y1:39, x2:100, y2:39}));
      svg.append(svgEl('path', {class:'sm__line', d: d.trim(),
                                'vector-effect':'non-scaling-stroke'}));
      return svg;
    };

    const smPanel = (name, kind, units, values) => {
      const d = make('div', 'sm');
      d.dataset.kind = String(kind || '').replace(/^:/, '');
      d.append(make('p', 'sm__name', name));
      const known = (values || []).map(num).filter((v) => v !== null);
      const range = known.length
        ? `${known[0].toPrecision(4)} → ${known[known.length - 1].toPrecision(4)}`
        : '値なし';
      d.append(make('p', 'sm__meta',
        `${d.dataset.kind}${units ? ' · ' + units : ''} · ${range}`));
      d.append(sparkline(values));
      return d;
    };

    let loopsData = null;

    const renderLoops = (d) => {
      loopsData = d;
      const m = (d && d.model) || {};
      const lv = (d && d.leverage) || {};
      const state = bare(m.state);
      const traj = m.trajectory || {};

      const src = $('#loops-source');
      if (src) src.textContent = d && d.business
        ? (d.business.name || d.business.slug)
        : '事業を選択してください。';

      const note = $('#loops-model-state');
      if (note) {
        note.replaceChildren();
        if (state === 'resolved') {
          note.append(make('strong', null, `モデル ${m.name || m['simulated-model'] || ''} `));
          note.append(document.createTextNode(m.source || ''));
          // Which model ran, when a document declares several. Picking the first
          // is a choice, so it is stated rather than hidden.
          if ((m.models || []).length > 1) {
            note.append(make('strong', null,
              ` ${m.models.length} 個のモデルのうち「${m['simulated-model']}」を実行`));
          }
          if (bare(traj.state) !== 'simulated') {
            note.append(make('strong', null, ' シミュレーションできません: '));
            note.append(document.createTextNode(traj.reason || ''));
          }
        } else {
          note.append(make('strong', null, (m.detail ? '' : state) + ' '));
          note.append(document.createTextNode(m.detail || ''));
        }
      }
      const meta = $('#loops-meta');
      if (meta) meta.textContent = bare(traj.state) === 'simulated'
        ? `${traj.steps} step` : '';

      renderSensitivity(m.sensitivity);

      const st = $('#loops-structure');
      if (st) {
        st.replaceChildren();
        const s = m.structure || {};
        (s.stocks || []).forEach((v) => {
          const li = make('li', 'req-row');
          const head = make('div', 'req-row__head');
          head.append(make('strong', null, v.name));
          const b = make('span', 'req-row__state', 'stock');
          b.dataset.tone = 'note';
          head.append(b);
          li.append(head);
          li.append(make('p', 'req-row__detail',
            `${(v.inflows || []).join('・') || '流入なし'} → ${v.name} → `
            + `${(v.outflows || []).join('・') || '流出なし'}`));
          if (v.units) li.append(make('p', 'req-row__caveat', v.units));
          st.append(li);
        });
        [['flow', s.flows], ['aux', s.auxes]].forEach(([kind, vs]) => {
          (vs || []).forEach((v) => {
            const li = make('li', 'req-row');
            const head = make('div', 'req-row__head');
            head.append(make('strong', null, v.name));
            const b = make('span', 'req-row__state', kind);
            b.dataset.tone = 'note';
            head.append(b);
            li.append(head);
            if (v.units) li.append(make('p', 'req-row__caveat', v.units));
            st.append(li);
          });
        });
        if (!st.childElementCount) {
          st.append(make('li', 'empty-state',
            state === 'resolved' ? '変数がありません。' : 'モデルを読み込めていません。'));
        }
      }

      // The series grid and the table are the same data in two forms; the table
      // is the accessible view and also the one that shows every variable when
      // the grid gets long.
      const grid = $('#loops-series');
      const table = $('#loops-table');
      const series = traj.series || {};
      const names = Object.keys(series).sort();
      const kindOf = {};
      const unitsOf = {};
      ['stocks','flows','auxes'].forEach((g) => {
        ((m.structure || {})[g] || []).forEach((v) => {
          kindOf[v.name] = bare(v.kind); unitsOf[v.name] = v.units;
        });
      });
      if (grid) {
        grid.replaceChildren();
        if (bare(traj.state) === 'simulated') {
          names.forEach((n) => grid.append(
            smPanel(n, kindOf[n] || 'aux', unitsOf[n], series[n])));
        }
      }
      if (table) {
        table.replaceChildren();
        if (bare(traj.state) === 'simulated') {
          const t = make('table', 'dads-table');
          const thead = make('thead');
          const hr = make('tr');
          hr.append(make('th', null, 't'));
          names.forEach((n) => hr.append(make('th', null, n)));
          thead.append(hr); t.append(thead);
          const tb = make('tbody');
          (traj.times || []).forEach((tv, i) => {
            const row = make('tr');
            row.append(make('td', null, String(tv)));
            names.forEach((n) => {
              const v = (series[n] || [])[i];
              // An absent value is not zero. `—` is what the funding pane does
              // with an unknown balance, for the same reason.
              row.append(make('td', null,
                typeof v === 'number' && Number.isFinite(v) ? v.toPrecision(6) : '—'));
            });
            tb.append(row);
          });
          t.append(tb);
          table.append(t);
        }
      }

      const cav = $('#loops-leverage-caveat');
      if (cav) {
        cav.textContent = bare(lv.state) === 'resolved'
          ? (lv['models-what'] || '')
          : (lv.detail || '');
      }
      const list = $('#loops-leverage');
      if (list) {
        list.replaceChildren();
        (lv.ranked || []).forEach((r) => {
          const li = make('li', 'req-row');
          const head = make('div', 'req-row__head');
          head.append(make('strong', null, r.id));
          const b = make('span', 'req-row__state',
            `${bare(r.band)} · ${typeof r.score === 'number' ? r.score.toPrecision(3) : '—'}`);
          b.dataset.tone = (lv['top-3'] || []).includes(r.id) ? 'ok' : 'note';
          head.append(b);
          li.append(head);
          li.append(make('p', 'req-row__detail', r['band-label'] || ''));
          list.append(li);
        });
        if (!(lv.ranked || []).length) {
          list.append(make('li', 'empty-state', lv.detail || 'ranking がありません。'));
        }
      }
      const strength = $('#loops-strength');
      if (strength) {
        const s = lv['structural-strength'];
        // nil from dynamics.core is carried through as its own state; it is not
        // rendered as 0, and not omitted either.
        strength.textContent = !s ? ''
          : (bare(s.state) === 'computed'
             ? `構造的強度: ${Number(s.value).toPrecision(4)}`
             : `構造的強度: — ${s.detail || ''}`);
      }
      const bands = $('#loops-bands');
      if (bands) {
        bands.replaceChildren();
        (d?.bands || []).forEach((b) => bands.append(
          listItem(`${bare(b.band)} — ${b.label}`,
                   `Meadows tier ${(b.tiers || []).join(', ')}`,
                   `重み ${b.weight}`)));
      }
      const badge = $('#loops-count');
      if (badge) badge.textContent = (lv.ranked || []).length || '—';
    };

    // Elasticity: percent change in a stock per percent change in a constant,
    // measured by re-running the model. Dimensionless, so a rate in tenants/day
    // and a window in days are comparable — which is the only reason ordering
    // them means anything.
    //
    // An elasticity of exactly 0 is ambiguous, so the server decides the
    // ambiguity structurally: a constant no equation references is `disconnected`
    // and says so, rather than reading as 「動かしても効かない」.
    const sensitivityRow = (p) => {
      const li = make('li', 'req-row');
      const head = make('div', 'req-row__head');
      head.append(make('strong', null,
        `${p.name}${p.units ? ' (' + p.units + ')' : ''}`));
      const chip = make('span', 'req-row__state',
        p['connected?'] === false ? '未接続' : bare(p.kind));
      chip.dataset.tone = p['connected?'] === false ? 'warn' : 'note';
      head.append(chip);
      li.append(head);
      li.append(make('p', 'req-row__detail',
        `baseline ${p.baseline}`
        + (p['referenced-by']?.length ? ` · 参照元: ${p['referenced-by'].join('・')}` : '')));
      if (p.detail) li.append(make('p', 'req-row__caveat', p.detail));
      (p.effects || []).forEach((e) => {
        const row = make('div', 'axis-row');
        row.append(make('span', null, e.outcome));
        if (bare(e.state) === 'computed') {
          // The bar shows |elasticity| capped at 1, and the number carries the
          // sign — a bar cannot show a negative without inventing a direction.
          const track = make('div', 'axis-row__track');
          const fill = make('div', 'axis-row__fill');
          fill.style.width = `${Math.round(Math.min(1, Math.abs(e.value)) * 100)}%`;
          track.append(fill);
          row.append(track);
          row.append(make('span', 'axis-row__value',
            (e.value >= 0 ? '+' : '') + e.value.toFixed(3)));
        } else {
          row.append(make('div', 'axis-row__unscored'));
          row.append(make('span', 'axis-row__value',
            `未定義 (${bare(e.reason)})`));
        }
        li.append(row);
      });
      return li;
    };

    const renderSensitivity = (sens) => {
      const list = $('#loops-sensitivity'); if (!list) return;
      const note = $('#loops-sensitivity-note');
      list.replaceChildren();
      if (!sens || bare(sens.state) !== 'computed') {
        list.append(make('li', 'empty-state',
          sens?.reason ? `感度を計算できません: ${sens.reason}` : 'モデルがありません。'));
        if (note) note.textContent = 'モデルを再実行して測ります（介入の実行しやすさを点数化しません）。';
        return;
      }
      if (note) note.textContent = sens.note || '';
      // Ordered by the largest absolute effect on any outcome, so the constant
      // that moves the system most is first. Disconnected constants sort last
      // by construction, since their effect is 0.
      const strength = (p) => Math.max(0, ...(p.effects || [])
        .filter((e) => bare(e.state) === 'computed')
        .map((e) => Math.abs(e.value)));
      [...(sens.parameters || [])].sort((a, b) => strength(b) - strength(a))
        .forEach((p) => list.append(sensitivityRow(p)));
      if (!(sens.parameters || []).length) {
        list.append(make('li', 'empty-state', '定数がありません。'));
      }
    };

    const loadLoops = (businessId) => {
      if (!businessId) { renderLoops(null); return Promise.resolve(false); }
      return fetch(`/api/business/${encodeURIComponent(businessId)}/loops`)
        .then((r) => r.ok ? r.json() : null)
        .then((d) => { renderLoops(d); return Boolean(d); })
        .catch(() => {
          const src = $('#loops-source');
          if (src) src.textContent = 'loops を読み込めません。';
          return false;
        });
    };

    const fillLoopsBusinesses = (rows) => {
      const sel = $('#loops-business'); if (!sel) return;
      const keep = sel.value || selectedBusinessId || '';
      sel.replaceChildren(make('option', null, '事業を選択…'));
      sel.firstChild.value = '';
      (rows || []).forEach((b) => {
        const o = make('option', null, b.name || b.slug);
        o.value = b.id;
        sel.append(o);
      });
      const next = (rows || []).some((b) => b.id === keep) ? keep : '';
      sel.value = next;
      if (next && next !== loopsData?.business?.id) loadLoops(next);
      if (!next) renderLoops(null);
    };

    $('#loops-business')?.addEventListener('change', (e) => {
      selectedBusinessId = e.currentTarget.value || selectedBusinessId;
      loadLoops(e.currentTarget.value);
    });

    $('#loops-table-toggle')?.addEventListener('click', (e) => {
      const table = $('#loops-table'), grid = $('#loops-series');
      const showTable = table.hidden;
      table.hidden = !showTable;
      if (grid) grid.hidden = showTable;
      e.currentTarget.setAttribute('aria-pressed', String(showTable));
      e.currentTarget.textContent = showTable ? 'グラフで見る' : '表で見る';
    });

    // ── Repos（事業の実装と成熟度）─────────────────────────────────────
    //
    // An UNSCORED axis gets no bar. A zero-width bar and a score of 0.0 look
    // identical, and :maturity/stage-score is nil for 2,732 of 3,899 repos —
    // drawing those as empty bars would put 70% of the fleet at the bottom of an
    // axis nobody assessed them on.
    const axisRow = (a) => {
      const row = make('div', 'axis-row');
      row.append(make('span', null, a.label || bare(a.axis)));
      if (a['scored?'] && typeof a.score === 'number') {
        const track = make('div', 'axis-row__track');
        const fill = make('div', 'axis-row__fill');
        fill.style.width = `${Math.round(Math.max(0, Math.min(1, a.score)) * 100)}%`;
        track.append(fill);
        row.append(track);
        // The method rides beside the number: an :impl score is a
        // size-and-scaffold heuristic and a :stage score is a parsed marker,
        // and two identical-looking decimals hide which is which.
        row.append(make('span', 'axis-row__value',
          a.score.toFixed(2) + (a.method ? ` (${bare(a.method)})` : '')));
      } else {
        row.append(make('div', 'axis-row__unscored'));
        row.append(make('span', 'axis-row__value', '未評価'));
      }
      row.title = a.detail || '';
      return row;
    };

    const repoRow = (r) => {
      const li = make('li', 'req-row');
      const head = make('div', 'req-row__head');
      head.append(make('strong', null, r.path || r.repo || '(path 不明)'));
      const chip = make('span', 'req-row__state',
        typeof r.composite === 'number' ? r.composite.toFixed(2) : '未評価');
      chip.dataset.tone = typeof r.composite === 'number' ? 'ok' : 'note';
      head.append(chip);
      li.append(head);
      const bits = [bare(r.source) === 'adoptions' ? '参与' : '宣言',
                    r.kind ? bare(r.kind) : null,
                    r.stage ? `stage ${bare(r.stage)}` : null,
                    r.traits || null].filter(Boolean);
      li.append(make('p', 'req-row__detail', bits.join(' · ')));
      if (r['kind-evidence']) li.append(make('p', 'req-row__caveat', r['kind-evidence']));
      if (r.detail) li.append(make('p', 'req-row__caveat', r.detail));
      (r.axes || []).forEach((a) => li.append(axisRow(a)));
      return li;
    };

    const renderRepos = (d) => {
      const rows = (d && d.repos) || [];
      const plane = (d && d.plane) || {};
      const roll = (d && d['roll-up']) || {};
      const src = $('#repos-source');
      if (src) src.textContent = d && d.business
        ? (d.business.name || d.business.slug) : '事業を選択してください。';
      const note = $('#repos-plane');
      if (note) {
        note.replaceChildren();
        if (bare(plane.state) === 'resolved') {
          note.append(make('strong', null, 'generated plane: '));
          note.append(document.createTextNode(
            `${d.sources?.taxonomy || ''} + ${d.sources?.maturity || ''}`));
        } else {
          note.append(make('strong', null, bare(plane.state) + ' '));
          note.append(document.createTextNode(plane.detail || ''));
        }
      }
      const stats = $('#repos-stats');
      if (stats) {
        stats.replaceChildren(
          statTile('repo', roll.repos ?? 0),
          statTile('評価済み', roll.scored ?? 0),
          // Shown beside the mean on purpose: a mean over 3 of 12 is a different
          // claim from a mean over 12.
          statTile('未評価', roll.unscored ?? 0),
          statTile('composite 平均',
            typeof roll['mean-composite'] === 'number'
              ? roll['mean-composite'].toFixed(2) : '—'));
      }
      const list = $('#repos-list');
      if (list) {
        list.replaceChildren();
        rows.forEach((r) => list.append(repoRow(r)));
        if (!rows.length) {
          list.append(make('li', 'empty-state',
            'この事業に repo / 参与が紐付いていません。Portfolio で紐付けてください。'));
        }
      }
      const meta = $('#repos-meta');
      if (meta) meta.textContent = rows.length ? `${rows.length} repo` : '';
      const badge = $('#repos-count');
      if (badge) badge.textContent = rows.length || '—';
    };

    const loadRepos = (businessId) => {
      if (!businessId) { renderRepos(null); return Promise.resolve(false); }
      return fetch(`/api/business/${encodeURIComponent(businessId)}/repos`)
        .then((r) => r.ok ? r.json() : null)
        .then((d) => { reposBusinessId = businessId; renderRepos(d); return Boolean(d); })
        .catch(() => {
          const s = $('#repos-source');
          if (s) s.textContent = 'repo を読み込めません。';
          return false;
        });
    };

    // ── Metrics（実測と、その古さ）─────────────────────────────────────
    //
    // Freshness leads. A 28-day-old file's numbers are not current numbers, and
    // one of the twelve real metrics files is exactly that.
    const freshnessLabel = {fresh:'新しい', stale:'古い', undated:'日付なし'};
    const kv = (dl, pairs) => {
      dl.replaceChildren();
      pairs.forEach(([k, v]) => {
        if (v === null || v === undefined || v === '') return;
        dl.append(make('dt', null, k), make('dd', null, String(v)));
      });
    };

    const renderMetrics = (d) => {
      const state = bare(d && d.state);
      const f = (d && d.freshness) || {};
      const src = $('#metrics-source');
      if (src) src.textContent = d && d.business
        ? `${d.business.name || d.business.slug}`
          + (d.business.canvas ? ` → ${bare(d.business.canvas)}` : '')
        : '事業を選択してください。';

      const note = $('#metrics-freshness');
      if (note) {
        note.replaceChildren();
        if (state === 'resolved') {
          const fs = bare(f.state);
          note.append(make('strong', null,
            `測定 ${freshnessLabel[fs] || fs}: ${f['as-of'] || '不明'} `));
          if (typeof f['age-days'] === 'number') {
            note.append(document.createTextNode(
              `（${f['age-days'].toFixed(1)} 日前 / 上限 ${f['max-age-days']} 日）`));
          }
          if (fs === 'stale') {
            note.append(make('strong', null,
              ' この数値は現在の値ではありません。emitter を再実行してください。'));
          }
          if (fs === 'undated') note.append(document.createTextNode(' ' + (f.detail || '')));
        } else {
          note.append(make('strong', null, state + ' '));
          note.append(document.createTextNode((d && d.detail) || ''));
        }
      }

      const t = (d && d.traffic) || {};
      kv($('#metrics-traffic'), [
        ['zone', t.zone],
        ['requests / 7d', t['requests-7d']],
        ['pageviews / 7d', t['pageviews-7d']],
        ['uniques / 7d', t['uniques-7d']],
        [`probe 4xx (${t.window || ''})`, t['probe-4xx-pct'] != null ? t['probe-4xx-pct'] + '%' : null],
        [`5xx (${t.window || ''})`, t['error-5xx-pct'] != null ? t['error-5xx-pct'] + '%' : null],
        ['health', d && d['health-status']]
      ]);
      const cav = $('#metrics-traffic-caveat');
      // The caveat comes from the server, which decided it from the same numbers
      // it sent — the renderer does not re-derive a threshold of its own.
      if (cav) cav.textContent = t.caveat || '';

      const sig = $('#metrics-signal');
      if (sig) sig.textContent = (d && d.signal) || '—';
      const tp = $('#metrics-top-paths');
      if (tp) tp.textContent = (d && d['top-paths']) || '';
      const so = $('#metrics-sources');
      if (so) so.textContent = (d && d.sources || []).length
        ? `sources: ${d.sources.join(' · ')}${d.note ? ' — ' + d.note : ''}` : '';

      kv($('#metrics-specific'),
         ((d && d['product-specific']) || []).map((e) => [e.key, e.value]));
      const meta = $('#metrics-meta');
      if (meta) meta.textContent = state === 'resolved' ? (d.source || '') : '';
      const badge = $('#metrics-count');
      if (badge) badge.textContent = state === 'resolved' ? bare(f.state).slice(0, 1).toUpperCase() : '—';
    };

    const loadMetrics = (businessId) => {
      if (!businessId) { renderMetrics(null); return Promise.resolve(false); }
      return fetch(`/api/business/${encodeURIComponent(businessId)}/metrics`)
        .then((r) => r.ok ? r.json() : null)
        .then((d) => { metricsBusinessId = businessId; renderMetrics(d); return Boolean(d); })
        .catch(() => {
          const s = $('#metrics-source');
          if (s) s.textContent = 'metrics を読み込めません。';
          return false;
        });
    };

    // One filler per pane, all fed from the portfolio, so no two panes can
    // disagree about which businesses exist.
    const fillBusinessSelect = (id, rows, load, currentId) => {
      const sel = $('#' + id); if (!sel) return;
      const keep = sel.value || selectedBusinessId || '';
      sel.replaceChildren(make('option', null, '事業を選択…'));
      sel.firstChild.value = '';
      (rows || []).forEach((b) => {
        const o = make('option', null, b.name || b.slug);
        o.value = b.id;
        sel.append(o);
      });
      const next = (rows || []).some((b) => b.id === keep) ? keep : '';
      sel.value = next;
      if (next && next !== currentId) load(next); else if (!next) load(null);
    };

    ['repos-business', 'metrics-business'].forEach((id) => {
      $('#' + id)?.addEventListener('change', (e) => {
        selectedBusinessId = e.currentTarget.value || selectedBusinessId;
        (id === 'repos-business' ? loadRepos : loadMetrics)(e.currentTarget.value);
      });
    });

    $('#portfolio-bind-form')?.addEventListener('submit', (e) => {
      e.preventDefault();
      const status = $('#portfolio-bind-status');
      if (!selectedBusinessId) { if (status) status.textContent = '事業を選んでください。'; return; }
      fetch(`/api/business/${encodeURIComponent(selectedBusinessId)}/bind`, {
        method: 'POST', headers: identityHeaders(),
        body: JSON.stringify({
          canvas: ($('#portfolio-bind-canvas')?.value || '').trim(),
          model: ($('#portfolio-bind-model')?.value || '').trim(),
          leverage: ($('#portfolio-bind-leverage')?.value || '').trim(),
          lei: ($('#portfolio-bind-lei')?.value || '').trim(),
          adoptions: splitList('portfolio-bind-adoptions'),
          repos: splitList('portfolio-bind-repos')
        })
      }).then((r) => {
        if (!r.ok) { if (status) status.textContent = `保存できません (${r.status})`; return; }
        if (status) status.textContent = '紐付けを保存しました。';
        return loadPortfolio();
      }).catch(() => { if (status) status.textContent = '保存できません。'; });
    });

    $('#fleet-filter-form')?.addEventListener('submit', (e) => { e.preventDefault(); searchFleet(); });
    ['fleet-role','fleet-maturity','fleet-iso3166','fleet-callable'].forEach((id) => {
      $('#' + id)?.addEventListener('change', searchFleet);
    });
    $('#fleet-text')?.addEventListener('input', () => {
      clearTimeout(window.__fleetDebounce);
      window.__fleetDebounce = setTimeout(searchFleet, 250);
    });

    $('#operator-profile-form')?.addEventListener('submit', (e) => {
      e.preventDefault();
      const kind = ($('#operator-licence-kind')?.value || '').trim();
      const by = ($('#operator-licence-by')?.value || '').trim();
      // A licence with no attester is not an attestation, so it is not sent.
      const licences = (kind && by) ? [{
        'licence/kind': kind,
        'licence/authority': ($('#operator-licence-authority')?.value || '').trim(),
        'licence/number': ($('#operator-licence-number')?.value || '').trim(),
        'licence/attested-by': by,
        'licence/attested-on': new Date().toISOString().slice(0, 10)
      }] : [];
      fetch('/api/operator/profile', {
        method: 'POST', headers: identityHeaders(),
        body: JSON.stringify({
          name: ($('#operator-name')?.value || '').trim(),
          isic: splitList('operator-isic'),
          isco: splitList('operator-isco'),
          iso3166: splitList('operator-iso3166'),
          technologies: splitList('operator-tech'),
          licences: licences
        })
      }).then(() => loadOperator())
        .catch(() => { $('#operator-source').textContent = 'プロファイルを保存できません。'; });
    });

    // Debounced, because this asks the server now: the Drive's search
    // filters a list it already has and can run on every keystroke, and
    // this one would be a request per character.
    let inboxSearchTimer = null;
    $('#inbox-search').addEventListener('input', () => {
      if (inboxSearchTimer) clearTimeout(inboxSearchTimer);
      inboxSearchTimer = setTimeout(() => { selectedInbox = null; loadInbox(); }, 200);
    });
    // The box filters the list as you type, which is instant and local, and
    // separately asks the server what is inside the documents, which is not.
    // Debounced because the server read is every readable document's bytes —
    // a request per keystroke would be a scan per keystroke.
    let contentSearchTimer = null;
    const runContentSearch = async (query) => {
      const panel = $('#drive-found');
      if (!query) { panel.hidden = true; panel.replaceChildren(); return; }
      try {
        const request = await fetch(
          `/api/workspace/drive/search?q=${encodeURIComponent(query)}`);
        const data = await request.json();
        if (!request.ok) return;
        const inside = (data.results || []).filter((r) => r.where === 'content');
        panel.replaceChildren();
        if (!inside.length) { panel.hidden = true; return; }
        panel.hidden = false;
        panel.append(make('h3', 'sharing__title', `本文に一致 ${inside.length} 件`));
        const list = make('ul', 'sharing__list');
        inside.forEach((hit) => {
          const row = make('li', 'sharing__entry');
          const open = make('button', 'tool-button', `${hit.name}（${hit.label}）`);
          open.type = 'button';
          open.addEventListener('click', () => {
            const item = (driveData.items || []).find((i) => i.id === hit.id);
            if (!item) return;
            driveEditor = closedEditor(item.id);
            selectedDrive = item;
            $('#drive-search').value = '';
            renderDrive(driveData);
          });
          row.append(open, make('span', 'surface-note', hit.snippet));
          list.append(row);
        });
        panel.append(list);
      } catch (error) { panel.hidden = true; }
    };
    $('#drive-search').addEventListener('input', () => {
      renderDrive(driveData);
      const query = ($('#drive-search').value || '').trim();
      window.clearTimeout(contentSearchTimer);
      contentSearchTimer = window.setTimeout(() => runContentSearch(query), 300);
    });
    $('#drive-more').addEventListener('click', loadMoreDrive);
    $('#drive-trash-empty').addEventListener('click', () => driveAction(
      '/api/workspace/drive/trash/empty', {}, 'ゴミ箱を空にしました。'));
    let identityState = null;
    // The same value the server sees as the actor: a user record is stored
    // under its own id, so `user.id` is `(:user-id session)`.
    const currentUserId = () => identityState?.user?.id;
    const connectorMarks = {github:'GH', google:'G', microsoft:'M'};
    const identityHeaders = () => ({
      'Content-Type':'application/json',
      'X-CLOUD-ITONAMI-CSRF':identityState?.csrf || ''
    });
    const b64urlToBytes = (value) => {
      const base64 = value.replace(/-/g, '+').replace(/_/g, '/')
        .padEnd(Math.ceil(value.length / 4) * 4, '=');
      return Uint8Array.from(atob(base64), (character) => character.charCodeAt(0));
    };
    const bytesToB64url = (value) => {
      if (value === null || value === undefined) return null;
      const bytes = new Uint8Array(value);
      let binary = '';
      bytes.forEach((byte) => { binary += String.fromCharCode(byte); });
      return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
    };
    const creationOptions = (payload) => {
      const options = payload.options;
      options.publicKey.challenge = b64urlToBytes(options.publicKey.challenge);
      options.publicKey.user.id = b64urlToBytes(options.publicKey.user.id);
      (options.publicKey.excludeCredentials || []).forEach((item) => {
        item.id = b64urlToBytes(item.id);
      });
      return options;
    };
    const assertionOptions = (payload) => {
      const options = payload.options;
      options.publicKey.challenge = b64urlToBytes(options.publicKey.challenge);
      (options.publicKey.allowCredentials || []).forEach((item) => {
        item.id = b64urlToBytes(item.id);
      });
      return options;
    };
    const credentialJSON = (credential) => {
      const response = credential.response;
      const result = {
        id:credential.id, rawId:bytesToB64url(credential.rawId),
        type:credential.type,
        authenticatorAttachment:credential.authenticatorAttachment || null,
        clientExtensionResults:credential.getClientExtensionResults(),
        response:{clientDataJSON:bytesToB64url(response.clientDataJSON)}
      };
      if (response.attestationObject) {
        result.response.attestationObject = bytesToB64url(response.attestationObject);
        result.response.transports = response.getTransports ? response.getTransports() : [];
      } else {
        result.response.authenticatorData = bytesToB64url(response.authenticatorData);
        result.response.signature = bytesToB64url(response.signature);
        result.response.userHandle = bytesToB64url(response.userHandle);
      }
      return result;
    };
    const refreshIdentityForWrite = async () => {
      // A hosted sign-in, session renewal, or resident-server restart can
      // replace the cookie while this single-page document stays open.  The
      // CSRF token belongs to that cookie's session, so the value captured by
      // the page must move with it.  This same-origin read remains protected
      // by the browser's origin boundary; it does not weaken the server check.
      const request = await fetch('/api/identity', {cache:'no-store'});
      const data = await request.json();
      if (!request.ok || typeof data.csrf !== 'string' || !data.csrf) {
        throw new Error('セッションを更新できませんでした。もう一度サインインしてください。');
      }
      identityState = data;
    };
    const writeJSON = async (path, method, body={}, authenticated=false) => {
      if (authenticated && !identityState?.csrf) await refreshIdentityForWrite();
      const send = async () => {
        const request = await fetch(path, {
          method,
          headers:authenticated ? identityHeaders() : {'Content-Type':'application/json'},
          body:JSON.stringify(body)
        });
        return {request, data:await request.json()};
      };
      let {request, data} = await send();
      // Retry once, and only for the server's exact stale-CSRF answer.  Other
      // 403s (origin, authority, policy) must remain refusals rather than being
      // disguised as session refreshes.
      if (authenticated && request.status === 403
          && data?.error?.type === 'invalid-csrf') {
        await refreshIdentityForWrite();
        ({request, data} = await send());
      }
      if (!request.ok) throw new Error(data?.error?.message || '認証要求を完了できませんでした。');
      return data;
    };
    const postJSON = (path, body={}, authenticated=false) =>
      writeJSON(path, 'POST', body, authenticated);
    const memoryControls = {
      local:$('#memory-local-toggle'), screen:$('#memory-screen-toggle'),
      tool:$('#memory-tool-toggle')
    };
    const renderChronicle = (data) => {
      const settings = data.settings || {};
      memoryControls.local.checked = Boolean(settings['local-memory-enabled?']);
      memoryControls.screen.checked = Boolean(settings['screen-context-enabled?']);
      memoryControls.tool.checked = Boolean(settings['tool-memory-enabled?']);
      const permission = data.permission?.['screen-recording'] || 'unknown';
      const permissionNode = $('#memory-screen-permission');
      permissionNode.dataset.state = permission;
      permissionNode.textContent = permission === 'granted'
        ? 'ステータス: 画面収録は許可されています'
        : permission === 'required'
          ? 'ステータス: 画面収録が許可されていません（設定を開く）'
          : 'ステータス: この環境では画面収録権限を確認できません';
      const counts = data.counts || {};
      $('#memory-counts').replaceChildren(
        make('span', 'memory-stat', `画面 ${counts.frames || 0} 件`),
        make('span', 'memory-stat', `記憶 ${counts.memories || 0} 件`),
        make('span', 'memory-stat', data.runtime?.['ocr-available?']
          ? 'OCR 利用可能' : 'OCR 未導入'));
      const recent = $('#memory-recent-list');
      recent.replaceChildren();
      const entries = [
        ...(data['recent-memories'] || []).map((item) => ({
          at:item.at, text:`${item.source || 'chat'} — ${item.summary || ''}`})),
        ...(data['recent-frames'] || []).map((item) => ({
          at:item['captured-at'], text:`${item.application || '画面'} — ${item['text-preview'] || 'OCR 文字列なし'}`}))
      ].sort((a, b) => String(b.at || '').localeCompare(String(a.at || ''))).slice(0, 8);
      if (!entries.length) recent.append(make('li', null, 'まだ記憶はありません。'));
      entries.forEach((entry) => recent.append(
        make('li', null, `${formatDate(entry.at)}  ${entry.text}`)));
      const lastError = data['last-error'];
      $('#memory-status').textContent = lastError
        ? `直近の取得エラー: ${lastError.message}` : '';
    };
    const loadChronicle = async () => {
      const response = await fetch('/api/chronicle', {headers:identityHeaders()});
      const data = await response.json();
      if (!response.ok) throw new Error(data?.error?.message || 'メモリ設定を取得できませんでした。');
      renderChronicle(data);
      return data;
    };
    const saveChronicleSettings = async () => {
      $('#memory-status').textContent = '設定を保存しています…';
      const data = await postJSON('/api/chronicle/settings', {
        'local-memory-enabled?':memoryControls.local.checked,
        'screen-context-enabled?':memoryControls.screen.checked,
        'tool-memory-enabled?':memoryControls.tool.checked
      }, true);
      renderChronicle(data);
      $('#memory-status').textContent = 'この端末のメモリ設定を保存しました。';
    };
    Object.values(memoryControls).forEach((control) =>
      control.addEventListener('change', () => saveChronicleSettings().catch((error) => {
        $('#memory-status').textContent = error.message;
        loadChronicle().catch(() => {});
      })));
    $('#memory-open-settings').addEventListener('click', async () => {
      try {
        await postJSON('/api/chronicle/open-settings', {}, true);
        $('#memory-status').textContent = 'システム設定を開きました。許可後にこの画面へ戻ってください。';
      } catch (error) { $('#memory-status').textContent = error.message; }
    });
    $('#memory-capture-button').addEventListener('click', async () => {
      const button = $('#memory-capture-button');
      button.disabled = true;
      try {
        $('#memory-status').textContent = '画面を取得して OCR しています…';
        renderChronicle(await postJSON('/api/chronicle/capture', {}, true));
        $('#memory-status').textContent = '画面コンテキストを端末内へ保存しました。';
      } catch (error) { $('#memory-status').textContent = error.message; }
      finally { button.disabled = false; }
    });
    $('#memory-delete-button').addEventListener('click', async () => {
      if (!window.confirm('画面、OCR、派生メモリをこの端末から削除します。チャット履歴は残ります。')) return;
      try {
        await postJSON('/api/chronicle/delete', {}, true);
        await loadChronicle();
        $('#memory-status').textContent = 'ローカルメモリを削除しました。';
      } catch (error) { $('#memory-status').textContent = error.message; }
    });
    const requireWebAuthn = () => {
      if (!window.PublicKeyCredential || !navigator.credentials) {
        throw new Error('このブラウザは Passkey / WebAuthn に対応していません。');
      }
    };
    const registerCurrentPasskey = async () => {
      requireWebAuthn();
      const started = await postJSON('/api/passkeys/register/start', {}, true);
      const credential = await navigator.credentials.create(creationOptions(started));
      await postJSON('/api/passkeys/register/finish', {
        'transaction-id':started['transaction-id'],
        credential:credentialJSON(credential)
      }, true);
      await loadIdentity();
      $('#identity-status').textContent = 'Passkey を登録しました。';
    };
    const renderMembers = (organization) => {
      const list = $('#member-list'); list.replaceChildren();
      (organization?.users || []).forEach((user) => {
        const row = make('li');
        const copy = make('div');
        copy.append(make('strong', null, user['display-name']),
          make('span', null, user.email));
        row.append(copy, make('span', 'state-chip', user.role));
        list.append(row);
      });
    };
    const renderConnectors = (data) => {
      const list = $('#connector-list'); list.replaceChildren();
      const connections = new Map((data.connections || [])
        .map((connection) => [connection.provider, connection]));
      (data.providers || []).forEach((provider) => {
        const connection = connections.get(provider.id);
        const card = make('article', 'connector-card');
        card.append(make('div', 'connector-logo', connectorMarks[provider.id] || '•'));
        const copy = make('div');
        copy.append(make('h3', null, provider.name));
        const description = connection
          ? `${connection['display-name'] || connection.email || '接続済み'} · ${connection.scopes?.length || 0} 権限`
          : provider['configured?']
            ? `${provider.scopes.length} 個の読み取り権限を確認して接続`
            : 'OAuth クライアント設定が必要です';
        copy.append(make('p', null, description));
        const button = make('button', 'tool-button',
          connection ? '再接続' : provider['configured?'] ? '接続する' : '未設定');
        button.type = 'button';
        button.disabled = !provider['configured?'];
        button.addEventListener('click', async () => {
          button.disabled = true; button.textContent = '接続を準備中…';
          try {
            const request = await fetch(`/api/connections/${provider.id}/start`, {
              method:'POST', headers:identityHeaders(), body:'{}'
            });
            const result = await request.json();
            if (!request.ok) throw new Error(result?.error?.message || '接続を開始できませんでした。');
            location.assign(result.url);
          } catch (error) {
            button.disabled = false; button.textContent = '接続する';
            $('#identity-status').textContent = error.message;
          }
        });
        card.append(copy, button); list.append(card);
      });
    };
    const authProviderLabels = {
      google:'Google', microsoft:'Microsoft', github:'GitHub',
      'itonami-cloud':'auth.itonami.cloud'
    };
    // The native window is a webview, and an authorization request must not
    // open inside one — RFC 8252 says so, and this application has its own
    // evidence: the embedded webview cannot do WebAuthn, which is how people
    // actually sign in here. `?surface=native` is set by app.kotoba.edn, the
    // one file that declares the native surface.
    const nativeSurface = () =>
      new URLSearchParams(location.search).get('surface') === 'native';
    const claimSession = async (claim, deadline) => {
      // Poll until the person finishes in the system browser. Every refusal
      // comes back as the same {ready?: false} — an early poll, a wrong token
      // and a spent one are one answer — so there is nothing to branch on
      // here but readiness and the clock.
      while (Date.now() < deadline) {
        const request = await fetch('/api/auth/itonami/handoff', {
          method:'POST', headers:{'Content-Type':'application/json'},
          body: JSON.stringify({handoff: claim})
        });
        const result = await request.json().catch(() => ({}));
        if (result?.['ready?']) return result;
        await new Promise((resolve) => setTimeout(resolve, 1500));
      }
      return null;
    };
    const startCentralAuth = async (button) => {
      button.disabled = true;
      const previous = button.textContent;
      button.textContent = '準備中…';
      const native = nativeSurface();
      try {
        const headers = identityState?.['authenticated?']
          ? identityHeaders() : {'Content-Type':'application/json'};
        const request = await fetch('/api/auth/itonami/start', {
          method:'POST', headers, body: JSON.stringify({handoff: native})
        });
        const result = await request.json();
        if (!request.ok) {
          throw new Error(result?.error?.message || '中央認証を開始できませんでした。');
        }
        if (!native) {
          location.assign(result.url);
          return;
        }
        // The server opened the URL in the default browser. If it could not,
        // say so and show the link rather than leaving a window that looks
        // like it is doing something.
        button.textContent = 'ブラウザで認証中…';
        $('#identity-status').textContent = result['opened-externally?']
          ? 'ブラウザでサインインを続けてください。完了するとこの画面に戻ります。'
          : 'ブラウザを開けませんでした。次のURLを手動で開いてください: ' + result.url;
        const claimed = await claimSession(result.handoff, Date.now() + 300000);
        if (!claimed) {
          throw new Error('サインインが完了しませんでした。もう一度お試しください。');
        }
        await loadIdentity();
        $('#identity-status').textContent = 'サインインしました。';
      } catch (error) {
        button.disabled = false;
        button.textContent = previous;
        $('#identity-status').textContent = error.message;
      }
    };
    const startSso = async (provider, mode, button) => {
      button.disabled = true;
      const previous = button.textContent;
      button.textContent = '準備中…';
      try {
        const headers = mode === 'link'
          ? identityHeaders() : {'Content-Type':'application/json'};
        const request = await fetch(`/api/auth/sso/${provider}/start`, {
          method:'POST', headers, body:JSON.stringify({mode})
        });
        const result = await request.json();
        if (!request.ok) throw new Error(result?.error?.message || 'SSOを開始できませんでした。');
        location.assign(result.url);
      } catch (error) {
        button.disabled = false;
        button.textContent = previous;
        $('#identity-status').textContent = error.message;
      }
    };
    const renderAuthMethods = (data) => {
      const methods = data['auth-methods'] || {};
      // Only providers this deployment can actually start. An unconfigured
      // provider is not an entrance, and drawing it as a disabled button said
      // so only in a `title` tooltip — invisible on a touch screen, and
      // announced as a button by a screen reader.
      const providers = (methods.sso || []).filter((p) => p['configured?']);
      const centralConfigured = Boolean(methods.central?.['configured?']);
      $('#itonami-cloud-signin-card').hidden = !centralConfigured;
      const enrol = $('#itonami-enrolment-link');
      if (enrol) {
        const url = methods.central?.['enrolment-url'];
        if (url) enrol.setAttribute('href', url);
        enrol.hidden = !url || Boolean(data['authenticated?']);
      }
      const signin = $('#sso-signin-list');
      signin.replaceChildren();
      providers.forEach((provider) => {
        const label = provider.name || authProviderLabels[provider.id] || provider.id;
        const button = make('button', 'tool-button', `${label}で続ける`);
        button.type = 'button';
        button.addEventListener('click', () => startSso(provider.id, 'authenticate', button));
        signin.append(button);
      });
      $('#sso-signin-card').hidden = !providers.length;
      if (!data['authenticated?']) return;
      const session = data.session || {};
      const provider = session['authn-provider'];
      const issuedVia = session['issued-via'];
      $('#current-auth-method').textContent = provider
        ? `${authProviderLabels[provider] || provider}でサインイン中`
        : issuedVia === 'email-magic-link' ? 'Emailでサインイン中'
        : issuedVia === 'passkey' ? 'Passkeyでサインイン中'
        : 'サインイン済み';
      const linked = $('#linked-auth-methods');
      linked.replaceChildren();
      const identities = data['login-identities'] || [];
      if (!identities.length) {
        linked.append(make('li', null, '接続済みSSO / Emailはありません。'));
      } else {
        identities.forEach((identity) => {
          const label = authProviderLabels[identity.provider] ||
            (identity.provider === 'email' ? 'Email' : identity.provider);
          const row = make('li');
          row.append(make('span', null,
            `${label} · ${identity.email || identity['display-name'] || '接続済み'}`));
          const unlink = make('button', 'tool-button', '解除');
          unlink.type = 'button';
          unlink.addEventListener('click', async () => {
            if (!window.confirm(`${label}の接続を解除しますか？`)) return;
            unlink.disabled = true;
            try {
              await postJSON('/api/auth/identities/unlink', {
                provider:identity.provider, subject:identity.subject
              }, true);
              await loadIdentity();
              $('#identity-status').textContent = `${label}の接続を解除しました。`;
            } catch (error) {
              unlink.disabled = false;
              $('#identity-status').textContent = error.message;
            }
          });
          row.append(unlink);
          linked.append(row);
        });
      }
      const linkedProviders = new Set(identities.map((identity) => identity.provider));
      $('#itonami-cloud-link').hidden = !centralConfigured ||
        linkedProviders.has('itonami-cloud');
      const links = $('#sso-link-list');
      links.replaceChildren();
      providers.filter((item) => item['configured?'] && !linkedProviders.has(item.id))
        .forEach((item) => {
          const label = item.name || authProviderLabels[item.id] || item.id;
          const button = make('button', 'tool-button', `${label}を接続`);
          button.type = 'button';
          button.addEventListener('click', () => startSso(item.id, 'link', button));
          links.append(button);
        });
    };
    const renderSessions = async () => {
      const list = $('#auth-session-list');
      try {
        const request = await fetch('/api/auth/sessions');
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || 'セッションを確認できません。');
        list.replaceChildren();
        (data.sessions || []).forEach((session) => {
          const row = make('li');
          const provider = session['authn-provider'];
          const method = provider ? (authProviderLabels[provider] || provider)
            : session['issued-via'] === 'email-magic-link' ? 'Email'
            : session['issued-via'] === 'passkey' ? 'Passkey' : session.kind;
          row.append(make('span', null,
            `${method}${session['current?'] ? ' · この端末' : ''} · ${session['created-at'] || '開始時刻不明'}`));
          if (!session['current?']) {
            const revoke = make('button', 'tool-button', 'ログアウト');
            revoke.type = 'button';
            revoke.addEventListener('click', async () => {
              revoke.disabled = true;
              try {
                await postJSON('/api/auth/sessions/revoke', {'session-id':session.id}, true);
                await renderSessions();
              } catch (error) {
                revoke.disabled = false;
                $('#identity-status').textContent = error.message;
              }
            });
            row.append(revoke);
          }
          list.append(row);
        });
        if (!list.children.length) list.append(make('li', null, '有効なセッションはありません。'));
      } catch (error) {
        list.replaceChildren(make('li', null, error.message));
      }
    };
    let activeDomainVerification = null;
    const renderDomainVerifications = (data) => {
      const records = data.verifications || [];
      activeDomainVerification = records.length ? records[records.length - 1] : null;
      const state = $('#domain-verification-state');
      const record = $('#domain-verification-record');
      const activation = $('#domain-verification-activation');
      if (!activeDomainVerification) {
        state.textContent = '確認済みの会社ドメインはありません。';
        record.hidden = true;
        activation.hidden = true;
        return;
      }
      const verification = activeDomainVerification;
      // Four states, not a boolean (ADR-0043). `claimed` is the one worth
      // spelling out: the proof succeeded and the tenant is still NOT named by
      // the domain, which a card that said "確認済み" here would hide.
      const status = verification.status;
      const domain = verification.domain;
      const messages = {
        pending: `${domain} のTXTレコードをDNSへ追加してから「DNSを確認」を押してください。`,
        claimed: `${domain} の所有権は確認できました。まだこのOrganizationの名前ではありません — DNSをこの deployment に向けてから「有効化」を押してください。`,
        live: `${domain} はこのOrganizationの名前です。`,
        lapsed: `${domain} は応答しなくなったため、名前を管理ドメインへ戻しました。発行済みの証明書は取り消していません。`,
      };
      state.textContent = messages[status] || `${domain}: ${status}`;
      record.hidden = status !== 'pending';
      activation.hidden = status === 'pending';
      $('#domain-verification-record-name').textContent = verification['record-name'] || '—';
      $('#domain-verification-record-value').textContent = verification['record-value'] || '—';
      $('#domain-verification-expiry').textContent = verification['expires-at']
        ? `有効期限: ${formatDate(verification['expires-at'])}` : '—';
      $('#domain-verification-activation-url').textContent =
        verification['activation-url'] || '—';
      // The measurement, not just the verdict. Which of DNS, TLS and routing is
      // wrong is written in this sentence, and an owner told only "失敗" has to
      // guess at all three.
      const probe = verification.probe || {};
      $('#domain-verification-probe').textContent = probe.error
        ? `前回の確認: ${probe.error}`
        : (probe.at ? `前回の確認: ${formatDate(probe.at)} に応答を確認しました。` : '—');
      if (domain) $('#company-domain').value = domain;
    };
    const loadDomainVerifications = async () => {
      const state = $('#domain-verification-state');
      try {
        const request = await fetch('/api/identity/domain-verifications');
        const data = await request.json();
        if (!request.ok) {
          throw new Error(data?.error?.message || '会社ドメインを読み込めませんでした。');
        }
        renderDomainVerifications(data);
      } catch (error) {
        state.textContent = error.message;
      }
    };

    // Whether a Passkey ceremony can start here at all. Asked before the
    // button is offered rather than when it is clicked: on a browser without
    // WebAuthn the only entrance on this screen looks live and is not.
    const passkeySupported = () =>
      Boolean(window.PublicKeyCredential && navigator.credentials);
    // The entrances besides Passkey that this deployment has configured. One
    // reading, so the notice, the lead and the cards cannot disagree.
    const otherSigninMethods = (data) => [
      data['auth-methods']?.central?.['configured?'] ? 'auth.itonami.cloud' : null,
      data['email-login-configured?'] ? 'Email' : null,
      (data['auth-methods']?.sso || []).some((p) => p['configured?']) ? 'SSO' : null
    ].filter(Boolean);
    // What this screen actually offers, said on every load.
    //
    // The interrupted owner ceremony is the case that needs it. Registration is
    // two steps — `/api/identity/register` creates the account, then
    // `navigator.credentials.create` enrols the Passkey — so cancelling the
    // system prompt leaves an account with no Passkey. From then on
    // `registered?` hides the registration form and the sign-in button becomes
    // "resume". Measured 2026-08-12 on a real store: one cancelled prompt, and
    // every later launch offered exactly one control, labelled as if the user
    // had asked to resume something. The explanation existed — in a status line
    // written once, which the next reload erased.
    const renderSigninGate = (data) => {
      const others = otherSigninMethods(data);
      const resuming = Boolean(data['passkey-required?']);
      const supported = passkeySupported();
      const hosted = others.includes('auth.itonami.cloud');
      $('#signin-gate-headline').textContent = resuming
        ? '前回の Passkey 作成が完了していません。'
        : hosted
          ? 'パスキーでサインインしてください。'
          : 'サインイン方法を選んでください。';
      $('#signin-gate-note').textContent = !supported
        ? (others.length
          ? ` このブラウザは Passkey / WebAuthn に対応していません。${others.join('、')}で続けられます。`
          : ' このブラウザは Passkey / WebAuthn に対応していません。この端末から入る方法が今はありません。')
        : resuming
          // Naming the resume without naming the alternatives is the same
          // defect this function exists to fix: a screen that describes one
          // way in while another is sitting on it unmentioned.
          ? ` アカウントはできていて、Passkey だけがありません。下のボタンで続きから作成します。${
            others.length ? `${others.join('、')}でも入れます。` : ''}`
          : hosted
            ? ` 入口は auth.itonami.cloud です。この端末の Passkey は追加確認に使います。${
              others.filter((name) => name !== 'auth.itonami.cloud').length
                ? others.filter((name) => name !== 'auth.itonami.cloud').join('、') + 'でも入れます。'
                : ''}`
          : others.length
            ? ` この端末で使える入口は Passkey、${others.join('、')} です。重要操作では Passkey を追加確認します。`
            : ' この端末で使える入口は Passkey だけです。重要操作では Passkey を追加確認します。';
      // The native window is a webview and the ceremony happens in the
      // system browser (RFC 8252) — say so, or the round-trip reads as the
      // window silently losing the person. This overrides the unsupported
      // copy too: whether or not the webview claims WebAuthn, the path from
      // here is the system browser, not this window.
      if (nativeSurface() && hosted && !resuming) {
        $('#signin-gate-note').textContent =
          ' サインインはブラウザ（auth.itonami.cloud）で行います。完了するとこの画面に自動で戻ります。';
      }
      // A control that cannot work is disabled with its reason on the screen,
      // not left live to fail on click.
      [$('#passkey-signin'), $('#registration-submit')].forEach((button) => {
        button.disabled = !supported;
      });
    };
    const renderIdentity = (data) => {
      identityState = data;
      renderAuthMethods(data);
      const identityReady = Boolean(data['authenticated?'] && data['may-act?']);
      appUnlocked = identityReady;
      if (data['authenticated?']) renderSessions();
      $$('.local-nav__item').forEach((item) => {
        item.disabled = !identityReady && !publicViews.has(item.dataset.view);
        item.setAttribute('aria-disabled', String(item.disabled));
      });
      document.body.dataset.identityGate = identityReady ? 'ready' : 'required';
      $$('.authenticated-only').forEach((node) => { node.hidden = !identityReady; });
      const signInNav = document.querySelector(".local-nav__item[data-view='signin']");
      if (signInNav) signInNav.hidden = Boolean(data['authenticated?']);
      $('#passkey-gate-notice').hidden = identityReady;
      if (!identityReady) {
        // a public view the user actually asked for stays put
        showView(publicViews.has(requestedView) ? requestedView : 'signin');
        $('#current-view').textContent =
          currentView === 'storage' ? 'Storage' : 'サインイン';
        $('#workspace-status').textContent = 'サインインが必要です';
      } else {
        bootstrapApp();
        // Context capture is a background capability, not a page the person
        // must discover first. This GET materializes new-user defaults for the
        // scheduler and keeps Settings in sync without navigating there.
        loadChronicle().catch((error) => {
          $('#memory-status').textContent = error.message;
        });
        showView(requestedView === 'signin' ? 'settings' : requestedView);
      }
      const onboarding = $('#identity-onboarding');
      const workspace = $('#identity-workspace');
      onboarding.hidden = data['authenticated?'];
      workspace.hidden = !data['authenticated?'];
      $('#registered-auth').hidden = Boolean(data['authenticated?']);
      const recovery = $('#local-recovery');
      if (recovery) {
        recovery.hidden = Boolean(data['authenticated?']);
        recovery.open = Boolean(data['passkey-required?']);
      }
      $('#email-login-form').hidden = Boolean(data['authenticated?']) ||
        !data['email-login-configured?'];
      $('#registration-form').hidden = Boolean(data['registered?']);
      // Gated on a CREDENTIAL existing, not on a User existing. A device with
      // a User and no enrolled Passkey has nothing to authenticate with, and
      // showing the button there offers a door that cannot open.
      $('#passkey-signin').hidden = !data['device-passkey?'];
      if (data['registered?'] && !data['authenticated?']) {
        const pendingPasskey = data['passkey-required?'];
        $('#registration-title').textContent = pendingPasskey
          ? 'Passkey 登録を再開'
          : 'サインイン';
        const others = otherSigninMethods(data);
        $('#registration-lead').textContent = pendingPasskey
          ? '仮登録は完了しています。Passkey を作成するとアプリを利用できます。'
          : others.length
            ? `Passkey、${others.join('、')}で続行できます。`
            : 'Passkeyで続行できます。';
        $('#passkey-signin').textContent = pendingPasskey
          ? 'Passkey 登録を再開'
          : 'Passkey でサインイン';
        $('#registration-form').hidden = true;
      }
      renderSigninGate(data);
      if (!data['authenticated?']) return;
      $('#identity-avatar').textContent =
        (data.user['display-name'] || 'U').slice(0, 2);
      $('#identity-name').textContent = data.user['display-name'] || 'Passkey user';
      // Says where to go, not just that something is missing. A handle is
      // claimed by naming your personal tenant (ADR-0023), and somebody who
      // named an organization first has no other route to one — the previous
      // text pointed at the Organization ID form, which no longer sets it.
      $('#identity-email').textContent = data.user['account-id']
        ? data.user.email
        : 'アカウント ID 未設定 — 個人テナントに切り替えて設定します';
      $('#identity-did').textContent = data.user.did || 'Passkey 登録後に発行';
      $('#passkey-state').textContent = data.user['passkey-enrolled?']
        ? 'Passkey 登録済み'
        : '未登録: 通常利用は可能です。重要操作の追加確認に登録してください。';
      $('#passkey-register').textContent = data.user['passkey-enrolled?']
        ? '別の Passkey を追加' : 'Passkey を登録';
      const organizationReady = Boolean(data.organization?.['profile-complete?']);
      // A personal tenant is a tenant like any other; what it is not is a place
      // other people can be added to, so the switcher and the ID form say which
      // one you are standing in rather than calling both "Organization".
      const personalTenant = data.organization?.kind === 'personal';
      $('#organization-name').textContent =
        organizationReady
          ? (personalTenant ? `${data.organization.name} · 個人`
             : data.organization.name)
          : (personalTenant ? '個人テナント ID 未設定' : 'Organization ID 未設定');
      $('#organization-domain').textContent = organizationReady
        ? `${data.organization.domain} · ${data.organization.role}`
        : 'サインイン後に設定できます';
      $('#organization-did').textContent =
        data.organization?.did || 'Organization DID は ID 設定後に発行';
      const organizationSwitcher = $('#organization-switcher');
      organizationSwitcher.replaceChildren();
      (data.organizations || []).forEach((organization) => {
        const option = document.createElement('option');
        option.value = organization.id;
        const label = organization.name || organization['organization-id']
          || organization.id;
        option.textContent = organization.kind === 'personal'
          ? `${label} · 個人` : label;
        option.selected = Boolean(organization['active?']);
        organizationSwitcher.append(option);
      });
      organizationSwitcher.disabled = (data.organizations || []).length < 2;
      // Where a project can be moved to: your other tenants, owner/admin only.
      // The server checks both sides again — this only keeps the list from
      // offering a destination it is going to refuse.
      const transferTargets = $('#project-transfer-tenant');
      const eligible = (data.organizations || []).filter((organization) =>
        !organization['active?'] && ['owner', 'admin'].includes(organization.role));
      transferTargets.replaceChildren();
      eligible.forEach((organization) => {
        const option = document.createElement('option');
        option.value = organization.id;
        const label = organization.name || organization['organization-id']
          || organization.id;
        option.textContent = organization.kind === 'personal'
          ? `${label} · 個人` : label;
        transferTargets.append(option);
      });
      $('#project-transfer-form').hidden = !eligible.length;
      const invitations = data['organization-invitations'] || [];
      $('#organization-invitation-state').textContent = invitations.length
        ? `${invitations.length}件の参加待ち招待があります。コードを入力して参加できます。`
        : '参加待ちの招待はありません。';
      $('#organization-form').hidden = organizationReady;
      $('#organization-submit').textContent = personalTenant
        ? 'アカウント ID を設定' : 'Organization ID を設定';
      // Members belong to organizations. A personal tenant has exactly one
      // member by construction, so the card that adds them stays away.
      $('#member-card').hidden = !organizationReady || personalTenant;
      const mayVerifyDomain = organizationReady && !personalTenant
        && data.organization?.role === 'owner';
      $('#domain-verification-card').hidden = !mayVerifyDomain;
      if (mayVerifyDomain) loadDomainVerifications();
      renderMembers(data.organization);
      renderConnectors(data);
      loadCloudAlias(data);
      loadTenantConnections();
      loadMailAccounts();
    };
    // Mailboxes, one row per account. Deliberately not grouped by provider:
    // two Gmail accounts are two rows with two sync states, because a single
    // "Google" row cannot say which of the two stopped working.
    const mailStatusText = (account) => {
      const sync = account.sync || {};
      if (account.status === 'error' || sync['last-error']) {
        return `同期エラー: ${sync['last-error'] || '原因不明'}`;
      }
      if (account.status === 'never-synced' || !sync['last-synced-at']) {
        return 'まだ同期していません';
      }
      return `${sync['message-count'] || 0} 件 · 最終同期 ${sync['last-synced-at']}`;
    };
    const renderMailAccounts = (data) => {
      const list = $('#mail-account-list');
      const state = $('#mail-account-state');
      if (!list || !state) return;
      const accounts = data.accounts || [];
      list.replaceChildren();
      state.textContent = accounts.length
        ? `${accounts.length} 個のメールボックスを統合しています。`
        : 'メールボックスはまだありません。';
      accounts.forEach((account) => {
        const item = make('li', 'member-list__item');
        const copy = make('div');
        copy.append(
          make('strong', null, account.address || account.id),
          make('p', 'form-help',
            `${mailKindNames[account.kind] || account.kind}${account['delegated?'] ? ' · 委任' : ''}`),
          make('p', 'form-help', mailStatusText(account)));
        const actions = make('div', 'record-actions');
        const sync = make('button', 'tool-button', '今すぐ同期');
        sync.type = 'button';
        sync.addEventListener('click', async () => {
          sync.disabled = true; sync.textContent = '同期中…';
          try {
            const request = await fetch(
              `/api/mail/accounts/${encodeURIComponent(account.id)}/sync`,
              {method:'POST', headers:identityHeaders(), body:'{}'});
            const result = await request.json();
            if (!request.ok) throw new Error(result?.error?.message || '同期できませんでした。');
            if (result.error) throw new Error(result.error);
            await loadMailAccounts();
          } catch (error) {
            state.textContent = error.message;
          } finally {
            sync.disabled = false; sync.textContent = '今すぐ同期';
          }
        });
        actions.append(sync);
        // Only an IMAP account can be forgotten here. An OAuth mailbox exists
        // because a grant exists, so removing it means disconnecting the
        // grant — a different act, and doing it from here would leave the
        // connection live and the mailbox merely hidden.
        if (account['removable?']) {
          const remove = make('button', 'tool-button', '削除');
          remove.type = 'button';
          remove.addEventListener('click', async () => {
            remove.disabled = true;
            try {
              const request = await fetch(
                `/api/mail/accounts/${encodeURIComponent(account.id)}`,
                {method:'DELETE', headers:identityHeaders()});
              const result = await request.json();
              if (!request.ok) throw new Error(result?.error?.message || '削除できませんでした。');
              await loadMailAccounts();
            } catch (error) {
              state.textContent = error.message; remove.disabled = false;
            }
          });
          actions.append(remove);
        }
        item.append(copy, actions);
        list.append(item);
      });
    };
    const loadMailAccounts = async () => {
      const state = $('#mail-account-state');
      if (!state) return;
      try {
        const request = await fetch('/api/mail/accounts', {headers:identityHeaders()});
        if (!request.ok) return;
        renderMailAccounts(await request.json());
      } catch (error) {
        state.textContent = 'メールアカウントを読み込めませんでした。';
      }
    };
    $('#mail-account-form')?.addEventListener('submit', async (event) => {
      event.preventDefault();
      const state = $('#mail-account-state');
      const button = $('#mail-account-submit');
      button.disabled = true;
      try {
        const request = await fetch('/api/mail/accounts', {
          method:'POST', headers:identityHeaders(),
          body:JSON.stringify({
            address: $('#mail-account-address').value.trim(),
            protocol: $('#mail-account-protocol').value,
            host: $('#mail-account-host').value.trim(),
            'smtp-host': $('#mail-account-smtp-host').value.trim(),
            password: $('#mail-account-password').value
          })
        });
        const result = await request.json();
        if (!request.ok) throw new Error(result?.error?.message || 'メールボックスを追加できませんでした。');
        // The password never goes back into the field: it is in the Keychain
        // now, and leaving it in the DOM is leaving it where a screenshot or
        // an autofill inspector finds it.
        $('#mail-account-form').reset();
        await loadMailAccounts();
      } catch (error) {
        state.textContent = error.message;
      } finally {
        button.disabled = false;
      }
    });
    const renderTenantConnections = (data) => {
      // Kept so the Fleet detail can render sentences without a second copy
      // of the wording living in this file.
      if (data['capability-catalog']) fleetCapabilityCatalog = data['capability-catalog'];
      const list = $('#tenant-connection-list');
      const state = $('#tenant-connection-state');
      if (!list || !state) return;
      const connections = data.connections || [];
      list.replaceChildren();
      connections.forEach((connection) => {
        const item = make('li', 'member-list__item');
        const copy = make('div');
        // Sentences, not identifiers. The server sends capability-details so
        // the wording lives in one place; falling back to the raw list keeps
        // an older server readable rather than blank.
        const details = connection['capability-details'];
        const capabilities = details && details.length
          ? details.map((d) => d.label).join('、')
          : (connection.capabilities || []).join('、');
        // Outbound capabilities hand something of yours to another app, which
        // is a different decision from letting an agent act in your own
        // workspace. Saying so is the whole reason the two are distinguished.
        const outbound = (details || []).filter((d) => d.direction === 'outbound');
        copy.append(
          make('strong', null, connection['agent-id'] || connection.id),
          make('p', 'form-help',
            `${connection['tenant-organization-id'] || connection['tenant-id']} · ${capabilities}`),
          make('p', 'form-help',
            `${connection.status} · ${connection['expires-at'] || '未承認'}`));
        if (outbound.length) {
          copy.append(make('p', 'form-help',
            `⚠ 外部の app に渡します: ${outbound.map((d) => d.label).join('、')}`));
        }
        const actions = make('div', 'worker-actions');
        const needsApproval = connection.status === 'pending-approval'
          || Boolean(connection['renewal-requested-at']);
        if (needsApproval) {
          const approve = make('button', 'primary-action', 'Passkey sessionで承認');
          approve.type = 'button';
          approve.addEventListener('click', async () => {
            approve.disabled = true;
            try {
              await postJSON(`/v1/tenant-connections/${encodeURIComponent(connection.id)}/approve`, {}, true);
              $('#identity-status').textContent = 'Agent tenant connectionを承認しました。';
              await loadTenantConnections();
            } catch (error) {
              $('#identity-status').textContent = error.message;
              approve.disabled = false;
            }
          });
          actions.append(approve);
        }
        if (connection.status !== 'revoked') {
          const revoke = make('button', 'tool-button', '取り消す');
          revoke.type = 'button';
          revoke.addEventListener('click', async () => {
            revoke.disabled = true;
            try {
              await postJSON(`/v1/tenant-connections/${encodeURIComponent(connection.id)}/revoke`, {}, true);
              $('#identity-status').textContent = 'Agent tenant connectionを取り消しました。';
              await loadTenantConnections();
            } catch (error) {
              $('#identity-status').textContent = error.message;
              revoke.disabled = false;
            }
          });
          actions.append(revoke);
        }
        item.append(copy, actions); list.append(item);
      });
      if (!connections.length)
        list.append(make('li', 'empty-state', 'Agentからの接続申請はありません。'));
      state.textContent = `${connections.length}件のtenant connection`;
    };
    const loadTenantConnections = async () => {
      if (!identityState?.['authenticated?']) return;
      try {
        const request = await fetch('/v1/tenant-connections');
        const data = await request.json();
        if (!request.ok)
          throw new Error(data?.error?.message || 'tenant connectionを確認できませんでした。');
        renderTenantConnections(data);
      } catch (error) {
        const state = $('#tenant-connection-state');
        if (state) state.textContent = error.message;
      }
    };
    const loadCloudAlias = async (identity) => {
      const state = $('#cloud-alias-state');
      const button = $('#cloud-alias-reserve');
      if (!state || !identity?.['authenticated?']) return;
      if (!identity.user?.['account-id']) {
        state.textContent = 'Organization ID を設定すると公開メールアドレスを予約できます。';
        button.disabled = true;
        return;
      }
      try {
        const request = await fetch('/api/cloud/alias');
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || 'クラウド状態を確認できません。');
        if (!data['configured?']) {
          state.textContent = 'Private Relay provider はまだ設定されていません。';
          button.disabled = true;
          return;
        }
        const alias = data.alias;
        state.textContent = data.found
          ? `${alias.address} · ${alias.status}`
          : `${identity.user.email} はグローバル未予約です。`;
        button.disabled = data.found && alias.status === 'active';
      } catch (error) {
        state.textContent = error.message;
        button.disabled = true;
      }
    };
    const loadIdentity = async () => {
      try {
        const request = await fetch('/api/identity');
        const data = await request.json();
        if (!request.ok) throw new Error('アカウント情報を読み込めませんでした。');
        renderIdentity(data);
      } catch (error) {
        $('#identity-status').textContent = error.message;
      }
    };
    const renderDesktopUpdate = (data) => {
      const status = $('#desktop-update-status');
      const action = $('#desktop-update-action');
      if (data['restart-required?']) {
        status.textContent = `${data['staged-version'] || data['available-version']} を検証済みです。更新すると現在のウインドウを閉じ、安全に適用して開き直します。`;
        action.textContent = 'アプリを閉じて更新';
        action.dataset.updateReady = 'true';
        return;
      }
      delete action.dataset.updateReady;
      action.textContent = '更新を確認';
      if (data.status === 'error') {
        status.textContent = `更新を確認できません: ${data.error}`;
        return;
      }
      if (data['available?']) {
        status.textContent = `${data['installed-version']} → ${data['available-version']} を利用できます。`;
        action.textContent = '検証して更新を準備';
        return;
      }
      status.textContent = data['installed-version']
        ? (data['last-applied']?.version === data['installed-version']
          ? `${data['installed-version']} に更新しました。更新前: ${data['last-applied']['from-version']}。`
          : `${data['installed-version']} は最新です。`)
        : '更新状態をまだ確認していません。';
    };
    const loadDesktopUpdate = async (refresh = false) => {
      const button = $('#desktop-update-action');
      button.disabled = true;
      try {
        const request = await fetch(refresh ? '/api/update/check' : '/api/update',
          refresh ? {method:'POST', headers:{'Content-Type':'application/json'}, body:'{}'} : {});
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || '更新状態を確認できません。');
        renderDesktopUpdate(data);
      } catch (error) {
        renderDesktopUpdate({status:'error', error:error.message});
      } finally {
        button.disabled = false;
      }
    };
    $('#desktop-update-action').addEventListener('click', async () => {
      const button = $('#desktop-update-action');
      if (button.dataset.updateReady === 'true') {
        window.close();
        return;
      }
      button.disabled = true;
      try {
        const checked = await fetch('/api/update/check', {
          method:'POST', headers:{'Content-Type':'application/json'}, body:'{}'});
        const checkData = await checked.json();
        if (!checked.ok) throw new Error(checkData?.error?.message || '更新状態を確認できません。');
        if (!checkData['available?']) {
          renderDesktopUpdate(checkData);
          return;
        }
        const downloaded = await fetch('/api/update/download', {
          method:'POST', headers:{'Content-Type':'application/json'}, body:'{}'});
        const downloadData = await downloaded.json();
        if (!downloaded.ok) throw new Error(downloadData?.error?.message || '更新を準備できません。');
        renderDesktopUpdate(downloadData);
      } catch (error) {
        renderDesktopUpdate({status:'error', error:error.message});
      } finally {
        button.disabled = false;
      }
    });
    loadDesktopUpdate();
    $('#registration-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = $('#registration-submit');
      button.disabled = true; button.textContent = '登録中…';
      try {
        const request = await fetch('/api/identity/register', {
          method:'POST', headers:{'Content-Type':'application/json'},
          body:JSON.stringify({})
        });
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || '登録できませんでした。');
        renderIdentity(data);
        $('#identity-status').textContent = 'Passkey を登録します。';
        try { await registerCurrentPasskey(); }
        catch (passkeyError) {
          $('#identity-status').textContent =
            `アカウントは登録済みです。Passkey 登録: ${passkeyError.message}`;
        }
      } catch (error) {
        $('#identity-status').textContent = error.message;
        button.disabled = false; button.textContent = 'Passkey で登録';
      }
    });
    $('#organization-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = $('#organization-submit');
      const fields = Object.fromEntries(new FormData(event.currentTarget));
      button.disabled = true; button.textContent = '設定中…';
      try {
        const data = await postJSON('/api/identity/organization', fields, true);
        renderIdentity(data);
        $('#identity-status').textContent =
          `${data.organization.domain} と ${data.user.email} を設定しました。`;
      } catch (error) {
        $('#identity-status').textContent = error.message;
        button.disabled = false; button.textContent = 'Organization ID を設定';
      }
    });
    $('#organization-create-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = $('#organization-create');
      const fields = Object.fromEntries(new FormData(event.currentTarget));
      button.disabled = true; button.textContent = '追加中…';
      try {
        const data = await postJSON('/api/identity/organizations', fields, true);
        event.currentTarget.reset();
        renderIdentity(data);
        $('#identity-status').textContent =
          `${fields['organization-name'] || fields['organization-id']} を追加しました。`;
      } catch (error) {
        $('#identity-status').textContent = error.message;
      } finally {
        button.disabled = false; button.textContent = 'Organizationを追加';
      }
    });
    $('#domain-verification-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = $('#domain-verification-start');
      const domain = $('#company-domain').value.trim();
      button.disabled = true;
      button.textContent = '発行中…';
      try {
        const verification = await postJSON(
          '/api/identity/domain-verifications', {domain}, true);
        renderDomainVerifications({verifications:[verification]});
        $('#identity-status').textContent =
          `${verification.domain} のTXTレコードを発行しました。`;
      } catch (error) {
        $('#domain-verification-state').textContent = error.message;
      } finally {
        button.disabled = false;
        button.textContent = 'TXTレコードを発行';
      }
    });
    $('#domain-verification-copy').addEventListener('click', async () => {
      if (!activeDomainVerification) return;
      const text = `${activeDomainVerification['record-name']}\n${activeDomainVerification['record-value']}`;
      await navigator.clipboard.writeText(text);
      $('#domain-verification-state').textContent = 'TXTのホスト名と値をコピーしました。';
    });
    // The three gate steps share one shape: post, re-render from the answer,
    // reload the identity card because the tenant's own domain can change under
    // two of them, and put a refusal where the state line is rather than
    // swallowing it.
    const runDomainStep = async (selector, path, label, busy, done) => {
      $(selector).addEventListener('click', async () => {
        if (!activeDomainVerification) return;
        const button = $(selector);
        button.disabled = true;
        button.textContent = busy;
        try {
          const verification = await postJSON(
            path, {'verification-id':activeDomainVerification.id}, true);
          renderDomainVerifications({verifications:[verification]});
          $('#identity-status').textContent = done(verification);
          await loadIdentity();
        } catch (error) {
          $('#domain-verification-state').textContent = error.message;
        } finally {
          button.disabled = false;
          button.textContent = label;
        }
      });
    };
    runDomainStep('#domain-verification-claim',
                  '/api/identity/domain-verifications/claim',
                  'DNSを確認', '確認中…',
                  (v) => `${v.domain} の所有権を確認しました。次にDNSをこの deployment へ向けてください。`);
    runDomainStep('#domain-verification-activate',
                  '/api/identity/domain-verifications/activate',
                  '有効化', '有効化中…',
                  (v) => `${v.domain} をこのOrganizationの名前にしました。`);
    runDomainStep('#domain-verification-recheck',
                  '/api/identity/domain-verifications/recheck',
                  '再確認', '再確認中…',
                  (v) => `${v.domain} を再確認しました（${v.status}）。`);
    $('#project-transfer-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = $('#project-transfer-submit');
      const fields = Object.fromEntries(new FormData(event.currentTarget));
      button.disabled = true; button.textContent = '移動中…';
      try {
        const data = await postJSON(
          `/api/projects/${encodeURIComponent(fields.project)}/transfer`,
          {tenant: fields.tenant}, true);
        event.currentTarget.reset();
        // The receipt names what did NOT move, so say it here too. Mail filed
        // against the project stays with the tenant it was filed in, and
        // finding that out later is how somebody concludes the move was
        // partial and broken (ADR-0024).
        const stayed = data['stayed-behind'] || {};
        const left = (stayed['filed-messages'] || 0) + (stayed['filing-rules'] || 0);
        $('#identity-status').textContent =
          `${data['project-id']} を ${data.to.name} へ移動しました。`
          + (left ? ` メール ${stayed['filed-messages'] || 0} 件と振り分け規則 `
             + `${stayed['filing-rules'] || 0} 件は移動元に残ります。` : '');
      } catch (error) {
        $('#identity-status').textContent = error.message;
      } finally {
        button.disabled = false; button.textContent = '移動する';
      }
    });
    $('#organization-switcher').addEventListener('change', async (event) => {
      const select = event.currentTarget;
      select.disabled = true;
      try {
        const data = await postJSON('/api/identity/organizations/switch',
          {'organization-id':select.value}, true);
        organismCursor = null;
        organismWorkers = [];
        selectedOrganism = null;
        governanceHydrated = false;
        renderIdentity(data);
        await Promise.all([loadOrganisms(), loadWorkGovernance(), loadLocalProjects()]);
        $('#identity-status').textContent =
          `${data.organization.name} に切り替えました。`;
      } catch (error) {
        $('#identity-status').textContent = error.message;
        renderIdentity(identityState);
      }
    });
    $('#member-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const memberForm = event.currentTarget;
      const fields = new FormData(memberForm);
      try {
        const request = await fetch('/api/identity/users', {
          method:'POST', headers:identityHeaders(),
          body:JSON.stringify(Object.fromEntries(fields))
        });
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || 'ユーザーを追加できませんでした。');
        memberForm.reset(); renderIdentity(data.identity);
        const invitation = data.invitation;
        const result = $('#enrollment-result');
        result.hidden = false;
        const existing = invitation.kind === 'organization-invitation';
        const code = existing
          ? invitation['invitation-code'] : invitation['enrollment-code'];
        result.textContent = existing
          ? `${invitation.email} のOrganization招待コード: ${code}（24時間・1回限り）`
          : `${invitation.email} のenrollment code: ${code}（24時間・1回限り）`;
        $('#identity-status').textContent = existing
          ? '既存UserへOrganization参加招待を発行しました。'
          : '組織Userとenrollment codeを発行しました。';
      } catch (error) {
        $('#identity-status').textContent = error.message;
      }
    });
    $('#organization-invitation-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = $('#organization-invitation-accept');
      const fields = Object.fromEntries(new FormData(event.currentTarget));
      button.disabled = true; button.textContent = '参加中…';
      try {
        const data = await postJSON('/api/identity/organizations/accept',
          fields, true);
        event.currentTarget.reset();
        organismCursor = null;
        organismWorkers = [];
        selectedOrganism = null;
        renderIdentity(data);
        await Promise.all([loadOrganisms(), loadLocalProjects()]);
        $('#identity-status').textContent =
          `${data.organization.name} に参加して切り替えました。`;
      } catch (error) {
        $('#identity-status').textContent = error.message;
      } finally {
        button.disabled = false; button.textContent = '招待を承認して参加';
      }
    });
    $('#passkey-register').addEventListener('click', async () => {
      const button = $('#passkey-register');
      button.disabled = true;
      try { await registerCurrentPasskey(); }
      catch (error) { $('#identity-status').textContent = error.message; }
      finally { button.disabled = false; }
    });
    $('#cloud-alias-reserve').addEventListener('click', async () => {
      const button = $('#cloud-alias-reserve');
      const destination = $('#cloud-alias-destination').value.trim();
      button.disabled = true;
      try {
        const request = await fetch('/api/cloud/alias', {
          method:'POST',
          headers:identityHeaders(),
          body:JSON.stringify({destination})
        });
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || 'グローバル予約に失敗しました。');
        $('#cloud-alias-state').textContent =
          `${data.address} を予約しました。転送先の確認メールを送信しました。`;
      } catch (error) {
        $('#cloud-alias-state').textContent = error.message;
        button.disabled = false;
      }
    });
    $('#passkey-signin').addEventListener('click', async () => {
      const button = $('#passkey-signin'); button.disabled = true;
      try {
        if (identityState?.['passkey-required?']) {
          const resumed = await postJSON('/api/passkeys/onboarding/resume');
          renderIdentity(resumed);
          await registerCurrentPasskey();
          $('#identity-status').textContent = 'Passkey を登録してアプリを開きました。';
          return;
        }
        requireWebAuthn();
        const started = await postJSON('/api/passkeys/authenticate/start');
        const credential = await navigator.credentials.get(assertionOptions(started));
        const data = await postJSON('/api/passkeys/authenticate/finish', {
          'transaction-id':started['transaction-id'],
          credential:credentialJSON(credential)
        });
        renderIdentity(data);
        $('#identity-status').textContent = 'Passkey でサインインしました。';
      } catch (error) {
        $('#identity-status').textContent = error.message;
      } finally { button.disabled = false; }
    });
    $('#itonami-cloud-signin').addEventListener('click', (event) => {
      // Browser follows href to GET /api/auth/itonami/start, which 303s to
      // auth.itonami.cloud. Native webview cannot do WebAuthn, so it keeps
      // the POST + system-browser handoff.
      if (nativeSurface()) {
        event.preventDefault();
        startCentralAuth(event.currentTarget);
      }
    });
    $('#itonami-enrolment-link').addEventListener('click', async (event) => {
      // Same RFC 8252 boundary as sign-in: a first passkey cannot be minted
      // inside the native webview, so the page must not navigate itself to
      // itonami.cloud there. The server opens its CONFIGURED enrolment page
      // in the system browser; this handler names no url.
      if (!nativeSurface()) return;
      event.preventDefault();
      try {
        const request = await fetch('/api/auth/itonami/enrolment/open', {
          method:'POST', headers:{'Content-Type':'application/json'}, body:'{}'
        });
        const result = await request.json().catch(() => ({}));
        $('#identity-status').textContent = result['opened-externally?']
          ? 'ブラウザで itonami.cloud を開きました。パスキーを作ったら、この画面に戻って「パスキーでサインイン」を押してください。'
          : 'ブラウザを開けませんでした。' + (result.url || 'https://itonami.cloud/ja/signin/') +
            ' を手動で開いてください。';
      } catch (error) {
        $('#identity-status').textContent = error.message;
      }
    });
    $('#itonami-cloud-link').addEventListener('click', (event) => {
      startCentralAuth(event.currentTarget);
    });
    $('#sign-out-current').addEventListener('click', async (event) => {
      const button = event.currentTarget;
      button.disabled = true;
      try {
        await postJSON('/api/auth/signout', {}, true);
        await loadIdentity();
        $('#identity-status').textContent = 'ログアウトしました。';
      } catch (error) {
        button.disabled = false;
        $('#identity-status').textContent = error.message;
      }
    });
    $('#email-login-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = $('#email-login-submit');
      const fields = Object.fromEntries(new FormData(event.currentTarget));
      button.disabled = true; button.textContent = '送信中…';
      try {
        await postJSON('/api/email-authenticate/start', fields);
        $('#identity-status').textContent =
          '登録済みの場合、ログインリンクを送信しました。メールを確認してください。';
      } catch (error) {
        $('#identity-status').textContent = error.message;
      } finally {
        button.disabled = false; button.textContent = 'ログインリンクを送る';
      }
    });
    $('#enrollment-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = $('#enrollment-submit'); button.disabled = true;
      try {
        requireWebAuthn();
        const fields = Object.fromEntries(new FormData(event.currentTarget));
        const started = await postJSON('/api/passkeys/enroll/start', fields);
        const credential = await navigator.credentials.create(creationOptions(started));
        const data = await postJSON('/api/passkeys/enroll/finish', {
          'transaction-id':started['transaction-id'],
          credential:credentialJSON(credential)
        });
        renderIdentity(data);
        $('#identity-status').textContent = 'Passkey を登録してサインインしました。';
      } catch (error) {
        $('#identity-status').textContent = error.message;
      } finally { button.disabled = false; }
    });
    const workerLabels = {queued:'待機中', running:'実行中', done:'完了',
      failed:'失敗', cancelled:'中止'};
    const workerChipClass = {queued:'', running:' state-chip--run',
      done:' state-chip--done', failed:' state-chip--fail', cancelled:''};
    let workerData = {items:[], counts:{}, active:0};
    let selectedWorker = null;
    let workerTimer = null;
    const workerHelp = (message) => {
      $('#worker-form-help').textContent = message;
    };
    const renderWorkerDetail = () => {
      const target = $('#worker-detail');
      const previous = target.querySelector('.worker-output');
      const follow = !previous || (previous.scrollTop + previous.clientHeight
        >= previous.scrollHeight - 8);
      target.replaceChildren();
      const run = selectedWorker;
      if (!run) {
        target.append(make('div', 'empty-state', 'ジョブを選択してください。'));
        return;
      }
      target.append(
        make('p', 'record-detail__eyebrow',
          `${workerLabels[run.status] || run.status} · ${run.agent}`),
        make('h2', null, run.title),
        make('p', 'record-detail__body', run.prompt));
      if (run.error) {
        target.append(make('p', 'settings-notice settings-notice--error', run.error));
      }
      const output = make('div', 'worker-output', run.output
        || (run.status === 'queued' ? 'まだ実行を開始していません。' : '出力はまだありません。'));
      output.setAttribute('aria-live', 'polite');
      target.append(output);
      if (run['truncated?']) {
        target.append(make('p', 'form-help',
          '出力が上限に達したため、以降は保存していません。'));
      }
      const meta = make('dl', 'local-meta record-detail__meta');
      [['状態', workerLabels[run.status] || run.status],
       ['モデル', run.model || '既定のモデル'],
       ['プロバイダ', run.provider],
       ['登録', formatDate(run['created-at'])],
       ['開始', run['started-at'] ? formatDate(run['started-at']) : null],
       ['終了', run['finished-at'] ? formatDate(run['finished-at']) : null],
       ['トークン', run.usage?.total_tokens]
      ].forEach(([label, value]) => {
        meta.append(make('dt', null, label), make('dd', null,
          value === null || value === undefined || value === '' ? '—' : String(value)));
      });
      target.append(meta);
      if (run.status === 'queued' || run.status === 'running') {
        const cancel = make('button', 'tool-button', 'このジョブを中止');
        cancel.type = 'button';
        cancel.addEventListener('click', async () => {
          cancel.disabled = true;
          try {
            const request = await fetch(
              `/api/workers/${encodeURIComponent(run.id)}/cancel`,
              {method:'POST', headers:identityHeaders(), body:'{}'});
            const data = await request.json();
            if (!request.ok) {
              throw new Error(data?.error?.message || 'ジョブを中止できませんでした。');
            }
            renderWorker(data);
          } catch (error) {
            cancel.disabled = false;
            workerHelp(error.message);
          }
        });
        const actions = make('div', 'worker-actions');
        actions.append(cancel);
        target.append(actions);
      }
      if (follow) output.scrollTop = output.scrollHeight;
    };
    const renderWorker = (data) => {
      workerData = data;
      const items = data.items || [];
      const previousId = selectedWorker?.id;
      selectedWorker = items.find((item) => item.id === previousId)
        || items[0] || null;
      const list = $('#worker-list');
      list.replaceChildren();
      const select = (run) => { selectedWorker = run; renderWorker(workerData); };
      items.forEach((item) => list.append(recordButton(
        item, item.id === selectedWorker?.id, select, {
          title:item.title,
          time:workerLabels[item.status] || item.status,
          meta:`${item.agent} · ${item.model || '既定のモデル'}`,
          snippet:String(item.output || item.error || '出力はまだありません').slice(0, 140)
        })));
      if (!items.length) {
        list.append(make('li', 'empty-state',
          'まだジョブはありません。指示を登録すると背後で実行します。'));
      }
      renderWorkerDetail();
      const summary = $('#worker-summary');
      summary.replaceChildren();
      ['running', 'queued', 'done', 'failed', 'cancelled'].forEach((key) => {
        const count = data.counts?.[key] || 0;
        if (!count) return;
        summary.append(make('span', `state-chip${workerChipClass[key]}`,
          `${workerLabels[key]} ${count}`));
      });
      if (!summary.childElementCount) {
        summary.append(make('span', 'state-chip', 'ジョブなし'));
      }
      $('#worker-count').textContent = data.active || 0;
      $('#worker-source').textContent = `${data.source} · 同時実行 ${data['max-concurrency']}`
        + ` · 保持 ${items.length} / ${data['max-runs']} 件`;
      $('#worker-clear').disabled = items.length === (data.active || 0);
      scheduleWorkerPoll();
    };
    const scheduleWorkerPoll = () => {
      if (workerTimer) { clearTimeout(workerTimer); workerTimer = null; }
      const active = (workerData.active || 0) > 0;
      if (!appUnlocked || (!active && currentView !== 'worker')) return;
      workerTimer = setTimeout(() => loadWorkspace('worker', renderWorker),
        active ? 1500 : 5000);
    };
    // ── Credentials ──────────────────────────────────────────────────────
    const credentialRoleText = {
      owner:'オーナー', admin:'管理者', member:'メンバー',
      auditor:'監査', guest:'ゲスト'
    };
    let credentialsMayRevoke = false;
    const credentialIssueStatus = (message) => {
      $('#credential-issue-status').textContent = message || '';
    };
    const revokeCredential = async (index) => {
      credentialIssueStatus('失効させています…');
      try {
        await postJSON(`/api/credentials/${encodeURIComponent(index)}/revoke`, {}, true);
        credentialIssueStatus(`#${index} を失効させました。提示されたどこでも honour されません。`);
        await loadCredentials();
      } catch (error) {
        credentialIssueStatus(error.message);
      }
    };
    const renderCredentialRegister = (data) => {
      credentialsMayRevoke = Boolean(data['may-revoke?']);
      const list = $('#credential-list');
      list.replaceChildren();
      const issued = data.issued || [];
      $('#credentials-count').textContent = issued.length;
      const live = issued.filter((record) => !record['revoked?']).length;
      $('#credentials-source').textContent = issued.length
        ? `${issued.length} 件発行済み・${issued.length - live} 件失効`
        : 'まだ発行していません。';
      if (!issued.length) {
        list.append(make('li', 'empty-state',
          'まだありません。上の「発行する」で、いま操作している membership に対して発行できます。'));
        return;
      }
      issued.forEach((record) => {
        const item = make('li', 'data-list__item');
        const body = make('div');
        const revoked = Boolean(record['revoked?']);
        body.append(make('p', 'data-list__title',
          `#${record['status-index']} · ${credentialRoleText[record.role] || record.role}`));
        body.append(make('p', 'data-list__meta',
          `${record.subject || '主体不明'} · ${formatDate(record['issued-at'])}`));
        item.append(body);
        const side = make('div', 'data-list__side');
        // 「署名は正しいが失効済み」を1語で言う。:verified だけで判断すると
        // 失効後も通してしまうので、画面でもその2つを分けて出す。
        side.append(make('span', null, revoked ? '失効済み' : '有効'));
        if (!revoked && credentialsMayRevoke) {
          const button = make('button', 'tool-button', '失効させる');
          button.type = 'button';
          button.setAttribute('aria-label',
            `#${record['status-index']} の credential を失効させる`);
          button.addEventListener('click', () => revokeCredential(record['status-index']));
          side.append(button);
        }
        item.append(side);
        list.append(item);
      });
    };
    const renderTrustedIssuers = (data) => {
      const list = $('#credential-trusted-issuers');
      list.replaceChildren();
      const issuers = data['trusted-issuers'] || [];
      if (!issuers.length) {
        // 空であることを見せる。見えないと、他組織検証の失敗が全部
        // 不具合に見える。
        list.append(make('li', 'empty-state',
          '空です。他組織が発行した credential は、どれも検証を拒否されます。'));
        return;
      }
      issuers.forEach((domain) => {
        const item = make('li', 'data-list__item');
        item.append(make('div', 'data-list__title', domain));
        item.append(make('div', 'data-list__side', 'did:web'));
        list.append(item);
      });
    };
    const loadCredentials = async () => {
      const [register, issuers] = await Promise.all([
        fetch('/api/credentials').then((request) => request.json()),
        fetch('/api/credentials/trusted-issuers').then((request) => request.json())
      ]);
      renderCredentialRegister(register);
      renderTrustedIssuers(issuers);
    };
    const renderVerifyResult = (result) => {
      const target = $('#credential-verify-result');
      target.replaceChildren();
      const verified = Boolean(result.verified);
      const valid = Boolean(result['valid?']);
      target.append(make('p', 'data-list__title',
        valid ? '有効です。' : (verified ? '署名は正しいが、有効ではありません。' : '検証できませんでした。')));
      const detail = [];
      if (verified && !valid) {
        // この2つを混ぜないことがこの画面の要点。
        if (result.revocation === 'unchecked') {
          detail.push('失効一覧を解決できないため、失効しているかどうかが分かりません。'
            + '署名が正しいことは、まだ有効であることを意味しません。');
        } else if (result['revoked?'] || result.revocation === 'revoked') {
          detail.push('失効しています。honour してはいけません。');
        }
      }
      if (result.reason) detail.push(`理由: ${result.reason}`);
      if (result.subject) detail.push(`主体: ${result.subject}`);
      if (result.role) detail.push(`役割: ${credentialRoleText[result.role] || result.role}`);
      if (result.issuer) detail.push(`発行体: ${result.issuer}`);
      if (result['verification-method']) {
        detail.push(`鍵: ${result['verification-method']}`);
      }
      detail.forEach((line) => target.append(make('p', 'data-list__meta', line)));
    };
    const verifyCredential = async (path) => {
      const raw = $('#credential-verify-input').value.trim();
      const target = $('#credential-verify-result');
      if (!raw) {
        target.replaceChildren(make('p', 'data-list__meta', 'credential の JSON を貼り付けてください。'));
        return;
      }
      let parsed;
      try {
        parsed = JSON.parse(raw);
      } catch (error) {
        // 貼り付け間違いを「検証失敗」と混同させない。
        target.replaceChildren(make('p', 'data-list__meta',
          'JSON として読めませんでした。貼り付け内容を確認してください。'));
        return;
      }
      target.replaceChildren(make('p', 'data-list__meta', '検証しています…'));
      try {
        renderVerifyResult(await postJSON(path, {credential:parsed}, true));
      } catch (error) {
        target.replaceChildren(make('p', 'data-list__meta', error.message));
      }
    };
    // ── SD-JWT VC ────────────────────────────────────────────────────────
    const sdJwtStatus = (message) => {
      $('#credential-sd-jwt-status').textContent = message || '';
    };
    const renderSdJwtIssued = (issued) => {
      const target = $('#credential-sd-jwt-result');
      target.replaceChildren();
      target.append(make('p', 'data-list__title', `vct: ${issued.vct}`));
      // Both halves are shown because the holder's choice IS the feature: the
      // first string discloses the subject, the second withholds it.
      const jwt = issued.presentation.split('~')[0];
      target.append(make('p', 'data-list__meta', '主体を開示する提示（full）:'));
      target.append(make('p', 'form-help', issued.presentation));
      target.append(make('p', 'data-list__meta', '主体を伏せる提示（sub を除く）:'));
      target.append(make('p', 'form-help', `${jwt}~`));
      target.append(make('p', 'form-help',
        '下の検証欄に貼り付けて「SD-JWT VC として検証」を押すと、'
        + 'どちらが何を明かすか確認できます。'));
    };
    $('#credential-issue-sd-jwt').addEventListener('click', async () => {
      const button = $('#credential-issue-sd-jwt');
      button.disabled = true;
      sdJwtStatus('発行しています…');
      try {
        const issued = await postJSON('/api/credentials/membership/sd-jwt-vc', {}, true);
        sdJwtStatus('発行しました。credential 本体は保存していないので、この結果を holder へ渡してください。');
        renderSdJwtIssued(issued);
        $('#credential-verify-input').value = issued.presentation;
      } catch (error) {
        sdJwtStatus(error.message);
      } finally {
        button.disabled = false;
      }
    });
    $('#credential-verify-sd-jwt').addEventListener('click', async () => {
      // An SD-JWT VC presentation is a compact tilde-separated STRING, not JSON,
      // so this path deliberately does not JSON.parse the textarea.
      const raw = $('#credential-verify-input').value.trim();
      const target = $('#credential-verify-result');
      if (!raw) {
        target.replaceChildren(make('p', 'data-list__meta', 'presentation を貼り付けてください。'));
        return;
      }
      target.replaceChildren(make('p', 'data-list__meta', '検証しています…'));
      try {
        const r = await postJSON('/api/credentials/sd-jwt-vc/verify', {presentation:raw}, true);
        target.replaceChildren();
        target.append(make('p', 'data-list__title',
          r['valid?'] ? '有効です。' : '検証できませんでした。'));
        if (r.reason) target.append(make('p', 'data-list__meta', `理由: ${r.reason}`));
        if (r['valid?']) {
          // The distinction this format exists for, said explicitly rather than
          // left to be inferred from a missing field.
          target.append(make('p', 'data-list__meta',
            r['subject-disclosed?']
              ? `主体を開示: ${r.subject}`
              : '主体は伏せられています（role と組織のみ証明されています）'));
          if (r.role) target.append(make('p', 'data-list__meta',
            `役割: ${credentialRoleText[r.role] || r.role}`));
          if (r['bearer-presentable?']) {
            target.append(make('p', 'form-help',
              'この形式は所持者拘束を持たないため、提示者が主体本人であることは'
              + '証明されていません。'));
          }
        }
      } catch (error) {
        target.replaceChildren(make('p', 'data-list__meta', error.message));
      }
    });
    $('#credential-issue').addEventListener('click', async () => {
      const button = $('#credential-issue');
      button.disabled = true;
      credentialIssueStatus('発行しています…');
      try {
        const issued = await postJSON('/api/credentials/membership', {}, true);
        credentialIssueStatus(
          `#${issued['status-index']} を発行しました。credential 本体は保存していないので、`
          + 'この結果を holder へ渡してください。');
        $('#credential-verify-input').value = JSON.stringify(issued.credential, null, 2);
        await loadCredentials();
      } catch (error) {
        credentialIssueStatus(error.message);
      } finally {
        button.disabled = false;
      }
    });
    $('#credential-verify-form').addEventListener('submit', (event) => {
      event.preventDefault();
      verifyCredential('/api/credentials/verify');
    });
    $('#credential-verify-external').addEventListener('click', () =>
      verifyCredential('/api/credentials/verify/external'));

    // ── Bots ───────────────────────────────────────────────────────────────
    //
    // A Bot is a durable record on the server; nothing here decides anything
    // about one. This module picks services, makes the face, and renders what
    // /api/bots answers — including the cards, which are the parts of a turn
    // that cannot be prose because they have to be acted on.
    //
    // `botsState.status` is never computed here. The server derives it from
    // what is outstanding, and a second derivation in the client is how a
    // sidebar starts showing "working" for a Bot that is actually waiting.
    const botsState = {
      bots:[], catalog:[], modelProviders:[], providerReadiness:[],
      palette:{colors:[], glyphs:[]},
      selected:null, messages:[], picked:new Set(),
      draft:{color:'blue', glyph:'circle'}, loaded:false, busy:false,
      defaultWorkspace:'',
      browserAvailable:false, controller:null, runId:null, shellBusy:false,
      activeRuns:new Map(),
      latestTurn:null, threadVersion:null, syncTimer:null, syncing:false,
      slo:null, routines:[], routinesLoading:false
    };
    const syncBotsProjectContext = () => {
      const select = $('#bots-context-project-select');
      const selected = botsState.bots.find((bot) => bot.id === botsState.selected);
      select.disabled = !selected;
      select.value = selected?.['context-project-id'] || '';
    };
    $('#bots-context-project-select').addEventListener('change', async (event) => {
      const botId = botsState.selected;
      if (!botId) return;
      const nextProject = event.currentTarget.value || null;
      event.currentTarget.disabled = true;
      try {
        const data = await postJSON(`/api/bots/${botId}`,
          {'context-project-id':nextProject}, true);
        botsState.bots = data.bots || [];
        syncBotsProjectContext();
        renderBotsRail();
        botsSetStatus(nextProject
          ? 'この Project を会話contextにしました。権限やworkspaceは変わりません。'
          : 'この Bot の Project context を外しました。');
      } catch (error) {
        syncBotsProjectContext();
        botsSetStatus(error.message);
      }
    });
    const botAvatar = (node, avatar, status = null) => {
      node.dataset.color = avatar?.color || 'blue';
      node.dataset.glyph = avatar?.glyph || 'circle';
      node.dataset.variant = String(avatar?.variant || 0);
      if (status) node.dataset.status = status;
      else delete node.dataset.status;
      node.setAttribute('aria-hidden', 'true');
      return node;
    };
    const botsStatusText = {
      'idle':'待機中', 'working':'作業中',
      'waiting-approval':'承認待ち', 'waiting-connection':'接続待ち',
      'disabled':'停止中'
    };
    const botsSetStatus = (message) => {
      $('#bots-thread-status-line').textContent = message || '';
    };
    const botsGateLabels = {
      'sample-size':'24時間の標本数',
      'completion-rate':'24時間の完了率',
      'interactive-p90':'対話の応答時間',
      'provider-timeout':'モデル接続のタイムアウト',
      'tool-budget':'ツール上限での停止',
      'stale-running':'止まったままの実行',
      'duplicate-no-op':'重複・無変化の再通知',
      'quality-suite':'固定20タスクの出力品質',
      'seven-day':'7日間の再現性'
    };
    const botsGateState = {
      pass:['✓', '合格'], fail:['×', '不合格'],
      unmeasured:['—', '未計測'], 'insufficient-sample':['—', '標本不足']
    };
    const renderBotsSlo = () => {
      const statusNode = $('#bots-quality-status');
      const scoresNode = $('#bots-quality-scores');
      const gatesNode = $('#bots-quality-gates');
      const noteNode = $('#bots-quality-note');
      const slo = botsState.slo;
      statusNode.replaceChildren();
      scoresNode.replaceChildren();
      gatesNode.replaceChildren();
      if (!slo) {
        statusNode.append(make('span', 'bots-quality-status__badge', '未計測'));
        noteNode.textContent = '評価データを取得できませんでした。未計測は合格として扱いません。';
        return;
      }
      const status = slo.status || 'insufficient-sample';
      const statusText = status === 'pass' ? 'PASS' : status === 'fail' ? 'FAIL' : '計測不足';
      const badge = make('span', 'bots-quality-status__badge', statusText);
      badge.dataset.status = status;
      statusNode.append(badge, make('span', null, `評価時点 ${slo['as-of'] || '—'}`));
      [['S', '安定性', 'stability'], ['Q', '成功時品質', 'quality'],
       ['E', '実効品質', 'effective']].forEach(([symbol, label, key]) => {
        const card = make('div', 'bots-quality-score');
        card.append(make('span', 'bots-quality-score__label', `${symbol} · ${label}`),
                    make('strong', 'bots-quality-score__value',
                         slo.scores?.[key] == null ? '—' : `${slo.scores[key]} / 100`));
        scoresNode.append(card);
      });
      (slo.gates || []).forEach((gate) => {
        const state = gate.state || (gate['pass?'] ? 'pass' : 'fail');
        const [mark, stateText] = botsGateState[state] || botsGateState.fail;
        const row = make('li', 'bots-quality-gate');
        const markNode = make('span', 'bots-quality-gate__mark', mark);
        markNode.dataset.state = state;
        row.append(markNode,
                   make('strong', null, `${botsGateLabels[gate.id] || gate.id} · ${stateText}`),
                   make('span', 'bots-quality-gate__target', `基準: ${gate.target}`));
        gatesNode.append(row);
      });
      const quality = slo.quality;
      noteNode.textContent = quality
        ? `出力品質の固定評価: ${quality['sample-size']} / ${quality['required-sample-size']}タスク。` +
          (quality.state === 'measured' ? '合格判定に使用中です。' : '完了するまでは暫定値です。')
        : '出力品質は未計測です。未計測は合格として扱いません。';
    };
    const setBotsQualityOpen = (open) => {
      $('#bots-quality-panel').hidden = !open;
      $('#bots-quality').setAttribute('aria-expanded', String(open));
      if (open) {
        $('#bots-routines-panel').hidden = true;
        $('#bots-routines').setAttribute('aria-expanded', 'false');
        $('#bots-conversations-panel').hidden = true;
        $('#bots-conversations').setAttribute('aria-expanded', 'false');
        renderBotsSlo();
      }
    };
    const routineDate = (value) => value
      ? new Date(value).toLocaleString('ja-JP', {dateStyle:'short', timeStyle:'short'})
      : 'まだありません';
    const routineNext = (value) => {
      if (!value) return '未設定';
      return new Date(value).getTime() <= Date.now() ? '次の確認時' : routineDate(value);
    };
    const routineCadence = (routine) => {
      const minutes = routine.schedule?.['every-minutes'];
      if (!minutes) return '手動のみ';
      if (minutes === 60) return '1時間ごと';
      if (minutes === 1440) return '毎日';
      if (minutes === 10080) return '毎週';
      return `${minutes}分ごと`;
    };
    const routineStateText = {
      idle:'実行可能', disabled:'停止中', stale:'権限の見直しが必要',
      running:'実行中', 'waiting-approval':'承認待ち', completed:'完了',
      blocked:'停止', failed:'失敗', checkpointed:'継続中'
    };
    const renderBotRoutines = () => {
      const list = $('#bots-routines-list');
      list.replaceChildren();
      if (botsState.routinesLoading) {
        list.append(make('li', 'empty-state', '定期ジョブを読んでいます…'));
        return;
      }
      if (!botsState.routines.length) {
        list.append(make('li', 'empty-state',
          'まだ定期ジョブはありません。Botに仕事を一度実行してもらってから保存してください。'));
        return;
      }
      botsState.routines.forEach((routine) => {
        const row = make('li', 'bots-routine');
        const head = make('div', 'bots-routine__head');
        const dot = make('span', 'bots-routine__dot');
        dot.dataset.state = routine.status;
        const title = make('strong', null, routine.name || '名前のないジョブ');
        const enabled = make('input');
        enabled.type = 'checkbox';
        enabled.checked = Boolean(routine['enabled?']);
        enabled.setAttribute('aria-label', `${routine.name}を有効にする`);
        enabled.addEventListener('change', async () => {
          enabled.disabled = true;
          try {
            await postJSON(`/api/bots/${botsState.selected}/routines/${routine.id}`,
                           {'enabled?':enabled.checked}, true);
            await loadBotRoutines();
          } catch (error) {
            enabled.checked = !enabled.checked;
            $('#bots-routines-status').textContent = error.message;
          } finally { enabled.disabled = false; }
        });
        head.append(dot, title, enabled);
        row.append(head,
          make('div', 'bots-routine__meta',
            `${routineStateText[routine.status] || routine.status} · ${routineCadence(routine)} · 次回 ${routineNext(routine['next-run-at'])} · 最終 ${routineDate(routine['last-run-at'])}`));
        const actions = make('div', 'bots-routine__actions');
        const run = make('button', 'tool-button', '今すぐ実行');
        run.type = 'button';
        run.disabled = !routine['may-start?'];
        run.addEventListener('click', async () => {
          run.disabled = true;
          $('#bots-routines-status').textContent = `${routine.name}を実行しています…`;
          try {
            await postJSON(`/api/bots/${botsState.selected}/routines/${routine.id}/start`, {}, true);
            await Promise.all([loadBotRoutines(), refreshBotsThread()]);
            $('#bots-routines-status').textContent = `${routine.name}を実行しました。`;
          } catch (error) {
            $('#bots-routines-status').textContent = error.message;
          } finally { run.disabled = false; }
        });
        const cadence = make('select');
        [[15,'15分'],[30,'30分'],[60,'1時間'],[360,'6時間'],[1440,'毎日'],[10080,'毎週']]
          .forEach(([value, label]) => {
            const option = make('option', null, label);
            option.value = String(value);
            option.selected = value === routine.schedule?.['every-minutes'];
            cadence.append(option);
          });
        cadence.setAttribute('aria-label', `${routine.name}の繰り返し`);
        cadence.addEventListener('change', async () => {
          cadence.disabled = true;
          try {
            await postJSON(`/api/bots/${botsState.selected}/routines/${routine.id}`,
              {schedule:{kind:'every-minutes', 'every-minutes':Number(cadence.value)}}, true);
            await loadBotRoutines();
          } catch (error) { $('#bots-routines-status').textContent = error.message; }
          finally { cadence.disabled = false; }
        });
        const forget = make('button', 'tool-button', '削除');
        forget.type = 'button';
        forget.addEventListener('click', async () => {
          if (!window.confirm(`定期ジョブ「${routine.name}」を削除しますか？`)) return;
          forget.disabled = true;
          try {
            await postJSON(`/api/bots/${botsState.selected}/routines/${routine.id}/forget`, {}, true);
            await loadBotRoutines();
          } catch (error) { $('#bots-routines-status').textContent = error.message; }
        });
        actions.append(run, cadence, forget);
        row.append(actions);
        if ((routine.runs || []).length) {
          const history = make('ol', 'bots-routine__history');
          routine.runs.slice(0, 5).forEach((entry) => history.append(make('li', null,
            `${entry.source === 'schedule' ? '定期' : '手動'} · ${routineStateText[entry.state] || entry.state} · ${routineDate(entry['started-at'])}`)));
          row.append(make('div', 'bots-routine__meta', '最近の実行'), history);
        }
        list.append(row);
      });
    };
    async function loadBotRoutines() {
      if (!botsState.selected) return;
      const botId = botsState.selected;
      botsState.routinesLoading = true;
      renderBotRoutines();
      try {
        const request = await fetch(`/api/bots/${botId}/routines`);
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || '定期ジョブを読めませんでした。');
        if (botsState.selected === botId) botsState.routines = data.routines || [];
      } catch (error) {
        $('#bots-routines-status').textContent = error.message;
      } finally {
        botsState.routinesLoading = false;
        renderBotRoutines();
      }
    }
    const setBotRoutinesOpen = async (open) => {
      $('#bots-routines-panel').hidden = !open;
      $('#bots-routines').setAttribute('aria-expanded', String(open));
      if (open) {
        setBotsQualityOpen(false);
        $('#bots-conversations-panel').hidden = true;
        $('#bots-conversations').setAttribute('aria-expanded', 'false');
        await loadBotRoutines();
      }
    };
    const botsPhaseText = (phase, tool = null) => ({
      accepted:'依頼を受け付けました。',
      model:'モデルの応答を待っています…',
      'tool-proposed':tool ? `${tool} を確認しています…` : 'ツールを確認しています…',
      'tool-executed':tool ? `${tool} を実行しました。` : 'ツールを実行しました。',
      continuing:'Goal を継続しています…',
      verifying:'完了条件を確認しています…',
      'waiting-approval':'承認を待っています。',
      blocked:'外部条件により停止しました。',
      completed:'完了しました。',
      cancelled:'中止しました。',
      failed:'実行に失敗しました。',
      interrupted:'前回の実行はアプリの再起動で中断されました。'
    }[phase] || '実行しています…');
    const botsShowLastTurn = (bot) => {
      const turn = bot?.['last-turn'];
      if (turn?.state === 'interrupted' || turn?.state === 'failed') {
        botsSetStatus(botsPhaseText(turn.state, turn.tool));
      }
    };
    const botsActivityTime = (bot) => {
      const value = bot?.['activity-at'] || bot?.['last-message']?.at || bot?.['updated-at'];
      const parsed = value ? Date.parse(value) : 0;
      return Number.isFinite(parsed) ? parsed : 0;
    };
    const botsRecentFirst = (bots) => [...bots].sort((a, b) =>
      botsActivityTime(b) - botsActivityTime(a) ||
        a.name.localeCompare(b.name, 'ja'));
    const botsCompactTime = (value) => {
      const parsed = value ? new Date(value) : null;
      if (!parsed || Number.isNaN(parsed.getTime())) return '';
      const now = new Date();
      if (parsed.toDateString() === now.toDateString()) {
        return new Intl.DateTimeFormat('ja-JP', {
          hour:'2-digit', minute:'2-digit', hour12:false
        }).format(parsed);
      }
      const yesterday = new Date(now);
      yesterday.setDate(now.getDate() - 1);
      if (parsed.toDateString() === yesterday.toDateString()) return '昨日';
      return new Intl.DateTimeFormat('ja-JP', {month:'numeric', day:'numeric'}).format(parsed);
    };
    const renderBotsRun = (turn) => {
      const node = $('#bots-run');
      node.replaceChildren();
      node.hidden = !turn;
      if (!turn) return;
      const row = make('div', 'bots-run__row');
      const visibleState = turn.state === 'running' ? turn.phase : turn.state;
      row.append(make('span', 'bots-run__state',
                      botsPhaseText(visibleState, turn.tool)));
      const usage = turn.usage || {};
      const tokens = usage.total_tokens ?? usage.totalTokens ?? 0;
      const provider = [turn.provider, turn.model].filter(Boolean).join(' / ');
      row.append(make('span', 'bots-run__meta',
        `${turn['elapsed-seconds'] || 0}秒 · ${turn['tool-count'] || 0} tools · ${tokens} tokens`));
      if (provider) row.append(make('span', 'bots-run__meta', provider));
      node.append(row);
      if (turn.objective) node.append(make('div', 'bots-run__objective', turn.objective));
      const plan = turn.job?.plan || [];
      if (plan.length) {
        const list = make('ol', 'bots-run__plan');
        plan.forEach((step) => {
          const mark = step.state === 'verified' ? '✓' :
            (step.state === 'running' ? '…' : '○');
          list.append(make('li', `bots-run__step bots-run__step--${step.state}`,
                           `${mark} ${step.title}`));
        });
        node.append(list);
        const receipts = (turn.job.events || [])
          .filter((event) => event.kind === 'action/finished').length;
        const children = (turn.job.children || []).length;
        node.append(make('div', 'bots-run__meta',
          `AgentRun: ${turn.job.state} · ${children} child runs · ${receipts} execution receipts`));
      }
      if (tokens && turn.cost?.status === 'not-calculated') {
        node.append(make('div', 'bots-run__meta', '利用料: provider の請求額が未提供のため未算出'));
      }
      if (turn['error-type']) {
        const status = turn['error-status'] ? ` · HTTP ${turn['error-status']}` : '';
        node.append(make('div', 'bots-run__meta', `error: ${turn['error-type']}${status}`));
      }
    };
    const renderBotsRail = () => {
      const list = $('#bots-list');
      list.replaceChildren();
      const query = $('#bots-filter').value.trim().toLocaleLowerCase('ja');
      const visibleBots = botsRecentFirst(botsState.bots)
        .filter((bot) => {
          if (!query) return true;
          return [bot.name, bot.business?.name, bot.role?.name,
                  bot['last-message']?.text]
            .filter(Boolean).join(' ').toLocaleLowerCase('ja').includes(query);
        });
      const empty = $('#bots-rail-empty');
      empty.hidden = visibleBots.length > 0;
      empty.textContent = query ? '一致する Bot がいません' : 'まだ Bot がいません';
      visibleBots
        .forEach((bot) => {
        const item = make('button', 'bots-rail__item');
        item.type = 'button';
        item.setAttribute('aria-current', String(bot.id === botsState.selected));
        const preview = bot['last-message']?.text ||
          botsStatusText[bot.status] || bot.status;
        item.setAttribute('aria-label',
          `${bot.name}、${botsStatusText[bot.status] || bot.status}、${preview}`);
        const avatar = botAvatar(make('span', 'bot-avatar'), bot.avatar, bot.status);
        const copy = make('div', 'bots-rail__copy');
        const headline = make('span', 'bots-rail__headline');
        headline.append(make('span', 'bots-rail__name', bot.name));
        const time = botsCompactTime(bot['activity-at'] || bot['last-message']?.at);
        if (time) headline.append(make('span', 'bots-rail__time', time));
        copy.append(headline, make('span', 'bots-rail__last', preview));
        const dot = make('span', 'bots-dot');
        dot.dataset.status = bot.status;
        dot.title = botsStatusText[bot.status] || bot.status;
        item.append(avatar, copy, dot);
        item.addEventListener('click', () => selectBot(bot.id));
        const entry = make('li');
        entry.append(item);
        list.append(entry);
      });
      const badge = $('#bots-count');
      if (badge) {
        const needing = botsState.bots.filter((bot) =>
          bot.status === 'waiting-approval' || bot.status === 'waiting-connection').length;
        badge.textContent = needing ? String(needing) : '';
        badge.dataset.tone = needing ? 'warn' : 'ok';
      }
    };
    const renderBotsServiceGrid = () => {
      const query = $('#bots-service-search').value.trim().toLowerCase();
      const grid = $('#bots-service-grid');
      grid.replaceChildren();
      let noTools = 0;
      let noClient = 0;
      botsState.catalog.forEach((service) => {
        if (query && !service.name.toLowerCase().includes(query)) return;
        // A connector with no enabled tool cannot do anything for a Bot, and
        // offering it would be an invitation to authorize an account for
        // nothing. Shown, disabled, and labelled — not silently dropped.
        //
        // The same holds one step earlier: a connector whose OAuth client is
        // not configured on this machine has nothing to authorize AGAINST, so
        // picking it produces a Bot that can only ever answer 'connect first'
        // with a button that fails. Two reasons, reported apart, because they
        // are fixed in different places.
        const hasTools = service['enabled-tool-count'] > 0 && service['configurable?'];
        const authable = service['authable?'] !== false;
        const usable = hasTools && authable;
        if (!hasTools) noTools += 1;
        else if (!authable) noClient += 1;
        const tile = make('button', 'bots-tile');
        tile.type = 'button';
        tile.disabled = !usable;
        tile.setAttribute('aria-pressed', String(botsState.picked.has(service.id)));
        const copy = make('div', 'bots-tile__copy');
        copy.append(make('span', 'bots-tile__name', service.name),
                    make('span', 'bots-tile__meta',
                         usable
                           ? `${service['enabled-tool-count']} 個のツール${service['connected?'] ? '・接続済み' : ''}`
                           : hasTools
                             ? 'OAuth クライアント設定が必要です'
                             : 'このビルドでは有効なツールがありません'));
        tile.append(copy);
        if (botsState.picked.has(service.id)) {
          tile.append(make('span', 'bots-tile__check', '✓'));
        }
        tile.addEventListener('click', () => {
          if (botsState.picked.has(service.id)) botsState.picked.delete(service.id);
          else botsState.picked.add(service.id);
          renderBotsServiceGrid();
        });
        grid.append(tile);
      });
      $('#bots-service-note').textContent = [
        noTools ? `${noTools} 件はこのビルドに有効なツールが無いので選べません。` : '',
        noClient
          ? `${noClient} 件は OAuth クライアントが未設定なので選べません（Settings の接続に同じ表示が出ます）。`
          : '',
      ].filter(Boolean).join(' ');
      $('#bots-services-next').disabled = false;
    };
    const renderBotsPalette = () => {
      const preview = botAvatar($('#bots-avatar-preview'), botsState.draft);
      preview.textContent = '';
      const colorRow = $('#bots-color-row');
      colorRow.replaceChildren();
      botsState.palette.colors.forEach((color) => {
        const swatch = make('button', 'bots-swatch');
        swatch.type = 'button';
        swatch.setAttribute('role', 'radio');
        swatch.setAttribute('aria-checked', String(botsState.draft.color === color));
        swatch.setAttribute('aria-label', color);
        swatch.append(botAvatar(make('span', 'bot-avatar'),
                                {color, glyph:botsState.draft.glyph}));
        swatch.addEventListener('click', () => {
          botsState.draft.color = color; renderBotsPalette();
        });
        colorRow.append(swatch);
      });
      const glyphRow = $('#bots-glyph-row');
      glyphRow.replaceChildren();
      botsState.palette.glyphs.forEach((glyph) => {
        const swatch = make('button', 'bots-swatch');
        swatch.type = 'button';
        swatch.setAttribute('role', 'radio');
        swatch.setAttribute('aria-checked', String(botsState.draft.glyph === glyph));
        swatch.setAttribute('aria-label', glyph);
        swatch.append(botAvatar(make('span', 'bot-avatar'),
                                {color:botsState.draft.color, glyph}));
        swatch.addEventListener('click', () => {
          botsState.draft.glyph = glyph; renderBotsPalette();
        });
        glyphRow.append(swatch);
      });
    };
    const fillBotModels = (select, provider, currentModel = '') => {
      const models = [...(provider?.models || [])];
      if (currentModel && !models.includes(currentModel)) models.unshift(currentModel);
      select.replaceChildren();
      models.forEach((modelId) => {
        const option = make('option', null, modelId);
        option.value = modelId;
        option.selected = modelId === currentModel;
        select.append(option);
      });
      if (!select.value && provider?.model) select.value = provider.model;
      select.disabled = !models.length;
    };
    const renderBotsSuggestions = async () => {
      const holder = $('#bots-suggestions');
      holder.replaceChildren();
      let suggestions = [];
      try {
        const data = await postJSON('/api/bots/suggestions',
                                    {connectors:[...botsState.picked]}, true);
        suggestions = data.suggestions || [];
      } catch (_) { return; }
      suggestions.forEach((suggestion) => {
        const card = make('button', 'bots-suggestion');
        card.type = 'button';
        const copy = make('div', 'bots-tile__copy');
        copy.append(make('span', 'bots-suggestion__name', suggestion.name),
                    make('span', 'bots-suggestion__summary', suggestion.summary));
        card.append(botAvatar(make('span', 'bot-avatar'), suggestion.avatar), copy);
        card.addEventListener('click', () => {
          $('#bots-name').value = suggestion.name;
          $('#bots-brief').value = suggestion.brief || '';
          botsState.draft = {...suggestion.avatar};
          renderBotsPalette();
        });
        holder.append(card);
      });
    };
    const botsConnectionCard = (card, botId) => {
      const node = make('div', 'bots-card');
      node.append(make('div', 'bots-card__title', card.title));
      if (card.summary) node.append(make('div', 'bots-card__summary', card.summary));
      if ((card.scopes || []).length) {
        const scopes = make('ul', 'bots-card__scopes');
        card.scopes.forEach((scope) => scopes.append(make('li', null, scope)));
        node.append(scopes);
      }
      const row = make('div', 'bots-card__row');
      // One chip per ACCOUNT already held here. A person may hold two Google
      // accounts; they are two grants in two Keychain slots, and a card that
      // said only "Google — connected" would be answering a question this
      // application decided some time ago is not the question.
      (card.accounts || []).forEach((account) => {
        const chip = make('button', 'bots-chip', account.label || account.email);
        chip.type = 'button';
        chip.title = '名前を変える';
        chip.addEventListener('click', async () => {
          // `window.` is load bearing: `prompt` is bound near the top of this
          // file to the chat composer's textarea, so the bare name resolves to
          // an element and calling it throws.
          const label = window.prompt('このアカウントの呼び名', account.label || '');
          if (label === null) return;
          try {
            await postJSON('/api/bots/accounts/label',
                           {connection:account.id, label}, true);
            await refreshBotsThread();
          } catch (error) { botsSetStatus(error.message); }
        });
        row.append(chip);
      });
      const connect = async (button, addAccount) => {
        button.disabled = true;
        try {
          // The existing connect flow, unchanged. A Bot does not get a second
          // way to obtain a grant — it points at the one the app already has.
          const result = await postJSON(`/api/connections/${card.connector}/start`,
                                        {'add-account':addAccount}, true);
          location.assign(result.url);
        } catch (error) {
          button.disabled = false;
          botsSetStatus(error.message);
        }
      };
      if (card['authable?'] === false) {
        // Nothing to authorize against on this machine. Say so where the
        // button would have been, rather than letting somebody press it and
        // read the same fact as an error afterwards.
        const button = make('button', 'tool-button', '未設定');
        button.type = 'button';
        button.disabled = true;
        row.append(button);
        row.append(make('span', 'bots-card__state',
                        'この端末に OAuth クライアントがありません'));
      } else if ((card.accounts || []).length || card.state === 'connected') {
        // `connected` counts here as well as a listed account. A card written
        // while nothing was authorized lists none, and the server recomputes
        // its state from the provider rather than replaying what was stored
        // (ADR-0044) — so without this the same row said 接続済み and offered
        // 認証する, and the person pressed the button for something already
        // done.
        const another = make('button', 'tool-button', '＋ 別のアカウントを追加');
        another.type = 'button';
        another.addEventListener('click', () => connect(another, true));
        row.append(another);
      } else {
        const button = make('button', 'tool-button',
                            card.state === 'waiting' ? '認証画面を開き直す' : '認証する');
        button.type = 'button';
        button.addEventListener('click', () => connect(button, false));
        row.append(button);
      }
      const state = make('span', 'bots-card__state',
                         card.state === 'connected' ? '接続済み'
                           : card.state === 'waiting' ? '認証待ち' : '');
      state.dataset.state = card.state;
      row.append(state);
      node.append(row);
      return node;
    };
    const botsChoiceCard = (card, botId) => {
      const node = make('div', 'bots-card');
      node.append(make('div', 'bots-card__title', card.prompt));
      if (card.detail) node.append(make('div', 'bots-card__summary', card.detail));
      card.options.forEach((option) => {
        const button = make('button', 'bots-option');
        button.type = 'button';
        button.disabled = Boolean(card.answer);
        button.setAttribute('aria-pressed', String(card.answer === option.key));
        button.append(make('span', 'bots-option__key', option.key),
                      make('span', null, option.label));
        button.addEventListener('click', async () => {
          try {
            const data = await postJSON(
              `/api/bots/${botId}/cards/${card.id}/answer`, {answer:option.key}, true);
            botsState.messages = data.messages || [];
            renderBotsThread();
          } catch (error) { botsSetStatus(error.message); }
        });
        node.append(button);
      });
      return node;
    };
    const botsApprovalCard = (card, botId) => {
      const node = make('div', 'bots-card');
      node.append(make('div', 'bots-card__title', card.title));
      if (card.action) node.append(make('div', 'bots-card__summary', card.action));
      if (card.summary) node.append(make('div', 'bots-card__summary', card.summary));
      if (card.impact) node.append(make('div', 'bots-card__summary', card.impact));
      if (card.decision) {
        const state = make('span', 'bots-card__state',
                           card.decision === 'approved'
                             ? (card['decision-mode'] === 'omakase' ? 'おまかせ承認済み' : '承認済み')
                             : '却下しました');
        state.dataset.state = card.decision;
        node.append(state);
        return node;
      }
      if (card.standing === 'superseded') {
        // Asked under an instruction the person has since replaced. The server
        // refuses it, so rendering 承認して実行 would be a button whose only
        // outcome is an error — the same failure `authable?` prevents on a
        // connection card. Say what happened instead (ADR-0046).
        const state = make('span', 'bots-card__state', '古い指示のため取り下げ');
        state.dataset.state = 'superseded';
        node.append(state);
        return node;
      }
      const row = make('div', 'bots-card__row');
      const decide = async (decision, button) => {
        button.disabled = true;
        botsSetStatus(decision === 'approved' ? '実行しています…' : '取り消しています…');
        const shellCommand = decision === 'approved' && card.action === 'virtual_shell';
        if (shellCommand) {
          botsState.shellBusy = true;
          botsState.busy = true;
          botsCancel.hidden = false;
        }
        try {
          const data = await postJSON(
            `/api/bots/${botId}/cards/${card.id}/decide`, {decision}, true);
          botsState.messages = data.messages || [];
          renderBotsThread();
          botsSetStatus('');
          await loadBots({keepSelection:true});
        } catch (error) {
          button.disabled = false;
          botsSetStatus(error.message);
        } finally {
          if (shellCommand) {
            botsState.shellBusy = false;
            botsState.busy = false;
            botsCancel.hidden = true;
            resizeBotsInput();
          }
        }
      };
      const approve = make('button', 'primary-action', '承認して実行');
      approve.type = 'button';
      approve.addEventListener('click', () => decide('approved', approve));
      const reject = make('button', 'tool-button', 'しない');
      reject.type = 'button';
      reject.addEventListener('click', () => decide('rejected', reject));
      row.append(approve, reject);
      node.append(row);
      return node;
    };
    const botsThreadVersion = (data) => JSON.stringify({
      messages:(data.messages || []).map((message) => ({
        id:message.id, role:message.role, text:message.text,
        cards:message.cards || [], direction:message.direction,
        contextId:message['context-id']
      })),
      turn:data.turn ? {
        id:data.turn.id, state:data.turn.state, phase:data.turn.phase,
        tool:data.turn.tool, updatedAt:data.turn['updated-at']
      } : null,
      handoffs:(data.handoffs || []).map((handoff) => ({
        id:handoff.id, state:handoff.state, updatedAt:handoff['updated-at']
      }))
    });
    const renderBotsMessages = (bot, options = {}) => {
      const holder = $('#bots-messages');
      holder.replaceChildren();
      if (!bot) return;
      botsState.messages.forEach((message) => {
        const entry = make('li', 'bots-msg');
        entry.dataset.role = message.role;
        if (message.text) {
          const bubble = make('div', 'bots-msg__bubble');
          if (message.role === 'bot') renderMarkdown(bubble, message.text);
          else bubble.textContent = message.text;
          entry.append(bubble);
        }
        (message.cards || []).forEach((card) => {
          if (card.kind === 'connection') entry.append(botsConnectionCard(card, bot.id));
          else if (card.kind === 'choice') entry.append(botsChoiceCard(card, bot.id));
          else if (card.kind === 'approval') entry.append(botsApprovalCard(card, bot.id));
        });
        holder.append(entry);
      });
      requestAnimationFrame(() => {
        const scroll = $('#bots-thread-scroll');
        if (options.stickToBottom !== false) scroll.scrollTop = scroll.scrollHeight;
        else if (Number.isFinite(options.scrollTop)) scroll.scrollTop = options.scrollTop;
      });
    };
    const renderBotsThread = () => {
      const bot = botsState.bots.find((candidate) => candidate.id === botsState.selected);
      if (!bot) {
        $('#bots-messages').replaceChildren();
        return;
      }
      if (!botsState.latestTurn) botsState.latestTurn = bot['last-turn'] || null;
      renderBotsRun(botsState.latestTurn);
      botAvatar($('#bots-titlebar-avatar'), bot.avatar, bot.status);
      $('#bots-titlebar-name').textContent = bot.name;
      $('#bots-titlebar-status').textContent = botsStatusText[bot.status] || bot.status;
      botAvatar($('#bots-mobile-avatar'), bot.avatar, bot.status);
      $('#bots-mobile-name').textContent = bot.name;
      $('#bots-mobile-status').textContent = botsStatusText[bot.status] || bot.status;
      $('#bots-mobile-context').hidden = false;
      $('#bots-input').placeholder = `${bot.name} に頼む`;
      $('#bots-titlebar-identity').hidden = false;
      $('#bots-thread-tools').hidden = false;
      const panel = $('#bots-thread-panel');
      panel.replaceChildren();
      panel.append(make('strong', 'bots-settings__title', 'Bot設定'));
      panel.append(make('div', null,
        `届く範囲: ${bot['admitted-tools'].length} 個のツール` +
        `${bot['writes?']
          ? (bot['omakase?'] ? '（おまかせで書き込み）' : '（書き込みは承認のうえで実行）')
          : '（読み取りのみ）'}`));
      panel.append(make('div', null,
        `Model: ${bot['provider-id']} / ${bot.model}`));
      const commerce = bot.commerce || {};
      const commerceReadiness = commerce.readiness || {};
      const commerceStore = commerce.store || {};
      const commerceCard = make('div', 'bots-card');
      commerceCard.append(
        make('strong', null, 'Commerce'),
        make('div', 'bots-card__summary', commerceReadiness['ready?']
          ? 'DID・x402・住所・発送の開設準備が揃っています。公開はまだ実行していません。'
          : `開設準備: ${(commerceReadiness.checks || []).filter((check) => check['ready?']).length}` +
            ` / ${(commerceReadiness.checks || []).length}`));
      if (commerceStore['merchant-did']) {
        commerceCard.append(make('div', 'bots-card__summary',
          `${commerceStore['display-name']} · ${commerceStore['merchant-did']}`));
      }
      const missing = (commerceReadiness.checks || [])
        .filter((check) => !check['ready?'])
        .map((check) => check.label);
      if (missing.length) {
        commerceCard.append(make('div', 'bots-card__summary', `次に必要: ${missing.join('、')}`));
      }
      const commercePrompt = make('button', 'tool-button',
        commerce.status === 'not-configured' ? 'ショップ開設を始める' : 'Commerce設定を会話で進める');
      commercePrompt.type = 'button';
      commercePrompt.addEventListener('click', () => {
        const input = $('#bots-input');
        input.value = commerce.status === 'not-configured'
          ? 'このTenantでECショップを開設したい。法人か個人事業主かを確認して、DID・法的表示・x402・発送を順に設定して。'
          : 'ショップ開設状況を確認して、不足しているCommerce設定を次の1件から進めて。公開済みとは扱わないで。';
        input.focus();
        input.dispatchEvent(new Event('input', {bubbles:true}));
      });
      commerceCard.append(commercePrompt);
      panel.append(commerceCard);
      if (bot['workforce-key']) {
        const workforceCard = make('div', 'bots-card');
        workforceCard.append(
          make('strong', null,
            `${bot.business?.name || '事業'} · ${bot.role?.name || '職務Bot'}`),
          make('div', 'bots-card__summary', bot.brief || ''));
        const responsibilities = bot.responsibilities || [];
        if (responsibilities.length) {
          const list = make('ul', 'bots-card__scopes');
          responsibilities.forEach((item) => list.append(make('li', null, item)));
          workforceCard.append(make('div', 'bots-card__summary', 'Responsibility'), list);
        }
        const decisions = {
          autonomous:'自律', 'voice-required':'合議',
          'approval-required':'承認必須', blocked:'禁止'
        };
        const capabilities = bot['capability-policy'] || [];
        if (capabilities.length) {
          const list = make('ul', 'bots-card__scopes');
          capabilities.forEach((entry) => list.append(make('li', null,
            `${entry.capability}: ${decisions[entry.decision] || entry.decision}`)));
          workforceCard.append(make('div', 'bots-card__summary', 'Capability policy'), list);
        }
        const job = bot['resident-job'];
        if (job) {
          const next = job['next-run-at']
            ? new Date(job['next-run-at']).toLocaleString('ja-JP') : '未設定';
          workforceCard.append(make('div', 'bots-card__state',
            `${job['enabled?'] ? '常駐中' : '停止中'} · ${job['cadence-minutes']}分周期 · 次回 ${next}`));
        }
        workforceCard.append(make('div', 'form-help',
          'Capability policy は職務上の境界です。実行権限は上の「届く範囲」と承認ゲートを越えません。'));
        panel.append(workforceCard);
      }
      const mailboxCard = make('div', 'bots-card');
      mailboxCard.append(make('strong', null, 'Mailbox'),
        make('div', null, bot.email || 'アドレス未発行'));
      const mailboxStatus = make('div', null,
        bot['mailbox-ready?'] ? '送受信できます' : '受信先アカウントを1つ選んでください');
      const mailboxActions = make('div', 'bots-card__row');
      const openMailbox = make('button', 'tool-button', '受信箱を読む');
      openMailbox.type = 'button';
      openMailbox.addEventListener('click', async () => {
        openMailbox.disabled = true;
        try {
          const request = await fetch(`/api/bots/${bot.id}/mailbox`);
          const data = await request.json();
          if (!request.ok) throw new Error(data?.error?.message || 'Mailbox を読めませんでした。');
          mailboxStatus.textContent = `受信 ${data.inbound.length} 件 / 送信 ${data.sent.length} 件`;
          const list = make('ul');
          data.inbound.slice(0, 10).forEach((mail) =>
            list.append(make('li', null, `${mail.subject || '(件名なし)'} — ${mail['from-email'] || ''}`)));
          mailboxCard.append(list);
        } catch (error) { botsSetStatus(error.message); }
        finally { openMailbox.disabled = false; }
      });
      const provisionMailbox = make('button', 'tool-button', '受信先を接続');
      provisionMailbox.type = 'button';
      provisionMailbox.hidden = Boolean(bot['mailbox-ready?']);
      provisionMailbox.addEventListener('click', async () => {
        provisionMailbox.disabled = true;
        try {
          await postJSON(`/api/bots/${bot.id}/mailbox/provision`, {}, true);
          mailboxStatus.textContent = '送受信できます';
          provisionMailbox.hidden = true;
        } catch (error) { botsSetStatus(error.message); }
        finally { provisionMailbox.disabled = false; }
      });
      const sendDetails = make('details');
      const sendSummary = make('summary', null, 'この Bot からメールを送る');
      const recipient = make('input'); recipient.type = 'email'; recipient.placeholder = 'to@example.com';
      const subject = make('input'); subject.type = 'text'; subject.placeholder = '件名';
      const mailText = make('textarea'); mailText.placeholder = '本文';
      const sendButton = make('button', 'tool-button', '送信'); sendButton.type = 'button';
      sendButton.disabled = !bot['writes?'];
      sendButton.addEventListener('click', async () => {
        sendButton.disabled = true;
        try {
          await postJSON(`/api/bots/${bot.id}/mailbox/send`,
            {to:recipient.value, subject:subject.value, text:mailText.value}, true);
          mailboxStatus.textContent = '送信しました';
          recipient.value = ''; subject.value = ''; mailText.value = '';
        } catch (error) { botsSetStatus(error.message); }
        finally { sendButton.disabled = !bot['writes?']; }
      });
      sendDetails.append(sendSummary, recipient, subject, mailText, sendButton);
      mailboxActions.append(openMailbox, provisionMailbox);
      mailboxCard.append(mailboxStatus, mailboxActions, sendDetails);
      panel.append(mailboxCard);
      const modelEditor = make('div', 'bots-card__row');
      const providerSelect = make('select');
      providerSelect.setAttribute('aria-label', 'Model provider');
      botsState.modelProviders.forEach((provider) => {
        const option = make('option', null, provider.name || provider.id);
        option.value = provider.id;
        option.dataset.model = provider.model || '';
        option.selected = provider.id === bot['provider-id'];
        providerSelect.append(option);
      });
      const modelInput = make('select');
      modelInput.setAttribute('aria-label', 'Model');
      const selectedProvider = botsState.modelProviders.find(
        (provider) => provider.id === bot['provider-id']);
      fillBotModels(modelInput, selectedProvider, bot.model || selectedProvider?.model);
      providerSelect.addEventListener('change', () => {
        const provider = botsState.modelProviders.find(
          (candidate) => candidate.id === providerSelect.value);
        fillBotModels(modelInput, provider, provider?.model);
      });
      const saveModel = make('button', 'tool-button', 'Model を変更');
      saveModel.type = 'button';
      saveModel.disabled = !botsState.modelProviders.length;
      saveModel.addEventListener('click', async () => {
        if (!modelInput.value.trim()) {
          botsSetStatus('Model id を入れてください。');
          return;
        }
        saveModel.disabled = true;
        try {
          const data = await postJSON(`/api/bots/${bot.id}`, {
            'provider-id':providerSelect.value, model:modelInput.value.trim()
          }, true);
          botsState.bots = data.bots || [];
          botsState.modelProviders = data['model-providers'] || botsState.modelProviders;
          renderBotsRail();
          renderBotsThread();
          botsSetStatus('Model を変更しました。');
        } catch (error) {
          saveModel.disabled = false;
          botsSetStatus(error.message);
        }
      });
      modelEditor.append(providerSelect, modelInput, saveModel);
      panel.append(modelEditor);
      const authorityEditor = make('div', 'bots-card');
      authorityEditor.append(make('strong', null, '自律実行と権限'));
      const writesBox = make('input');
      writesBox.type = 'checkbox';
      writesBox.checked = Boolean(bot['writes?']);
      writesBox.setAttribute('aria-label', 'ファイル・Git・接続先への書き込みを許可');
      const omakaseBox = make('input');
      omakaseBox.type = 'checkbox';
      omakaseBox.checked = Boolean(bot['omakase?']);
      omakaseBox.setAttribute('aria-label', 'おまかせモード');
      const browserBox = make('input');
      browserBox.type = 'checkbox';
      browserBox.checked = Boolean(bot['browser?']);
      browserBox.disabled = !botsState.browserAvailable;
      browserBox.setAttribute('aria-label', 'Bot専用の分離ブラウザーを許可');
      const peersBox = make('input');
      peersBox.type = 'checkbox';
      peersBox.checked = Boolean(bot['peers?']);
      peersBox.setAttribute('aria-label', 'ほかのBotとの書き置きを許可');
      const authorityOption = (box, title, help) => {
        const label = make('label', 'bots-permission');
        const copy = make('span', 'bots-permission__copy');
        copy.append(make('span', null, title), make('span', 'bots-permission__help', help));
        label.append(box, copy);
        return label;
      };
      authorityEditor.append(
        authorityOption(writesBox, '書き込みを許可',
          '選択したworkspaceと、明示的に接続したサービスの範囲だけです。'),
        authorityOption(omakaseBox, '自律モード',
          '許可済みの操作を待たずに実行し、承認receiptを会話に残します。渡していないツールは、自分で承認しても使えません。'),
        authorityOption(browserBox, '分離ブラウザー',
          botsState.browserAvailable
            ? 'このBot専用プロファイルで画面認識・操作します。'
            : 'このマシンのSettingsで分離ブラウザーが無効です。'),
        authorityOption(peersBox, 'Bot間連携',
          'ほかのBotへ書き置きできます。ツール・アカウント・秘密は渡しません。'));
      const saveAuthority = make('button', 'tool-button', '権限を保存');
      saveAuthority.type = 'button';
      saveAuthority.addEventListener('click', async () => {
        saveAuthority.disabled = true;
        try {
          const data = await postJSON(`/api/bots/${bot.id}`, {
            'writes?':writesBox.checked,
            'omakase?':omakaseBox.checked,
            'browser?':browserBox.checked,
            'peers?':peersBox.checked
          }, true);
          botsState.bots = data.bots || [];
          renderBotsRail();
          renderBotsThread();
          botsSetStatus('この Bot の自律実行と権限を保存しました。');
        } catch (error) {
          saveAuthority.disabled = false;
          botsSetStatus(error.message);
        }
      });
      authorityEditor.append(saveAuthority);
      panel.append(authorityEditor);
      const codingEditor = make('div', 'bots-card__row');
      const codingBox = make('input');
      codingBox.type = 'checkbox';
      codingBox.checked = Boolean(bot['coding?']);
      codingBox.setAttribute('aria-label', 'この PC の Git workspace で coding する');
      const virtualShellBox = make('input');
      virtualShellBox.type = 'checkbox';
      virtualShellBox.checked = Boolean(bot['virtual-shell?']);
      virtualShellBox.setAttribute('aria-label', '隔離された仮想環境で汎用shellを使う');
      const workspaceInput = make('input');
      workspaceInput.type = 'text';
      workspaceInput.maxLength = 4096;
      workspaceInput.value = bot.workspace || '';
      workspaceInput.placeholder = '/Users/name/github/project';
      workspaceInput.setAttribute('aria-label', 'Git workspace の絶対パス');
      const saveCoding = make('button', 'tool-button', 'Workspace を変更');
      saveCoding.type = 'button';
      saveCoding.addEventListener('click', async () => {
        if ((codingBox.checked || virtualShellBox.checked) && !workspaceInput.value.trim()) {
          botsSetStatus('Git workspace の絶対パスを入れてください。');
          return;
        }
        saveCoding.disabled = true;
        try {
          const data = await postJSON(`/api/bots/${bot.id}`, {
            'coding?':codingBox.checked,
            'virtual-shell?':virtualShellBox.checked,
            workspace:workspaceInput.value.trim()
          }, true);
          botsState.bots = data.bots || [];
          renderBotsRail();
          renderBotsThread();
          botsSetStatus(codingBox.checked || virtualShellBox.checked
            ? 'Git workspace と仮想環境を変更しました。'
            : 'Coding と仮想shellを無効にしました。');
        } catch (error) {
          saveCoding.disabled = false;
          botsSetStatus(error.message);
        }
      });
      codingEditor.append(codingBox, virtualShellBox, workspaceInput, saveCoding);
      panel.append(codingEditor);
      panel.append(make('div', null,
        bot['virtual-shell?']
          ? `仮想shell: Bot専用・networkなし・全command承認${bot['virtual-shell-ready?'] ? '（ready）' : '（image未準備）'}`
          : 'Local coding: 読み取りは自動、ファイル変更と commit は毎回承認。'));
      if (bot['grant-widens?']) {
        // Surfaced rather than repaired: the two readings need different
        // answers and both need a person to see them.
        panel.append(make('div', null,
          'この Bot には、この配備で有効になっていないツールが指定されています。Settings で有効にするか、この Bot の権限を見直してください。'));
      }
      if (bot['peers?']) {
        panel.append(make('div', null,
          'ピア: ほかの Bot への書き置きができます（相手も有効にしている場合）。'));
      }
      if (bot['browser-ready?']) {
        panel.append(make('div', null,
          '分離ブラウザー: この Bot 専用のプロファイル（実行前に承認する）'));
      } else if (bot['browser?']) {
        panel.append(make('div', null,
          '分離ブラウザーは依頼されていますが、このマシンの Settings で有効になっていません。'));
      }
      if (bot['admitted-tools'].length) {
        const list = make('ul');
        bot['admitted-tools'].forEach((tool) => list.append(make('li', null, tool)));
        panel.append(list);
      }
      renderBotsMessages(bot);
    };
    const refreshBotsThread = async () => {
      if (!botsState.selected) return;
      const request = await fetch(`/api/bots/${botsState.selected}/messages`);
      const data = await request.json();
      if (!request.ok) throw new Error(data?.error?.message || '会話を読めませんでした。');
      botsState.messages = data.messages || [];
      botsState.latestTurn = data.turn || botsState.latestTurn;
      botsState.threadVersion = botsThreadVersion(data);
      renderBotsThread();
    };
    const showBotsPane = () => {
      const hasBots = botsState.bots.length > 0;
      $('#bots-onboard').hidden = hasBots && Boolean(botsState.selected);
      $('#bots-thread').hidden = !(hasBots && botsState.selected);
      const selected = hasBots && Boolean(botsState.selected);
      $('#bots-titlebar-identity').hidden = !selected;
      $('#bots-mobile-context').hidden = !selected;
      $('#bots-thread-tools').hidden = !selected;
      $('#bots-routines').hidden = !selected;
      syncBotsProjectContext();
      if (!selected) setBotRoutinesOpen(false);
    };
    const selectBot = async (botId) => {
      botsState.selected = botId;
      botsState.routines = [];
      const selectedBot = botsState.bots.find((bot) => bot.id === botId);
      botsState.latestTurn = selectedBot?.['last-turn'] || null;
      $('#bots-goal').checked = Boolean(selectedBot?.['coding?'] || selectedBot?.['virtual-shell?']);
      renderBotsRail();
      showBotsPane();
      syncBotsProjectContext();
      try {
        const request = await fetch(`/api/bots/${botId}/messages`);
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || '会話を読めませんでした。');
        botsState.messages = data.messages || [];
        botsState.threadVersion = botsThreadVersion(data);
        renderBotsThread();
        botsShowLastTurn(botsState.bots.find((candidate) => candidate.id === botId));
      } catch (error) { botsSetStatus(error.message); }
      finally { if (typeof resizeBotsInput === 'function') resizeBotsInput(); }
    };
    const stopBotsRealtime = () => {
      if (botsState.syncTimer) window.clearTimeout(botsState.syncTimer);
      botsState.syncTimer = null;
    };
    const scheduleBotsRealtime = (delay = 1000) => {
      stopBotsRealtime();
      if (!appUnlocked || currentView !== 'bots') return;
      botsState.syncTimer = window.setTimeout(syncBotsFromResident, delay);
    };
    const syncBotsFromResident = async () => {
      botsState.syncTimer = null;
      if (!appUnlocked || currentView !== 'bots') return;
      if (document.hidden || botsState.activeRuns.has(botsState.selected) ||
          botsState.shellBusy || botsState.syncing || !botsState.selected) {
        scheduleBotsRealtime(document.hidden ? 5000 : 1000);
        return;
      }
      botsState.syncing = true;
      const botId = botsState.selected;
      try {
        const request = await fetch(`/api/bots/${botId}/messages`, {cache:'no-store'});
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || '会話を同期できませんでした。');
        if (botsState.selected !== botId) return;
        const version = botsThreadVersion(data);
        if (version !== botsState.threadVersion) {
          const scroll = $('#bots-thread-scroll');
          const previousTop = scroll.scrollTop;
          const stickToBottom = scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 48;
          botsState.messages = data.messages || [];
          botsState.latestTurn = data.turn || null;
          botsState.threadVersion = version;
          const overviewRequest = await fetch('/api/bots', {cache:'no-store'});
          const overview = await overviewRequest.json();
          if (overviewRequest.ok && botsState.selected === botId) {
            botsState.bots = overview.bots || botsState.bots;
            renderBotsRail();
          }
          if (botsState.selected !== botId) return;
          const bot = botsState.bots.find((candidate) => candidate.id === botId);
          renderBotsRun(botsState.latestTurn);
          if (bot) {
            botAvatar($('#bots-titlebar-avatar'), bot.avatar, bot.status);
            $('#bots-titlebar-status').textContent = botsStatusText[bot.status] || bot.status;
          }
          renderBotsMessages(bot, {stickToBottom, scrollTop:previousTop});
          botsSetStatus('CLI / MCP からの会話を同期しました。');
        }
      } catch (error) {
        // A transient resident restart should not turn a still-readable thread
        // into an error pane. Keep the conversation and retry; a successful
        // manual action will still surface its own error through the normal UI.
      } finally {
        botsState.syncing = false;
        scheduleBotsRealtime();
      }
    };
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden && currentView === 'bots') scheduleBotsRealtime(0);
    });
    const loadBots = async (options = {}) => {
      try {
        const request = await fetch('/api/bots');
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || 'Bots を読めませんでした。');
        botsState.bots = data.bots || [];
        botsState.catalog = data.catalog || [];
        botsState.modelProviders = data['model-providers'] || [];
        botsState.providerReadiness = data['model-provider-readiness'] || [];
        botsState.palette = data.palette || botsState.palette;
        botsState.defaultWorkspace = data['default-workspace'] || '';
        botsState.browserAvailable = Boolean(data['browser-available?']);
        botsState.slo = data.slo || null;
        botsState.loaded = true;
        renderBotsSlo();
        if (!$('#bots-workspace').value && botsState.defaultWorkspace) {
          $('#bots-workspace').value = botsState.defaultWorkspace;
        }
        if (!options.keepSelection && !botsState.selected && botsState.bots.length) {
          await selectBot(botsRecentFirst(botsState.bots)[0].id);
          return;
        }
        renderBotsRail();
        renderBotsServiceGrid();
        renderBotsPalette();
        if (!botsState.bots.length) {
          $('#bots-step-services').hidden = true;
          $('#bots-step-create').hidden = false;
        }
        showBotsPane();
        if (botsState.selected) renderBotsThread();
      } catch (error) { botsSetStatus(error.message); }
    };
    $('#bots-filter').addEventListener('input', renderBotsRail);
    $('#bots-service-search').addEventListener('input', renderBotsServiceGrid);
    $('#bots-services-next').addEventListener('click', () => {
      $('#bots-step-services').hidden = true;
      $('#bots-step-create').hidden = false;
      renderBotsPalette();
      renderBotsSuggestions();
    });
    $('#bots-new').addEventListener('click', () => {
      botsState.selected = null;
      botsState.messages = [];
      $('#bots-step-services').hidden = true;
      $('#bots-step-create').hidden = false;
      $('#bots-workspace').value = botsState.defaultWorkspace || '';
      if (botsState.palette.colors.length && botsState.palette.glyphs.length) {
        const bytes = new Uint32Array(2); crypto.getRandomValues(bytes);
        botsState.draft = {
          color:botsState.palette.colors[bytes[0] % botsState.palette.colors.length],
          glyph:botsState.palette.glyphs[bytes[1] % botsState.palette.glyphs.length]
        };
        renderBotsPalette();
      }
      renderBotsRail();
      renderBotsServiceGrid();
      showBotsPane();
    });
    $('#bots-pick-services').addEventListener('click', () => {
      $('#bots-step-create').hidden = true;
      $('#bots-step-services').hidden = false;
      renderBotsServiceGrid();
    });
    $('#bots-workforce').addEventListener('click', async () => {
      const button = $('#bots-workforce');
      button.disabled = true;
      botsSetStatus('8事業の職務Botを照合しています…');
      try {
        const status = await postJSON('/api/bots/workforce/provision', {}, true);
        await loadBots({keepSelection:true});
        botsSetStatus(
          `${status.businesses}事業 / ${status.bots}職務Botを常駐化しました。` +
          '既存の会話と実行履歴は保持されています。');
      } catch (error) {
        botsSetStatus(error.message);
      } finally {
        button.disabled = false;
      }
    });
    $('#bots-quality').addEventListener('click', () =>
      setBotsQualityOpen($('#bots-quality').getAttribute('aria-expanded') !== 'true'));
    $('#bots-quality-close').addEventListener('click', () => setBotsQualityOpen(false));
    $('#bots-routines').addEventListener('click', () =>
      setBotRoutinesOpen($('#bots-routines').getAttribute('aria-expanded') !== 'true'));
    $('#bots-routines-close').addEventListener('click', () => setBotRoutinesOpen(false));
    $('#bots-routine-create').addEventListener('submit', async (event) => {
      event.preventDefault();
      if (!botsState.selected) return;
      const button = event.currentTarget.querySelector('button[type="submit"]');
      const name = $('#bots-routine-name').value.trim();
      const intent = $('#bots-routine-intent').value.trim();
      if (!name || !intent) {
        $('#bots-routines-status').textContent = 'ジョブ名と目的を入力してください。';
        return;
      }
      button.disabled = true;
      $('#bots-routines-status').textContent = '直前の仕事を定期ジョブに保存しています…';
      try {
        await postJSON(`/api/bots/${botsState.selected}/routines`, {
          name, intent,
          schedule:{kind:'every-minutes',
                    'every-minutes':Number($('#bots-routine-cadence').value)}
        }, true);
        $('#bots-routine-name').value = '';
        $('#bots-routine-intent').value = '';
        await loadBotRoutines();
        $('#bots-routines-status').textContent = '定期ジョブを作成しました。';
      } catch (error) {
        $('#bots-routines-status').textContent = error.message;
      } finally { button.disabled = false; }
    });
    $('#bots-create').addEventListener('click', async () => {
      const button = $('#bots-create');
      const name = $('#bots-name').value.trim();
      if (!name) { $('#bots-create-status').textContent = '名前を入れてください。'; return; }
      if (!$('#bots-workspace').value.trim()) {
        $('#bots-create-status').textContent = 'Git workspace の絶対パスを入れてください。';
        return;
      }
      button.disabled = true;
      $('#bots-create-status').textContent = '作成しています…';
      try {
        const data = await postJSON('/api/bots', {
          name,
          avatar:{color:botsState.draft.color, glyph:botsState.draft.glyph},
          brief:$('#bots-brief').value,
          connectors:[...botsState.picked],
          'writes?':true,
          'omakase?':true,
          'browser?':botsState.browserAvailable,
          'peers?':true,
          'coding?':true,
          'virtual-shell?':false,
          workspace:$('#bots-workspace').value.trim()
        }, true);
        botsState.bots = data.bots || [];
        botsState.catalog = data.catalog || [];
        $('#bots-create-status').textContent = '';
        $('#bots-name').value = '';
        $('#bots-brief').value = '';
        const created = botsState.bots[botsState.bots.length - 1];
        if (created) await selectBot(created.id);
      } catch (error) {
        $('#bots-create-status').textContent = error.message;
      } finally { button.disabled = false; }
    });
    $('#bots-thread-tools').addEventListener('click', (event) => {
      const panel = $('#bots-thread-panel');
      panel.hidden = !panel.hidden;
      event.currentTarget.setAttribute('aria-expanded', String(!panel.hidden));
    });

    const botsInput = $('#bots-input');
    const botsCancel = $('#bots-cancel');
    const selectedBotsRun = () => botsState.activeRuns.get(botsState.selected) || null;
    const resizeBotsInput = () => {
      botsInput.style.height = 'auto';
      botsInput.style.height = `${Math.min(botsInput.scrollHeight, 192)}px`;
      const active = selectedBotsRun();
      $('#bots-send').disabled = !botsInput.value.trim() || botsState.shellBusy;
      $('#bots-send').textContent = active ? '追加で伝える' : '送る';
      $('#bots-goal').disabled = Boolean(active);
      botsCancel.hidden = !(active || botsState.shellBusy);
    };
    botsInput.addEventListener('input', resizeBotsInput);
    botsInput.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        $('#bots-form').requestSubmit();
      }
    });
    const openBotsStream = async (botId, text, runId, goal, signal) => {
      if (!identityState?.csrf) await refreshIdentityForWrite();
      const send = () => fetch(`/api/bots/${botId}/messages/stream`, {
        method:'POST', headers:identityHeaders(), signal,
        body:JSON.stringify({text, goal, 'run-id':runId})
      });
      let request = await send();
      if (request.status === 403) {
        const failure = await request.json();
        if (failure?.error?.type === 'invalid-csrf') {
          await refreshIdentityForWrite();
          request = await send();
        } else throw new Error(failure?.error?.message || 'Bot に送信できませんでした。');
      }
      if (!request.ok) {
        const failure = await request.json();
        throw new Error(failure?.error?.message || 'Bot に送信できませんでした。');
      }
      return request;
    };
    const readBotsStream = async (request, run, onPhase) => {
      const reader = request.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      while (true) {
        const {value, done} = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), {stream:!done});
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';
        for (const line of lines) {
          if (!line.trim()) continue;
          const frame = JSON.parse(line);
          if (frame.type === 'delta') {
            run.provisional.dataset.markdown =
              (run.provisional.dataset.markdown || '') + (frame.content || '');
            renderMarkdown(run.provisional, run.provisional.dataset.markdown);
            if (botsState.selected === run.botId) botsSetStatus('応答中…');
          } else if (frame.type === 'phase') {
            onPhase(frame);
            run.turn = {...(run.turn || {}), state:'running', phase:frame.phase,
              tool:frame.tool || run.turn?.tool,
              'tool-count':frame['tool-count'] || run.turn?.['tool-count'] || 0};
            if (botsState.selected === run.botId && botsState.latestTurn) {
              botsState.latestTurn = run.turn;
              renderBotsRun(botsState.latestTurn);
            }
            if (botsState.selected === run.botId) {
              botsSetStatus(botsPhaseText(frame.phase, frame.tool));
            }
          } else if (frame.type === 'followup-applied') {
            if (botsState.selected === run.botId) {
              const followupIds = new Set((frame.followups || []).map((item) => item.id));
              const anchors = [...$('#bots-messages').querySelectorAll('[data-followup-id]')]
                .filter((node) => followupIds.has(node.dataset.followupId));
              const entry = make('li', 'bots-msg');
              entry.dataset.role = 'bot';
              run.provisional = make('div', 'bots-msg__bubble');
              entry.append(run.provisional);
              const anchor = anchors[anchors.length - 1];
              if (anchor) anchor.after(entry); else $('#bots-messages').append(entry);
              botsSetStatus('追加メッセージを次のステップに反映しました。');
            } else {
              run.provisional = make('div', 'bots-msg__bubble');
            }
          } else if (frame.type === 'done') {
            run.messages = frame.messages || [];
            run.turn = frame.turn || run.turn;
            if (botsState.selected === run.botId) {
              botsState.messages = run.messages;
              botsState.latestTurn = run.turn || botsState.latestTurn;
            }
          } else if (frame.type === 'error') {
            run.turn = frame.turn || run.turn;
            if (botsState.selected === run.botId) {
              botsState.latestTurn = run.turn || botsState.latestTurn;
              renderBotsRun(botsState.latestTurn);
            }
            throw new Error(frame.message || 'Bot の実行に失敗しました。');
          }
        }
        if (done) break;
      }
    };
    const followDetachedGoal = async (run, signal) => {
      for (let poll = 0; poll < 1200; poll += 1) {
        if (signal.aborted) throw new DOMException('Aborted', 'AbortError');
        await new Promise((resolve) => window.setTimeout(resolve, 1000));
        const request = await fetch(`/api/bots/${run.botId}/messages`, {cache:'no-store'});
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || '会話を読めませんでした。');
        run.messages = data.messages || [];
        run.turn = data.turn || run.turn;
        if (botsState.selected === run.botId) {
          botsState.messages = run.messages;
          botsState.latestTurn = run.turn;
          renderBotsRun(run.turn);
          renderBotsThread();
        }
        if (run.turn?.id === run.runId && run.turn?.state !== 'running') return;
      }
      throw new Error('Goal は background で継続しています。後でこの Bot を開いて確認してください。');
    };
    $('#bots-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const text = botsInput.value.trim();
      if (!text || !botsState.selected || botsState.shellBusy) return;
      const botId = botsState.selected;
      const active = botsState.activeRuns.get(botId);
      if (active) {
        try {
          const queued = await postJSON(
            `/api/bots/${botId}/messages/${encodeURIComponent(active.runId)}/followups`,
            {text}, true);
          botsInput.value = '';
          resizeBotsInput();
          if (botsState.selected === botId) {
            const personEntry = make('li', 'bots-msg');
            personEntry.dataset.role = 'person';
            personEntry.dataset.followupId = queued.id;
            personEntry.append(make('div', 'bots-msg__bubble', text));
            $('#bots-messages').append(personEntry);
            botsSetStatus(`追加メッセージを受け付けました（待機 ${queued.queued}件）。`);
          }
        } catch (error) {
          botsSetStatus(error.message);
        }
        return;
      }
      const runId = crypto.randomUUID();
      const goal = $('#bots-goal').checked;
      const startedAt = Date.now();
      const progress = {phase:'accepted', tool:null};
      const turn = {
        id:runId, 'goal?':goal, objective:goal ? text : null,
        state:'running', phase:'accepted', 'elapsed-seconds':0,
        'tool-count':0, 'followup-count':0, usage:null
      };
      const controller = new AbortController();
      botsInput.value = '';
      const entry = make('li', 'bots-msg');
      entry.dataset.role = 'bot';
      const provisional = make('div', 'bots-msg__bubble');
      entry.append(provisional);
      const run = {botId, runId, goal, controller, provisional, turn, messages:[]};
      botsState.activeRuns.set(botId, run);
      botsState.latestTurn = turn;
      renderBotsRun(turn);
      resizeBotsInput();
      const personEntry = make('li', 'bots-msg');
      personEntry.dataset.role = 'person';
      personEntry.append(make('div', 'bots-msg__bubble', text));
      $('#bots-messages').append(personEntry, entry);
      const elapsed = window.setInterval(() => {
        const seconds = Math.floor((Date.now() - startedAt) / 1000);
        if (run.turn?.id === runId) {
          run.turn['elapsed-seconds'] = seconds;
          if (botsState.selected === botId) {
            botsState.latestTurn = run.turn;
            renderBotsRun(run.turn);
          }
        }
        if (run.provisional.textContent || botsState.selected !== botId) return;
        const phase = botsPhaseText(progress.phase, progress.tool);
        botsSetStatus(seconds >= 30
          ? `${phase} 通常より時間がかかっています… ${seconds}秒`
          : `${phase} ${seconds}秒`);
      }, 1000);
      botsSetStatus(`${botsPhaseText(progress.phase)} 0秒`);
      try {
        const request = await openBotsStream(botId, text, runId, goal,
                                             controller.signal);
        await readBotsStream(request, run, (frame) => {
          progress.phase = frame.phase;
          progress.tool = frame.tool || null;
        });
        if (goal && run.turn?.state === 'running') {
          await followDetachedGoal(run, controller.signal);
        }
        if (botsState.selected === botId) {
          botsState.messages = run.messages || botsState.messages;
          botsState.latestTurn = run.turn || botsState.latestTurn;
          renderBotsRun(botsState.latestTurn);
          renderBotsThread();
          botsSetStatus('');
        }
        // The run may have finished while another Bot was selected. Refresh
        // the rail independently so its preview/status does not stay stale.
        await loadBots({keepSelection:true});
      } catch (error) {
        if (botsState.selected === botId) {
          botsSetStatus(error.name === 'AbortError' ? '中止しました。' : error.message);
          await refreshBotsThread().catch(() => {});
        }
      } finally {
        window.clearInterval(elapsed);
        if (botsState.activeRuns.get(botId)?.runId === runId) {
          botsState.activeRuns.delete(botId);
        }
        if (botsState.selected === botId) resizeBotsInput();
      }
    });
    botsCancel.addEventListener('click', async () => {
      const botId = botsState.selected;
      const active = botsState.activeRuns.get(botId);
      const runId = active?.runId;
      if (!botId || (!runId && !botsState.shellBusy)) return;
      botsCancel.disabled = true;
      botsSetStatus('中止しています…');
      try {
        if (botsState.shellBusy) {
          await postJSON(`/api/bots/${botId}/shell/cancel`, {}, true);
          return;
        }
        await postJSON(`/api/bots/${botId}/messages/${encodeURIComponent(runId)}/cancel`, {}, true);
        window.setTimeout(() => {
          const current = botsState.activeRuns.get(botId);
          if (current?.runId === runId) {
            current.controller.abort();
            refreshBotsThread().catch((error) => botsSetStatus(error.message));
          }
        }, 3000);
      } catch (error) {
        botsSetStatus(error.message);
      } finally {
        botsCancel.disabled = false;
      }
    });
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && (selectedBotsRun() || botsState.shellBusy)) {
        botsCancel.click();
      }
    });

    // ── rooms (ADR-0063) ────────────────────────────────────────────────
    //
    // A room has no tools, so there is nothing here that proposes, holds or
    // approves anything — no card, no runnable set, no busy state to cancel.
    // That absence is the feature; if a control for one appears here later,
    // the room grew a capability the ADR says it does not have.
    const roomsState = {rooms:[], selected:null, bots:[]};
    const roomStatus = (text) => { $('#room-status').textContent = text || ''; };
    const renderRoomList = () => {
      const list = $('#room-list');
      list.replaceChildren();
      $('#rooms-count').textContent = String(roomsState.rooms.length);
      if (!roomsState.rooms.length) {
        list.append(make('li', 'empty-state', 'まだBot同士の会話はありません。'));
        return;
      }
      roomsState.rooms.forEach((room) => {
        const row = make('li');
        const button = make('button', 'record-button',
          `${room.name} · ${room.members.length}体`);
        button.type = 'button';
        button.setAttribute('aria-pressed',
          roomsState.selected === room.id ? 'true' : 'false');
        button.addEventListener('click', () => selectRoom(room.id));
        row.append(button);
        list.append(row);
      });
    };
    const renderRoomThread = (messages) => {
      const thread = $('#room-thread');
      thread.replaceChildren();
      if (!messages.length) {
        thread.append(make('li', 'empty-state', 'まだ発言はありません。'));
        return;
      }
      const names = new Map(roomsState.bots.map((bot) => [`bot:${bot.id}`, bot.name]));
      messages.forEach((message) => {
        // Attributed, as in the transcript the members themselves read. A room
        // where the lines are anonymous is a room where nobody can tell which
        // Bot to ask next, which is the only thing a room is for.
        const who = message.from ? (names.get(message.from) || message.from) : 'あなた';
        const row = make('li', 'record-list__row');
        row.append(make('div', 'record-list__title', who));
        row.append(make('div', 'record-list__meta', message.text));
        thread.append(row);
      });
    };
    const selectRoom = async (roomId) => {
      roomsState.selected = roomId;
      const room = roomsState.rooms.find((entry) => entry.id === roomId);
      $('#room-panel').hidden = false;
      $('#room-title').textContent = room ? room.name : 'ルーム';
      $('#room-members-summary').textContent = room
        ? room.members.map((member) =>
            member.enabled ? member.name : `${member.name}（停止中）`).join(' · ')
        : '';
      renderRoomList();
      roomStatus('読み込み中…');
      try {
        const request = await fetch(`/api/bots/groups/${encodeURIComponent(roomId)}/messages`);
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || 'ルームを読めませんでした。');
        renderRoomThread(data.messages || []);
        roomStatus('');
      } catch (error) {
        roomStatus(error.message);
      }
    };
    const loadRooms = async () => {
      try {
        const [roomsRequest, botsRequest] =
          await Promise.all([fetch('/api/bots/groups'), fetch('/api/bots')]);
        const roomsData = await roomsRequest.json();
        const botsData = await botsRequest.json();
        if (!roomsRequest.ok) {
          throw new Error(roomsData?.error?.message || 'ルームを読めませんでした。');
        }
        roomsState.rooms = roomsData.groups || [];
        roomsState.bots = botsRequest.ok ? (botsData.bots || []) : [];
        renderRoomList();
        if (roomsState.selected
            && !roomsState.rooms.some((room) => room.id === roomsState.selected)) {
          roomsState.selected = null;
          $('#room-panel').hidden = true;
        }
      } catch (error) {
        $('#room-list').replaceChildren(make('li', 'empty-state', error.message));
      }
    };
    const setBotConversationsOpen = (open) => {
      $('#bots-conversations-panel').hidden = !open;
      $('#bots-conversations').setAttribute('aria-expanded', String(open));
      if (open) {
        setBotsQualityOpen(false);
        $('#bots-routines-panel').hidden = true;
        $('#bots-routines').setAttribute('aria-expanded', 'false');
        loadRooms();
      }
    };
    $('#bots-conversations').addEventListener('click', () =>
      setBotConversationsOpen($('#bots-conversations').getAttribute('aria-expanded') !== 'true'));
    $('#bots-conversations-close').addEventListener('click', () =>
      setBotConversationsOpen(false));

    // ── Wallet: verified external accounts, one assignment per Bot ──────
    let walletState = null;
    let walletBalances = new Map();
    let selectedWalletBotId = null;
    const shortAddress = (address) => address
      ? `${address.slice(0, 8)}…${address.slice(-6)}` : '未割り当て';
    const weiToEth = (value) => {
      const wei = BigInt(value || '0');
      const whole = wei / (10n ** 18n);
      const fraction = String(wei % (10n ** 18n)).padStart(18, '0').replace(/0+$/, '');
      return fraction ? `${whole}.${fraction}` : String(whole);
    };
    const ethToWei = (value) => {
      const match = String(value || '').trim().match(/^(\d+)(?:\.(\d{1,18}))?$/);
      if (!match) throw new Error('数量は小数点以下18桁までのETHで入力してください。');
      const result = BigInt(match[1]) * (10n ** 18n)
        + BigInt((match[2] || '').padEnd(18, '0') || '0');
      if (result <= 0n) throw new Error('0より大きい数量を入力してください。');
      return String(result);
    };
    const requireInjectedWallet = () => {
      if (!window.ethereum?.request) {
        throw new Error('MetaMaskなどのEIP-1193 Walletが見つかりません。');
      }
      return window.ethereum;
    };
    const walletAccountById = (id) =>
      walletState?.accounts?.find((account) => account.id === id);
    const selectedWalletBot = () => walletState?.bots?.find((bot) =>
      bot.id === selectedWalletBotId);
    const refreshWalletBalances = async () => {
      if (!window.ethereum?.request || !walletState?.accounts?.length) return;
      try {
        const available = (await window.ethereum.request({method:'eth_accounts'}))
          .map((address) => address.toLowerCase());
        const chainId = Number(BigInt(await window.ethereum.request({method:'eth_chainId'})));
        const pairs = await Promise.all(walletState.accounts
          .filter((account) => account.status === 'active'
            && account['chain-id'] === chainId
            && available.includes(account.address.toLowerCase()))
          .map(async (account) => [account.id, await window.ethereum.request({
            method:'eth_getBalance', params:[account.address, 'latest']
          })]));
        walletBalances = new Map(pairs);
      } catch (_) { walletBalances = new Map(); }
    };
    const renderWallet = (data) => {
      walletState = data;
      const accounts = data.accounts || [];
      const bots = data.bots || [];
      const transfers = data.transfers || [];
      const activeAccounts = accounts.filter((account) => account.status === 'active');
      const assignedBots = bots.filter((bot) => bot.wallet?.['signer-connected?']);
      const waiting = transfers.filter((transfer) => transfer.status === 'awaiting-wallet');
      $('#wallet-count').textContent = assignedBots.length || '';
      $('#wallet-source').textContent = data['private-keys-stored?']
        ? '秘密鍵を保存しています' : `${bots.length}個のBot Wallet · 外部署名`;
      $('#wallet-summary').replaceChildren(
        make('span', null, String(bots.length)),
        make('span', null, String(activeAccounts.length)),
        make('span', null, String(waiting.length)));

      if (!bots.some((bot) => bot.id === selectedWalletBotId)) {
        selectedWalletBotId = bots[0]?.id || null;
      }
      const selector = $('#wallet-bot-select'); selector.replaceChildren();
      if (!bots.length) {
        const option = document.createElement('option'); option.value = '';
        option.textContent = '先にBotを作成してください'; selector.append(option);
      } else bots.forEach((bot) => {
        const option = document.createElement('option'); option.value = bot.id;
        option.textContent = bot.name; option.selected = bot.id === selectedWalletBotId;
        selector.append(option);
      });

      const selected = selectedWalletBot();
      const connected = Boolean(selected?.wallet?.['signer-connected?']);
      const balanceHex = connected ? walletBalances.get(selected.wallet['link-id']) : null;
      const balance = balanceHex ? `${weiToEth(BigInt(balanceHex))} ETH` : '— ETH';
      $('#wallet-account-state').textContent = connected ? '署名接続済み' : '署名を接続';
      $('#wallet-account-state').dataset.state = connected ? 'ready' : 'waiting';
      $('#wallet-bot-name').textContent = selected?.name || 'Bot Wallet';
      botAvatar($('#wallet-bot-avatar'), selected?.avatar || {});
      $('#wallet-network').textContent = connected
        ? `Chain ${selected.wallet['chain-id']}` : 'Ethereum';
      $('#wallet-balance').textContent = balance;
      $('#wallet-asset-balance').textContent = balance;
      const addressButton = $('#wallet-address');
      addressButton.textContent = connected ? shortAddress(selected.wallet.address)
        : '署名Walletを接続してください';
      addressButton.disabled = !connected;
      $('#wallet-receive').disabled = !connected;
      $('#wallet-send').disabled = !connected;
      $('#wallet-connect').querySelector('span:last-child').textContent = connected ? '署名変更' : '署名接続';
      $('#wallet-send-bot').value = selected?.id || '';

      const transferList = $('#wallet-transfer-list'); transferList.replaceChildren();
      const selectedTransfers = transfers.filter((transfer) => transfer['bot-id'] === selected?.id);
      if (!selectedTransfers.length) transferList.append(make('li', 'empty-state', 'アクティビティはまだありません。'));
      selectedTransfers.forEach((transfer) => {
        const row = make('li', 'data-list__item');
        const body = make('div');
        body.append(make('p', 'data-list__title', `${weiToEth(transfer['value-wei'])} ETH`),
          make('p', 'data-list__meta wallet-address', `${shortAddress(transfer.from)} → ${shortAddress(transfer.to)}`),
          make('p', 'data-list__meta', transfer.status === 'submitted'
            ? `送信済み · ${shortAddress(transfer['tx-hash'])}` : '外部Walletの署名待ち'));
        row.append(body);
        if (transfer.status === 'awaiting-wallet') {
          const submit = make('button', 'primary-action', 'MetaMaskで確認'); submit.type = 'button';
          submit.addEventListener('click', () => submitWalletTransfer(transfer, submit));
          row.append(submit);
        }
        transferList.append(row);
      });
    };
    const loadWallet = async () => {
      const request = await fetch('/api/wallet', {cache:'no-store'});
      const data = await request.json();
      if (!request.ok) throw new Error(data?.error?.message || 'Walletを読み込めません。');
      walletState = data;
      await refreshWalletBalances();
      renderWallet(data);
      return data;
    };
    $('#wallet-bot-select').addEventListener('change', (event) => {
      selectedWalletBotId = event.currentTarget.value || null;
      $('#wallet-send-drawer').hidden = true;
      renderWallet(walletState);
    });
    const copySelectedWalletAddress = async () => {
      const address = selectedWalletBot()?.wallet?.address;
      if (!address) return;
      await navigator.clipboard.writeText(address);
      $('#wallet-connect-status').textContent = '受取アドレスをコピーしました。';
    };
    $('#wallet-address').addEventListener('click', copySelectedWalletAddress);
    $('#wallet-receive').addEventListener('click', copySelectedWalletAddress);
    $('#wallet-send').addEventListener('click', () => {
      $('#wallet-send-drawer').hidden = false;
      $('#wallet-send-to').focus();
    });
    $('#wallet-send-close').addEventListener('click', () => {
      $('#wallet-send-drawer').hidden = true;
    });
    const selectWalletTab = (tab) => {
      const assets = tab === 'assets';
      $('#wallet-assets-tab').setAttribute('aria-selected', String(assets));
      $('#wallet-activity-tab').setAttribute('aria-selected', String(!assets));
      $('#wallet-assets-panel').hidden = !assets;
      $('#wallet-activity-panel').hidden = assets;
    };
    $('#wallet-assets-tab').addEventListener('click', () => selectWalletTab('assets'));
    $('#wallet-activity-tab').addEventListener('click', () => selectWalletTab('activity'));
    const submitWalletTransfer = async (transfer, button) => {
      const status = $('#wallet-send-status'); button.disabled = true;
      try {
        const provider = requireInjectedWallet();
        const accounts = await provider.request({method:'eth_requestAccounts'});
        if (!accounts.some((address) => address.toLowerCase() === transfer.from.toLowerCase())) {
          throw new Error(`MetaMaskで送信元 ${shortAddress(transfer.from)} を選択してください。`);
        }
        const chainId = Number(BigInt(await provider.request({method:'eth_chainId'})));
        if (chainId !== transfer['chain-id']) {
          throw new Error(`Chain ${transfer['chain-id']} に切り替えてください。`);
        }
        const txHash = await provider.request({method:'eth_sendTransaction', params:[{
          from:transfer.from, to:transfer.to,
          value:`0x${BigInt(transfer['value-wei']).toString(16)}`
        }]});
        await postJSON(`/api/wallet/transfers/${encodeURIComponent(transfer.id)}/submitted`,
          {'tx-hash':txHash}, true);
        status.textContent = '外部Walletへ送信し、transaction hashを記録しました。';
        await loadWallet();
      } catch (error) { status.textContent = error.message; button.disabled = false; }
    };
    $('#wallet-connect').addEventListener('click', async () => {
      const button = $('#wallet-connect'); const status = $('#wallet-connect-status');
      const botId = selectedWalletBotId;
      if (!botId) { status.textContent = '先にBotを作成してください。'; return; }
      button.disabled = true; status.textContent = 'Walletへ接続しています…';
      try {
        if (!window.ethereum?.request) {
          const opened = await postJSON('/api/wallet/open', {}, true);
          status.textContent = opened['opened-externally?']
            ? 'Wallet拡張のある既定ブラウザでWallet画面を開きました。'
            : `既定ブラウザで ${opened.url} を開いてください。`;
          return;
        }
        const provider = requireInjectedWallet();
        const [address] = await provider.request({method:'eth_requestAccounts'});
        const chainId = Number(BigInt(await provider.request({method:'eth_chainId'})));
        let link = walletState?.accounts?.find((account) => account.status === 'active'
          && account['chain-id'] === chainId
          && account.address.toLowerCase() === address.toLowerCase());
        if (!link) {
          const challenge = await postJSON('/api/wallet/connect/start',
            {address, 'chain-id':chainId}, true);
          const signature = await provider.request({
            method:'personal_sign', params:[challenge.message, address]
          });
          link = await postJSON('/api/wallet/connect/finish',
            {'transaction-id':challenge.id, signature}, true);
        }
        await postJSON(`/api/wallet/bots/${encodeURIComponent(botId)}/assign`,
          {'link-id':link.id}, true);
        status.textContent = '所有証明を確認し、このBot Walletへ署名権限を接続しました。';
        await loadWallet();
      } catch (error) { status.textContent = error.message; }
      finally { button.disabled = false; }
    });
    $('#wallet-send-form').addEventListener('submit', async (event) => {
      event.preventDefault(); const status = $('#wallet-send-status');
      try {
        const fields = Object.fromEntries(new FormData(event.currentTarget));
        await postJSON('/api/wallet/transfers', {
          'bot-id':fields['bot-id'], to:fields.to, 'value-wei':ethToWei(fields.amount)
        }, true);
        status.textContent = '送金提案を記録しました。内容を確認して外部Walletで署名してください。';
        event.currentTarget.reset(); $('#wallet-send-drawer').hidden = true;
        selectWalletTab('activity'); await loadWallet();
      } catch (error) { status.textContent = error.message; }
    });

    onViewChange = () => {
      scheduleWorkerPoll();
      if (currentView === 'bots') {
        loadBots({keepSelection:botsState.loaded});
        loadRooms();
        scheduleBotsRealtime(0);
      } else stopBotsRealtime();
      scheduleOrganismPoll();
      // Computed the first time the pane is actually opened, then left alone
      // until the button is pressed — it is expensive and it is not live data.
      if (currentView === 'portfolio' && !matrixLoaded) loadMatrix();
      if (currentView === 'organisms' && !organismWorkers.length) {
        loadOrganisms().catch((error) => {
          $('#organism-activity-state').textContent = error.message;
        });
      }
      if (currentView === 'credentials') {
        loadCredentials().catch((error) => {
          $('#credentials-source').textContent = error.message;
        });
      }
      if (currentView === 'wallet') loadWallet().catch((error) => {
        $('#wallet-source').textContent = error.message;
      });
      if (currentView === 'projects') loadProjectBoard();
      if (currentView === 'capture') loadCaptures().catch((error) => {
        $('#capture-status').textContent = error.message;
      });
      if (currentView === 'sites') loadSites();
      if (currentView === 'settings') loadChronicle().catch((error) => {
        $('#memory-status').textContent = error.message;
      });
    };
    $('#worker-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = $('#worker-submit');
      const fields = Object.fromEntries(new FormData(event.currentTarget));
      if (!String(fields.prompt || '').trim()) {
        workerHelp('実行する指示を入力してください。');
        return;
      }
      const label = button.textContent;
      button.disabled = true;
      button.textContent = '登録中…';
      try {
        const request = await fetch('/api/workers', {
          method:'POST', headers:identityHeaders(), body:JSON.stringify(fields)
        });
        const data = await request.json();
        if (!request.ok) {
          throw new Error(data?.error?.message || 'ジョブを登録できませんでした。');
        }
        $('#worker-prompt').value = '';
        $('#worker-title').value = '';
        selectedWorker = data;
        workerHelp(`${data.title} をバックグラウンドで実行しています。`);
        await loadWorkspace('worker', renderWorker);
      } catch (error) {
        workerHelp(error.message);
      } finally {
        button.disabled = false;
        button.textContent = label;
      }
    });
    $('#worker-clear').addEventListener('click', async () => {
      const button = $('#worker-clear');
      button.disabled = true;
      try {
        const request = await fetch('/api/workers/clear', {
          method:'POST', headers:identityHeaders(), body:'{}'
        });
        const data = await request.json();
        if (!request.ok) {
          throw new Error(data?.error?.message || '完了したジョブを整理できませんでした。');
        }
        selectedWorker = null;
        renderWorker(data);
      } catch (error) {
        button.disabled = false;
        workerHelp(error.message);
      }
    });
    if (initialParams.get('connection')) {
      const notice = $('#connection-notice');
      const connected = initialParams.get('connection') === 'connected';
      notice.hidden = false;
      notice.className = `settings-notice${connected ? '' : ' settings-notice--error'}`;
      notice.textContent = connected
        ? `${initialParams.get('provider')} を接続しました。`
        : `${initialParams.get('provider')} の接続を完了できませんでした。`;
    }
    if (initialParams.get('setup-domain')) {
      $('#company-domain').value = initialParams.get('setup-domain');
    }
    if (initialParams.get('auth')) {
      const provider = initialParams.get('provider');
      const ok = initialParams.get('auth') === 'sso';
      const label = authProviderLabels[provider] || provider || 'SSO';
      $('#identity-status').textContent = ok
        ? `${label}でサインインしました。`
        : `${label}のサインインを完了できませんでした。もう一度お試しください。`;
    }
    const finishEmailLoginFromLink = async () => {
      const token = emailLoginToken;
      if (!token) return;
      // Remove the bearer-like token from the address bar before doing any
      // other work. It remains available in this closure for this one POST.
      history.replaceState(null, '', `${location.pathname}${location.search}`);
      try {
        const data = await postJSON('/api/email-authenticate/finish', {token});
        renderIdentity(data);
        $('#identity-status').textContent = 'Email でサインインしました。';
      } catch (error) {
        $('#identity-status').textContent = error.message;
      }
    };
    finishEmailLoginFromLink().finally(loadIdentity);
    // after every const above is defined — calling this next to the initial
    // showView() would hit `Cannot access 'loadFilecoin' before initialization`
    loadFilecoin();
    $$('.view-switcher button').forEach((button) => button.addEventListener('click', () => {
      $$('.view-switcher button').forEach((item) =>
        item.setAttribute('aria-pressed', item === button ? 'true' : 'false'));
    }));
  });

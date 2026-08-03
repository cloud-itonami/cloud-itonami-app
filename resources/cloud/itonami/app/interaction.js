
  document.addEventListener('DOMContentLoaded', () => {
    const $ = (selector, root = document) => root.querySelector(selector);
    const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
    const make = (tag, className, text) => {
      const node = document.createElement(tag);
      if (className) node.className = className;
      if (text !== undefined && text !== null) node.textContent = text;
      return node;
    };
    const initialParams = new URLSearchParams(location.search);
    const requestedView = location.hash.slice(1) || 'chat';
    let appUnlocked = false;
    let appBootstrapped = false;
    // Views whose data is public, so the Passkey gate would protect nothing.
    // `storage` reads public Filecoin chain state and computes a PieceCID —
    // there is no workspace content in it. Everything else stays gated.
    const publicViews = new Set(['settings', 'storage']);
    let currentView = 'settings';
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
      if (!appUnlocked && !publicViews.has(name)) name = 'settings';
      $$('.local-nav__item').forEach((item) => item.setAttribute(
        'aria-current', item.dataset.view === name ? 'page' : 'false'));
      $$('.view').forEach((panel) => { panel.hidden = panel.dataset.viewPanel !== name; });
      const active = $(`.local-nav__item[data-view='${name}']`);
      $('#current-view').textContent = active?.dataset.title || 'Chat';
      history.replaceState(null, '', `#${name}`);
      const brand = document.querySelector('.workspace')?.dataset.brand || 'Cloud Itonami';
      document.title = `${active?.dataset.title || 'Chat'} | ${brand}`;
      currentView = name;
      onViewChange(name);
    };
    $$('.local-nav__item').forEach((item) =>
      item.addEventListener('click', () => showView(item.dataset.view)));
    showView('settings');

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
      try {
        const request = await fetch(`/api/session?session=${encodeURIComponent(sessionId)}`);
        const data = await request.json();
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
            model:modelSelect.value})
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
      $('#inbox-visible-count').textContent = `${items.length} 件を表示`;
      $('#inbox-count').textContent = data.count;
      $('#inbox-source').textContent = `${data.source} · ${data.count} 件`;
    };
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
      (driveData.items || [])
        .filter((item) => item.origin === 'workspace' && !item['trashed?'])
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
      $('#projects-count').textContent = data.items.length;
      $('#projects-source').textContent = `${data.source} · ${data.scope}`;
      $('#projects-state').textContent = data.status === 'connected' ? '接続済み' : '権限確認が必要';
      $('#projects-state').className = `state-chip${data.status === 'connected' ? '' : ' state-chip--warn'}`;
    };
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
      loadSession();
      loadWorkspace('worker', renderWorker);
      loadOrganisms().catch((error) => {
        $('#organism-list').replaceChildren(make('li', 'empty-state', error.message));
      });
      Promise.all([
        loadWorkspace('inbox', renderInbox),
        loadWorkspace('projects', renderProjects),
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
    const postJSON = async (path, body={}, authenticated=false) => {
      const request = await fetch(path, {
        method:'POST',
        headers:authenticated ? identityHeaders() : {'Content-Type':'application/json'},
        body:JSON.stringify(body)
      });
      const data = await request.json();
      if (!request.ok) throw new Error(data?.error?.message || '認証要求を完了できませんでした。');
      return data;
    };
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
    const renderIdentity = (data) => {
      identityState = data;
      const identityReady = Boolean(data['authenticated?'] && data['may-act?']);
      appUnlocked = identityReady;
      $$('.local-nav__item').forEach((item) => {
        item.disabled = !identityReady && !publicViews.has(item.dataset.view);
        item.setAttribute('aria-disabled', String(item.disabled));
      });
      document.body.dataset.identityGate = identityReady ? 'ready' : 'required';
      $('#passkey-gate-notice').hidden = identityReady;
      if (!identityReady) {
        // a public view the user actually asked for stays put
        showView(publicViews.has(requestedView) ? requestedView : 'settings');
        $('#current-view').textContent =
          currentView === 'storage' ? 'Storage' : 'Passkey 登録';
        $('#workspace-status').textContent = 'Passkey 登録が必要です';
      } else {
        bootstrapApp();
        showView(requestedView);
      }
      const onboarding = $('#identity-onboarding');
      const workspace = $('#identity-workspace');
      onboarding.hidden = data['registered?'];
      workspace.hidden = !data['authenticated?'];
      $('#registered-auth').hidden = !(data['registered?'] && !data['authenticated?']);
      $('#email-login-form').hidden = !(data['registered?'] &&
        !data['authenticated?'] && data['email-login-configured?']);
      if (data['registered?'] && !data['authenticated?']) {
        onboarding.hidden = false;
        const pendingPasskey = data['passkey-required?'];
        $('#registration-title').textContent = pendingPasskey
          ? 'Passkey 登録を再開'
          : 'Passkey でサインイン';
        $('#registration-lead').textContent = pendingPasskey
          ? '仮登録は完了しています。Passkey を作成するとアプリを利用できます。'
          : '登録済みの Passkey で本人確認するとアプリを開けます。';
        $('#passkey-signin').textContent = pendingPasskey
          ? 'Passkey 登録を再開'
          : 'Passkey でサインイン';
        $('#registration-form').hidden = true;
      }
      if (!data['authenticated?']) return;
      $('#identity-avatar').textContent =
        (data.user['display-name'] || 'U').slice(0, 2);
      $('#identity-name').textContent = data.user['display-name'] || 'Passkey user';
      $('#identity-email').textContent =
        data.user['account-id'] ? data.user.email : 'Organization ID 未設定';
      $('#identity-did').textContent = data.user.did || 'Passkey 登録後に発行';
      $('#passkey-state').textContent = data.user['passkey-enrolled?']
        ? 'Passkey 登録済み'
        : '必須: 続行するには Passkey を登録してください。';
      $('#passkey-register').textContent = data.user['passkey-enrolled?']
        ? '別の Passkey を追加' : 'Passkey を登録';
      const organizationReady = Boolean(data.organization?.['profile-complete?']);
      $('#organization-name').textContent =
        organizationReady ? data.organization.name : 'Organization ID 未設定';
      $('#organization-domain').textContent = organizationReady
        ? `${data.organization.domain} · ${data.organization.role}`
        : 'Passkey 登録後に設定できます';
      $('#organization-did').textContent =
        data.organization?.did || 'Organization DID は ID 設定後に発行';
      const organizationSwitcher = $('#organization-switcher');
      organizationSwitcher.replaceChildren();
      (data.organizations || []).forEach((organization) => {
        const option = document.createElement('option');
        option.value = organization.id;
        option.textContent = organization.name || organization['organization-id']
          || organization.id;
        option.selected = Boolean(organization['active?']);
        organizationSwitcher.append(option);
      });
      organizationSwitcher.disabled = (data.organizations || []).length < 2;
      const invitations = data['organization-invitations'] || [];
      $('#organization-invitation-state').textContent = invitations.length
        ? `${invitations.length}件の参加待ち招待があります。コードを入力して参加できます。`
        : '参加待ちの招待はありません。';
      $('#organization-form').hidden = organizationReady;
      $('#member-card').hidden = !organizationReady;
      renderMembers(data.organization);
      renderConnectors(data);
      loadCloudAlias(data);
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
    $('#organization-switcher').addEventListener('change', async (event) => {
      const select = event.currentTarget;
      select.disabled = true;
      try {
        const data = await postJSON('/api/identity/organizations/switch',
          {'organization-id':select.value}, true);
        organismCursor = null;
        organismWorkers = [];
        selectedOrganism = null;
        renderIdentity(data);
        await loadOrganisms();
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
        await loadOrganisms();
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

    onViewChange = () => {
      scheduleWorkerPoll();
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
    const finishEmailLoginFromLink = async () => {
      const token = new URLSearchParams(location.hash.slice(1)).get('email-login');
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

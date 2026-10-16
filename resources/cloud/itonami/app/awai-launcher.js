(() => {
  const trust = document.getElementById('awai-trust');
  const list = document.getElementById('awai-apps');
  const retry = document.getElementById('awai-retry');
  const runtime = document.getElementById('awai-app-runtime');
  const localInstaller = location.hostname === '127.0.0.1';
  let activeHostPort = null;

  const paragraph = (parent, text, className) => {
    const node = document.createElement('p');
    if (className) node.className = className;
    node.textContent = text;
    parent.append(node);
    return node;
  };

  const field = (label, value) => {
    const present = typeof value === 'string' ? value.length > 0 : Number.isSafeInteger(value);
    return `${label}: ${present ? String(value) : '未解決'}`;
  };

  const renderUnavailable = (message) => {
    trust.textContent = 'ローカル Awai peer から台帳を取得できません。';
    list.replaceChildren();
    paragraph(list, message || 'peer が未設定か、応答を検証できませんでした。', 'empty-state');
  };

  const renderCandidate = (candidate) => {
    const card = document.createElement('article');
    const title = document.createElement('h3');
    title.textContent = candidate.domain || 'domain 不明';
    card.append(title);
    paragraph(card, field('App ID', candidate['app-id']));
    paragraph(card, field('公開者', candidate['publisher-did']));
    paragraph(card, field('channel', candidate.channel));
    paragraph(card, field('release sequence', candidate['release-sequence']));
    paragraph(card, field('release CID', candidate['release-cid']));
    paragraph(card, field('manifest CID', candidate['manifest-cid']));
    paragraph(card, `状態: ${candidate.decision === 'candidate' ? '公開候補' : '利用不可'}`);
    paragraph(card, 'この候補の掲載は、複数 peer による合意やインストール可能性の証明ではありません。');

    const launchStatus = paragraph(card, 'local app runtime: 未起動');
    const launchButton = document.createElement('button');
    launchButton.type = 'button';
    launchButton.textContent = 'インストール済み app を隔離起動';
    launchButton.disabled = !localInstaller;
    launchButton.addEventListener('click', async () => {
      launchButton.disabled = true;
      launchStatus.textContent = '保護された install 記録と CID を再確認しています。';
      try {
        const response = await fetch(
          `/api/awai/app-launch?app-id=${encodeURIComponent(candidate['app-id'])}`,
          {
            method: 'POST',
            cache: 'no-store',
            credentials: 'same-origin',
            redirect: 'error',
            signal: AbortSignal.timeout(30000),
            headers: { accept: 'application/json' }
          }
        );
        const result = await response.json();
        if (!response.ok || result.status !== 'ready') {
          launchStatus.textContent = `起動できません: ${result.reason || result.status || '利用不可'}`;
          return;
        }
        const appOrigin = new URL(result['sandbox-origin']);
        if (appOrigin.protocol !== 'http:'
            || !appOrigin.hostname.endsWith('.localhost')
            || appOrigin.port !== location.port
            || result['app-id'] !== candidate['app-id']) {
          launchStatus.textContent = '起動先 origin がローカル隔離条件と一致しません。';
          return;
        }
        const frame = document.createElement('iframe');
        frame.title = `${candidate['app-id']} — Awai app`;
        // Keep the app in an opaque sandbox origin. The app host uses a
        // distinct hostname already, but allow-same-origin would make a later
        // navigation back to the launcher origin harder to reason about.
        frame.setAttribute('sandbox', 'allow-scripts');
        frame.setAttribute('allow', '');
        frame.referrerPolicy = 'no-referrer';
        frame.loading = 'lazy';
        if (activeHostPort) {
          activeHostPort.close();
          activeHostPort = null;
        }
        const hostCapabilities = Array.isArray(result['host-capabilities'])
          ? result['host-capabilities']
          : [];
        if (hostCapabilities.some((capability) =>
          ['awai.cell.read', 'awai.cell.write'].includes(capability))) {
          const channel = new MessageChannel();
          const inFlight = new Set();
          activeHostPort = channel.port1;
          channel.port1.onmessage = async (event) => {
            const message = event.data;
            const reply = (requestId, response) => {
              channel.port1.postMessage({
                schema: 'itonami.app.host-response.v1',
                'request-id': requestId,
                response
              });
            };
            if (!message || typeof message !== 'object' || Array.isArray(message)
                || Object.keys(message).sort().join(',')
                  !== 'capability,request,request-id,schema'
                || message.schema !== 'itonami.app.host-operation.v1'
                || typeof message['request-id'] !== 'string'
                || !/^[A-Za-z0-9_-]{1,128}$/.test(message['request-id'])
                || !['awai.cell.read', 'awai.cell.write'].includes(message.capability)
                || !message.request || typeof message.request !== 'object'
                || Array.isArray(message.request)
                || inFlight.has(message['request-id'])
                || inFlight.size >= 32) {
              reply(typeof message?.['request-id'] === 'string'
                ? message['request-id'].slice(0, 128)
                : '', { status: 'refused', reason: 'invalid-host-operation' });
              return;
            }
            const requestId = message['request-id'];
            inFlight.add(requestId);
            try {
              const body = JSON.stringify({
                'app-id': result['app-id'],
                capability: message.capability,
                request: message.request
              });
              if (body.length > 266240) {
                reply(requestId, { status: 'refused', reason: 'operation-too-large' });
                return;
              }
              const operationResponse = await fetch('/api/awai/app-operation', {
                method: 'POST',
                cache: 'no-store',
                credentials: 'same-origin',
                redirect: 'error',
                signal: AbortSignal.timeout(30000),
                headers: {
                  accept: 'application/json',
                  'content-type': 'application/json'
                },
                body
              });
              const text = await operationResponse.text();
              if (text.length > 1100000) {
                reply(requestId, { status: 'unavailable', reason: 'response-too-large' });
              } else {
                reply(requestId, {
                  'http-status': operationResponse.status,
                  result: JSON.parse(text)
                });
              }
            } catch (_) {
              reply(requestId, { status: 'indeterminate', reason: 'host-unavailable' });
            } finally {
              inFlight.delete(requestId);
            }
          };
          channel.port1.start();
          frame.addEventListener('load', () => {
            if (frame.contentWindow) {
              frame.contentWindow.postMessage({
                schema: 'itonami.app.host-connect.v1',
                'app-id': result['app-id'],
                'release-cid': result['release-cid'],
                capabilities: hostCapabilities,
                mediation: 'each-operation-rechecks-local-consent'
              }, '*', [channel.port2]);
            }
          }, { once: true });
        }
        frame.src = new URL('/', appOrigin).href;
        runtime.replaceChildren(frame);
        launchStatus.textContent = hostCapabilities.length
          ? '隔離 origin で起動中。host 操作は Itonami の capability 確認を毎回通ります。'
          : '隔離 origin で起動中。ネットワーク接続と host capability は無効です。';
      } catch (_) {
        launchStatus.textContent = '起動応答を確認できませんでした。';
      } finally {
        launchButton.disabled = !localInstaller;
      }
    });
    card.append(launchButton);

    const proofStatus = paragraph(card, 'DNSSEC proof: 未確認');
    const proofButton = document.createElement('button');
    proofButton.type = 'button';
    proofButton.textContent = 'domain binding を DNSSEC 検証';
    proofButton.addEventListener('click', async () => {
      proofButton.disabled = true;
      proofStatus.textContent = 'DNSSEC proof を確認しています。';
      try {
        const response = await fetch(
          `/api/awai/domain-proof?domain=${encodeURIComponent(candidate.domain)}`,
          {
            method: 'GET',
            cache: 'no-store',
            credentials: 'same-origin',
            redirect: 'error',
            signal: AbortSignal.timeout(15000),
            headers: { accept: 'application/json' }
          }
        );
        const result = await response.json();
        if (response.ok && result.status === 'verified' && result.proof) {
          const matches = result['binding-match?'] === true
            && result.proof['binding-cid'] === candidate['binding-cid'];
          proofStatus.textContent = matches
            ? 'DNSSEC 検証済み。Awai の domain binding CID と一致しました。'
            : 'DNSSEC proof は検証済みですが、Awai の binding CID と一致しません。';
        } else {
          proofStatus.textContent = `DNSSEC proof: ${result.reason || result.status || '利用不可'}`;
        }
      } catch (_) {
        proofStatus.textContent = 'DNSSEC proof を取得できませんでした。';
      } finally {
        proofButton.disabled = false;
      }
    });
    card.append(proofButton);

    const planStatus = paragraph(card, 'install plan: 未作成');
    const planButton = document.createElement('button');
    planButton.type = 'button';
    planButton.textContent = 'install plan を確認';
    planButton.disabled = candidate.decision !== 'candidate';
    let planReady = false;
    planButton.addEventListener('click', async () => {
      planButton.disabled = true;
      planStatus.textContent = 'DNS proof と manifest を確認しています。';
      try {
        const response = await fetch(
          `/api/awai/install-plan?domain=${encodeURIComponent(candidate.domain)}`,
          {
            method: 'POST',
            cache: 'no-store',
            credentials: 'same-origin',
            redirect: 'error',
            signal: AbortSignal.timeout(30000),
            headers: { accept: 'application/json' }
          }
        );
        const result = await response.json();
        if (response.ok && result.status === 'install-plan-ready') {
          planReady = true;
          const requiredCapabilities = Array.isArray(result['required-capabilities'])
            ? result['required-capabilities']
            : '未解決';
          const capabilities = Array.isArray(requiredCapabilities)
            ? requiredCapabilities.join(', ') || 'なし'
            : requiredCapabilities;
          const contentCount = Array.isArray(result['required-content-cids'])
            ? result['required-content-cids'].length
            : 0;
          planStatus.textContent = `plan ready; 必要権限: ${capabilities}; content CID: ${contentCount}件。未インストールです。`;
          const capabilityInputs = [];
          const consentGroup = document.createElement('fieldset');
          const consentLegend = document.createElement('legend');
          consentLegend.textContent = '許可する capability を個別に選択';
          consentGroup.append(consentLegend);
          if (Array.isArray(requiredCapabilities)) {
            if (requiredCapabilities.length === 0) {
              paragraph(consentGroup, 'この release は追加 capability を要求していません。');
            }
            requiredCapabilities.forEach((capability) => {
              const label = document.createElement('label');
              const checkbox = document.createElement('input');
              checkbox.type = 'checkbox';
              checkbox.value = capability;
              label.append(checkbox, document.createTextNode(` ${capability}`));
              consentGroup.append(label);
              capabilityInputs.push(checkbox);
            });
          }
          card.append(consentGroup);
          const installStatus = paragraph(card, localInstaller
            ? 'install: content 検証後に実行できます。'
            : 'install と capability 同意は 127.0.0.1 のローカル Itonami host でのみ実行できます。');
          const installButton = document.createElement('button');
          installButton.type = 'button';
          installButton.textContent = '選択した権限でこの app をローカル install';
          installButton.disabled = true;
          const stageStatus = paragraph(card, 'content staging: 未実行');
          const stageButton = document.createElement('button');
          stageButton.type = 'button';
          stageButton.textContent = '全 content を検証してローカル保存';
          stageButton.disabled = !localInstaller;
          if (stageButton.disabled) {
            stageStatus.textContent = 'content の取得・保存は利用者のローカル Itonami host から実行してください。';
          }
          stageButton.addEventListener('click', async () => {
            stageButton.disabled = true;
            stageStatus.textContent = '署名済み origin から block を取得し、CID を検証しています。上限は 1 GiB です。';
            try {
              const stageResponse = await fetch(
                `/api/awai/install-content?domain=${encodeURIComponent(candidate.domain)}`,
                {
                  method: 'POST',
                  cache: 'no-store',
                  credentials: 'same-origin',
                  redirect: 'error',
                  signal: AbortSignal.timeout(300000),
                  headers: { accept: 'application/json' }
                }
              );
              const staged = await stageResponse.json();
              if (stageResponse.ok && staged.status === 'content-staged') {
                installButton.disabled = false;
                const bytes = Number.isSafeInteger(staged['byte-count'])
                  ? `${(staged['byte-count'] / (1024 * 1024)).toFixed(1)} MiB`
                  : 'サイズ未解決';
                stageStatus.textContent = `CID 検証・ローカル保存済み: ${staged['block-count']} blocks, ${bytes}。capability 同意と Awai cell install は未実行です。`;
              } else {
                stageStatus.textContent = `content staging: ${staged.reason || staged.status || '利用不可'}`;
              }
            } catch (_) {
              stageStatus.textContent = 'content を検証・保存できませんでした。';
            } finally {
              stageButton.disabled = !localInstaller;
            }
          });
          card.append(stageButton);
          installButton.addEventListener('click', async () => {
            installButton.disabled = true;
            installStatus.textContent = 'registry、DNSSEC、manifest、content を再検証して install しています。';
            const approvedCapabilities = capabilityInputs
              .filter((checkbox) => checkbox.checked)
              .map((checkbox) => checkbox.value);
            try {
              const installResponse = await fetch('/api/awai/install', {
                method: 'POST',
                cache: 'no-store',
                credentials: 'same-origin',
                redirect: 'error',
                signal: AbortSignal.timeout(300000),
                headers: {
                  accept: 'application/json',
                  'content-type': 'application/json'
                },
                body: JSON.stringify({
                  domain: candidate.domain,
                  'release-cid': candidate['release-cid'],
                  'approved-capabilities': approvedCapabilities
                })
              });
              const installed = await installResponse.json();
              if (installResponse.ok && ['installed', 'already-installed'].includes(installed.status)) {
                const cells = Array.isArray(installed['installed-cells'])
                  ? installed['installed-cells'].length
                  : 0;
                installStatus.textContent = installed.status === 'already-installed'
                  ? 'この release はローカル install 済みです。上のボタンから静的 app を隔離起動できます。'
                  : `ローカル install 済み: Awai cell ${cells}件。上のボタンから静的 app を隔離起動できます。`;
              } else if (installed.status === 'install-incomplete') {
                const cells = Array.isArray(installed['installed-cells'])
                  ? installed['installed-cells'].length
                  : 0;
                installStatus.textContent = `install 未完了: cell ${cells}件を Awai node が受理済み。local record rollback: ${installed['local-record-rollback'] || '不明'}。理由: ${installed.reason || '不明'}`;
                installButton.disabled = !localInstaller;
              } else {
                installStatus.textContent = `install: ${installed.reason || installed.status || '利用不可'}`;
                installButton.disabled = !localInstaller;
              }
            } catch (_) {
              installStatus.textContent = 'install 応答を確認できませんでした。local install 状態を再読込してください。';
              installButton.disabled = !localInstaller;
            }
          });
          card.append(installButton);
        } else {
          planStatus.textContent = `install plan: ${result.reason || result.status || '利用不可'}`;
        }
      } catch (_) {
        planStatus.textContent = 'install plan を取得できませんでした。';
      } finally {
        planButton.disabled = candidate.decision !== 'candidate' || planReady;
      }
    });
    card.append(planButton);
    list.append(card);
  };

  const load = async () => {
    retry.disabled = true;
    trust.textContent = 'ローカル Awai peer を確認しています。';
    list.replaceChildren();
    try {
      const response = await fetch('/api/awai/app-registry', {
        method: 'GET',
        cache: 'no-store',
        credentials: 'same-origin',
        redirect: 'error',
        signal: AbortSignal.timeout(10000),
        headers: { accept: 'application/json' }
      });
      const result = await response.json();
      if (!response.ok || result.status !== 'ok' || !Array.isArray(result.candidates)) {
        renderUnavailable(result.reason || `台帳を取得できませんでした (HTTP ${response.status})。`);
        return;
      }
      trust.textContent = '単一のローカル peer から取得しました。各署名 envelope は検証済みですが、独立 peer 間の合意は未確認です。';
      if (result.candidates.length === 0) {
        paragraph(list, '現在、公開候補はありません。', 'empty-state');
        return;
      }
      result.candidates.forEach(renderCandidate);
    } catch (_) {
      renderUnavailable('peer が未設定か、台帳応答を取得できませんでした。');
    } finally {
      retry.disabled = false;
    }
  };

  retry.addEventListener('click', load);
  load();
})();

const invoke = window.__TAURI__?.core.invoke;
let status;
let busy = false;
let toastTimer;
const $ = (id) => document.getElementById(id);

function page(name) {
  if (!['home', 'updates', 'settings'].includes(name)) return;
  document.querySelectorAll('.page').forEach((element) => { element.hidden = element.id !== name; });
  document.querySelectorAll('[data-page]').forEach((element) => element.classList.toggle('active', element.dataset.page === name));
  $('page-name').textContent = name[0].toUpperCase() + name.slice(1);
}

function toast(message, error = false) {
  clearTimeout(toastTimer);
  $('toast').textContent = String(message);
  $('toast').classList.toggle('error', error);
  $('toast').hidden = false;
  toastTimer = setTimeout(() => { $('toast').hidden = true; }, 7000);
}

async function action(command, args = {}) {
  if (busy) return;
  if (!invoke) { toast('Open the desktop launcher to use this action.', true); return; }
  busy = true;
  document.querySelectorAll('button').forEach((button) => { if (!button.dataset.page && !button.dataset.go) button.disabled = true; });
  try {
    const result = await invoke(command, args);
    if (typeof result === 'string') toast(result);
    else if (command === 'save_settings') toast('Application overrides saved.');
    else if (command === 'save_preferences') toast('Preferences saved.');
    await refresh();
    return result;
  } catch (error) { toast(error, true); }
  finally { busy = false; render(); }
}

function render() {
  document.querySelectorAll('button').forEach((button) => { button.disabled = busy && !button.dataset.page && !button.dataset.go; });
  if (!status) return;
  $('platform').textContent = status.platform === 'linux' ? 'Linux' : 'Windows';
  for (const [kind, id] of [['hapticscape', 'haptic-status'], ['lumbridge', 'lumbridge-status']]) {
    const running = status[`${kind}Running`], installed = status[`${kind}Installed`];
    $(id).textContent = running ? 'Running' : installed ? 'Installed' : 'Not found · Set path in Settings';
    $(id).className = `app-status ${running ? 'running' : installed ? '' : 'missing'}`;
  }
  const character = status.characters.find((item) => item.accountId === status.selectedCharacter);
  $('play-hint').textContent = status.gameplayPortBusy && !status.hapticscapeRunning ? 'Gameplay port is in use. Close the other HapticScape client first.'
    : status.lumbridgeRunning ? 'LumBridge is running.' : !status.hapticscapeInstalled || !status.lumbridgeInstalled ? 'Set application paths in Settings.' : character ? '' : 'Add an account to play.';
  $('play').disabled = busy || !status.hapticscapeInstalled || !status.lumbridgeInstalled || !character || status.lumbridgeRunning;
  $('open-haptic').disabled = busy || !status.hapticscapeInstalled || status.hapticscapeRunning;
  $('sign-in').disabled = busy || status.signingIn;
  $('sign-in').textContent = status.signingIn ? 'Waiting for Jagex…' : 'Add account';
  $('cancel-login').hidden = !status.signingIn;
  $('account-actions').hidden = !status.signedIn;
  $('account-message').textContent = status.accountMessage || '';
  const accountSignature = JSON.stringify([status.accounts, status.selectedAccount]);
  if ($('accounts').dataset.signature !== accountSignature) {
    $('accounts').replaceChildren();
    if (!status.accounts.length) {
      const option = document.createElement('option'); option.value = ''; option.textContent = 'No accounts added'; $('accounts').append(option);
    }
    for (const account of status.accounts) {
      const option = document.createElement('option'); option.value = account.id; option.textContent = account.name || 'Jagex account'; $('accounts').append(option);
    }
    $('accounts').value = status.selectedAccount || '';
    $('accounts').dataset.signature = accountSignature;
  }
  $('accounts').disabled = busy || !status.accounts.length;
  const signature = JSON.stringify([status.characters, status.selectedCharacter]);
  if ($('characters').dataset.signature !== signature) {
    $('characters').replaceChildren();
    if (!status.characters.length) {
      const empty = document.createElement('p'); empty.className = 'character-empty';
      empty.textContent = status.signedIn ? 'No characters on this account.' : 'Add an account to choose a character.';
      $('characters').append(empty);
    }
    for (const character of status.characters) {
      const button = document.createElement('button'); button.type = 'button'; button.className = 'character-row';
      const selected = character.accountId === status.selectedCharacter;
      button.classList.toggle('selected', selected); button.setAttribute('aria-pressed', String(selected));
      const avatar = document.createElement('span'); avatar.className = 'character-avatar'; avatar.setAttribute('aria-hidden', 'true');
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); svg.setAttribute('viewBox', '0 0 24 24');
      const head = document.createElementNS('http://www.w3.org/2000/svg', 'circle'); head.setAttribute('cx', '12'); head.setAttribute('cy', '8'); head.setAttribute('r', '4');
      const shoulders = document.createElementNS('http://www.w3.org/2000/svg', 'path'); shoulders.setAttribute('d', 'M4 22v-3a8 8 0 0 1 16 0v3');
      svg.append(head, shoulders); avatar.append(svg);
      const name = document.createElement('span'); name.className = 'character-name'; name.textContent = character.displayName;
      const marker = document.createElement('span'); marker.className = 'character-selected'; marker.textContent = selected ? '✓' : ''; marker.setAttribute('aria-hidden', 'true');
      button.append(avatar, name, marker); button.addEventListener('click', () => action('select_character', { id: character.accountId }));
      $('characters').append(button);
    }
    $('characters').dataset.signature = signature;
  }
  $('characters').querySelectorAll('button').forEach((button) => { button.disabled = busy; });
}

async function refresh() {
  if (!invoke) return;
  try {
    status = await invoke('launcher_status');
    if (!$('settings-form').dataset.loaded) {
      for (const [key, value] of Object.entries(status.settings)) if ($('settings-form').elements.namedItem(key)) $('settings-form').elements.namedItem(key).value = value;
      $('settings-form').dataset.loaded = 'true';
    }
    if (!$('preferences-form').dataset.loaded) {
      for (const [key, value] of Object.entries(status.settings.preferences)) $('preferences-form').elements.namedItem(key).checked = value;
      $('preferences-form').dataset.loaded = 'true';
    }
    render();
  } catch (error) { if (!status) toast(error, true); }
}

document.querySelectorAll('[data-page], [data-go]').forEach((button) => button.addEventListener('click', () => page(button.dataset.page || button.dataset.go)));
$('accounts').addEventListener('change', (event) => action('select_account', { id: event.target.value }));
$('remove-account').addEventListener('click', () => { if (status?.selectedAccount) action('remove_account', { id: status.selectedAccount }); });
$('play').addEventListener('click', () => action('launch_app', { mode: 'play' }));
$('open-haptic').addEventListener('click', () => action('launch_app', { mode: 'hapticscape' }));
for (const [id, command] of [['sign-in', 'begin_login'], ['cancel-login', 'cancel_login']]) $(id).addEventListener('click', () => action(command));
$('preferences-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const preferences = Object.fromEntries(['minimizeOnPlay', 'checkUpdatesOnStartup'].map((key) => [key, event.currentTarget.elements.namedItem(key).checked]));
  await action('save_preferences', { preferences });
});
$('settings-form').addEventListener('submit', async (event) => {
  event.preventDefault(); await action('save_settings', { settings: { ...status.settings, ...Object.fromEntries(new FormData(event.currentTarget)) } });
});
async function checkUpdates() {
  const release = await action('check_updates');
  if (!release) return;
  $('release-name').textContent = release.name || release.tag; $('release-name').hidden = false;
  $('release-summary').textContent = `Latest published release: ${release.tag}${release.publishedAt ? ` · ${new Date(release.publishedAt).toLocaleDateString()}` : ''}`;
  $('release-notes').textContent = release.notes || 'No release notes were provided.'; $('release-notes').hidden = false;
}
$('check-updates').addEventListener('click', checkUpdates);
if (!invoke) {
  $('haptic-status').textContent = 'Desktop connection unavailable'; $('lumbridge-status').textContent = 'Desktop connection unavailable';
  $('play').disabled = true; $('open-haptic').disabled = true;
}
async function initialize() {
  if (invoke) {
    try { await invoke('load_accounts'); } catch (error) { toast(error, true); }
  }
  await refresh();
  if (!status) return;
  const preferences = status.settings.preferences;
  if (preferences.checkUpdatesOnStartup) await checkUpdates();
}
initialize(); setInterval(refresh, 2000);

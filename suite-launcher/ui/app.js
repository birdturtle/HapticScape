const invoke = window.__TAURI__?.core.invoke;
let status;
let busy = false;
let starting = false;
let installingComponents = false;
let updateRelease;
let checkingUpdates = false;
let updateFeedback = false;
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
  starting = command === 'launch_app' && args.mode === 'play';
  render();
  document.querySelectorAll('button').forEach((button) => { if (!button.dataset.page && !button.dataset.go) button.disabled = true; });
  try {
    const result = await invoke(command, args);
    if (typeof result === 'string') toast(result);
    else if (command === 'save_settings') toast('Application overrides saved.');
    else if (command === 'save_preferences') toast('Preferences saved.');
    await refresh();
    return result;
  } catch (error) {
    toast(error, true);
    if (command === 'check_updates' || command === 'install_update') {
      updateFeedback = true;
      $('update-message').textContent = String(error);
      if (command === 'check_updates') $('release-summary').textContent = 'Update check failed. Try again.';
    }
  }
  finally { busy = false; starting = false; render(); }
}

function render() {
  document.querySelectorAll('button').forEach((button) => { button.disabled = busy && !button.dataset.page && !button.dataset.go; });
  $('play').textContent = starting ? 'Starting…' : installingComponents || status?.componentsInstalling ? 'Installing…' : status?.componentsNeeded ? 'Retry installation' : 'Play';
  $('check-updates').textContent = checkingUpdates ? 'Checking…' : 'Check for updates';
  if (!status) return;
  $('installed-version').textContent = status.installedVersion || '';
  $('install-update').disabled = busy || status.updating || status.hapticscapeRunning || status.lumbridgeRunning || status.gameplayPortBusy;
  if (status.updateMessage && !busy && !updateFeedback) $('update-message').textContent = status.updateMessage;
  $('deep-link-message').textContent = status.deepLinkMessage || '';
  $('deep-link-message').hidden = !status.deepLinkMessage;
  $('platform').textContent = status.platform === 'linux' ? 'Linux' : 'Windows';
  for (const [kind, id] of [['hapticscape', 'haptic-status'], ['lumbridge', 'lumbridge-status']]) {
    const running = status[`${kind}Running`], installed = status[`${kind}Installed`];
    $(id).textContent = running ? 'Running' : installed ? 'Installed' : status.componentsNeeded ? 'Not installed' : 'Not found · Set path in Settings';
    $(id).className = `app-status ${running ? 'running' : installed ? '' : 'missing'}`;
  }
  const character = status.characters.find((item) => item.accountId === status.selectedCharacter);
  $('play-hint').textContent = status.componentsNeeded || installingComponents ? status.componentMessage || 'Preparing downloads…' : status.gameplayPortBusy && !status.hapticscapeRunning ? 'Gameplay port is in use. Close the other HapticScape client first.'
    : status.lumbridgeRunning ? 'LumBridge is running.' : !status.hapticscapeInstalled || !status.lumbridgeInstalled ? 'Set application paths in Settings.' : character ? '' : status.accounts.length ? 'Choose a character to play.' : 'Add an account to play.';
  $('play').disabled = busy || installingComponents || status.updating || (!status.componentsNeeded && (!status.hapticscapeInstalled || !status.lumbridgeInstalled || !character || status.lumbridgeRunning));
  $('open-haptic').disabled = busy || status.updating || status.componentsNeeded || !status.hapticscapeInstalled || status.hapticscapeRunning;
  $('sign-in').disabled = busy || status.signingIn;
  $('sign-in').textContent = status.signingIn ? 'Waiting for Jagex…' : 'Add account';
  $('reauthenticate').hidden = !status.needsSignIn;
  $('reauthenticate').disabled = busy || status.signingIn;
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
$('play').addEventListener('click', () => status?.componentsNeeded ? installComponents() : action('launch_app', { mode: 'play' }));
$('open-haptic').addEventListener('click', () => action('launch_app', { mode: 'hapticscape' }));
for (const [id, command] of [['sign-in', 'begin_login'], ['cancel-login', 'cancel_login']]) $(id).addEventListener('click', () => action(command));
$('preferences-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const preferences = Object.fromEntries(['minimizeOnPlay', 'checkUpdatesOnStartup', 'includeBetaUpdates'].map((key) => [key, event.currentTarget.elements.namedItem(key).checked]));
  await action('save_preferences', { preferences });
  updateRelease = undefined;
  $('install-update').hidden = true;
  $('release-name').hidden = true;
  $('release-notes').hidden = true;
  updateFeedback = true;
  $('update-message').textContent = 'Check for updates to use the saved preferences.';
  $('release-summary').textContent = `Installed: ${status.installedVersion || ''}`;
});
$('settings-form').addEventListener('submit', async (event) => {
  event.preventDefault(); await action('save_settings', { settings: { ...status.settings, ...Object.fromEntries(new FormData(event.currentTarget)) } });
});
async function checkUpdates() {
  if (busy) return;
  checkingUpdates = true;
  updateFeedback = true;
  updateRelease = undefined;
  $('install-update').hidden = true;
  $('release-name').hidden = true;
  $('release-notes').hidden = true;
  const channel = status?.settings.preferences.includeBetaUpdates ? 'Beta' : 'Stable';
  $('release-summary').textContent = `Installed: ${status?.installedVersion || ''} · Channel: ${channel}`;
  $('update-message').textContent = 'Checking for updates…';
  render();
  try {
    const release = await action('check_updates');
    if (!release) return;
    $('release-name').textContent = release.name || release.tag; $('release-name').hidden = false;
    updateRelease = release;
    $('install-update').hidden = !release.installable;
    $('update-message').textContent = release.message || '';
    $('release-summary').textContent = `Installed: ${release.installedVersion || status.installedVersion || ''} · Channel: ${channel} · Latest: ${release.tag}${release.publishedAt ? ` · ${new Date(release.publishedAt).toLocaleDateString()}` : ''}`;
    $('release-notes').textContent = release.notes || 'No release notes were provided.'; $('release-notes').hidden = false;
  } finally {
    checkingUpdates = false;
    render();
  }
}
$('check-updates').addEventListener('click', checkUpdates);
$('install-update').addEventListener('click', async () => {
  if (busy || !updateRelease?.installable) return;
  updateFeedback = true;
  $('update-message').textContent = 'Downloading and verifying the update…';
  await action('install_update', { tag: updateRelease.tag });
});
if (!invoke) {
  $('haptic-status').textContent = 'Desktop connection unavailable'; $('lumbridge-status').textContent = 'Desktop connection unavailable';
  $('play').disabled = true; $('open-haptic').disabled = true;
}
async function installComponents() {
  if (installingComponents || status?.updating || !invoke) return;
  installingComponents = true;
  render();
  try { await invoke('install_components'); }
  catch (error) { toast(error, true); }
  finally {
    installingComponents = false;
    $('settings-form').dataset.loaded = '';
    await refresh();
  }
}
async function initialize() {
  await refresh();
  if (!status) return;
  if (invoke) { try { await invoke('launcher_ready'); } catch (error) { toast(error, true); } }
  if (status.componentsNeeded) installComponents();
  if (invoke) {
    try { await invoke('load_accounts'); } catch (error) { toast(error, true); }
  }
  await refresh();
  if (!installingComponents && !status.componentsNeeded && status.settings.preferences.checkUpdatesOnStartup) await checkUpdates();
}
initialize(); setInterval(refresh, 2000);

$('reauthenticate').addEventListener('click', () => action('begin_login'));

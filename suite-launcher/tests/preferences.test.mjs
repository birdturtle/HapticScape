import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

function launcher(preferences, options = {}) {
  const elements = new Map();
  const calls = [];
  let poll;
  function element(id) {
    if (!elements.has(id)) elements.set(id, {
      dataset: {}, checked: false, children: [], listeners: {},
      querySelectorAll: () => [],
      setAttribute() {},
      classList: { toggle() {} },
      elements: { namedItem: (name) => element(`${id}:${name}`) },
      addEventListener(type, callback) { this.listeners[type] = callback; },
      replaceChildren() { this.children = []; },
      append(child) { this.children.push(child); },
    });
    return elements.get(id);
  }
  const status = {
    settings: { preferences }, platform: 'linux', accounts: [], characters: [],
    hapticscapeInstalled: true, lumbridgeInstalled: true, ...options.status,
  };
  runInNewContext(readFileSync(new URL('../ui/app.js', import.meta.url), 'utf8'), {
    window: { __TAURI__: { core: { async invoke(command, args) {
      calls.push({ command, args });
      if (command === 'launcher_status') return status;
      if (command === 'check_updates') {
        if (options.checkGate) await options.checkGate;
        if (options.checkError) throw options.checkError;
        return options.release || { tag: 'v1', name: 'Release', notes: 'Notes' };
      }
      if (command === 'install_components') {
        if (options.componentGate) await options.componentGate;
        if (options.componentError) { status.componentMessage = options.componentError; throw options.componentError; }
        status.componentsNeeded = false;
        status.hapticscapeInstalled = true;
        status.lumbridgeInstalled = true;
        status.componentMessage = '';
      }
      if (command === 'install_update' && options.installError) throw options.installError;
      if (command === 'save_preferences') status.settings.preferences = args.preferences;
    } } } },
    document: { getElementById: element, querySelectorAll: () => [], createElement: () => element(`option:${Math.random()}`), createElementNS: () => element(`svg:${Math.random()}`) },
    setTimeout: () => 1, clearTimeout() {}, setInterval(callback) { poll = callback; },
  });
  return { calls, element, poll: () => poll() };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

test('accounts load and releases check once; polling preserves unsaved edits', async () => {
  const app = launcher({ minimizeOnPlay: false, checkUpdatesOnStartup: true });
  await settle();
  assert.deepEqual(app.calls.filter((call) => call.command !== 'launcher_status').map((call) => call.command), ['launcher_ready', 'load_accounts', 'check_updates']);
  app.element('preferences-form:minimizeOnPlay').checked = true;
  await app.poll();
  assert.equal(app.element('preferences-form:minimizeOnPlay').checked, true);
  assert.equal(app.calls.filter((call) => call.command === 'load_accounts').length, 1);
  assert.equal(app.calls.filter((call) => call.command === 'check_updates').length, 1);
  await app.element('preferences-form').listeners.submit({ preventDefault() {}, currentTarget: app.element('preferences-form') });
  const saved = app.calls.find((call) => call.command === 'save_preferences');
  assert.equal(saved.args.preferences.minimizeOnPlay, true);
  assert.equal(typeof saved.args.preferences.checkUpdatesOnStartup, 'boolean');
});

test('accounts always load, while release checks remain opt-in', async () => {
  const app = launcher({ minimizeOnPlay: false, checkUpdatesOnStartup: false });
  await settle();
  assert.deepEqual(app.calls.map((call) => call.command), ['launcher_status', 'launcher_ready', 'load_accounts', 'launcher_status']);
});


test('compatible update installs the checked version and running apps disable installation', async () => {
  const release = { tag: 'v3.2.0', name: 'Release', installable: true, installedVersion: '3.1.0', message: 'Close apps first.' };
  const app = launcher({ checkUpdatesOnStartup: false }, { release });
  await settle();
  await app.element('check-updates').listeners.click();
  assert.equal(app.element('install-update').hidden, false);
  assert.equal(Boolean(app.element('install-update').disabled), false);
  await app.element('install-update').listeners.click();
  assert.equal(app.calls.find((call) => call.command === 'install_update').args.tag, 'v3.2.0');
  const running = launcher({ checkUpdatesOnStartup: true }, { release, status: { lumbridgeRunning: true } });
  await settle();
  assert.equal(running.element('install-update').disabled, true);
});

test('update failures stay visible and incompatible releases hide installation', async () => {
  const app = launcher({ checkUpdatesOnStartup: true }, { release: { tag: 'v3.2.0', installable: true }, installError: 'Checksum mismatch' });
  await settle();
  await app.element('install-update').listeners.click();
  assert.equal(app.element('update-message').textContent, 'Checksum mismatch');
  const old = launcher({ checkUpdatesOnStartup: true });
  await settle();
  assert.equal(old.element('install-update').hidden, true);
});


test('beta preference persists and invalidates a previously checked update', async () => {
  const app = launcher({ includeBetaUpdates: false, checkUpdatesOnStartup: true }, { release: { tag: 'v3.2.0', installable: true } });
  await settle();
  assert.equal(app.element('install-update').hidden, false);
  app.element('preferences-form:includeBetaUpdates').checked = true;
  await app.element('preferences-form').listeners.submit({ preventDefault() {}, currentTarget: app.element('preferences-form') });
  assert.equal(app.calls.find((call) => call.command === 'save_preferences').args.preferences.includeBetaUpdates, true);
  assert.equal(app.element('install-update').hidden, true);
  await app.element('install-update').listeners.click();
  assert.equal(app.calls.some((call) => call.command === 'install_update'), false);
});


test('update check shows progress, channel and persistent failure without stale install action', async () => {
  let finish;
  const options = {
    release: { tag: 'v3.2.0-beta.2', installable: true, message: 'Ready to install.' },
    status: { updateMessage: 'Updated to an older version.' },
  };
  const app = launcher({ includeBetaUpdates: true, checkUpdatesOnStartup: false }, options);
  await settle();
  await app.element('check-updates').listeners.click();
  assert.equal(app.element('install-update').hidden, false);
  assert.match(app.element('release-summary').textContent, /Channel: Beta/);
  options.checkGate = new Promise((resolve) => { finish = resolve; });
  options.checkError = 'Cannot reach GitHub.';
  const checking = app.element('check-updates').listeners.click();
  assert.equal(app.element('check-updates').textContent, 'Checking…');
  assert.equal(app.element('update-message').textContent, 'Checking for updates…');
  assert.equal(app.element('install-update').hidden, true);
  assert.equal(app.element('release-notes').hidden, true);
  await app.poll();
  assert.equal(app.element('update-message').textContent, 'Checking for updates…');
  finish();
  await checking;
  assert.equal(app.element('check-updates').textContent, 'Check for updates');
  assert.equal(app.element('update-message').textContent, 'Cannot reach GitHub.');
  assert.match(app.element('release-summary').textContent, /failed/);
  await app.poll();
  assert.equal(app.element('update-message').textContent, 'Cannot reach GitHub.');
  await app.element('install-update').listeners.click();
  assert.equal(app.calls.some((call) => call.command === 'install_update'), false);
  options.checkError = undefined;
  await app.element('check-updates').listeners.click();
  assert.equal(app.element('update-message').textContent, 'Ready to install.');
  assert.equal(app.element('install-update').hidden, false);
});

test('expired account offers the existing browser sign-in flow and keeps its character visible', async () => {
  const app = launcher({ checkUpdatesOnStartup: false }, { status: {
    needsSignIn: true, accounts: [{ id: 'saved', name: 'Character' }], selectedAccount: 'saved',
    characters: [{ accountId: 'character', displayName: 'Character' }], selectedCharacter: 'character',
    accountMessage: 'Sign in again to continue playing.',
  } });
  await settle();
  assert.equal(app.element('reauthenticate').hidden, false);
  assert.equal(app.element('account-message').textContent, 'Sign in again to continue playing.');
  assert.equal(app.element('characters').children.length, 1);
  await app.element('reauthenticate').listeners.click();
  assert.equal(app.calls.some((call) => call.command === 'begin_login'), true);
  assert.equal(app.calls.some((call) => call.command === 'remove_account'), false);
});


test('first-run download permits account loading, reports failure and retries without starting Java', async () => {
  let finish;
  const options = {
    status: { componentsNeeded: true, hapticscapeInstalled: false, lumbridgeInstalled: false, componentMessage: 'Downloading apps and Java… 42%' },
    componentGate: new Promise((resolve) => { finish = resolve; }),
    componentError: 'Cannot reach GitHub. Try again.',
  };
  const app = launcher({ checkUpdatesOnStartup: true }, options);
  await settle();
  assert.equal(app.calls.filter((c) => c.command === 'install_components').length, 1);
  assert.equal(app.calls.some((c) => c.command === 'load_accounts'), true);
  assert.equal(app.calls.some((c) => c.command === 'check_updates' || c.command === 'launch_app'), false);
  assert.equal(app.element('play').disabled, true);
  assert.equal(app.element('play').textContent, 'Installing…');
  assert.match(app.element('play-hint').textContent, /42%/);
  finish(); await settle();
  assert.equal(app.element('play').textContent, 'Retry installation');
  assert.equal(Boolean(app.element('play').disabled), false);
  assert.match(app.element('play-hint').textContent, /Cannot reach GitHub/);
  options.componentError = undefined;
  await app.element('play').listeners.click();
  assert.equal(app.element('play').textContent, 'Play');
  assert.equal(app.element('haptic-status').textContent, 'Installed');
  assert.equal(app.calls.filter((c) => c.command === 'install_components').length, 2);
  assert.equal(app.calls.some((c) => c.command === 'launch_app'), false);
  await app.poll();
  assert.equal(app.calls.filter((c) => c.command === 'install_components').length, 2);
});

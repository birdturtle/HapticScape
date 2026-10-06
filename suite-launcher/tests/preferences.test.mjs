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
      if (command === 'check_updates') return options.release || { tag: 'v1', name: 'Release', notes: 'Notes' };
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

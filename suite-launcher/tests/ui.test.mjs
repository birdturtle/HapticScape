import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

test('remote authentication window has no launcher capability', () => {
  const capability = JSON.parse(readFileSync(new URL('../src-tauri/capabilities/main.json', import.meta.url)));
  assert.deepEqual(capability.windows, ['main']);
  assert.equal(capability.remote, undefined);
});

test('launcher content policy prohibits remote scripts and frames', () => {
  const config = JSON.parse(readFileSync(new URL('../src-tauri/tauri.conf.json', import.meta.url)));
  assert.match(config.app.security.csp, /script-src 'self'/);
  assert.match(config.app.security.csp, /frame-src 'none'/);
  assert.doesNotMatch(config.app.security.csp, /unsafe-eval|unsafe-inline|https:\/\//);
});


test('first-run installation is declared and allowed only for the local launcher', () => {
  const build = readFileSync(new URL('../src-tauri/build.rs', import.meta.url), 'utf8');
  const permissions = readFileSync(new URL('../src-tauri/permissions/launcher.toml', import.meta.url), 'utf8');
  assert.match(build, /"install_components"/);
  assert.match(permissions, /"install_components"/);
});

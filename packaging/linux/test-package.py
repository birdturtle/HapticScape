"""Install the real .run bundle into a disposable home and check rendered startup."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import uuid

if not os.environ.get('HAPTICSCAPE_TEST_BUS'):
    raise SystemExit(subprocess.call(['dbus-run-session', '--', sys.executable, __file__, *sys.argv[1:]], env=dict(os.environ, HAPTICSCAPE_TEST_BUS='1')))

installer = Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory(prefix='hapticscape-package-test-') as temporary:
    root = Path(temporary)
    debs = list(installer.parent.glob('hapticscape-launcher_*.deb'))
    if debs:
        assert len(debs) == 1, 'Test a directory containing only one candidate version.'
        unpacked = root / 'deb'
        subprocess.run(['dpkg-deb', '-x', str(debs[0]), str(unpacked)], check=True)
        for component in ('launcher/hapticscape-launcher', 'app/bootstrap.json', 'app/release.json', 'app/suite.json'):
            assert (unpacked / 'opt/hapticscape' / component).is_file(), component
        assert not (unpacked / 'opt/hapticscape/runtime').exists()
        assert not (unpacked / 'opt/hapticscape/app/hapticscape-desktop.jar').exists()
        assert not (unpacked / 'opt/hapticscape/LumBridge').exists()
        assert (unpacked / 'usr/share/applications/com.hapticscape.launcher.desktop').is_file()
        dependencies = subprocess.check_output(['dpkg-deb', '-f', str(debs[0]), 'Depends'], text=True)
        assert 'libwebkit2gtk-4.1-0' in dependencies and 'libsecret-1-0' in dependencies
        print('Debian package payload, desktop entry and dependency metadata passed.')
    home = root / 'home'
    home.mkdir()
    tools = root / 'tools'
    tools.mkdir()
    for name in ('kbuildsycoca6', 'update-desktop-database', 'xdg-mime'):
        helper = tools / name
        helper.write_text('#!/bin/sh\nexit 0\n')
        helper.chmod(0o755)
    env = dict(os.environ, HOME=str(home), XDG_DATA_HOME=str(home / '.local/share'), XDG_CONFIG_HOME=str(home / '.config'), XDG_CACHE_HOME=str(home / '.cache'), PATH=f'{tools}:{os.environ["PATH"]}', GDK_BACKEND='x11')
    env.pop('WAYLAND_DISPLAY', None)
    subprocess.run(['bash', str(installer)], env=env, check=True)
    installed = home / '.local/share/hapticscape/current'
    assert (installed / 'app/bootstrap.json').is_file()
    assert not (installed / 'runtime').exists()
    assert not (installed / 'LumBridge').exists()
    payload = root / 'payload'
    payload.mkdir()
    # Use the same GNU tar semantics as packaging for this locally built fixture;
    # Java licence links are also validated by the native archive test in CI.
    subprocess.run(['tar', '-xzf', str(installer).removesuffix('.run') + '.tar.gz', '-C', str(payload)], check=True)
    bundled = payload / 'HapticScape'
    subprocess.run([str(bundled / 'runtime/bin/java'), '-jar', str(bundled / 'LumBridge/app/lumbridge.jar'), '--verify-runtime'], env=env, check=True)
    staging = root / 'HapticScape-update-smoke'
    staging.mkdir()
    marker = staging / 'launcher-ready'
    token = uuid.uuid4().hex
    # The acknowledgement is emitted by the actual frontend after its first
    # successful status render and native installed-component validation.
    log = root / 'launcher.log'
    with log.open('w') as output:
        process = subprocess.Popen(['xvfb-run', '-a', str(installed / 'launcher/hapticscape-launcher'), '--update-ready-file', str(marker), '--update-ready-token', token], env=env, stdout=output, stderr=output, start_new_session=True)
        try:
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline and process.poll() is None:
                if marker.exists() and marker.read_text() == token and (home / '.local/share/com.hapticscape.launcher/components').is_dir():
                    print('Launcher-only Linux installer, separate Java payload, frontend startup and first-run download permission passed.')
                    break
                time.sleep(0.2)
            else:
                raise AssertionError('Packaged launcher did not confirm frontend startup:\n' + log.read_text())
        finally:
            # Only our disposable smoke-test launcher and its X server run in
            # this process group. No Java applications are launched by the test.
            import signal
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=10)

"""Install the real .run bundle into a disposable home and check rendered startup."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import uuid

installer = Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory(prefix='hapticscape-package-test-') as temporary:
    root = Path(temporary)
    debs = list(installer.parent.glob('hapticscape-launcher_*.deb'))
    if debs:
        assert len(debs) == 1, 'Test a directory containing only one candidate version.'
        unpacked = root / 'deb'
        subprocess.run(['dpkg-deb', '-x', str(debs[0]), str(unpacked)], check=True)
        for component in ('launcher/hapticscape-launcher', 'runtime/bin/java', 'app/hapticscape-desktop.jar', 'LumBridge/app/lumbridge.jar'):
            assert (unpacked / 'opt/hapticscape' / component).is_file(), component
        assert (unpacked / 'usr/share/applications/com.hapticscape.launcher.desktop').is_file()
        dependencies = subprocess.check_output(['dpkg-deb', '-f', str(debs[0]), 'Depends'], text=True)
        assert 'libwebkit2gtk-4.1-0' in dependencies and 'libsecret-1-0' in dependencies
        print('Debian package payload, desktop entry and dependency metadata passed.')
    home = root / 'home'
    home.mkdir()
    tools = root / 'tools'
    tools.mkdir()
    for name in ('kbuildsycoca6', 'update-desktop-database'):
        helper = tools / name
        helper.write_text('#!/bin/sh\nexit 0\n')
        helper.chmod(0o755)
    env = dict(os.environ, HOME=str(home), XDG_DATA_HOME=str(home / '.local/share'), XDG_CONFIG_HOME=str(home / '.config'), XDG_CACHE_HOME=str(home / '.cache'), PATH=f'{tools}:{os.environ["PATH"]}', GDK_BACKEND='x11')
    env.pop('WAYLAND_DISPLAY', None)
    env.pop('DBUS_SESSION_BUS_ADDRESS', None)
    subprocess.run(['bash', str(installer)], env=env, check=True)
    installed = home / '.local/share/hapticscape/current'
    subprocess.run([str(installed / 'runtime/bin/java'), '-version'], env=env, check=True)
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
                if marker.exists() and marker.read_text() == token:
                    print('Real Linux installer, bundled Java, installed path discovery and frontend startup passed.')
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

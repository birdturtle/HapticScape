"""Exercise the packaged Linux helper against a real installation on Xvfb."""
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import tarfile
import time

if not os.environ.get('HAPTICSCAPE_TEST_DISPLAY'):
    raise SystemExit(subprocess.call(['xvfb-run', '-a', sys.executable, __file__, *sys.argv[1:]], env=dict(os.environ, HAPTICSCAPE_TEST_DISPLAY='1')))

installer = Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory(prefix='hapticscape-update-test-') as temporary:
    root = Path(temporary)
    home = root / 'home'
    home.mkdir()
    tools = root / 'tools'
    tools.mkdir()
    for name in ('kbuildsycoca6', 'update-desktop-database', 'xdg-mime'):
        tool = tools / name
        tool.write_text('#!/bin/sh\nexit 0\n')
        tool.chmod(0o755)
    env = dict(os.environ, HOME=str(home), XDG_DATA_HOME=str(home / '.local/share'), XDG_CONFIG_HOME=str(home / '.config'), XDG_CACHE_HOME=str(home / '.cache'), PATH=f'{tools}:{os.environ["PATH"]}', GDK_BACKEND='x11')
    env.pop('DBUS_SESSION_BUS_ADDRESS', None)
    env.pop('WAYLAND_DISPLAY', None)
    subprocess.run(['bash', str(installer)], env=env, check=True)
    install = home / '.local/share/hapticscape'
    current = install / 'current'
    initial = current.resolve()
    sentinel = home / '.config/existing-pairing'
    sentinel.parent.mkdir(parents=True)
    sentinel.write_text('preserved')
    for fail in (False, True):
        old = current.resolve()
        stage = install / ('HapticScape-update-failure' if fail else 'HapticScape-update-success')
        staged = stage / 'extracted/HapticScape'
        with tarfile.open(str(installer).removesuffix('.run') + '.tar.gz') as archive:
            archive.extractall(stage / 'extracted', filter='data')
        helper = stage / 'update-helper'
        shutil.copy2(old / 'launcher/hapticscape-launcher', helper)
        if fail:
            (staged / 'launcher/hapticscape-launcher').write_text('#!/bin/sh\nexit 1\n')
        parent = subprocess.Popen(['sleep', '0.5'])
        with (root / f'helper-{fail}.log').open('w') as log:
            process = subprocess.Popen([str(helper), '--apply-update', str(parent.pid), str(install), str(staged)], env=env, stdout=log, stderr=log, start_new_session=True)
            try:
                parent.wait(timeout=5)
                code = process.wait(timeout=100)
                assert code == (1 if fail else 0), (root / f'helper-{fail}.log').read_text()
                assert (current.resolve() == old) == fail
                assert initial.exists(), 'Previous installation was removed'
                assert sentinel.read_text() == 'preserved'
                result = (install / 'update-result.json').read_text()
                assert ('restored' in result) if fail else ('Updated' in result)
                assert stage.exists() == fail
                # Let the restored launcher initialize before shutting down this
                # test-only process group. No game or Java app is launched.
                if fail:
                    time.sleep(1)
            finally:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                if process.poll() is None:
                    process.wait(timeout=10)
    print('Real packaged Linux helper: activation, frontend acknowledgement, rollback, parent wait and preserved data passed.')

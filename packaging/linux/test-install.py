"""Exercise install/upgrade using an isolated home, with no desktop-session effects."""
import os
import hashlib
import tarfile
from pathlib import Path
import shutil
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='hapticscape-installer-test-') as temporary:
    root = Path(temporary)
    tools = root / 'tools'
    tools.mkdir()
    for name in ('kbuildsycoca6', 'update-desktop-database'):
        tool = tools / name
        tool.write_text('#!/bin/sh\nexit 0\n')
        tool.chmod(0o755)
    home = root / 'home with spaces'
    home.mkdir()
    env = dict(os.environ, HOME=str(home), XDG_DATA_HOME=str(home / '.local/share'), PATH=f'{tools}:{os.environ["PATH"]}')
    data = home / '.config/hapticscape/existing-pairing'
    data.parent.mkdir(parents=True)
    data.write_text('preserved')
    package = root / 'package'
    for path in ('launcher', 'app', 'LumBridge/app', 'runtime/bin'):
        (package / path).mkdir(parents=True)
    shutil.copy('/bin/true', package / 'launcher/hapticscape-launcher')
    shutil.copy('/bin/true', package / 'runtime/bin/java')
    shutil.copy(repo / 'packaging/linux/install.sh', package / 'install.sh')
    (package / 'icon.png').write_bytes(b'icon')
    (package / 'app/hapticscape-desktop.jar').write_bytes(b'first')
    (package / 'LumBridge/app/lumbridge.jar').write_bytes(b'bridge')
    (package / 'VERSION').write_text('0.1.0')
    subprocess.run(['bash', str(package / 'install.sh')], env=env, check=True)
    current = home / '.local/share/hapticscape/current'
    first = current.resolve()
    assert (first / 'app/hapticscape-desktop.jar').read_bytes() == b'first'
    (package / 'VERSION').write_text('0.2.0')
    (package / 'app/hapticscape-desktop.jar').write_bytes(b'second')
    subprocess.run(['bash', str(package / 'install.sh')], env=env, check=True)
    assert current.resolve() != first
    assert (first / 'app/hapticscape-desktop.jar').read_bytes() == b'first'
    assert (current / 'app/hapticscape-desktop.jar').read_bytes() == b'second'
    assert data.read_text() == 'preserved'
    desktop = home / '.local/share/applications/com.hapticscape.launcher.desktop'
    assert f'Exec="{current}/launcher/hapticscape-launcher"' in desktop.read_text()
    second = current.resolve()
    (package / 'LumBridge/app/lumbridge.jar').unlink()
    result = subprocess.run(['bash', str(package / 'install.sh')], env=env, capture_output=True)
    assert result.returncode != 0
    assert current.resolve() == second
    (package / 'LumBridge/app/lumbridge.jar').write_bytes(b'bridge')
    (package / 'VERSION').write_text('0.3.0')
    archive = root / 'payload.tar.gz'
    with tarfile.open(archive, 'w:gz') as bundle:
        bundle.add(package, arcname='HapticScape')
    payload = archive.read_bytes()
    header = (repo / 'packaging/linux/self-extract.sh').read_text().replace('__PAYLOAD_SHA256__', hashlib.sha256(payload).hexdigest())
    installer = root / 'installer.run'
    installer.write_bytes(header.encode() + payload)
    subprocess.run(['bash', str(installer)], env=env, check=True)
    third = current.resolve()
    assert third != second
    assert (current / 'VERSION').read_text() == '0.3.0'
    installer.write_bytes(header.encode() + payload + b'corrupted')
    result = subprocess.run(['bash', str(installer)], env=env, capture_output=True)
    assert result.returncode != 0
    assert current.resolve() == third
    print('Archive/self-extracting install, upgrades, desktop entry, retained data and invalid package rejection passed.')

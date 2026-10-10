"""Check the built package and Intiface choices without installing software."""
import json
import os
from pathlib import Path
import pty
import select
import subprocess
import sys
import tempfile
import time

repo = Path(__file__).resolve().parents[2]
package = Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory(prefix='hapticscape-aur-test-') as temporary:
    root = Path(temporary)
    payload = root / 'package'
    payload.mkdir()
    subprocess.run(['bsdtar', '-xf', str(package), '-C', str(payload)], check=True)
    install = payload / 'opt/hapticscape'
    for name in ('release.json', 'suite.json', 'bootstrap.json'):
        assert json.loads((install / 'app' / name).read_text())['version'] == '3.2.0-rc.2'
    assert (install / 'launcher/hapticscape-launcher').is_file()
    assert not (install / 'runtime').exists()
    assert not (install / 'LumBridge').exists()
    assert not (install / 'app/hapticscape-desktop.jar').exists()
    assert os.readlink(payload / 'usr/bin/hapticscape-launcher') == '/opt/hapticscape/launcher/hapticscape-launcher'
    desktop = (payload / 'usr/share/applications/com.hapticscape.launcher.desktop').read_text()
    assert 'MimeType=x-scheme-handler/hapticscape;' in desktop and ' %u' in desktop
    metadata = (payload / '.PKGINFO').read_text()
    assert 'optdepend = intiface-central-bin:' in metadata
    assert not any(line.startswith('depend = intiface-central') for line in metadata.splitlines())
    tools = root / 'tools'
    tools.mkdir()
    log = root / 'invocations'
    (tools / 'pacman').write_text('#!/bin/sh\nexit "${MOCK_INSTALLED:-1}"\n')
    for tool in ('paru', 'yay'):
        (tools / tool).write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$MOCK_LOG"\n')
    for tool in tools.iterdir():
        tool.chmod(0o755)
    helper = repo / 'packaging/aur/setup-intiface.sh'
    env = dict(os.environ, PATH=f'{tools}:/usr/bin:/bin', MOCK_LOG=str(log))

    def choose(answers, overrides=None):
        master, slave = pty.openpty()
        process = subprocess.Popen(['bash', str(helper)], env=dict(env, **(overrides or {})), stdin=slave, stdout=slave, stderr=slave)
        os.close(slave)
        os.write(master, answers.encode())
        output = bytearray()
        deadline = time.monotonic() + 5
        try:
            while time.monotonic() < deadline:
                if select.select([master], [], [], .1)[0]:
                    try:
                        chunk = os.read(master, 4096)
                    except OSError:
                        break
                    if not chunk:
                        break
                    output.extend(chunk)
                if process.poll() is not None:
                    break
            code = process.wait(timeout=1)
            return code, output.decode()
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            os.close(master)

    assert choose('n\n')[0] == 0 and not log.exists()
    assert choose('y\n1\n')[0] == 0
    assert log.read_text().splitlines() == ['-S --needed intiface-central-bin']
    log.unlink()
    assert choose('y\n2\n')[0] == 0
    assert log.read_text().splitlines() == ['-S --needed intiface-central']
    log.unlink()
    assert choose('y\ninvalid\n')[0] == 1 and not log.exists()
    assert choose('\n', {'MOCK_INSTALLED': '0'})[0] == 0 and not log.exists()
    result = subprocess.run(['bash', str(helper)], env=env, stdin=subprocess.DEVNULL, capture_output=True)
    assert result.returncode == 1 and not log.exists()
    print('Launcher-only AUR payload, desktop link handling and optional binary/source/decline prompts passed.')

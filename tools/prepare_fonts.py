"""Reproduce the offline font bundle from pinned OFL sources; no pip dependencies.

Downloads happen at BUILD time only. Every asset (including licenses) is hash checked.
Run: python3 tools/prepare_fonts.py [--output app/build/generated/readerFonts/assets]
"""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import time
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

def prepare(output):
    manifest = json.loads((ROOT / 'tools/fonts-manifest.json').read_text())
    families = manifest['families']
    if len(families) != 100 or len({x['name'] for x in families}) != 100:
        raise ValueError('The offline catalog must contain exactly 100 distinct families')
    base = 'https://raw.githubusercontent.com/google/fonts/' + manifest['commit'] + '/'
    output.mkdir(parents=True, exist_ok=True)
    files = [f for family in families for f in family['files']]
    def download(entry):
        destination = output / entry['asset']
        def valid(data):
            return len(data) == entry['bytes'] and hashlib.sha256(data).hexdigest() == entry['sha256']
        if destination.is_file() and valid(destination.read_bytes()):
            return
        for attempt in range(4):
            try:
                with urllib.request.urlopen(base + urllib.parse.quote(entry['path'], safe='/'), timeout=45) as response:
                    data = response.read(entry['bytes'] + 1)
                if not valid(data):
                    raise ValueError('Font integrity mismatch: ' + entry['path'])
                destination.parent.mkdir(parents=True, exist_ok=True)
                temporary = destination.with_suffix(destination.suffix + '.part')
                temporary.write_bytes(data)
                temporary.replace(destination)
                return
            except Exception:
                if attempt == 3:
                    raise
                time.sleep(attempt + 1)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(download, files))
    # Remove obsolete generated assets: the catalog and packaged fonts must agree.
    expected = {entry['asset'] for entry in files} | {'fonts/catalog.json'}
    for path in (output / 'fonts').glob('*'):
        if path.is_file() and str(path.relative_to(output)) not in expected:
            path.unlink()
    (output / 'fonts/catalog.json').write_text(json.dumps(manifest, ensure_ascii=False), encoding='utf-8')
    print(f'Verified {len(families)} offline font families, {len(files)} licensed assets')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=ROOT / 'app/build/generated/readerFonts/assets')
    prepare(parser.parse_args().output)

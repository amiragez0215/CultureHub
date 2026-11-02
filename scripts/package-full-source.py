"""Archive reproducible sources and seed definitions, never local credentials or database files."""
from pathlib import Path
import os,zipfile
root=Path(__file__).resolve().parents[1]
output=root/'CultureHub-V1.0-source.zip'
excluded={'.tools','.git','.idea','.vscode','target','node_modules','dist','logs','__pycache__'}
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
    for current,dirs,files in os.walk(root):
        dirs[:]=sorted(d for d in dirs if d not in excluded)
        for name in sorted(files):
            path=Path(current)/name
            if path.is_symlink() or path.suffix in {'.zip','.log','.pyc','.db','.rdb','.aof','.jar','.class'}:continue
            if name.startswith('.env') and name!='.env.example':continue
            archive.write(path,'CultureHub/'+path.relative_to(root).as_posix())
with zipfile.ZipFile(output) as archive:
    assert archive.testzip() is None
    for required in ['web/nginx.conf','web/src/sse.js','docs/technical-enhancement.md','deploy/migrations/20261101-participation-history-index.sql']:
        assert 'CultureHub/'+required in archive.namelist(),required
print('Full source archive verified:',output.name,output.stat().st_size,'bytes')

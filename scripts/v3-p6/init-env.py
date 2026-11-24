"""Generate local V3 credentials once. Never print generated secrets or overwrite an existing file."""
from pathlib import Path
import argparse,base64,secrets
parser=argparse.ArgumentParser();parser.add_argument('--demo',action='store_true');args=parser.parse_args()
root=Path(__file__).resolve().parents[2];target=root/'.env.v3'
if target.exists():raise SystemExit('.env.v3 already exists; preserved without changes')
lines=[]
for line in (root/'.env.v3.example').read_text(encoding='utf8').splitlines():
 if not line or line.startswith('#') or '=' not in line:lines.append(line);continue
 key,value=line.split('=',1)
 if key.endswith('_PASSWORD') or key.endswith('_DB_PASSWORD') or key.endswith('_TOKEN') or key in ('JWT_SECRET','MINIO_SECRET_KEY','NACOS_AUTH_IDENTITY_VALUE'):
  value=secrets.token_hex(24)
 if key=='NACOS_AUTH_TOKEN':value=base64.b64encode(secrets.token_bytes(48)).decode()
 if key=='MINIO_ACCESS_KEY':value='ch'+secrets.token_hex(8)
 if key=='DEMO_PASSWORD':value='123' if args.demo else ''
 lines.append(key+'='+value)
target.write_text('\n'.join(lines)+'\n',encoding='utf8');print('Created .env.v3. Secrets remain local; configure AI keys there when needed.')

"""Build a reviewable source snapshot without secrets, runtime data, dependencies or Git state."""
from pathlib import Path
import hashlib,json,os,zipfile
root=Path(__file__).resolve().parents[2]
archive=root/'CultureHub-V3-source-no-data.zip'
allowed_dirs={'culturehub-contracts','culturehub-business','culturehub-api','culturehub-notification-worker','culturehub-gateway','culturehub-service-runtime','culturehub-knowledge','culturehub-ai','web','deploy','docs','scripts','.mvn'}
root_files={'pom.xml','settings.xml','README.md','LICENSE','.gitignore','.dockerignore','.gitattributes','.env.example','.env.v3.example','mvnw','mvnw.cmd'}
skip={'target','node_modules','dist','.git','.tools','.idea','.vscode','logs','__pycache__','.codex','.agents'}
files=[]
for directory,names,entries in os.walk(root):
 relative=Path(directory).relative_to(root)
 names[:]=[name for name in names if name not in skip and (relative.parts or name in allowed_dirs)]
 for name in entries:
  path=Path(directory)/name;rel=path.relative_to(root)
  if not relative.parts and name not in root_files and not (name.startswith('compose') and path.suffix in ('.yml','.yaml')):continue
  if name.startswith('.env') and name not in ('.env.example','.env.v3.example'):continue
  if path.suffix.lower() in {'.zip','.7z','.jar','.class','.log','.pid','.rdb','.aof','.db','.sqlite','.sqlite3','.pem','.key','.p12','.pfx','.pyc'}:continue
  files.append(path)
files.sort()
def source_bytes(path):
 data=path.read_bytes()
 return data.replace(b'\r\n',b'\n') if path.suffix=='.sh' or path.name=='mvnw' else data
manifest={'branch':'codex/culturehub-v3','baselineCommit':'561d4382cc39cf45dd02fe79d3e3a4c05c0441a8','snapshot':'working-tree source, not a final release commit','files':{str(p.relative_to(root)).replace('\\','/'):hashlib.sha256(source_bytes(p)).hexdigest() for p in files}}
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as output:
 for path in files:
  info=zipfile.ZipInfo('CultureHub-V3/'+path.relative_to(root).as_posix());info.create_system=3
  info.external_attr=((0o100755 if path.suffix=='.sh' or path.name=='mvnw' else 0o100644)<<16)
  info.compress_type=zipfile.ZIP_DEFLATED
  output.writestr(info,source_bytes(path))
 output.writestr('CultureHub-V3/SOURCE-MANIFEST.json',json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
with zipfile.ZipFile(archive) as check:
 assert check.testzip() is None
 assert (check.getinfo('CultureHub-V3/deploy/v3-p1/00-init.sh').external_attr>>16)&0o111
 assert b'\r' not in check.read('CultureHub-V3/deploy/v3-p1/00-init.sh')
 names=check.namelist()
 for required in ['pom.xml','web/nginx.conf','web/package-lock.json','compose.v3.yaml','.env.v3.example','culturehub-ai/src/main/java/cn/culturehub/AiGroupController.java','docs/v3-test-report.md']:
  assert 'CultureHub-V3/'+required in names,required
 assert not any('/.tools/' in name or '/target/' in name or '/node_modules/' in name or name.endswith('/.env.v3') or name.endswith('/.env') for name in names)
print(json.dumps({'archive':archive.name,'files':len(files),'bytes':archive.stat().st_size,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest()}))

param(
  [string]$MysqlClient = 'mysql',
  [string]$Database = 'culturehub_demo',
  [int]$Port = 3306,
  [string]$DatabaseUser = 'root'
)
$ErrorActionPreference = 'Stop'
if ($Database -notmatch '^[a-zA-Z0-9_]+_demo$') { throw 'Use a dedicated database ending in _demo.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot
# Password is read by mysql from MYSQL_PWD, not printed or passed in command arguments.
$sql = [IO.File]::ReadAllText((Join-Path $projectRoot 'deploy/mysql/01-schema.sql')).Replace('culturehub', $Database)
$exists = & $MysqlClient --host=127.0.0.1 "--port=$Port" "--user=$DatabaseUser" --batch --skip-column-names -e "select count(*) from information_schema.tables where table_schema='$Database' and table_name='app_user'"
if ($LASTEXITCODE -ne 0) { throw 'Cannot connect to MySQL.' }
if ($exists.Trim() -eq '0') {
  $tempSql = Join-Path $projectRoot '.tools/demo-schema.sql'
  New-Item -ItemType Directory -Force (Split-Path $tempSql) | Out-Null
  [IO.File]::WriteAllText($tempSql, $sql, (New-Object Text.UTF8Encoding($false)))
  & $MysqlClient --host=127.0.0.1 "--port=$Port" "--user=$DatabaseUser" --default-character-set=utf8mb4 -e "source $($tempSql.Replace('\','/'))"
  if ($LASTEXITCODE -ne 0) { throw 'Schema initialization failed.' }
}
if (-not $env:DEMO_PASSWORD) { $env:DEMO_PASSWORD = '123' }
$migrationFile = (Join-Path $projectRoot 'deploy/migrations/20261101-venue-category.sql').Replace('\','/')
& $MysqlClient --host=127.0.0.1 "--port=$Port" "--user=$DatabaseUser" "--database=$Database" --default-character-set=utf8mb4 -e "source $migrationFile"
if ($LASTEXITCODE -ne 0) { throw 'Venue category migration failed.' }
$proposalMigration = (Join-Path $projectRoot 'deploy/migrations/20261101-ai-booking-proposal.sql').Replace('\','/')
& $MysqlClient --host=127.0.0.1 "--port=$Port" "--user=$DatabaseUser" "--database=$Database" --default-character-set=utf8mb4 -e "source $proposalMigration"
if ($LASTEXITCODE -ne 0) { throw 'AI proposal migration failed.' }
$historyMigration = (Join-Path $projectRoot 'deploy/migrations/20261101-participation-history-index.sql').Replace('\','/')
& $MysqlClient --host=127.0.0.1 "--port=$Port" "--user=$DatabaseUser" "--database=$Database" --default-character-set=utf8mb4 -e "source $historyMigration"
if ($LASTEXITCODE -ne 0) { throw 'Participation history index migration failed.' }
$env:DB_URL = "jdbc:mysql://localhost:$Port/$($Database)?connectionTimeZone=Asia/Shanghai"
$env:DB_USER = $DatabaseUser
$env:DB_PASSWORD = $env:MYSQL_PWD
$env:CULTURE_DEMO_RICH = 'true'
if (-not $env:JWT_SECRET) { $env:JWT_SECRET = [Guid]::NewGuid().ToString() + [Guid]::NewGuid().ToString() }
mvn -s settings.xml -q -DskipTests package
if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
java '-Duser.timezone=Asia/Shanghai' -jar culturehub-api/target/culturehub-api-1.0.0.jar --culture.jobs.enabled=false "--spring.datasource.password=$env:MYSQL_PWD"

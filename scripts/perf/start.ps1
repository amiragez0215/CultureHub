param([string]$Database='culturehub_perf',[int]$DbPort=13306,[int]$RedisPort=16379)
$ErrorActionPreference='Stop'
if($Database -notmatch '^[a-zA-Z0-9_]+_perf$'){throw 'Dedicated _perf database required'}
$root=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root
$existing=Get-NetTCPConnection -LocalPort 18090 -State Listen -ErrorAction SilentlyContinue
if($existing){
  $serviceId=$existing[0].OwningProcess
  $proc=Get-CimInstance Win32_Process -Filter "ProcessId=$serviceId"
  if($proc.CommandLine -notlike '*CultureHub*.tools*perf-api.jar*'){throw '18090 belongs to another application'}
  Stop-Process -Id $serviceId
  Wait-Process -Id $serviceId -ErrorAction SilentlyContinue
}
$env:DB_URL="jdbc:mysql://127.0.0.1:$DbPort/$($Database)?connectionTimeZone=Asia/Shanghai"
$env:DB_PASSWORD=$env:MYSQL_PWD
$env:JWT_SECRET='culturehub-isolated-performance-secret-2026'
$env:REDIS_PORT="$RedisPort"
$env:DEMO_PASSWORD=''
$env:AI_CHAT_PROVIDER='none'
$env:AI_EMBEDDING_PROVIDER='none'
$env:AI_VECTORSTORE='none'
$env:AI_API_KEY='not-configured'
$env:SPRING_PROFILES_ACTIVE=''
Copy-Item -LiteralPath "$root/culturehub-api/target/culturehub-api-1.0.0.jar" -Destination "$root/.tools/perf-api.jar" -Force
$arguments=@('-Duser.timezone=Asia/Shanghai','-jar',"$root/.tools/perf-api.jar",'--server.port=18090','--server.address=127.0.0.1','--spring.config.import=optional:file:.tools/perf.env[.properties]','--culture.demo.rich=false','--culture.jobs.enabled=false','--management.endpoints.web.exposure.include=health,metrics')
if(-not $env:MYSQL_PWD){$arguments+='--spring.datasource.password='}
Start-Process -FilePath (Get-Command java).Source -ArgumentList $arguments -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput "$root/.tools/perf-api.log" -RedirectStandardError "$root/.tools/perf-api-error.log" | Out-Null
for($attempt=0;$attempt -lt 60;$attempt++){
  try{if((Invoke-RestMethod 'http://127.0.0.1:18090/actuator/health' -TimeoutSec 2).status -eq 'UP'){Write-Output 'Isolated performance API ready: 18090';exit 0}}catch{}
  Start-Sleep -Milliseconds 500
}
throw 'API startup failed; inspect .tools/perf-api.log'

param([ValidateSet('Start','Stop','Run','Status')][string]$Action='Status')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$runtime=Join-Path $root '.tools/rabbit-runtime'
$env:ERLANG_HOME=Join-Path $runtime 'erlang'
$server=Join-Path $runtime 'rabbitmq/rabbitmq_server-4.1.4/sbin'
if(-not (Test-Path -LiteralPath "$env:ERLANG_HOME/bin/erl.exe")){throw 'Extract official OTP 27.3.4.1 Windows ZIP into .tools/rabbit-runtime/erlang first.'}
$env:RABBITMQ_BASE=Join-Path $runtime 'state'
$env:RABBITMQ_MNESIA_BASE=Join-Path $env:RABBITMQ_BASE 'db'
$env:RABBITMQ_LOG_BASE=Join-Path $env:RABBITMQ_BASE 'log'
$env:RABBITMQ_ENABLED_PLUGINS_FILE=Join-Path $env:RABBITMQ_BASE 'enabled_plugins'
$env:RABBITMQ_CONFIG_FILE=Join-Path $env:RABBITMQ_BASE 'rabbitmq'
$env:RABBITMQ_NODENAME='culturehub_acceptance@localhost'
$env:RABBITMQ_NODE_PORT='5673'
$env:RABBITMQ_DIST_PORT='25673'
# Isolated disposable localhost node. No Windows service, registry or user cookie changes.
$env:RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS='-setcookie culturehub_local_acceptance_only +S 4:4'
$env:RABBITMQ_CTL_ERL_ARGS='-setcookie culturehub_local_acceptance_only +S 2:2'
New-Item -ItemType Directory -Force -Path $env:RABBITMQ_BASE | Out-Null
@'
listeners.tcp.1 = 127.0.0.1:5673
management.tcp.port = 15673
management.tcp.ip = 127.0.0.1
distribution.listener.interface = 127.0.0.1
default_vhost = culturehub_acceptance
disk_free_limit.absolute = 200MB
'@ | Set-Content -LiteralPath "$($env:RABBITMQ_CONFIG_FILE).conf" -Encoding ASCII
'[rabbitmq_management].' | Set-Content -LiteralPath $env:RABBITMQ_ENABLED_PLUGINS_FILE -Encoding ASCII
switch($Action){
 'Run' { & "$server/rabbitmq-server.bat"; exit $LASTEXITCODE }
 'Stop' { & "$server/rabbitmqctl.bat" shutdown; exit $LASTEXITCODE }
 'Status' { & "$server/rabbitmq-diagnostics.bat" -q status; exit $LASTEXITCODE }
 'Start' {
   $process=Start-Process powershell.exe -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File',"`"$PSCommandPath`"",'-Action','Run' -WindowStyle Hidden -WorkingDirectory $root -RedirectStandardOutput "$runtime/server.log" -RedirectStandardError "$runtime/server-error.log" -PassThru
   Write-Output "RabbitMQ starting: PID=$($process.Id), AMQP=127.0.0.1:5673, management=127.0.0.1:15673, vhost=culturehub_acceptance"
 }
}

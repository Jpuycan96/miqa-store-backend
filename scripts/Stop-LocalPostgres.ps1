param([string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$dataPath = Join-Path $projectRoot '.local/postgres'
if (!(Test-Path (Join-Path $dataPath 'PG_VERSION'))) { throw 'No project PostgreSQL cluster exists' }
& (Join-Path $PostgresBin 'pg_ctl.exe') -D $dataPath -m fast -w stop
if ($LASTEXITCODE -ne 0) { throw 'Could not stop the project PostgreSQL cluster' }

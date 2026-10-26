param(
    [string]$JdkHome = (Join-Path $env:USERPROFILE '.jdks\ms-17.0.16')
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$gatewayRoot = Join-Path $projectRoot 'model-gateway'
$python = Join-Path $gatewayRoot '.venv\Scripts\python.exe'
$java = Join-Path $JdkHome 'bin\java.exe'
$output = Join-Path $projectRoot 'target\gateway-phase2'
$started = @()
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:Path
$oldCache = $env:ENGINE_CACHE_DIR
$oldOffline = $env:DJL_OFFLINE

function Invoke-CheckedNative([string]$Command, [string[]]$NativeArguments, [string]$Log) {
    # Windows PowerShell treats native stderr warnings as error records. Judge the process exit code.
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Command @NativeArguments *> $Log
        $nativeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($nativeExit -ne 0) { throw "Native test command failed ($nativeExit); inspect $Log" }
}

function Wait-LocalHealth([string]$Url, $Process) {
    for ($attempt = 0; $attempt -lt 100; $attempt++) {
        if ($Process.HasExited) { throw "Local test process exited; inspect $output" }
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 1
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Milliseconds 200
    }
    throw "Local test service did not become healthy: $Url"
}

Push-Location $projectRoot
try {
    if (!(Test-Path -LiteralPath $python) -or !(Test-Path -LiteralPath $java)) {
        throw 'Prepare the project Python virtual environment and JDK 17 first; see docs/model-gateway.md'
    }
    foreach ($port in @(8080, 8001, 8002)) {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
        try { $listener.Start() } finally { $listener.Stop() }
    }
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    $env:JAVA_HOME = $JdkHome
    $env:Path = "$JdkHome\bin;$oldPath"
    $env:ENGINE_CACHE_DIR = Join-Path $projectRoot 'target\rag-diagnosis-native-cache'
    $env:DJL_OFFLINE = 'true'
    $javaVersion = & $java --version | Out-String
    if ($javaVersion -notmatch '(openjdk|java) 17\.') { throw 'The integration run requires JDK 17' }
    Write-Output $javaVersion

    Invoke-CheckedNative $python @('-m', 'pytest', 'model-gateway/tests', '-q', '-c', 'model-gateway/pyproject.toml', "--junitxml=$output\python.xml") "$output\python-tests.log"
    Invoke-CheckedNative 'mvn' @('-o', 'test') "$output\java-tests.log"

    # Use the actual Surefire classpath; no dependency plugin, new Maven dependency or production startup.
    $report = [xml](Get-Content -Raw -LiteralPath 'target\surefire-reports\TEST-com.example.rag.chat.ModelGatewayHttpTest.xml')
    $classpath = ($report.testsuite.properties.property | Where-Object name -EQ 'java.class.path').value
    if (!$classpath) { throw 'Missing Java test classpath' }
    $argFile = Join-Path $output 'probe-java.args'
    $argText = '-cp' + "`n" + '"' + $classpath.Replace('\', '/') + '"' + "`n" + 'com.example.rag.chat.ModelGatewayIT$ProbeApplication' + "`n"
    [IO.File]::WriteAllText($argFile, $argText, [Text.UTF8Encoding]::new($false))

    $fake = Start-Process -FilePath $python -ArgumentList '-m uvicorn fake_model:app --app-dir tests --host 127.0.0.1 --port 8002 --no-access-log --log-level warning' `
        -WorkingDirectory $gatewayRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$output\fake.out.log" -RedirectStandardError "$output\fake.err.log"
    $started += $fake
    Wait-LocalHealth 'http://127.0.0.1:8002/health' $fake
    $gateway = Start-Process -FilePath $python -ArgumentList '-m uvicorn app:app --host 127.0.0.1 --port 8001 --no-access-log --log-level warning' `
        -WorkingDirectory $gatewayRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$output\gateway.out.log" -RedirectStandardError "$output\gateway.err.log"
    $started += $gateway
    Wait-LocalHealth 'http://127.0.0.1:8001/health' $gateway
    $probe = Start-Process -FilePath $java -ArgumentList ('"@' + $argFile + '"') `
        -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$output\java.out.log" -RedirectStandardError "$output\java.err.log"
    $started += $probe
    Wait-LocalHealth 'http://127.0.0.1:8080/health' $probe

    @{java_pid=$probe.Id; gateway_launcher_pid=$gateway.Id; fake_launcher_pid=$fake.Id;
        java_port=8080; gateway_port=8001; fake_port=8002} | ConvertTo-Json | Set-Content -Encoding UTF8 "$output\processes.json"

    Invoke-CheckedNative 'mvn' @('-o', '-Dtest=ModelGatewayIT', 'test') "$output\integration-tests.log"
    Copy-Item -LiteralPath 'target\surefire-reports\TEST-com.example.rag.chat.ModelGatewayIT.xml' -Destination "$output\integration.xml"
    Write-Output "Cross-language tests passed. Reports: $output"
}
finally {
    # Stop only process objects created by this invocation. Never stop by name or touch existing services/data.
    [array]::Reverse($started)
    foreach ($process in $started) {
        if (!$process.HasExited) {
            # Include the Windows venv redirector's child; PID roots are exclusively our own process objects.
            $savedPreference = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try { & taskkill.exe /PID $process.Id /T /F *> $null }
            finally { $ErrorActionPreference = $savedPreference }
            if (!$process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
        }
    }
    $env:JAVA_HOME = $oldJavaHome
    $env:Path = $oldPath
    $env:ENGINE_CACHE_DIR = $oldCache
    $env:DJL_OFFLINE = $oldOffline
    Pop-Location
}

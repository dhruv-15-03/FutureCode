$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$root = Split-Path $PSScriptRoot -Parent
$jar = Join-Path $root 'target\futurecode.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build target\futurecode.jar with .\mvnw.cmd package first.' }
$probeInfo = [System.Diagnostics.ProcessStartInfo]::new()
$probeInfo.FileName = (Get-Command java -CommandType Application | Select-Object -First 1).Source
$probeInfo.Arguments = '-XshowSettings:properties -version'
$probeInfo.UseShellExecute = $false
$probeInfo.CreateNoWindow = $true
$probeInfo.RedirectStandardOutput = $true
$probeInfo.RedirectStandardError = $true
$probe = [System.Diagnostics.Process]::Start($probeInfo)
try {
    $probeOut = $probe.StandardOutput.ReadToEndAsync()
    $probeErr = $probe.StandardError.ReadToEndAsync()
    if (-not $probe.WaitForExit(20000)) { Stop-Process -Id $probe.Id -Force; throw 'The Java version probe timed out.' }
    $javaSettings = $probeOut.GetAwaiter().GetResult() + "`n" + $probeErr.GetAwaiter().GetResult()
    $javaHome = [regex]::Match($javaSettings, '(?m)^\s*java\.home = (.+)\r?$')
    if ($probe.ExitCode -ne 0 -or -not $javaHome.Success) { throw 'Could not resolve the active JVM executable.' }
    $javaExecutable = Join-Path $javaHome.Groups[1].Value.Trim() 'bin\java.exe'
} finally { $probe.Dispose() }
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()
$origin = "http://127.0.0.1:$port"
$logBase = Join-Path $root ("target\smoke-" + [guid]::NewGuid().ToString('N'))
$outLog = "$logBase.out.log"
$errLog = "$logBase.err.log"
$jvmProcess = $null
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AutomaticDecompression = [System.Net.DecompressionMethods]::GZip
$handler.MaxConnectionsPerServer = 10
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(5)
try {
    $arguments = @('-Xms32m', '-Xmx160m', '-Xss512k', '-jar', ('"' + $jar + '"'),
        "--server.port=$port", '--server.address=127.0.0.1', "--futurecode.origin=$origin",
        '--futurecode.environment=development', '--futurecode.command=serve',
        '--futurecode.database-url=', '--futurecode.google-client-id=', '--futurecode.google-client-secret=',
        '--futurecode.session-secret=', '--futurecode.mysql-ca-file=',
        '--futurecode.integrations.execution-enabled=false', '--futurecode.integrations.payments-enabled=false',
        '--futurecode.integrations.razorpay-key-id=', '--futurecode.integrations.razorpay-key-secret=',
        '--futurecode.integrations.razorpay-webhook-secret=', '--futurecode.integrations.support-amount-paise=0')
    $jvmProcess = Start-Process -FilePath $javaExecutable -ArgumentList $arguments -WorkingDirectory $root -PassThru -WindowStyle Hidden -RedirectStandardOutput $outLog -RedirectStandardError $errLog
    $ready = $false
    $peak = 0L
    $startup = [System.Diagnostics.Stopwatch]::StartNew()
    while ($startup.Elapsed.TotalSeconds -lt 45) {
        $jvmProcess.Refresh()
        if ($jvmProcess.HasExited) { throw 'The Java preview exited before readiness. Check Java version and application configuration.' }
        $peak = [Math]::Max($peak, $jvmProcess.WorkingSet64)
        try {
            $response = $client.GetAsync("$origin/livez").GetAwaiter().GetResult()
            $ready = $response.IsSuccessStatusCode
            $response.Dispose()
        } catch [System.Net.Http.HttpRequestException] {
            $ready = $false
        } catch [System.Threading.Tasks.TaskCanceledException] {
            $ready = $false
        }
        if ($ready) { break }
        Start-Sleep -Milliseconds 100
    }
    if (-not $ready) { throw 'The Java preview did not become responsive within 45 seconds.' }
    $startup.Stop()
    $jvmProcess.Refresh()
    $baseline = $jvmProcess.WorkingSet64
    $durations = [System.Collections.Generic.List[double]]::new()
    $failures = 0
    $paths = @('/', '/api/catalog', '/app.js', '/styles.css')
    for ($batch = 0; $batch -lt 20; $batch++) {
        $pending = @()
        for ($index = 0; $index -lt 10; $index++) {
            $pending += [pscustomobject]@{
                Started = [System.Diagnostics.Stopwatch]::GetTimestamp()
                Request = $client.GetAsync($origin + $paths[(($batch * 10 + $index) % $paths.Length)])
            }
        }
        while (@($pending | Where-Object { -not $_.Request.IsCompleted }).Count -gt 0) {
            $jvmProcess.Refresh()
            $peak = [Math]::Max($peak, $jvmProcess.WorkingSet64)
            Start-Sleep -Milliseconds 20
        }
        foreach ($item in $pending) {
            $response = $item.Request.GetAwaiter().GetResult()
            if ([int]$response.StatusCode -ne 200) { $failures++ }
            $durations.Add(1000 * ([System.Diagnostics.Stopwatch]::GetTimestamp() - $item.Started) / [System.Diagnostics.Stopwatch]::Frequency)
            $response.Dispose()
        }
    }
    $jvmProcess.Refresh()
    $peak = [Math]::Max($peak, $jvmProcess.WorkingSet64)
    $bundleBytes = 0
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $root 'public') -File | Where-Object { $_.Extension -in @('.js', '.css', '.svg') }) {
        $compressed = [System.IO.MemoryStream]::new()
        $gzip = [System.IO.Compression.GZipStream]::new($compressed, [System.IO.Compression.CompressionLevel]::Optimal, $true)
        try { $bytes = [System.IO.File]::ReadAllBytes($file.FullName); $gzip.Write($bytes, 0, $bytes.Length) } finally { $gzip.Dispose() }
        $bundleBytes += $compressed.Length
        $compressed.Dispose()
    }
    if ($failures -ne 0) { throw "$failures HTTP requests failed." }
    if ($bundleBytes -ge 100000) { throw "Browser gzip budget exceeded: $bundleBytes bytes." }
    if ($peak -ge 512MB) { throw "Sampled Java preview RSS exceeded 512 MiB: $peak bytes." }
    $durations.Sort()
    [pscustomobject]@{
        scope = 'Java/Spring Boot local preview; 160 MiB heap; no database, OAuth or provider load'
        requests = $durations.Count
        concurrency = 10
        failures = $failures
        p95Ms = [Math]::Round($durations[[Math]::Floor($durations.Count * 0.95)])
        startupMs = [Math]::Round($startup.Elapsed.TotalMilliseconds)
        browserAssetsGzipBytes = $bundleBytes
        baselineWorkingSetBytes = $baseline
        peakSampledWorkingSetBytes = $peak
    } | ConvertTo-Json
} finally {
    $client.Dispose()
    if ($jvmProcess) {
        $jvmProcess.Refresh()
        if (-not $jvmProcess.HasExited) { Stop-Process -Id $jvmProcess.Id -Force; $null = $jvmProcess.WaitForExit(5000) }
        $jvmProcess.Dispose()
    }
    foreach ($file in @($outLog, $errLog)) {
        if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force }
    }
}

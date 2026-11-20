#Requires -Version 7.0
param([string]$Serial = 'emulator-5560')

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$adb = (Get-Command adb -CommandType Application).Source
$deviceArgs = @('-s', $Serial)
$debugPackage = 'com.simplechat.app.debug'
$credentialPath = 'cache/deepseek-live-key'
$stage = $null
Push-Location $repo
try {
    $key = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'Process')
    if (-not $key) { $key = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User') }
    if (-not $key -or $key.Trim() -notmatch '^[\x21-\x7e]+$') {
        throw 'Set DEEPSEEK_API_KEY in the current process or Windows User environment.'
    }
    if (-not $env:JAVA_HOME) {
        $studioJbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
        if (Test-Path -LiteralPath $studioJbr) { $env:JAVA_HOME = $studioJbr }
    }
    & $adb @deviceArgs get-state | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Android device '$Serial' is not ready." }

    & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }

    foreach ($variant in 'debug', 'androidTest/debug') {
        $apkDir = Join-Path $repo "app/build/outputs/apk/$variant"
        $metadata = Get-Content -LiteralPath (Join-Path $apkDir 'output-metadata.json') -Raw | ConvertFrom-Json
        if ($metadata.elements.Count -ne 1) { throw "Expected one APK for $variant." }
        $apk = Join-Path $apkDir $metadata.elements[0].outputFile
        & $adb @deviceArgs install -r -t $apk
        if ($LASTEXITCODE -ne 0) { throw "APK install failed for $variant." }
    }

    $start = [Diagnostics.ProcessStartInfo]::new($adb)
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $true
    foreach ($argument in ($deviceArgs + @('shell', '-T',
        "run-as $debugPackage sh -c `"umask 077; cat > $credentialPath`""))) {
        [void]$start.ArgumentList.Add($argument)
    }
    $stage = [Diagnostics.Process]::Start($start)
    $bytes = [Text.Encoding]::ASCII.GetBytes($key.Trim())
    try {
        $stage.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
    } finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
        $key = $null
        $stage.StandardInput.BaseStream.Close()
    }
    $stage.WaitForExit()
    if ($stage.ExitCode -ne 0) { throw 'Could not stage the credential in the app-private cache.' }

    Write-Host 'Running six paid long-context requests against api.deepseek.com.'
    $passed = $false
    & $adb @deviceArgs shell am instrument -w -r -e deepSeekLive true `
        "$debugPackage.test/com.simplechat.app.DeviceRegression" | ForEach-Object {
        Write-Output $_
        if ($_.Trim() -eq 'ALL DEVICE REGRESSIONS PASSED') { $passed = $true }
    }
    if ($LASTEXITCODE -ne 0 -or -not $passed) { throw 'DeepSeek live regression failed.' }
} finally {
    if ($null -ne $stage) {
        try {
            & $adb @deviceArgs shell run-as $debugPackage rm -f $credentialPath
            if ($LASTEXITCODE -ne 0) { Write-Warning 'Could not confirm credential cleanup on the device.' }
        } finally { $stage.Dispose() }
    }
    $key = $null
    Pop-Location
}

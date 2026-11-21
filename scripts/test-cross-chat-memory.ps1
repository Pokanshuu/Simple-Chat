#Requires -Version 7.0
<#
.SYNOPSIS
Runs the cross-chat memory stress test on a running Android emulator with a 256 MiB app heap.
.DESCRIPTION
Builds and installs the debug and instrumentation APKs. The regression uses synthetic
history and local mock endpoints. The script checks the heap class reported by the
instrumented app and fails if it is not 256 MiB. Configure the heap before running it.
.EXAMPLE
.\scripts\test-cross-chat-memory.ps1 -Serial emulator-5564
#>
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^emulator-[0-9]+$')]
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$adb = (Get-Command adb -CommandType Application).Source
$deviceArgs = @('-s', $Serial)
$previousJavaHome = $env:JAVA_HOME

Push-Location $repo
try {
    $state = & $adb @deviceArgs get-state
    if ($LASTEXITCODE -ne 0 -or $state -ne 'device') {
        throw "Emulator '$Serial' is not ready."
    }
    if (-not $env:JAVA_HOME) {
        $studioJbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
        if (Test-Path -LiteralPath $studioJbr) { $env:JAVA_HOME = $studioJbr }
    }

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

    $passed = $false
    $heapVerified = $false
    & $adb @deviceArgs shell am instrument -w -e crossChatMemory true `
        'com.simplechat.app.debug.test/com.simplechat.app.DeviceRegression' | ForEach-Object {
        Write-Output $_
        if ($_.Trim() -eq 'ALL DEVICE REGRESSIONS PASSED') { $passed = $true }
        if ($_ -match '^PASS .*\bheap=256MiB\b') { $heapVerified = $true }
    }
    if ($LASTEXITCODE -ne 0 -or -not $passed) { throw 'Cross-chat memory regression failed.' }
    if (-not $heapVerified) { throw 'The app did not report a 256 MiB heap. Check the emulator configuration.' }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    Pop-Location
}

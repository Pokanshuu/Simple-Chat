# 收藏夹 / 分享截图验收脚本：JVM 单测 + Debug/Release 构建 + 测试结果汇总。
# 用法：pwsh -File scripts/verify-favorites-share.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    & .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --console=plain --offline
    if ($LASTEXITCODE -ne 0) { throw "Gradle verification failed with exit code $LASTEXITCODE" }

    $results = Join-Path $root 'app\build\test-results\testDebugUnitTest'
    $tests = 0; $failures = 0; $skipped = 0
    if (Test-Path $results) {
        Get-ChildItem $results -Filter 'TEST-*.xml' | ForEach-Object {
            [xml]$suite = Get-Content $_.FullName
            $tests += [int]$suite.testsuite.tests
            $failures += [int]$suite.testsuite.failures + [int]$suite.testsuite.errors
            $skipped += [int]$suite.testsuite.skipped
        }
    }
    "tests=$tests failures=$failures skipped=$skipped"
    if ($failures -ne 0) { throw "Unit test failures: $failures" }
    "verification=ok"
} finally {
    Pop-Location
}

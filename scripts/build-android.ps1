param([string[]]$Tasks = @(':app:assembleDebug', ':app:testDebugUnitTest', ':device:testDebugUnitTest', ':runtime:testDebugUnitTest'), [switch]$SkipRuntime, [switch]$SkipChat)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$androidDir = Join-Path $repo 'android'
$tooling = Join-Path $androidDir '.tooling'
$gradleVersion = '8.13'
$gradleHome = Join-Path $tooling "gradle-$gradleVersion"
if (!(Test-Path (Join-Path $gradleHome 'bin/gradle.bat'))) {
    New-Item -ItemType Directory -Force $tooling | Out-Null
    $zip = Join-Path $tooling "gradle-$gradleVersion-bin.zip"
    $url = "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip"
    $expected = '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
    if (!(Test-Path $zip)) { Invoke-WebRequest $url -OutFile $zip }
    if ((Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
        throw 'Gradle archive checksum mismatch'
    }
    Expand-Archive -LiteralPath $zip -DestinationPath $tooling -Force
}
if (!$env:JAVA_HOME) {
    $jdk = Get-ChildItem 'C:/Program Files/Eclipse Adoptium' -Directory -Filter 'jdk-17*' | Select-Object -First 1
    if (!$jdk) { throw 'Set JAVA_HOME to a JDK 17 installation' }
    $env:JAVA_HOME = $jdk.FullName
}
if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (!$SkipChat) {
    $chatDir = Join-Path $androidDir 'chat-ui'
    & npm --prefix $chatDir ci --ignore-scripts --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'Chat dependency installation failed' }
    & npm --prefix $chatDir run build
    if ($LASTEXITCODE -ne 0) { throw 'Chat asset build failed' }
}
if (!$SkipRuntime) {
    & node (Join-Path $repo 'scripts/android-runtime/prepare.mjs')
    if ($LASTEXITCODE -ne 0) { throw 'Runtime asset preparation failed' }
}
& (Join-Path $gradleHome 'bin/gradle.bat') -p $androidDir @Tasks --console=plain
if ($LASTEXITCODE -ne 0) { throw "Android build failed ($LASTEXITCODE)" }

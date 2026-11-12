[CmdletBinding()]
param(
    [switch]$BuildOnly,
    [switch]$Devices
)

$ErrorActionPreference = 'Continue'
$script:Failures = 0
$script:Warnings = 0
$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectDir

function Write-Check {
    param([ValidateSet('OK', 'WARN', 'FAIL')][string]$State, [string]$Message)
    $color = switch ($State) {
        'OK' { 'Green' }
        'WARN' { 'Yellow' }
        default { 'Red' }
    }
    Write-Host ('[{0}] {1}' -f $State, $Message) -ForegroundColor $color
    if ($State -eq 'FAIL') { $script:Failures++ }
    if ($State -eq 'WARN') { $script:Warnings++ }
}

function Normalize-PathText {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    try { return [IO.Path]::GetFullPath($Value.Trim().Trim('"')).TrimEnd([char]92, [char]47) }
    catch { return $Value.Trim().Trim('"').TrimEnd([char]92, [char]47) }
}

Write-Host 'TransferKit Android environment preflight' -ForegroundColor Cyan
Write-Host ('Project: {0}' -f $projectDir)

# Gradle's local.properties is the SDK source used by this checkout. Compare it with env vars
# instead of silently choosing one of several different SDK installations.
$localSdk = $null
$localProperties = Join-Path $projectDir 'local.properties'
if (Test-Path -LiteralPath $localProperties) {
    $sdkLine = Get-Content -LiteralPath $localProperties -ErrorAction SilentlyContinue |
        Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
        Select-Object -First 1
    if ($sdkLine -match '^\s*sdk\.dir\s*=(.*)$') {
        $localSdk = $Matches[1].Trim().Replace('\\', '\').Replace('\:', ':')
    }
}

$sdkValues = @{}
if ($env:ANDROID_HOME) { $sdkValues['ANDROID_HOME'] = Normalize-PathText $env:ANDROID_HOME }
if ($env:ANDROID_SDK_ROOT) { $sdkValues['ANDROID_SDK_ROOT'] = Normalize-PathText $env:ANDROID_SDK_ROOT }
if ($localSdk) { $sdkValues['local.properties sdk.dir'] = Normalize-PathText $localSdk }
$sdkPath = $null
if ($localSdk) { $sdkPath = Normalize-PathText $localSdk }
elseif ($env:ANDROID_HOME) { $sdkPath = Normalize-PathText $env:ANDROID_HOME }
elseif ($env:ANDROID_SDK_ROOT) { $sdkPath = Normalize-PathText $env:ANDROID_SDK_ROOT }
elseif ($env:LOCALAPPDATA) { $sdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

$uniqueSdkPaths = @($sdkValues.Values | Where-Object { $_ } | Sort-Object -Unique)
if ($uniqueSdkPaths.Count -gt 1) {
    $sdkDetails = ($sdkValues.GetEnumerator() | ForEach-Object { '{0}={1}' -f $_.Key, $_.Value }) -join '; '
    Write-Check 'FAIL' ('SDK paths disagree: {0}. Set ANDROID_HOME, ANDROID_SDK_ROOT and local.properties sdk.dir to one installation.' -f $sdkDetails)
} elseif ($sdkPath -and (Test-Path -LiteralPath $sdkPath -PathType Container)) {
    Write-Check 'OK' ('Android SDK: {0}' -f $sdkPath)
} else {
    Write-Check 'FAIL' 'Android SDK directory was not found. Set sdk.dir in local.properties or ANDROID_HOME.'
}

if ($sdkPath -and (Test-Path -LiteralPath $sdkPath -PathType Container)) {
    $androidJar = Join-Path $sdkPath 'platforms\android-34\android.jar'
    $aapt = Join-Path $sdkPath 'build-tools\34.0.0\aapt.exe'
    if (Test-Path -LiteralPath $androidJar) { Write-Check 'OK' 'Android API 34 platform is installed.' }
    else { Write-Check 'FAIL' 'Android API 34 is missing. Install Android SDK Platform 34 in SDK Manager.' }
    if (Test-Path -LiteralPath $aapt) { Write-Check 'OK' 'Android Build Tools 34.0.0 are installed.' }
    else { Write-Check 'FAIL' 'Android Build Tools 34.0.0 are missing. Install them in SDK Manager.' }

    $adbPath = Join-Path $sdkPath 'platform-tools\adb.exe'
    if (Test-Path -LiteralPath $adbPath) {
        Write-Check 'OK' ('SDK ADB client: {0}' -f $adbPath)
        $pathAdb = @(& where.exe adb.exe 2>$null)
        $otherAdb = @($pathAdb | Where-Object { (Normalize-PathText $_) -ne (Normalize-PathText $adbPath) })
        if ($otherAdb.Count -gt 0) {
            Write-Check 'WARN' ('Other ADB clients are earlier/also present on PATH: {0}. Use the SDK ADB consistently; mixed client versions restart the shared server.' -f ($otherAdb -join ', '))
        } elseif ($pathAdb.Count -eq 0) {
            Write-Check 'OK' 'No competing ADB executable is on PATH; scripts can use the SDK copy directly.'
        } else {
            Write-Check 'OK' 'PATH ADB resolves to the selected SDK client.'
        }
        if ($Devices) {
            Write-Host 'ADB device state (this option starts the ADB server if needed):'
            $deviceOutput = @(& $adbPath devices -l 2>&1)
            $deviceOutput | ForEach-Object { Write-Host $_ }
            if ($deviceOutput -match 'server version .* does not match this client') {
                Write-Check 'WARN' 'ADB client/server versions differ; close other ADB clients and restart with this SDK executable only.'
            }
            if ($deviceOutput -match '\bunauthorized\b') { Write-Check 'WARN' 'A phone is waiting for its on-device RSA authorization prompt.' }
            if ($deviceOutput -match '\boffline\b') { Write-Check 'WARN' 'ADB lists an offline device; reconnect/unlock it and retry with the same ADB client.' }
            $onlineRows = @($deviceOutput | Where-Object { $_ -match '^\S+\s+device(?:\s|$)' })
            if ($onlineRows.Count -gt 1) { Write-Check 'WARN' 'Multiple devices are online; every install/test command must specify -s SERIAL.' }
            elseif ($onlineRows.Count -eq 0) { Write-Check 'WARN' 'No authorized online device is available; device tests/install need a phone or emulator.' }
        }
    } else {
        if ($BuildOnly) { Write-Check 'WARN' 'SDK platform-tools/adb.exe is missing; APK compilation can proceed, but phone operations cannot.' }
        else { Write-Check 'FAIL' 'SDK platform-tools/adb.exe is missing. Install Android SDK Platform-Tools.' }
    }
}

$javaPath = $null
if ($env:JAVA_HOME) {
    $javaHomeCandidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path -LiteralPath $javaHomeCandidate) { $javaPath = $javaHomeCandidate }
}
if (-not $javaPath) {
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) { $javaPath = $javaCommand.Source }
}
if ($javaPath) {
    $javaVersionOutput = (& $javaPath -version 2>&1 | Out-String)
    if ($javaVersionOutput -match 'version\s+"?(\d+)') {
        $javaMajor = [int]$Matches[1]
        $javacPath = Join-Path (Split-Path -Parent (Split-Path -Parent $javaPath)) 'bin\javac.exe'
        if ($javaMajor -eq 17 -and (Test-Path -LiteralPath $javacPath)) { Write-Check 'OK' ('JDK 17 selected: {0}' -f $javaPath) }
        elseif ($javaMajor -eq 17) { Write-Check 'FAIL' ('Java 17 runtime was found without javac.exe; install a JDK and set JAVA_HOME. Source was {0}.' -f $javaPath) }
        else { Write-Check 'FAIL' ('JDK {0} selected. This project is pinned to Java 17; set JAVA_HOME to JDK 17. Source was {1}.' -f $javaMajor, $javaPath) }
    } else {
        Write-Check 'FAIL' ('Could not parse java -version output from {0}.' -f $javaPath)
    }
} else {
    Write-Check 'FAIL' 'Java was not found. Install JDK 17 and set JAVA_HOME.'
}

$wrapper = Join-Path $projectDir 'gradlew.bat'
$wrapperJar = Join-Path $projectDir 'gradle\wrapper\gradle-wrapper.jar'
$wrapperProperties = Join-Path $projectDir 'gradle\wrapper\gradle-wrapper.properties'
if ((Test-Path -LiteralPath $wrapper) -and (Test-Path -LiteralPath $wrapperJar) -and (Test-Path -LiteralPath $wrapperProperties)) {
    $wrapperText = Get-Content -LiteralPath $wrapperProperties -Raw
    if ($wrapperText -match 'gradle-8\.7-') { Write-Check 'OK' 'Gradle Wrapper 8.7 files are present; use gradlew.bat, not system Gradle.' }
    else { Write-Check 'WARN' 'Wrapper distribution differs from the documented Gradle 8.7 baseline; review AGP compatibility before changing it.' }
} else {
    Write-Check 'FAIL' 'Gradle Wrapper files are incomplete. Restore gradlew.bat and gradle/wrapper files from the project source.'
}

if ($BuildOnly) {
    Write-Host 'BuildOnly mode: no ADB device command, Gradle task, APK install, or test was run.' -ForegroundColor DarkGray
}
if ($script:Failures -gt 0) {
    Write-Host ('Preflight found {0} blocking issue(s) and {1} warning(s).' -f $script:Failures, $script:Warnings) -ForegroundColor Red
    exit 1
}
Write-Host ('Preflight found no blocking setup issue(s); warnings: {0}. No build or tests were run by this script.' -f $script:Warnings) -ForegroundColor Green
exit 0

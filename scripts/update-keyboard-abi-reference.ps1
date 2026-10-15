# Generate structural witnesses from a verified APK; never installs or starts the IME.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$TargetApk,
    [string]$DeviceSerial,
    [string]$ModuleApk,
    [string]$OutputFile
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (!$ModuleApk) { $ModuleApk = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk' }
if (!$OutputFile) { $OutputFile = Join-Path $projectRoot 'app/src/main/resources/xatype/keyboard-abi.json' }
$TargetApk = (Resolve-Path -LiteralPath $TargetApk).Path
$ModuleApk = (Resolve-Path -LiteralPath $ModuleApk).Path
$sdkLine = Get-Content (Join-Path $projectRoot 'local.properties') | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
$sdkRoot = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
$androidJar = Join-Path $sdkRoot 'platforms/android-35/android.jar'
$d8 = Join-Path $sdkRoot 'build-tools/35.0.0/d8.bat'
$gradleRoot = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$dexkit = Get-ChildItem (Join-Path $gradleRoot 'caches/8.13/transforms') -Recurse -Filter 'dexkit-2.3.0-runtime.jar' | Select-Object -First 1 -ExpandProperty FullName
$stdlib = Get-ChildItem (Join-Path $gradleRoot 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/1.9.20') -Recurse -Filter 'kotlin-stdlib-1.9.20.jar' | Select-Object -First 1 -ExpandProperty FullName
if (!$DeviceSerial) {
    $devices = @(adb devices | Where-Object { $_ -match '^\S+\s+device$' })
    if ($devices.Count -ne 1) { throw 'Connect one authorized Android device or pass -DeviceSerial.' }
    $DeviceSerial = ($devices[0] -split '\s+')[0]
}
if ($DeviceSerial -notmatch '^[A-Za-z0-9_.:-]+$') { throw 'Invalid device serial.' }
$taskId = [Guid]::NewGuid().ToString('N')
$workDir = Join-Path $projectRoot "work/keyboard-abi-reference-$taskId"
$remoteDir = "/data/local/tmp/xatype-abi-$taskId"
New-Item -ItemType Directory -Force "$workDir/classes", "$workDir/dex", "$workDir/lib" | Out-Null
python (Join-Path $PSScriptRoot 'compat/keyboard-abi-manifest.py') "$workDir/manifest.json"
if ($LASTEXITCODE -ne 0) { throw 'Manifest generation failed.' }
function Invoke-Adb {
    $ErrorActionPreference = 'Continue'
    $lines = @(& adb -s $DeviceSerial @args 2>&1 | ForEach-Object { "$_" })
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $args`n$($lines -join "`n")" }
    $lines
}
$classpath = @($androidJar,$dexkit,$stdlib) -join ';'
javac --release 8 -encoding UTF-8 -classpath $classpath -d "$workDir/classes" (Join-Path $PSScriptRoot 'compat/KeyboardAbiReferenceProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Reference probe compilation failed.' }
& $d8 --lib $androidJar --classpath $dexkit --classpath $stdlib --min-api 26 --output "$workDir/dex" "$workDir/classes/KeyboardAbiReferenceProbe.class"
if ($LASTEXITCODE -ne 0) { throw 'Reference probe DEX compilation failed.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ModuleApk).Path)
try {
    foreach ($entry in $zip.Entries | Where-Object { $_.FullName -like 'lib/arm64-v8a/*.so' }) {
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path "$workDir/lib" $entry.Name), $true)
    }
} finally { $zip.Dispose() }
Invoke-Adb shell mkdir -p "$remoteDir/lib" "$remoteDir/work" | Out-Null
Invoke-Adb push "$workDir/dex/classes.dex" "$remoteDir/probe.dex" | Out-Null
Invoke-Adb push $ModuleApk "$remoteDir/module.apk" | Out-Null
Invoke-Adb push $TargetApk "$remoteDir/target.apk" | Out-Null
Invoke-Adb push "$workDir/manifest.json" "$remoteDir/manifest.json" | Out-Null
Get-ChildItem "$workDir/lib" | ForEach-Object { Invoke-Adb push $_.FullName "$remoteDir/lib/$($_.Name)" | Out-Null }
Invoke-Adb shell chmod 444 "$remoteDir/probe.dex" "$remoteDir/module.apk" "$remoteDir/target.apk" | Out-Null
$command = "CLASSPATH=$remoteDir/probe.dex:$remoteDir/module.apk app_process /system/bin KeyboardAbiReferenceProbe " +
    "$remoteDir/lib $remoteDir/target.apk $remoteDir/work $remoteDir/manifest.json $remoteDir/reference.json"
Invoke-Adb shell su -c "'$command'" | Tee-Object -FilePath "$workDir/result.txt"
Invoke-Adb shell su -c "'chmod 644 $remoteDir/reference.json'" | Out-Null
New-Item -ItemType Directory -Force (Split-Path $OutputFile -Parent) | Out-Null
Invoke-Adb pull "$remoteDir/reference.json" $OutputFile
Write-Output "Reference: $OutputFile"
Write-Output "Evidence: $workDir"

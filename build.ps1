param(
    [Parameter(Mandatory)] [string] $JdkBin,
    [Parameter(Mandatory)] [string] $AndroidJar,
    [Parameter(Mandatory)] [string] $BuildTools,
    [string] $Keystore,
    [string] $Alias = 'codex-cover'
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$JdkBin = (Resolve-Path -LiteralPath $JdkBin).Path
$AndroidJar = (Resolve-Path -LiteralPath $AndroidJar).Path
$BuildTools = (Resolve-Path -LiteralPath $BuildTools).Path
if ($Keystore) { $Keystore = (Resolve-Path -LiteralPath $Keystore).Path }

$out = Join-Path $PSScriptRoot 'out'
$classes = Join-Path $out 'classes'
$dex = Join-Path $out 'dex'
New-Item -ItemType Directory -Force -Path $classes, $dex | Out-Null
$source = Join-Path $PSScriptRoot 'android'
$unsigned = Join-Path $out 'unsigned.apk'
$aligned = Join-Path $out 'codex-cover-unsigned.apk'
$signed = Join-Path $out 'codex-cover.apk'

& "$JdkBin/javac.exe" -encoding UTF-8 --release 8 -cp $AndroidJar -d $classes "$source/src/local/codex/cover/MainActivity.java"
& "$JdkBin/java.exe" -cp "$BuildTools/lib/d8.jar" com.android.tools.r8.D8 --lib $AndroidJar --min-api 34 --output $dex (Get-ChildItem $classes -Recurse -Filter '*.class').FullName
& "$BuildTools/aapt.exe" package -f -M "$source/AndroidManifest.xml" -S "$source/res" -I $AndroidJar -F $unsigned
Push-Location $dex
try { & "$BuildTools/aapt.exe" add $unsigned classes.dex }
finally { Pop-Location }
& "$BuildTools/zipalign.exe" -f 4 $unsigned $aligned
if ($Keystore) {
    & "$JdkBin/java.exe" -jar "$BuildTools/lib/apksigner.jar" sign --ks $Keystore --ks-key-alias $Alias --out $signed $aligned
    & "$JdkBin/java.exe" -jar "$BuildTools/lib/apksigner.jar" verify $signed
    Write-Output $signed
} else {
    Write-Output $aligned
}

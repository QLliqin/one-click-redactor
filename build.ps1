$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$source = Join-Path $root "Program.cs"
$lib = Join-Path $root "lib"
$npoi = Join-Path $lib "NPOI.dll"
$hwpf = Join-Path $lib "NPOI.ScratchPad.HWPF.dll"
$sharpZip = Join-Path $lib "ICSharpCode.SharpZipLib.dll"
$icon = Join-Path $root "assets\app.ico"
$outputName = (-join @([char]0x4E00, [char]0x952E, [char]0x8131, [char]0x654F, [char]0x5DE5, [char]0x5177)) + ".exe"
$output = Join-Path $root $outputName
$csc = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"

if (-not (Test-Path $csc)) {
    $csc = Join-Path $env:WINDIR "Microsoft.NET\Framework\v4.0.30319\csc.exe"
}

if (-not (Test-Path $csc)) {
    throw "csc.exe was not found."
}

foreach ($dependency in @($npoi, $hwpf, $sharpZip)) {
    if (-not (Test-Path $dependency)) {
        throw "Build dependency was not found: $dependency"
    }
}

if (-not (Test-Path $icon)) {
    throw "Application icon was not found: $icon"
}

& $csc `
    /nologo `
    /target:winexe `
    /platform:anycpu `
    /optimize+ `
    /codepage:65001 `
    /win32icon:$icon `
    /out:$output `
    /reference:System.dll `
    /reference:System.Core.dll `
    /reference:System.Drawing.dll `
    /reference:System.Windows.Forms.dll `
    /reference:System.Xml.dll `
    /reference:System.Xml.Linq.dll `
    /reference:System.IO.Compression.dll `
    /reference:System.IO.Compression.FileSystem.dll `
    /reference:$npoi `
    /reference:$hwpf `
    /reference:$sharpZip `
    /resource:$npoi,Embedded.NPOI.dll `
    /resource:$hwpf,Embedded.NPOI.ScratchPad.HWPF.dll `
    /resource:$sharpZip,Embedded.ICSharpCode.SharpZipLib.dll `
    $source

if ($LASTEXITCODE -ne 0) {
    throw "Build failed with exit code $LASTEXITCODE."
}

Write-Host "Built: $output"

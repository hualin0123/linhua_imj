$root    = Split-Path -Parent $MyInvocation.MyCommand.Path
$ij      = 'C:\Users\hual\ij154-win-java8\ImageJ\ij.jar'
$srcDir  = "$root\src\main\java"
$resDir  = "$root\src\main\resources"
$out     = "$root\target\build"
$d       = (Get-Date -Format yyMMdd)
$jar     = "$root\target\LinHua_V$d.jar"
$plugins = 'C:\Users\hual\ij154-win-java8\ImageJ\plugins'
$javac   = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\javac.exe'
$jarTool = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\jar.exe'

$cfg = Join-Path $resDir 'plugins.config'
if (Test-Path $cfg) {
    $bad = @()
    $ln  = 0
    foreach ($line in (Get-Content $cfg)) {
        $ln++
        if ($line -match '\s>|>\s') {
            $bad += "  line $ln : $line"
        }
    }
    if ($bad.Count -gt 0) {
        Write-Host 'ERROR: plugins.config ">" delimiter must NOT have spaces around it, otherwise ImageJ will NOT create the submenu'
        $bad | ForEach-Object { Write-Host $_ }
        Write-Host 'Use no-space form, e.g.  Plugins>LinHua   or   Plugins>LinHua><tool>'
        exit 1
    }
    Write-Host 'OK: plugins.config delimiter check passed'
} else {
    Write-Host 'WARN: plugins.config not found, skip format check'
}

cmd.exe /c "rmdir /s /q $out"
New-Item -ItemType Directory -Force -Path $out | Out-Null

$srcFiles = (Get-ChildItem $srcDir -Filter *.java -Recurse).FullName
& $javac -encoding UTF-8 --release 8 -cp $ij -d $out $srcFiles
if ($LASTEXITCODE -ne 0) {
    Write-Host 'JAVAC_FAIL'
    exit 1
}

if (Test-Path $resDir) {
    Copy-Item "$resDir\*" $out -Recurse -Force
}

& $jarTool cf $jar -C $out .
Copy-Item $jar $plugins -Force
Write-Host "Deployed: $jar -> $plugins"

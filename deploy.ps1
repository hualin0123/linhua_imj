$root = Split-Path -Parent $PSCommandPath
if (-not $root) { $root = $PSScriptRoot }
if (-not $root) { $root = (Get-Location).Path }
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

# Clean build dir (use PowerShell native cmdlet, not cmd.exe /c rmdir)
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

# Build the source list and write it to a javac @file.
# Two non-obvious gotchas when running via "powershell -File deploy.ps1":
#  (a) passing a PowerShell array of source paths directly to javac drops the args,
#      so we feed the list through javac's @file mechanism instead;
#  (b) javac's stdout/stderr must be drained through a pipeline, otherwise in the
#      non-interactive -File context javac exits 0 but produces NO .class files
#      (which previously produced an empty jar -> ClassNotFoundException in ImageJ).
$srcFiles = (Get-ChildItem $srcDir -Filter *.java -Recurse).FullName
if ($srcFiles -eq $null -or $srcFiles.Count -eq 0) {
    Write-Host "ERROR: no .java sources found under $srcDir"
    exit 1
}
$srcList = Join-Path $root 'target\sources.txt'
[System.IO.File]::WriteAllLines($srcList, $srcFiles)
# Force a read-back so the @file is guaranteed flushed to disk before javac reads it
[System.IO.File]::ReadAllLines($srcList) | Out-Null

# --release 8 is correct for targeting ImageJ's Java 8 runtime.
# Newer JDKs emit a deprecation warning for target 8; -Xlint:-options silences that
# advisory so it cannot be turned into a hard error later.
$javacOut = & $javac -encoding UTF-8 -Xlint:-options --release 8 -cp $ij -d $out "@$srcList" 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    Write-Host 'JAVAC_FAIL'
    Write-Host $javacOut
    exit 1
}
# Defensive: ensure classes were actually produced, otherwise the jar would package an
# empty directory and ImageJ would throw ClassNotFoundException.
$classCount = @(Get-ChildItem $out -Recurse -Filter *.class).Count
if ($classCount -eq 0) {
    Write-Host "ERROR: javac produced no .class files (count=0), abort packaging"
    exit 1
}
Remove-Item $srcList -ErrorAction SilentlyContinue

if (Test-Path $resDir) {
    Copy-Item "$resDir\*" $out -Recurse -Force
}

& $jarTool cf $jar -C $out .
Copy-Item $jar $plugins -Force
Write-Host "Deployed: $jar -> $plugins (classes=$classCount)"

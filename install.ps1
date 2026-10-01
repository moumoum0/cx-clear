# Cx Clear CLI 安装。默认会询问是否下载 Java；CXCLEAR_SILENT=1 时缺 Java 21 直接下载。
$ErrorActionPreference = 'Stop'
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
} catch {}

$Repo = 'moumoum0/cx-clear'
$InstallDir = Join-Path $env:LOCALAPPDATA 'cxclear'
$UserAgent = 'cxclear-install'

function Get-JavaMajor([string]$JavaExe) {
    if (-not (Test-Path -LiteralPath $JavaExe)) { return 0 }
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $line = (& $JavaExe -version 2>&1 | Select-Object -First 1 | Out-String)
    } finally {
        $ErrorActionPreference = $prev
    }
    if ($line -match '"(\d+)(?:\.\d+)*"') { return [int]$Matches[1] }
    return 0
}

function Test-UsableJava([string]$Home) {
    $bundled = Join-Path $Home 'runtime\bin\java.exe'
    $fromHome = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { $null }
    foreach ($exe in @($bundled, $fromHome)) {
        if ($exe -and (Get-JavaMajor $exe) -eq 21) { return $true }
    }
    return $false
}

function Add-UserPath([string]$Dir) {
    $current = [Environment]::GetEnvironmentVariable('Path', 'User')
    $parts = @()
    if ($current) {
        $parts = $current.Split(';') | Where-Object { $_ -ne '' }
    }
    $already = $false
    foreach ($part in $parts) {
        if ($part.TrimEnd('\') -ieq $Dir.TrimEnd('\')) { $already = $true }
    }
    if (-not $already) {
        $joined = (@($parts) + $Dir) -join ';'
        [Environment]::SetEnvironmentVariable('Path', $joined, 'User')
    }
    if (($env:PATH -split ';' | ForEach-Object { $_.TrimEnd('\') }) -notcontains $Dir.TrimEnd('\')) {
        $env:PATH = $env:PATH.TrimEnd(';') + ';' + $Dir
    }
    Add-Type -Namespace CxClear -Name PathNotify -MemberDefinition @'
[DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
public static extern IntPtr SendMessageTimeout(
    IntPtr hWnd, uint Msg, UIntPtr wParam, string lParam,
    uint fuFlags, uint uTimeout, out UIntPtr lpdwResult);
'@ -ErrorAction SilentlyContinue
    $result = [UIntPtr]::Zero
    [CxClear.PathNotify]::SendMessageTimeout(
        [IntPtr]0xffff, 0x001A, [UIntPtr]::Zero, 'Environment', 2, 5000, [ref]$result) | Out-Null
}

function Expand-Zip([string]$Zip, [string]$Dest) {
    if (Test-Path -LiteralPath $Dest) {
        Remove-Item -LiteralPath $Dest -Recurse -Force
    }
    New-Item -ItemType Directory -Path $Dest | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($Zip, $Dest)
}

$silent = $env:CXCLEAR_SILENT -eq '1'
$headers = @{ 'User-Agent' = $UserAgent; Accept = 'application/vnd.github+json' }
$release = Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/$Repo/releases/latest"
$asset = @($release.assets) | Where-Object { $_.name -like '*-cli.zip' } | Select-Object -First 1
if (-not $asset) { throw "最新 Release 里没有 CLI 包" }

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("cxclear-install-" + [guid]::NewGuid().ToString('n'))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
try {
    $zip = Join-Path $tempRoot 'cli.zip'
    Invoke-WebRequest -Headers @{ 'User-Agent' = $UserAgent } -Uri $asset.browser_download_url -OutFile $zip
    $extracted = Join-Path $tempRoot 'unzipped'
    Expand-Zip $zip $extracted
    if (-not (Test-Path -LiteralPath (Join-Path $extracted 'cxclear.exe'))) {
        throw "CLI 包里没有 cxclear.exe"
    }

    if (Test-Path -LiteralPath $InstallDir) {
        $oldRuntime = Join-Path $InstallDir 'runtime'
        if (Test-Path -LiteralPath $oldRuntime) {
            $keptRuntime = Join-Path $tempRoot 'kept-runtime'
            Move-Item -LiteralPath $oldRuntime -Destination $keptRuntime
        }
        Remove-Item -LiteralPath $InstallDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path (Split-Path $InstallDir) -Force | Out-Null
    Move-Item -LiteralPath $extracted -Destination $InstallDir
    Add-UserPath $InstallDir
    $keptRuntime = Join-Path $tempRoot 'kept-runtime'
    if (Test-Path -LiteralPath $keptRuntime) {
        Move-Item -LiteralPath $keptRuntime -Destination (Join-Path $InstallDir 'runtime')
    }
    Write-Host "已安装到 $InstallDir"
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if (Test-UsableJava $InstallDir) { return }

$arch = switch ($env:PROCESSOR_ARCHITECTURE) {
    'AMD64' { 'x64' }
    'ARM64' { 'aarch64' }
    default { $null }
}
$javaUrl = if ($arch) {
    "https://api.adoptium.net/v3/binary/latest/21/ga/windows/$arch/jre/hotspot/normal/eclipse?project=jdk"
} else { $null }

if (-not $javaUrl) {
    throw "装好了 CLI，但这台机器不是 x64 / arm64，请自己安装 JDK 21 并设置 JAVA_HOME，否则 cxclear 起不来"
}

if (-not $silent) {
    if (-not [Environment]::UserInteractive) {
        throw "没有 Java 21。当前没法询问，请改用：`$env:CXCLEAR_SILENT = '1'"
    }
    Write-Host "没有 Java 21。将下载 Eclipse Temurin 21 JRE："
    Write-Host $javaUrl
    $answer = Read-Host '下载并装到安装目录的 runtime？[y/N]'
    if ($answer -notmatch '^(y|yes)$') {
        throw "未下载 Java。请安装 JDK 21 并设置 JAVA_HOME，否则 cxclear 起不来"
    }
}

$runtime = Join-Path $InstallDir 'runtime'
$javaTemp = Join-Path ([IO.Path]::GetTempPath()) ("cxclear-java-" + [guid]::NewGuid().ToString('n'))
New-Item -ItemType Directory -Path $javaTemp | Out-Null
try {
    $javaZip = Join-Path $javaTemp 'jre.zip'
    Invoke-WebRequest -Headers @{ 'User-Agent' = $UserAgent } -Uri $javaUrl -OutFile $javaZip
    $javaDir = Join-Path $javaTemp 'unzipped'
    Expand-Zip $javaZip $javaDir
    $root = Get-ChildItem -LiteralPath $javaDir -Directory | Select-Object -First 1
    if (-not $root -or -not (Test-Path -LiteralPath (Join-Path $root.FullName 'bin\java.exe'))) {
        throw "Java 压缩包结构不对"
    }
    if (Test-Path -LiteralPath $runtime) {
        Remove-Item -LiteralPath $runtime -Recurse -Force
    }
    Move-Item -LiteralPath $root.FullName -Destination $runtime
} catch {
    throw "Java 21 下载失败，CLI 已装在 $InstallDir，但 cxclear 起不来：$($_.Exception.Message)"
} finally {
    if (Test-Path -LiteralPath $javaTemp) {
        Remove-Item -LiteralPath $javaTemp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ((Get-JavaMajor (Join-Path $runtime 'bin\java.exe')) -ne 21) {
    throw "下载到的不是 Java 21，cxclear 起不来"
}
Write-Host "已安装 Java 21 到 $runtime"

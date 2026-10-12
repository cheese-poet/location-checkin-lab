# 不依赖 Gradle / Android SDK 的自检运行脚本。
#
# 只需要 JDK 17+。用 --release 17 产出字节码，所以本机装的是 JDK 21 也能跑。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/run-selfcheck.ps1
#
# ============================ 重要：本文件必须保存为 UTF-8 with BOM ============================
# Windows PowerShell 5.1 判断脚本编码的方式是「看有没有 BOM」：没有 BOM 就按当前 ANSI 代码页
# （简体中文下是 GBK）解析。对本文件来说，后果不是中文变乱码这么简单——GBK 把 UTF-8 的中文
# 注释解码成乱码后，乱码字节里可能凑出引号或续行，把后面的**代码行吞进注释里**。
# 症状极具迷惑性：脚本「跑完了」、退出码 0、但关键命令一行都没执行，且不报任何错。
# 很多编辑器（含部分自动化改写工具）会在保存时静默去掉 BOM，改完这个文件请确认前三个字节是
# EF BB BF。PowerShell 7 没有这个问题。
# =============================================================================================

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$srcDirs = @(
    (Join-Path $root 'core/src/main/java'),
    (Join-Path $root 'core/src/test/java')
)
$outDir = Join-Path $root 'selfcheck-out'
$reportFile = Join-Path $outDir 'selfcheck-report.txt'

if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

$sources = @(Get-ChildItem -Path $srcDirs -Recurse -Filter *.java | Select-Object -ExpandProperty FullName)
if ($sources.Count -eq 0) { throw 'no java sources found under core/src' }

Write-Host "Compiling $($sources.Count) source files ..." -ForegroundColor Cyan
& javac -encoding UTF-8 --release 17 -d $outDir @sources
if ($LASTEXITCODE -ne 0) { throw 'compile failed' }

Write-Host "Running self-check ...`n" -ForegroundColor Cyan

# 不加 -Dfile.encoding：程序内部已用 PrintStream 强制 UTF-8 输出。
# 捕获 stdout 期间把 ErrorActionPreference 放开，避免原生命令写 stderr 时被当成终止错误。
$ErrorActionPreference = 'Continue'
$lines = @(& java -cp $outDir dev.lab.location.core.RiskScorerSelfCheck 2>&1)
$javaExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'

foreach ($line in $lines) { Write-Host $line }

# 顺便落一份 UTF-8 报告，便于归档或贴进 CI 日志
$lines | Set-Content -LiteralPath $reportFile -Encoding UTF8
Write-Host "报告已保存：$reportFile" -ForegroundColor DarkGray

exit $javaExit

#!/usr/bin/env bash
#
# 零依赖自检脚本（Linux / macOS 版）。
#
# 与 tools/run-selfcheck.ps1 等价，供 CI 使用：GitHub Actions 的 runner 是 ubuntu，
# 跑不了 PowerShell 脚本。
#
# 只需要 JDK 17+。用 --release 17 产出字节码，所以 JDK 21 也能编译。
#
# 用法：
#   bash tools/run-selfcheck.sh
#
# 退出码：0 = 全部通过，1 = 有失败用例，非 0/1 = 编译或环境错误。

set -uo pipefail      # 注意：这里刻意不加 -e
                      # 因为需要自己接管 java 的退出码。若加 -e，java 失败会立刻中止，
                      # 拿不到报告，也分不清「用例失败」和「编译失败」。
                      # 反过来说，如果靠 set -e 兜底，末尾的 echo 会把退出码覆盖成 0，
                      # CI 就会在自检失败时显示绿灯 —— 这是最危险的一类 bug。

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out_dir="$root/selfcheck-out"
report_file="$out_dir/selfcheck-report.txt"

src_dirs=(
  "$root/core/src/main/java"
  "$root/core/src/test/java"
)

rm -rf "$out_dir"
mkdir -p "$out_dir"

# 收集所有 .java 源文件
# 用 -print0 / read -d '' 处理带空格的文件名；
# 用进程替换 < <(...) 而不是管道，否则 while 会在子 shell 里执行，数组收不到内容。
sources=()
while IFS= read -r -d '' f; do
  sources+=("$f")
done < <(find "${src_dirs[@]}" -name '*.java' -print0)

if [ ${#sources[@]} -eq 0 ]; then
  echo "未找到任何 Java 源文件，请检查目录结构。" >&2
  exit 2
fi

echo "编译 ${#sources[@]} 个源文件 ..."
if ! javac -encoding UTF-8 --release 17 -d "$out_dir" "${sources[@]}"; then
  echo "编译失败。" >&2
  exit 2
fi

echo "运行自检 ..."
echo

# 输出同时送终端和报告文件。
# tee 之后必须用 PIPESTATUS[0] 取 java 的退出码：$? 会是 tee 的（永远是 0），
# 这正是「CI 显示通过但实际有用例失败」的经典成因。
java -cp "$out_dir" dev.lab.location.core.RiskScorerSelfCheck 2>&1 | tee "$report_file"
java_exit=${PIPESTATUS[0]}

echo
if [ "$java_exit" -eq 0 ]; then
  echo "报告已保存：$report_file"
else
  echo "自检未通过（java 退出码 $java_exit）。报告：$report_file" >&2
fi

exit "$java_exit"

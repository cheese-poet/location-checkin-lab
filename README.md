# location-checkin-lab

[![self-check](https://github.com/cheese-poet/location-checkin-lab/actions/workflows/selfcheck.yml/badge.svg)](https://github.com/cheese-poet/location-checkin-lab/actions/workflows/selfcheck.yml)

> ⚠️ 徽章里的 `OWNER` 要换成你的 GitHub 用户名，否则显示为失效图标。

位置签到风控的**检测引擎 + 攻击数据生成器**，用来回答一个问题：

> 纯靠位置信息做签到，防守的上限到底在哪里？

结论写在代码里而不是文档里：**客户端产生的信号全部可以被伪造，所以必须分层加权；
真正难绕过的是 GNSS 交叉验证和服务端统计。** 这个工程让你能亲手把这句话验证一遍。

\---

## 边界声明（先读这一段）

本工程用于**防守方**：设计、实现、回归一个位置签到风控系统。

* `core` 模块是**检测引擎**（防守）；
* `app` 模块是**测试夹具**，用 Android 公开 SDK 生成攻击数据来验证检测引擎。

它不包含、也不会包含「针对某个具体考勤系统的绕过步骤」。测试夹具的正当使用场景是你自己的
应用、你自己的测试机、以及 CI。把它用在别人的考勤或风控系统上，后果不在技术层面。

\---

## 快速开始

### 1\. 只跑检测引擎（零依赖，不需要 Android SDK，不需要联网）

```bash
# Linux / macOS / CI
bash tools/run-selfcheck.sh
```

```powershell
# Windows PowerShell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run-selfcheck.ps1
```

两个脚本等价，都输出 11 个场景的通过/失败、一份审计报告样例，并把报告写到
`selfcheck-out/selfcheck-report.txt`。退出码 0 = 全部通过，1 = 有用例失败，2 = 编译或环境错误。

**注意 `run-selfcheck.sh` 必须用 `bash` 执行，不能用 `sh`。** 它依赖 bash 专有的
`PIPESTATUS` 来正确传递退出码（POSIX sh 没有这个数组，退化后会让 CI 在自检失败时显示绿灯）。

实测结果（全部通过）：

```
\[PASS] S01  score=  0 LOW    正常·室外步行GPS
\[PASS] S02  score=  0 LOW    正常·室内仅网络定位(0星)
\[PASS] S07  score=  0 LOW    正常·全班同教室(已排除花名册)
\[PASS] S03  score=100 HIGH   攻击·粗糙mock(室内)
\[PASS] S04  score= 50 MEDIUM 攻击·室外mock(仅标记命中)
\[PASS] S05  score= 85 HIGH   攻击·精致mock(绕过标记)
\[PASS] S06  score= 80 HIGH   攻击·代签(位置信号全正常)
\[PASS] S09  score= 40 MEDIUM 攻击·系统时间回拨
\[PASS] S10  score= 55 MEDIUM 攻击·瞬移(1秒2公里)
\[PASS] S08  score= 50 MEDIUM 反例·未排除花名册(全班误伤)
\[PASS] S11  score= 15 LOW    边界·气压计不符(单样本)
通过 11 / 失败 0
```

### 2\. 构建 Android 演示工程

需要 JDK 17+、Gradle 8.7+、Android SDK Platform 35。用 Android Studio 打开根目录即可
（它会自动补齐 Gradle wrapper）。

```bash
gradle wrapper            # 若本地没有 wrapper
./gradlew :core:selfCheck # 在 Gradle 里跑同一套自检
./gradlew :app:installDebug
```

装好后先给应用授权模拟位置，免手点的方式：

```bash
adb shell appops set dev.lab.location.app android:mock\_location allow
adb shell appops get dev.lab.location.app android:mock\_location
```

然后按界面上的 1→5 步操作：启动注入、推送坐标或回放路线、选服务端场景、采集评分、停止清理。

\---

## 目录结构

```
location-checkin-lab/
├── core/                        纯 JVM 检测引擎（零依赖）
│   ├── Geo.java                 大圆距离 / 方位角 / 插值
│   ├── PoseSample.java          一次位置采样（字段对应 android.location.Location）
│   ├── ClientEnv.java           客户端环境取证结果
│   ├── CheckinContext.java      服务端上下文（唯一攻击者看不到的一层）
│   ├── Finding.java             一条命中的规则 + 原始证据
│   ├── RiskLevel.java           风险等级
│   ├── Action.java              处置动作（没有「判作弊」这个选项，是刻意的）
│   ├── Thresholds.java          全部权重与阈值，集中可调
│   ├── RiskScorer.java          评分引擎（规则实现）
│   └── RiskScorerSelfCheck.java 11 个自检场景，零依赖
├── app/                         Android 演示工程
│   ├── MockLocationController.kt  mock 注入 + 路线回放
│   ├── GnssWatcher.kt             GNSS 卫星状态（最难伪造的信号）
│   ├── EnvProbe.kt                AppOps / 开发者选项 / root 取证
│   ├── SignalCollector.kt         连续采样 + 气压计交叉验证
│   └── MainActivity.kt            注入 → 采集 → 评分 闭环界面
├── tools/
│   ├── run-selfcheck.ps1       零依赖自检脚本（Windows）
│   └── run-selfcheck.sh        零依赖自检脚本（Linux / macOS / CI）
├── .github/workflows/selfcheck.yml  CI：JDK 17 与 21 双版本跑自检
└── LICENSE                      MIT
```

\---

## 三个设计决策

**1. 检测引擎是纯 Java，不依赖 Android。**
规则跑在普通 JVM 上，所以单元测试不需要模拟器或真机（CI 友好），而且**同一份代码可以同时被
客户端和服务端引用**——避免两端算距离、判阈值的方式不一致导致判定分歧。

**2. 自检不用 JUnit。**
一个不需要任何外部依赖就能编译运行的断言程序，可以在没有 Android SDK、没有网络的环境里
验证规则行为。换成 JUnit 只是把断言换成注解，逻辑完全一样。

**3. Android 侧不引入 androidx 和协程。**
原生 `Activity` + `Handler` 足够，依赖越少越容易一次构建通过。这是测试夹具，不是产品代码。

\---

## 检测规则全表

权重按**伪造成本**排序，不是拍脑袋定的。

|规则码|触发条件|权重|伪造成本|误伤风险|
|-|-|-|-|-|
|`GNSS\_NO\_SATELLITE`|provider=gps 但可见卫星为 0|**45**|高（需 hook 到 HAL）|低（已隔离 network/fused）|
|`MOCK\_FLAG`|系统标记为模拟位置|**50**|低|极低|
|`ELAPSED\_INVALID`|`elapsedRealtimeNanos` 为 0 或不递增|**40**|低|低|
|`CLOCK\_TAMPERED`|墙上时钟与单调时钟不一致|40|低|低|
|`DEVICE\_SHARED\_HIGH`|同设备 ≥3 个账号签到|**40**|极高（服务端统计）|低|
|`MOCK\_APP\_OP`|AppOps 枚举到被授权的 mock 应用|40|中|低|
|`GNSS\_NO\_USED\_IN\_FIX`|有卫星但一颗都没参与解算|30|高|中（捕获期）|
|`GRID\_CLUSTER`|花名册外 ≥5 账号同网格|30|极高|低（前提是正确排除花名册）|
|`TELEPORT`|推算速度 > 80 米/秒|25|低|低|
|`SERVER\_CLOCK\_SKEW`|客户端与服务端时间差 > 5 秒|25|低|低|
|`DEVICE\_SHARED\_LOW`|同设备恰好 2 个账号|25|极高|中（借用手机）|
|`NO\_LOCATION`|一个样本都没采到|30|—|低|
|`STATIC\_ACCURACY`|≥3 次采样精度完全一致|20|低|低|
|`SPEED\_MISMATCH`|上报速度与位移推算差 > 15 米/秒|20|低|低|
|`IP\_MID\_FROM\_CLAIM`|IP 距坐标 > 150 公里|20|中|中（跨网出口）|
|`IP\_FAR\_FROM\_CLAIM`|IP 距坐标 > 500 公里|35|中|中|
|`IMPLAUSIBLE\_ACCURACY`|精度 ≤ 1 米|15|低|低|
|`BAROMETER\_MISMATCH`|气压计与 GPS 海拔差 > 50 米|15|中|中（室内无海拔）|
|`ROOT\_SUSPECTED`|疑似 root / 解锁|15|中|**高**|
|`HISTORY\_JUMP`|距历史活动区域 > 200 公里|15|低|中（需 ≥5 次历史样本）|
|`STATIC\_PROVIDER`|位移 > 30 米但 provider 全程不变|10|低|**高**|
|`DEV\_OPTIONS\_ON`|开发者选项开启|10|极低|**高**|
|`LATE\_WINDOW`|距窗口关闭 ≤ 20 秒提交|10|—|**高**（拖延是常见行为）|

### 两条关键规则的实现细节

**`GNSS\_NO\_SATELLITE` —— 命中的不是「没有卫星」，而是「自相矛盾」。**
真实的室内设备通常把 provider 报成 `fused` 或 `network`，因为确实没有 GPS 定位；
而 mock 往往固定写成 `gps`。所以规则只在 `provider == "gps"` 时生效：
**声称来自 GPS，接收机却一颗卫星都看不到**。这个隔离让室内正常用户完全不受影响
（自检场景 S02 就是这条的回归线）。

**`ELAPSED\_INVALID` —— 用单调时钟校验墙上时钟。**
`Location.getTime()` 是墙上时钟，用户能改；`getElapsedRealtimeNanos()` 是开机以来的
单调时钟，用户改不了。真实位置由系统写入，两者差值稳定。粗糙的 mock 实现经常根本不填后者，
于是字段为 0 或恒定不变。

\---

## 分级与处置

|风险分|等级|处置|
|-|-|-|
|< 35|低|放行|
|35 \~ 69|中|二次验证：重新签到 / 动态码 / 延长采样|
|≥ 70|高|人工复核：保留证据，**不自动判作弊**|

**「不判作弊」是刻意的设计。** 纯位置签到的误伤不可避免（地下室、定制 ROM、开发机、
全班未排除花名册），一个自动拉黑用户的系统一定会出事。系统只负责记录证据和分级，
裁决交给流程和人——技术上更稳，合规上也更安全。

\---

## 自检用例矩阵

用例分成三类，这是刻意设计的结构：

|类别|用例|验证目标|
|-|-|-|
|**正常场景**|S01 室外步行 / S02 室内仅网络定位 / S07 全班同教室|误伤回归线，必须落在低风险|
|**攻击场景**|S03 粗糙 mock / S04 室外 mock / S05 精致 mock / S06 代签 / S09 时间回拨 / S10 瞬移|拦截能力|
|**配置反例**|S08 未排除花名册 / S11 气压计不符（单样本）|参数传错的代价、弱信号的边界|

两个用例值得单独说：

* **S04（室外标准 mock）只到中风险（50 分）。** 这是量化结论：真机 GNSS 正常工作时，
唯一的破绽只剩 `MOCK\_FLAG`，而它可被 hook 掉。**单靠客户端标记拦不住攻击**，
这个数字是给决策者看的。
* **S05（精致 mock）到高风险（85 分）。** 假设攻击者已经绕过 `isMock` 标记、
字段也全修对了，仍然被 GNSS 交叉验证（45）+ 服务端设备聚类（40）抓住。
这就是纵深防御生效的直接证据。

\---

## 调参指南

**先保证误伤率为零，再谈拦截率。** 上线前请用一批真实用户的正常签到数据回归，
确认没有正常样本落入中风险以上。

按场景建议的起步配置：

|场景|建议启用|说明|
|-|-|-|
|教室签到|`GNSS\_NO\_SATELLITE`、`ELAPSED\_INVALID`、`DEVICE\_SHARED\_\*`、`GRID\_CLUSTER` + 短半径|成本低、隐私代价小|
|异地 / 线上|上面全部，再加连续采样、`HISTORY\_JUMP`，必要时上 Play Integrity|硬件证明是唯一绕不过的一层|
|开发者 / 测试账号|走白名单，跳过 `DEV\_OPTIONS\_ON` 与 `ROOT\_SUSPECTED`|否则这两个规则会一直误伤|

容易踩的坑：

* **`CheckinContext.sameGridAccountCountOutsideRoster` 必须排除花名册。** 40 人的班级坐在同一间
教室，本来就该有 40 个账号落在同一网格；只有花名册**之外**的账号混进来才是异常。
传错这个字段的结果见 S08。
* **半径别设 500 米**，隔壁宿舍都能签。50\~100 米。
* **不要信客户端上报的 `is\_device\_trusted: true` 之类的字段**，那是给攻击者写的接口。
* **精度不是越小越可信。** 真实 GPS 极少优于 1 米，规则要检查它的**分布**而不是数值大小。

\---

## 隐私合规清单

采集的每一项都在扩大你自己的泄露责任。上线前逐条确认：

1. **最小必要**：BSSID、基站、气压计、人脸都可推断位置或身份，人脸属**敏感个人信息**。
2. **单独同意**：人脸识别必须单独告知并取得同意，不能塞在总协议里。
3. **告知保存期限与用途**，到期删除。
4. **评分尽量在服务端做**，客户端只上报最小字段。
5. **日志脱敏**：不要把全量 WiFi 列表长期落库。
6. **必须提供申诉通道**：误判是必然存在的，没有申诉通道的系统一定会出事。
7. **证据留存**：`Finding.evidence` 就是为了这个——「系统记录到这几条异常」和
「系统判定你作弊」是两回事。

这条清单和上面的技术设计是同一件事：**位置验证与隐私保护存在结构性冲突**，
一个 App 想要可靠地验证位置，就必须采集远多于位置本身的数据。
控制范围是设计约束，不是附加项。

\---

## 已知边界与盲区

* **mock 标记去不掉。** 用公开 SDK 注入的位置必然带标记，所以 `app` 只能验证
「有标记的注入」这一条路径。想测「无标记的注入」需要改系统，不在本工程范围内。
* **部分定制 ROM / 游戏手机自带位置模拟功能**，属系统级实现，可能不经过 `OP\_MOCK\_LOCATION`
通路，会让 `MOCK\_FLAG` 与 `MOCK\_APP\_OP` 同时失效。这是必须靠 GNSS 服务端统计兜底的原因。
* **室内真的没有 GPS。** 所有基于「必须有卫星」的规则都必须先判断 provider 和场景。
* **`AppOpsManager.getPackagesForOps` 在部分 ROM 上会返回空列表**，空不等于安全。
* **本工程没有实现服务端**，`CheckinContext` 的字段需要由你的服务端统计后填入。

\---

## 环境踩坑记录

写这个工程时踩到的坑，都会让人误判成「代码有问题」，记在这里省时间：

1. **PowerShell 5.1 与 UTF-8 BOM。** `tools/run-selfcheck.ps1` 必须保存为 UTF-8 **with BOM**。
   没有 BOM 时 PS 5.1 按 GBK 解析，中文注释被解码成乱码后可能凑出引号或续行，
   **把后面的代码行吞进注释**。症状极具迷惑性：脚本「跑完了」、退出码 0、关键命令一行没执行、
   也不报错。很多编辑器（含自动化改写工具）保存时会静默去掉 BOM。

2. **PS 5.1 会拆分 `-Dfile.encoding=UTF-8`**，导致 Java 报 `ClassNotFoundException`。
   解决办法是别传这个参数——程序内部已用 `PrintStream` 强制 UTF-8 输出。

3. **`Location.elapsedRealtimeNanos` 不设就永远是 0。** 这在 Android 12+ 上尤其明显，
   是「粗糙 mock」最典型的特征。写注入代码时最容易漏这一行。

4. **`java ... | tee f; exit $?` 会丢掉 java 的退出码**（`$?` 是 tee 的，永远是 0），
   让 CI 在自检失败时显示绿灯。必须用 `${PIPESTATUS[0]}`，而它是 **bash 专有**的——
   所以脚本要用 `bash` 执行，不能用 `sh`。

5. **`find ... | while read` 会在子 shell 里执行**，数组收不到内容。
   要用进程替换 `< <(find ... -print0)` 配合 `read -d ''`。

---

## 许可证

MIT，见 [LICENSE](LICENSE)。

## 关于公开这个仓库

`core` 是真正值得复用的部分：一个平台无关、可测试的检测引擎。

`app` 是一个**完整可编译的 mock 定位应用**。公开它等于把「假定位工具」的现成实现放出去——
虽然用到的全是公开 API，但它替人省掉了全部工作量。发布前请权衡，两种做法：

- **只公开 `core`**：把 `app` 留在私有仓库或本地分支；
- **一起公开**：把上面的边界声明保留在显著位置，并考虑选用带使用限制的许可
  （注意 MIT 不允许附加限制，真要限制就得换许可证）。

本工程的定位是**检测引擎的测试夹具**，不是假定位工具——它的每个设计细节（精度抖动、
GNSS 卫星状态采集、字段自洽的路线回放）都是为了「让检测规则可被复现地验证」，
这一点和普通的假定位应用有本质区别。




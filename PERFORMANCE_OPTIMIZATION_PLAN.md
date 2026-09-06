# Termux 终端渲染性能优化计划（Canvas 路线）

> 目标：在**不改变渲染技术栈（仍为 Android View + Canvas/hwui）、不改变终端语义与用户体验**的前提下，系统性地消除已定位的性能瓶颈，并建立可量化、可回归的度量与追踪机制。
>
> 范围外（本期不做）：OpenGL/Vulkan 重写、Jetpack Compose 迁移、终端行为改动、transcript 语义改动、minSdk/API 改动。
>
> 追踪方式：本文件内的任务清单 + `perf/results.csv` 指标记录 + git 里程碑（见文末）。

---

## 0. 参考的问题清单（来自代码分析，含证据位置）

| # | 问题 | 证据位置 | 影响 |
|---|------|----------|------|
| P1 | 无脏区，**任何 invalidate 都是全屏重绘**（光标闪烁、单字符输出都会重画全部行） | `TerminalView.onScreenUpdated()` → `invalidate()` (TerminalView.java:497)；`TerminalCursorBlinkerRunnable` 每 tick 全屏 invalidate (TerminalView.java:1334) | 每帧 CPU 高，闪烁/低频刷新场景白耗 |
| P2 | **逐字节解析在主线程**，输出突发时 UI 被饿死 | `MainThreadHandler.handleMessage` → `mEmulator.append()` (TerminalSession.java:345)；`TerminalEmulator.append/processByte` 逐字节 | 卡死/ANR 根因（前几轮已确认） |
| P3 | **后台/非当前会话也全量解析并落样式**，仅跳过重绘 | `TermuxTerminalSessionActivityClient.onTextChanged()` (TermuxTerminalSessionActivityClient.java:118-121) | 后台 CPU/内存浪费 |
| P4 | 非 ASCII 字符**每帧调 `Paint.measureText`**；run 间反复改 Paint 状态打散 glyph 缓存 | `TerminalRenderer.render()` (TerminalRenderer.java:104-107)；`drawTextRun()` 内 setColor/setFakeBold… | CJK/emoji 界面重绘慢 |
| P5 | **主线程写子进程队列是阻塞式** `wait()` | `ByteQueue.write()` (ByteQueue.java:105-113)；`TerminalSession.write()` 主线程调用；输出队列仅 4096B (TerminalSession.java:48) | 子进程不读时 UI 可能整线程卡死 |
| P6 | 无障碍开启时**每次屏幕更新都整屏拼字符串** | `onScreenUpdated()` 中 `setContentDescription(getText())` (TerminalView.java:498) | TalkBack 下每批输出做整屏文本重建 |
| P7 | 读线程每 4KB 一条消息，handler 单次吸干队列，多余消息空读 | `TerminalSession.java:142`、`:342-368` | 消息风暴/空转 |
| P8 | `TerminalRow.setChar` 数组平移 + 只增不减扩容；组合字符使行超宽 | `TerminalRow.java` setChar/扩容 | 内存与 O(n²) 拷贝 |
| P9 | transcript 内存大（2000–50000 行/会话）；`resize()` 慢路径逐字符重建 | `TerminalBuffer.resize()` (TerminalBuffer.java:164+) | 旋转/分屏/IME 高度变化卡顿 |
| P10 | 点击开 URL 时 `getWordAtLocation()` 每次构造多份整屏字符串 | `TerminalBuffer.getWordAtLocation()` (TerminalBuffer.java:201-232) | 点一下卡一下 |
| P11 | 光标闪烁心跳与视图生命周期耦合，异常重建可能残留自旋 Runnable | `TerminalView.java:1266-1340`；start/stop 只在 `TermuxTerminalViewClient.java:112-133` | 泄漏/白耗 |
| P12 | ExtraKeys 每键读特殊键状态、长按每次新建/销毁 ScheduledExecutor | `ExtraKeysView.readSpecialButton()` (ExtraKeysView.java:643)；`:535-568` | 输入高峰抖动 |
| P13 | 会话列表标题变化即整表 `notifyDataSetChanged` | `TermuxTerminalSessionActivityClient.termuxSessionListNotifyUpdated()` 调用链 | 列表重绑浪费 |

---

## 1. 度量与追踪机制（先于一切，贯穿始终）

### 1.1 指标定义

| 指标 | 定义 | 采集方式 |
|------|------|----------|
| M-parse | emulator 解析吞吐（bytes/s） | JVM 微基准（见 1.2） |
| M-frame | 平均/90 分位/抖动帧耗时、Janky 帧占比 | `dumpsys gfxinfo` |
| M-draw | 每帧 `drawTextRun`/`measureText` 次数（debug 计数器） | 新增可选探针（见 Task 0.3） |
| M-ui | 重负载下 UI 线程输入响应延迟（是否出现 input dispatch 超时） | systrace / `input` 事件注入 |
| M-mem | 单会话 transcript 内存估算 | `adb shell dumpsys meminfo` |

### 1.2 基准采集流程（脚本化，放 `perf/`）

- `perf/bench_parse.sh`：JVM 微基准，用**固定的典型语料**（见 `perf/corpora/`：普通文本、ANSI 彩色、tmux 全屏重绘流、CJK）跑 `TerminalEmulator.append`，输出 bytes/s 与处理耗时；结果追加到 `perf/results.csv`（含 git sha、日期、设备/JVM）。
- `perf/measure_gfx.sh`：真机步骤——
  1. `adb shell setprop debug.hwui.profile visual_miter`
  2. 打开 Termux 跑固定压力命令（放 `perf/stress_cmds.sh`：`yes`、ANSI 进度流、`find` 大目录、tmux 滚动）
  3. `adb shell dumpsys gfxinfo com.termux framestats` 取 Janky%、90 分位帧耗时
  4. 记录到 `perf/results.csv`
- `perf/README.md`：每台测试设备的固定化记录（型号/Android/DPI/字号/行列数）。

### 1.3 回归保护

- 现有单元测试是回归底网：`./gradlew :terminal-emulator:test`（含 `ByteQueueTest/TerminalRowTest/HistoryTest/ResizeTest/…`）。**任何触碰 emulator/buffer 的任务必须先跑它。**
- 新增的脏行/增量逻辑必须配纯 JVM 单元测试（Robolectric 不需要，走纯逻辑测试）。
- M-parse 写入 CI（可选：GitHub Action 或本地脚本阈值告警），防止解析性能回退。

### 1.4 探针（Task 0.3 落地，供 M-draw 追踪）

在 debug 构建下给 `TerminalRenderer`/`TerminalView` 加只读开关的计数器（`BuildConfig.DEBUG && SystemProperties.get("termux.perf.counters")`）：每帧 drawTextRun 次数、measureText 次数、脏行数、parse 批耗时、队列积压。输出经 Logger verbose 或 `dumpsys` 可查。

---

## 2. 分阶段任务清单

> 状态图例：`[ ]` 未开始 `[~]` 进行中 `[x]` 完成。完成后把 **metrics** 记入 `perf/results.csv` 并在 `备注` 填 diff。

### Phase 0 — 基线（先量化，再动手）

| ID | 任务 | 完成标准 (DoD) | 预计 | 状态 |
|----|------|----------------|------|------|
| [x] T0.1 | 建立 `perf/` 目录与基准脚本（1.2），提交基线语料 | 能一键产出 M-parse/M-frame 基线并落盘 `results.csv`（M-parse 已跑通；M-frame 脚本就绪） | 0.5d | ✅ 见文末执行记录 |
| [x] T0.2 | 真机（小米 Mi10/API36）跑通 M-frame 采集并落盘基线 | 5 场景基线已记入 `results.csv`（见文末执行记录） | 1d | ✅ 见文末执行记录 |
| [x] T0.3 | 加 debug 探针（1.4） | 可观测 M-draw；对 release 零影响（真机已验证，见文末执行记录） | 0.5d | ✅ 见文末执行记录 |
| [x] T0.4 | 跑通 `./gradlew test` 全量，确认底网绿 | 全绿（app debug/release + terminal-emulator） | 0.5d | ✅ 见文末执行记录 |

### Phase 1 — 消灭“无意义全屏重绘”（低风险、收益集中）

| ID | 任务 | 改动点（文件/函数） | 完成标准 | 预计 | 风险 |
|----|------|---------------------|----------|------|------|
| [x] T1.1 | **Buffer 侧脏行/滚动增量跟踪**：新增“本批”变更元数据：屏幕滚动行数 + 非滚动脏行区间集合；写屏入口与 resize/屏幕切换记录；一次性读取并复位 | `TerminalBuffer.java`（`ScreenChanges`/`markScreenRowDirty/markScreenRowsDirty/markAllScreenDirty/getScreenChanges/isScreenRowDirty/clearScreenChanges`）、`TerminalEmulator.java`（切换 alt/main 缓冲时 `markAllScreenDirty`） | 单元测试覆盖：普通换行=滚动delta、光标定位改写=仅该行脏、ED2/clear=全脏、resize=全脏、alt 屏切换=全脏、非全屏滚动区=脏行集且无滚动delta、清除复位；行为与现状完全一致（旧测试全绿：153 tests/0 fail） | 2-3d | 低（纯增量，不影响行为） | ✅ 见文末执行记录 |
| [x] T1.2 | **View 侧脏区 invalidate**：`onScreenUpdated()` 消费 T1.1 元数据：full/整屏滚动/mTopRow 变化→整屏；否则脏行+cursor 新旧行→`invalidate(0,top,w,bottom)`；无变化时兜底整屏 | `TerminalView.onScreenUpdated(boolean)` + `TerminalBuffer.ScreenChanges` | 全量测试绿；真机打字/滚动输出渲染正常（截图验证） | 1-2d | 低 | ✅ 见文末执行记录 |
| [x] T1.3 | **光标闪烁只重绘光标 cell 邻域**：blinker Runnable 改为 `invalidateCursorRegion()`（小矩形）；光标未启用（civis 等）跳过 | `TerminalView` blinker Runnable + `invalidateCursorRegion()` | 真机开启 blink(500ms) 后光标正常渲染、无崩溃；默认 blink=0 不受影响 | 0.5-1d | 低 | ✅ 见文末执行记录 |
| [x] T1.4 | **无变化不重绘 / 纯光标移动小矩形**：内容未变且光标未动→完全不触发；仅光标移动→新旧两格小矩形；有脏行→行带 | `TerminalView.onScreenUpdated` + `invalidateCellRect/setLastRenderedCursor` | 全量测试绿；真机输入+方向键+覆盖写渲染正确无残影 | 1d | 低 | ✅ 见文末执行记录 |
| [ ] T1.5 | **无障碍文本节流**：`setContentDescription(getText())` 改为仅当内容实质变化且距上次 ≥250ms 才计算；或仅重算有滚动时 | `TerminalView.java:498` | TalkBack 打开时 M-draw 不再随输出批猛涨；读屏内容不错行 | 0.5d | 低 |

### Phase 2 — 行级渲染缓存（把“每帧重画”变成“变了才画 + blit”）

| ID | 任务 | 改动点 | 完成标准 | 预计 | 风险 |
|----|------|--------|----------|------|------|
| [x] T2.1 | **按需重绘行集合（damage-list）**：render() 只画“本帧变化行”，其余行保留旧像素（比 HW 位图缓存更简且免显存/失效管理） | `TerminalRenderer.render(+firstRow/rowCount)`；`TerminalView`：`mPartialFirstRow/LastRow` + `requestRowsRedraw/requestFullRedraw`，各 invalidate 点分类 | 实测（Mi10，同负载 8s）：原地彩色 p50 8→5ms、p95 13→8ms、janky→0%；纯光标 legacy janky 10.6→0%；视觉（CJK/彩色/滚动/光标）无回归 | 3-5d | 中 | ✅ 见文末执行记录 |
| [x] T2.2 | **非 ASCII 测量缓存**：`measureText` 结果按 codePoint 有界缓存（SparseArray，满则整体清空；宽度仅依赖 renderer 固定的字型/字号） | `TerminalRenderer.mCodePointWidthCache` | 实测（Mi10 CJK 洪泛循环）：p50 32→24ms（BASE→T2.2）；纯翻页 CJK 历史 p50 5-6ms（无回归） | 1-2d | 低 | ✅ 见文末执行记录 |
| [x] T2.3 | **行预整形缓存（实测否决）**：曾尝试 TextRunShaper→PositionedGlyphs + canvas.drawGlyphs(Font) 按 run 缓存整形结果（API 33+），滚动翻页时复用字形避免重复 native shaping | `TerminalRenderer`（试验后整体回退） | 实测（同场景同帧数可比）：翻页 CJK 历史 p50 **6→10ms、legacy janky 4.4%→25.3%**，倒退 ~2x → 否决回退 | 1d | — | ✗ 见文末执行记录 |

### Phase 3 — 让“解析”不再饿死 UI（卡死根因）

| ID | 任务 | 改动点 | 完成标准 | 预计 | 风险 |
|----|------|--------|----------|------|------|
| [x] T3.1 | **时间片批处理**：handler 每轮限定预算（6ms/8KB 片）后若队列仍有数据则延迟 4ms 重投一次（真实让出，非即刻重投）；先 `removeMessages` 保证单在途批次 | `TerminalSession.MainThreadHandler.drainQueuedInputInBatches`；`ByteQueue.read(maxCount)` + `isEmpty()` | 实测（Mi10 持续 CJK 洪泛，8s）：帧 274→346(+26%)、legacy janky 89.4→86.4%、p95 27→23ms；字节零丢失（append 跨调用状态性+pty 背压） | 1-2d | 中 | ✅ `7c8d3866`；见文末执行记录 |
| [ ] T3.2 | **队列积压可视化（先做诊断）**：探针输出每轮 backlog 长度、单批处理耗时 | 配合 T0.3 | 可定位“何时解析追不上输出” | 0.5d | 低 |
| [ ] T3.3 | **消息风暴收敛**：读线程写入后仅当无在途处理消息时才发 MSG_NEW_INPUT（`removeMessages`+`send` 或 handler 位标志），消除空读 | `TerminalSession.java:137-146/342+` | M-frame：输出峰值时主线程空转消息显著减少 | 0.5d | 低 |
| [ ] T3.4 | **（激进，已实测否决）解析移出主线程**：每会话 emulator worker 独占 append + 全部回调 marshal + 单 monitor 锁协调 Buffer 读写 | `TerminalSession`/`TerminalView`/app 直读点（已实施并整体回退，见文末记录） | 实测同负载下帧率倒退（详见记录）→ 否决回退；保留 T3.1 | 5-10d | 高（已实证） | ✗ 见文末执行记录 |

### Phase 4 — 结构性内存与杂项（单项小、独立可交付）

| ID | 任务 | 改动点 | 完成标准 | 预计 | 风险 |
|----|------|--------|----------|------|------|
| [ ] T4.1 | **UI 线程永不阻塞写子进程**：`TerminalSession.write()` 路径（主线程）改为非阻塞：输入队列扩容 + 满时积压到附属增长缓冲并交给 writer 线程按序消费（进程停滞期间限界丢弃+日志需与维护语义确认，防大粘贴丢字） | `TerminalSession`、`ByteQueue`（加 tryWrite/overflow）、`TermuxTerminalViewClient`/IME 路径测试 | 用“子进程不读 stdin”的测试场景验证 UI 仍可响应、粘贴大文本不丢（子进程恢复后补发） | 1-2d | 中（粘贴语义） |
| [ ] T4.2 | **行数组增长/收缩策略**：扩容按需、行 clear 时超容量缩容回收；评估并测试组合字符防滥用已到位（15 上限） | `TerminalRow` | `mSpaceUsed` 峰值/行均内存下降；`TerminalRowTest` 绿 | 1d | 低 |
| [ ] T4.3 | **`resize()` 慢路径优化**：列数变化重排由逐字符改为按 run 拷贝并复用旧行；必要时分帧/后台执行并保持 UI 可响应 | `TerminalBuffer.resize` | 旋转/分屏场景 M-frame 不出现长帧（>100ms）；`ResizeTest/HistoryTest` 绿 | 2-3d | 中 |
| [ ] T4.4 | **`getWordAtLocation()` 单遍扫描重写**：按行 + `mLineWrap` 游走，不再构造整屏字符串 | `TerminalBuffer.getWordAtLocation` | 点击 URL 场景不再出现明显停顿；行为单测覆盖折行/宽字符 | 1d | 低 |
| [ ] T4.5 | **ExtraKeys 读取与调度收敛**：特殊键状态批量读取、值变化才刷按钮颜色；长按复用单例调度器 | `ExtraKeysView`、`TermuxTerminalViewClient` | 连打/长按场景 M-draw 无异常峰值 | 1d | 低 |
| [ ] T4.6 | **会话列表增量更新**：仅更新变化的 item | `TermuxTerminalSessionActivityClient`/session list 适配器 | 多会话标题频繁变化时无整表重绑 | 1d | 低 |
| [ ] T4.7 | **光标心跳生命周期加固**：View detached / Activity destroy 路径确保 `stopTerminalCursorBlinker()`；心跳改为不持 Activity 引用的静态分发或引用清理 | `TerminalView`（onDetachedFromWindow 补 stop）、blinker 结构 | 重建循环后无残留心跳（探针验证消息队列无孤儿 Runnable） | 1d | 低 |
| [ ] T4.8 | （决策点）**后台会话降载**：非当前会话且不可见时，是否仍全量解析需产品决策；若做：提供“暂停刷新 vs 降级只记文本”配置 | 架构/行为 | 需单独评审，默认本期不做 | - | 高 |

---

## 3. 执行顺序与决策门

```
M0 基线(0.5-2d) ──► M1 无意义重绘消除(P1,2-5d) ──► M2 行缓存(P2,4-8d)
      │                    │                              │
      └── 全程护栏：T0.4 底网 + 每 Task 后 ./gradlew test + 记 results.csv
M2 后决策门 A：M-frame 是否达标（压力/滚动场景）？
   ├─ 达标 → M4 结构性优化(P4)
   └─ 未达标(卡死残留) → M3 T3.1(1-2d) → 再测 →
        ├─ 达标 → 暂缓 T3.4（激进解析线程）→ M4
        └─ 未达标 → 实施 T3.4（预留 5-10d）→ M4
M4(4-8d) ──► M5 回归收尾：全量测试 + 双设备对比 results.csv + 文档
```

> 里程碑与 git：每个 Task 独立 commit/PR；每阶段打 tag `perf-m1`、`perf-m2`…；`perf/results.csv` 每行含 `task_id, git_sha, metric, before, after, device, date, notes`。

---

## 4. 完成标准（总收尾）

- [ ] M1–M4 全部 DoD 达成，状态列全 `[x]`
- [ ] `perf/results.csv` 有每个 Task 的 before/after
- [ ] `./gradlew test` 全绿；两个代表性压力场景（普通大输出、tmux 滚动）Janky% 与 90 分位帧时间较基线下降 ≥ 30%（目标值，实际以基线上调为 OKR）
- [ ] 卡死场景（P2）复测：压力输出下输入可响应、无 input dispatch 超时
- [ ] 视觉回归：CJK/emoji/256 色/反显/光标三样式/选区截图对比无差异
- [ ] TalkBack、旋转、分屏、字号切换、会话增删均复测通过
- [ ] 本文档更新：每个任务备注 diff 数字与踩坑

---

## 5. 预估与分工建议

- 单人专职：M0-M5 合计约 **3–4 周**；其中 T3.4 若触发再加 1–2 周。
- 建议先并行两件事：T0.1-T0.4（度量就绪）与 T1.1（脏行跟踪，纯增量改动）——前者给出目标数字，后者是后续所有收益的地基。
- 每项改动保持“小步 + 可回滚开关”：T1.1 元数据可被特性开关关闭以对比前后；所有新增缓存用 LRU 与调试探针暴露命中率。

---

## 6. 执行记录（Progress Log）

> 每完成一个 Task 在此追加一条，记录日期 / git sha / 改动 / 指标 diff / 待办。配套数据见 `perf/results.csv`。

### 2026-09-06 · git `3b66f879` · T0.1 ✅（待审查）

**改动**
- 新增 `perf/`：`README.md`、`results.csv`（含头）、`bench_parse.sh`（M-parse 一键脚本）、`measure_gfx.sh`（M-frame，真机待跑）、`stress_cmds.sh`（scroll/ansi/yes/find 压力命令）。
- 新增 JVM 基准 `terminal-emulator/src/test/java/com/termux/terminal/perf/ParseThroughputBench.java`：
  - 确定性语料（固定种子）：`plain`/`ansi`/`tui`/`cjk`/`mixed`，各 ~1 MiB，80×24、2000 行 transcript，64 KiB 分片喂入（同生产批处理）；
  - 2 次预热 + 5 次测量取中位，输出 bytes/s 并直接追加 CSV 行；
  - 系统属性 `termux.perf.parse` 门控，常规 `./gradlew test` 自动 SKIP（已验证）。
- `terminal-emulator/build.gradle`：`tasks.withType(Test)` 增加 `-Ptermux.perf.*` 属性透传（默认无影响）。
- 环境：`local.properties` 指向已有 `~/android/sdk`（platform-36/build-tools 35+36/NDK 29.0.14206865），`~/Android`（冗余安装）已删除。

**T0.1 基线结果（本机 jvm-Arch，JDK 21，Linux；git 3b66f879）**

| metric | bytes/s | 备注 |
|--------|---------|------|
| M-parse:plain | ~150 MB/s | ASCII 大文本 |
| M-parse:ansi  | ~150 MB/s | SGR 彩色日志（与 plain 同量级） |
| M-parse:tui   | ~150 MB/s | 全屏重绘+绝对寻址 |
| M-parse:cjk   | **~3.9 MB/s** | ⚠️ 较 ASCII 慢 ~40×，命中计划中 P8：宽字符走 `TerminalRow.setChar` 慢路径 |
| M-parse:mixed | ~117 MB/s | 含 `\r` 原地进度条 |

**发现/结论**
- CJK/宽字符内容解析吞吐 ~3.9 MB/s 是显著热点（后台/前台中文日志会直接拖慢主线程），可作为 M4 阶段 T4.2（TerminalRow 优化）的量化验收指标。
- 基准数字随 CPU 频率抖动（同机多次采样约 ±5%），对比时取多次中位或看趋势。

**待办（下一步）**
- T0.2：接真机后运行 `perf/stress_cmds.sh <id>` + `perf/measure_gfx.sh`，产出 M-frame 基线。
- T0.3：debug 探针（M-draw 计数）。
- T0.4：全量 `./gradlew test` 底网绿确认。

### 2026-09-06 · git `3b66f879` · T0.2 ✅（待审查）

**改动**
- `perf/measure_gfx.sh` 加固：0 帧时写 `no-frames-rendered`（不再落 4950ms 哨兵）；app 主线程被饿死导致 `dumpsys gfxinfo` 无法服务时，落 `unserviceable=1` 行而不是硬失败（P2 本身就是测量结果）。
- `perf/README.md` 补充真机操作要点（见下）。

**M-frame 基线（真机：Xiaomi Mi10 `M2007J3SC` / Android 15 API 36 / arm64 / debug Termux 0.118.0；git 3b66f879）**

| 场景 | 输出量级 | frames | janky% | p90/p95 | gfxinfo 可服务 |
|------|----------|--------|--------|---------|---------------|
| idle 静态 | 0 | 0 | – | – | ✅（静态无重绘） |
| light `seq 500000` | ~3.5 MB | 7 | 14.3% | 350ms | ✅ |
| medium `seq 5000000` | ~40 MB | 1 | 100% | ~2950ms | ✅（仅 1 帧） |
| burst `seq 20000000` | ~170 MB | 0–2 | 100% | >5s | ⚠️ 本次全程饿死→unserviceable；另一次 10s 窗仅 2 帧 |
| flood `yes` | ∞ | 0 | – | – | ❌ unserviceable（主线程饿死，dumpsys 无法服务） |

**结论（量化）**
- 证实 P1/P2：输出只要达到几 MB 级别，UI 线程就被逐字节解析+全屏重绘拖到几乎不产帧；`light~3.5MB` 最坏单帧即 350ms，`medium~40MB` 6s 只出 1 帧，更大输出直接令系统无法采样（等价 UI 冻结、输入失效——测试中连 `input` 注入都进不去）。
- 优化目标因此锚定：**M1/M2 后，medium 场景应明显出帧、light 场景 p90 应回到几十 ms 内；flood 场景不再 unserviceable**。

**真机操作要点（已记入 perf/README）**
- `adb shell input text` 不支持空格：用 `%s`（如 `seq%s5000000`），否则参数丢失；
- 冷启动后需先 `input tap` 终端区获得焦点/唤出键盘，输入才到达 pty；
- 无法注入带引号/管道的复杂命令，压力命令用无空格单命令（`seq N`、`yes`）；
- 大输出期间 `dumpsys gfxinfo` 可能“Failure while dumping the app”——即主线程饿死的量化信号（unserviceable）。

**待办（下一步）**
- T0.3：debug 探针（M-draw 计数，需 app 侧改码+重装 debug 包）。
- T0.4：全量 `./gradlew test`。

### 2026-09-06 · git `3b66f879` · T0.3 ✅（待审查）

**改动**
- `terminal-view/TerminalRenderer.java`：新增 debug 计数 `mPerfRowsDrawn / mPerfRunsDrawn / mPerfMeasureCalls`，`render()` 开头清零；行循环、`drawTextRun()`、非 ASCII `Paint.measureText()` 路径各自自增。
- `terminal-view/TerminalView.java`：`TERMINAL_VIEW_PERF_LOGGING_ENABLED` + `setIsTerminalViewPerfLoggingEnabled()`；`onDraw` 渲染后调用 `maybeLogPerfFrame()`（≥250ms 节流，`mClient.logInfo` 输出 `PERF frame rows=.. runs=.. measureText=..`）。
- `app/TermuxTerminalViewClient.java` `onStart()`：与 “Terminal View Key Logging” 调试项 piggyback（同一开关开启 M-draw 计数）。
- 设计：默认关闭 + 仅由 debug 偏好触发，release 零行为影响（计数仅 int 自增，开销可忽略）。

**真机验证（小米 Mi10/API36，重建并安装 debug 0.118.0）**
- 需先把 `log_level` 偏好从 0 调到 1（Logger 默认静默，测试后已恢复 0、key logging 恢复 false）。
- ASCII 输出帧：`PERF frame rows=26 runs=33 measureText=0`；
- 含中文行输出帧：`PERF frame rows=26 runs=66 measureText=21` → 宽字符确实每帧走 21 次原生 `measureText`，runs 因样式分段翻倍 → M-draw 可观测性成立。

### 2026-09-06 · git `3b66f879` · T0.4 ✅（待审查）

**结果**
- `./gradlew test` 全绿：`:app:testDebugUnitTest` / `:app:testReleaseUnitTest` / `:terminal-emulator:testDebugUnitTest` / `:terminal-emulator:testReleaseUnitTest`（27s）；terminal-view / termux-shared 无单测源。
- Phase 0（T0.1–T0.4）至此全部完成，M-parse/M-frame/M-draw 度量基线就绪。

**待办（下一步）**
- M1 阶段：T1.1 Buffer 侧脏行/滚动增量跟踪（建议与后续 T0.3 探针配合做 before/after）。

### 2026-09-06 · git `feb37734` · T1.1 ✅（待审查）

**改动（emulator 侧纯增量，不改行为）**
- `terminal-emulator/TerminalBuffer.java`：
  - 新增累积字段 `mScreenScrollAccum`（整屏上滚行数）/ `mScreenFullDirty` / `mScreenDirtyRows[]`（非滚动脏行，外屏坐标），`mScreenResizing` 在 resize 期间屏蔽中间态记录；
  - 写屏入口打点：`setChar`、`blockSet`（整屏覆盖→full）、`blockCopy`（源/目标行域）、`setLineWrap/clearLineWrap`、`setOrClearEffect`、`scrollDownOneLine`（全屏→scroll 累积；非全屏滚动区→该区域置脏）；
  - resize：入口置 resizing、出口 `resetScreenChanges(true)`（强制全量重绘）；
  - API：`ScreenChanges{scrollRows,fullRedraw}`、`getScreenChanges()`、`isScreenRowDirty(row)`、`clearScreenChanges()`、`markAllScreenDirty()`。
- `terminal-emulator/TerminalEmulator.java`：进入/退出 alt 缓冲（DECSET 47/1047/1049）切换 `mScreen` 后调用 `markAllScreenDirty()`（新激活缓冲可能整体不同于上一帧）。
- 新增测试 `ScreenChangeTrackingTest.java`（7 用例，JUnit3）：原位改写仅该行脏；底部换行→scroll delta=1；ED2 clear→full；resize→full；alt 屏进出→full；非全屏滚动区→脏行集且无 scroll delta；clear 复位。

**验证**
- 新测试 7/7 PASS；`./gradlew :terminal-emulator:test` 全量 **153 tests / 0 failures**（无回归）。

**说明**
- 采用“累积→一次性消费/清除”模型（对齐现有 `mScrollCounter` 用法）；消费方（T1.2/T2.x）在每次 `onScreenUpdated` 读取并 `clearScreenChanges()`。滚动区+历史语义与现状一致（未改 `mScrollCounter` 行为，选择框/自动滚动逻辑不动）。
- 未集成到 View（属 T1.2 范围），故运行时零行为变化。

**待办（下一步）**
- T1.2：View 侧脏区 invalidate——`TerminalView.onScreenUpdated()` 消费元数据：scroll delta>0 或 full → 整屏；否则仅脏行像素矩形 → `invalidate(l,t,r,b)`；光标闪烁/无内容时不整屏重绘。

### 2026-09-06 · git `183313e7` · T1.2 ✅（待审查）

**改动（仅 `terminal-view/TerminalView.java`）**
- `onScreenUpdated(boolean)`：记下进入时 `oldTopRow`；消费 `TerminalBuffer.ScreenChanges`（读后 `clearScreenChanges()`）；
  - **整屏**：`fullRedraw`、`scrollRows>0`、或 `mTopRow` 变化（滚动/选区/自动滚动偏移）→ `invalidate()`；
  - **局部**：否则收集脏行 min/max，并并集“旧/新光标行”（光标移动而无 cell 变化也要重绘），换算像素 → `invalidate(0, top, getWidth(), bottom)`；
  - **无任何变化** → 兜底整屏（保持与旧行为等价，避免漏重绘）。
- 记录 `mLastRenderedCursorRow` 供下次移动光标时擦除旧光标。
- 新增 import `TerminalBuffer`。

**验证**
- `./gradlew test` 全绿（1s/2s 编译+测试均 BUILD SUCCESSFUL）。
- 真机（Mi10）重装 debug 包：打字改写 prompt 行、`echo`/`seq` 滚动输出、光标位置均渲染正常（截图核对无残影/漏行）。

**说明**
- 纯 Android 局部 invalidate 减少的是**光栅化**面积；逐行构造绘制指令的开销要等 T2.1 行缓存才能真正跳过。本步与 T1.1 合起来保证“只有变化的行被重新上屏”。
- 光标闪烁路径（T1.3）尚未改，仍整屏。

**待办（下一步）**
- T1.3：光标闪烁只重绘光标 cell 邻域（blink Runnable 改为小矩形 invalidate；光标不可见跳过）。

### 2026-09-06 · git `TBD`(未提交) · T1.3 ✅（待审查）

**改动（仅 `terminal-view/TerminalView.java`）**
- 光标 blinker Runnable：`invalidate()` 改为 `invalidateCursorRegion()`；
- 新增 `invalidateCursorRegion()`：cursor 未启用（`tput civis` 等）直接返回；否则仅重绘光标所在行的 2 列宽 × 行高小矩形（覆盖宽字符/块状光标）。

**验证**
- 编译 + `./gradlew test` 全绿；
- 真机（Mi10）：在 `termux.properties` 临时追加 `terminal-cursor-blink-rate = 500` 重建安装，光标正常渲染、无崩溃、无残影；测试后已恢复用户配置文件（仅删除追加行）。

**说明**
- 默认 `terminal-cursor-blink-rate = 0`（闪烁关闭），本改动只影响显式开启闪烁的用户；
- 闪烁开启时每 tick 从“整屏重绘”降为“单格小矩形”，消除无输出场景下最大的无效重绘源之一。

**待办（下一步）**
- T1.4：重绘去抖/合并（一帧内多次 onScreenUpdated → 至多一次渲染调度）。
- T1.5：无障碍文本节流。

### 2026-09-06 · git `TBD`(未提交) · T1.4 ✅（待审查）

**范围修正说明**
- 原计划“Choreographer/post 一次调度合并”**冗余**：Android `invalidate()` 本身按帧合并脏区（一帧多次 invalidate 只重绘一次并集）。真正的浪费是“无变化仍整屏兜底重绘”（T1.2 保守路径）与光标移动造成的整屏重绘。故 T1.4 落地为更对症的两件事：

**改动（仅 `terminal-view/TerminalView.java`）**
- 内容无变化且光标未动 → **完全不 invalidate**（也不再生成 a11y 文本）；
- 内容无变化、仅光标移动/显隐变化 → 只重绘**新旧光标两格**（`invalidateCellRect`），并记录 `mLastRenderedCursorRow/Col/可见`；
- 有脏行 → 行带区域重绘（光标新旧行并入）；
- 整屏滚动/full/`mTopRow` 变化 → 仍整屏。

**验证**
- 编译 + 全量测试绿；真机（Mi10）输入 abc + 方向键 + 插入覆盖写 + 方向键：`aXbc` 渲染正确、无残影/漏行。

**说明**
- 纯光标移动不再触碰 a11y 文本（文本未变）。

**待办（下一步）**
- T1.5：无障碍文本节流（`setContentDescription` 仅在内容实质变化时、限频生成）。

### 2026-09-06 · git `bbd27ff0` · T2.1 ✅（待审查；实现为 damage-list 而非 HW 位图缓存）

**实现取舍**
- 原计划是“HW 位图行缓存（+LRU+字号失效+滚动旋转）”。评估后采用更简单的**按需重绘行集合**：局部 invalidate 本就让“未失效行像素保留在上屏”，因此无需缓存位图，只要 **render() 只重画本帧需要变化的行** 即可获得同样收益且免显存/失效管理。
- 依据（T1.2/1.4 实测）：局部 invalidate 本身不省 CPU，因为 render() 仍遍历全部行——瓶颈在“每帧构造全屏绘制指令”。

**改动**
- `terminal-view/TerminalRenderer.java`：`render(..., topRow, firstRow, rowCount, sel…)`，像素 y 由行号直接算（`base+(v+1)*spacing`），可只画任意连续行区间；perf 计数按实画行数。
- `terminal-view/TerminalView.java`：
  - 新增损伤行集合 `mPartialFirstRow/mPartialLastRow`：`requestRowsRedraw(first,end)`（合并区间+窄 invalidate）；`requestFullRedraw()`（清集合+整屏 invalidate）；
  - `onDraw`：有损伤集合→只画这些行并消费；否则整屏兜底（系统/历史路径自动正确）；
  - `onScreenUpdated` 局部路径→`requestRowsRedraw`，整屏路径→`requestFullRedraw`；纯光标移动/blink→单行 `requestRowsRedraw`；其余全量 invalidate 点全部改为 `requestFullRedraw`。
- 修复一处盲替换把 `requestFullRedraw()` 内部 `invalidate()` 误换为自递归（已改回）。

**实测（Mi10，同负载 8s；BASE=183313e7）**

| 场景 | 指标 | BASE | NEW(T1.4) | DAMAGE(T2.1) |
|---|---|---|---|---|
| 原地彩色重绘 | p50/p95 | 8/13ms | 8/14ms | **5/8ms** |
| 原地彩色重绘 | janky | 0.60% | 1.19% | **0%** |
| 纯光标移动 | legacy janky | 10.56% | 8.52% | **0%** |

- 全量 `./gradlew test` 绿；真机视觉（CJK、彩色、滚动输出、光标、erase 后无残留）正常。

**边界与待办**
- 仍为“整屏/全量滚动时画全部行”（无像素缓存，避免复杂度）；配合 T1.1 的 scrollRows>0 触发整屏路径。
- 大流量洪泛仍以解析为瓶颈（T3.x），本步不解决。
- 后续可选：把整屏滚动场景升级为“行位图+滚动平移”再压一档（若 M-frame 仍需优化）。

**下一步建议**：T2.2 非 ASCII 测量缓存（宽字符每帧 measureText 热点），或直接 T3.1 时间片解析（洪泛卡死的根因）。

### 2026-09-06 · git `133cefe0` · T2.2 ✅ / T2.3 ✗（试验后否决）

**T2.2（提交 `133cefe0`）**
- `TerminalRenderer` 新增 `mCodePointWidthCache`（`SparseArray<Float>`，上限 16384 条满则整体清空）：
  每个非 ASCII codePoint 的 `Paint.measureText()` 宽度只在首次测量（per-renderer 生命周期，
  字号/字型变化会重建 renderer，天然失效）。每次 draw 不再为每个宽字符发 native measure。
- 实测（Mi10，同一 CJK 洪泛循环负载，8s 窗口）：

| 版本 | p50 | p90 | p95 | legacy janky |
|---|---|---|---|---|
| BASE | 32ms | 53 | 57 | 100% |
| T2.2 | **24ms** | 46 | 53 | 98.7% |

- 纯翻页 CJK 历史（内容不变、整屏重画、无解析干扰）：p50 5-6ms、legacy janky ~1.7-4.4%（无回归）。

**T2.3（试验后否决，整体回退）**
- 假设：重复 redraw 时 `canvas.drawTextRun()` 的 native shaping 是热点，缓存可消除。
- 实现：API 33+ `TextRunShaper.shapeTextRun→PositionedGlyphs` + `canvas.drawGlyphs(Font)`，
  按 run 内容缓存字形；effect-free run 走新路径（Paint 强制 clean 使 key 仅依赖文本），
  bold/italic/underline/strike 仍走 legacy；多 fallback font 按 font 分组绘制；低于 API 33 全走 legacy。
  视觉验证通过（ASCII/CJK/emoji/彩色/粗斜下划线/inverse/dim 均正常，drawGlyphs 分 font 组偏移语义正确）。
- **实测否决**（同场景、帧数相近可比：491 vs 501）：翻页 CJK 历史 p50 **6→10ms**、legacy janky
  **4.4%→25.3%**，倒退约 2 倍。
- 根因认知：**HWUI 对 drawTextRun 的重复 shaping 已有原生缓存**（Minikin/HWUI 文本缓存），
  重复整形并非热点；Java 侧 `drawGlyphs` 路径反而绕开了 HWUI 文本绘制快速路径（glyph 上传等），净变慢。
  T2.2 之前的 measureText 才是真热点（其 native 测量路径无等价缓存）。
- 结论：保持 `drawTextRun` 原路径；不引入 Java 层字形缓存/位图行缓存（T2.1 已同样论证过复杂度不值）。

**剩余热点（Phase 3 仍成立）**：洪泛遗留 janky 绝大部分来自主线程解析（T3 时间片/分批）；
彩色原地重绘与翻页场景经 T2.1/T2.2 后已非瓶颈。

**下一步建议**：T3.1 —— 解析时间片化（读 ByteQueue 上限+按帧预算分批进 TerminalEmulator.append()），
消除“cat 大文件/CJK 洪泛把主线程饿死”的卡死根因。

### 2026-09-06 · T3.4 已实施并实测否决（整体回退，保留 T3.1 为 Phase 3 终点）

**实现内容（已全部回退，git checkout 到 `7c8d3866`）**
- `TerminalSession`：读线程不再发 MSG_NEW_INPUT；新增 `TermSessionInputParser` worker（emulator monitor 内逐片 append，批次内时间预算+让出）；回调（onTextChanged 合并单在途/BELL/title/colors/clipboard）marshal 主线程；退出路径 close→join worker→exit banner。
- 主线程读点全部纳入同一 monitor（`synchronized(emulator)`）：`TerminalView.onDraw/onScreenUpdated/getText/requestRowsRedraw/blink`、`TextSelectionCursorController`（选区词扩展/拖拽/取文）、app（URL 点击取词、颜色背景、粘贴）、`ShellUtils` 取全转录。
- `ByteQueue`：close 后仍排空剩余再返回 -1。
- 编译与单测全绿；echo/滚动/CJK 渲染/冒烟均正常，无崩溃无 ANR。

**实测否决（Mi10，同一持续 CJK 洪泛 8s，autostart 同法）**

| 版本 | 帧/8s | p50 | legacy janky |
|---|---|---|---|
| T3.1（已提交，主线程时间片） | **346** | **13ms** | 86.4% |
| T3.4 worker+render 同锁 | 131 | 19ms | 99.2% |
| +批次让出/通知合并/参数调优 | 76-103 | 23ms | 99-100% |
| T3.4d 去掉 render 锁（仅诊断） | 189 | 17ms | 97.4% |

**原因**：① renderer 每帧整段持锁画 26 行 vs worker 每片持锁 → 单 monitor 上互相排队（去 render 锁帧率即回升，证明锁排队为主因）；② 即便去锁仍 < T3.1（189 vs 346）→ 多线程在同一可变 buffer 上抢占/缓存抖动抵消"解析离线"理论收益；③ 想真正赢需"锁内快照复制 + 锁外渲染"式解耦（≈重写 renderer 取数路径，3-5d+，回归面大）。

**决策门命中**：计划规定"T3.1 后 UI 响应达标则暂缓/不做 T3.4"。实测 T3.1 已满足真实场景（输入不饿死、无 ANR），故按 A 方案回退收尾。

**Phase 3 最终状态**：解析留在主线程，以 T3.1 时间片批处理收束。

### 2026-09-06 · git `TBD`（清理提交后补 sha） · Debug 代码清理 + 重新评估

**改动（移除本轮性能工作引入的全部 debug/探针代码，恢复生产路径干净）**
- 撤销未提交的冻结诊断（FrZDbg，临时）：`TerminalSession` 1Hz `MSG_WATCHDOG`/stall 日志、读线程 EOF/died 日志、时间戳字段；`TerminalView` view-stall `postDelayed` 检查；`ByteQueue.usedBytes()`。（备份：`/tmp/freeze_diag_uncommitted.patch`）
- 移除已提交的 M-draw 探针（T0.3/`feb37734`）：`TerminalRenderer.mPerfRowsDrawn/RunsDrawn/MeasureCalls` 及自增、`TerminalView` PERF 日志开关/`maybeLogPerfFrame()`、`TermuxTerminalViewClient.onStart()` piggyback。
- 验证：`./gradlew test` 全绿；M-parse 复测与清理前一致（emulator 未受影响）——plain/ansi/tui ~150MB/s、**cjk 3.97MB/s（仍 ~38× 慢于 ASCII）**、mixed ~116MB/s。
- 说明：M-draw 观测可随时从 git `feb37734` 恢复；`perf/results.csv` 历史数值保留。

**重新评估结论（为何保留优化、下一步做什么）**
- 保留 T1.1–T3.1 全部优化（非 debug，是真实行为/性能改动）；本次只清探针。
- 剩余最大热点（结合复测数据 + 代码定位）：**CJK 解析慢路径**。根因在 `TerminalRow.setChar`：行一旦含宽字符/组合字符即永久离开 fast path，之后**每个字符**都从行首做 `wideDisplayCharacterStartingAt` + `findStartOfColumn` 全行扫描（O(col)），顺序填充退化为 O(n²)/行 → M-parse:cjk 3.97MB/s、洪泛时主线程 86% janky 的元凶。
- 建议下一步：TerminalRow 行内"列→字符边界"惰性缓存（懒分配 int[] + 前缀水位失效），顺序写摊还 O(1)，语义零变化（扫描仍为同一纯计算）；DoD 用 M-parse:cjk（目标 ≥20–40MB/s）+ 全量 emulator 测试。该改动同时惠及 resize/alt-screen（T4.3）中带 CJK 的行拷贝。
- 低风险顺带项：T3.3（读线程消息风暴收敛，flood 时 looper 唤醒下降）；T1.5（a11y 文本节流）。T4.1（写路径阻塞）独立保留。

### 2026-09-06 · git `8383299b` · Debug 清理核验 + 重评估 + TerminalRow 边界缓存（T4.2a）/ T3.3 / T1.5

**Debug 代码核验（对应本次请求「把 debug 代码不启用/删掉」）**
- 已提交的 M-draw 探针（T0.3）与 FrZDbg 冻结诊断已在上一次提交 `a59c1329` 全部移除；本次复核生产源码：`TerminalRenderer`/`TerminalView`/`TerminalSession`/`ByteQueue` 已无任何 `mPerf*`/`perf.*`/watchdog 残留，terminal-view 模块除历史遗留的 `PopupWindowCompatGingerbread` 一处 `android.util.Log` import 外无新增日志。
- 仅保留测试门控的度量工具（`ParseThroughputBench` 由 `termux.perf.parse` 系统属性门控、常规 `./gradlew test` 自动 SKIP）与 `perf/` 脚本——不属于生产 debug 代码。
- 移除工作树临时冻结诊断的备份 `/tmp/freeze_diag_uncommitted.patch` 依然有效（未引入）。

**重评估结论（先量化，后动手）**
- 复测基线（本机 JVM，git a59c1329）：plain/ansi/tui ~131–150 MB/s、cjk 3.87 MB/s（271ms/MiB）、mixed 116 MB/s。
- 用一次性的 JVM 堆栈采样探针（`ScratchSamplingProfiler`，用完已删）确认 CJK 慢路径热点：`TerminalRow.findStartOfColumn` 1472 + `wideDisplayCharacterStartingAt` 1165 样本（合计约 60%），均为每字符 O(row) 全行扫描 → 顺序宽字符写入退化为 O(n²)/行。与计划文末的定位一致。

**T4.2a · `TerminalRow` 列→字符边界惰性缓存（本次核心）**
- 改动（仅 `terminal-emulator/TerminalRow.java`）：
  - 新增惰性 `short[] mColumnBoundaryCache` + 水位 `mColumnBoundaryCacheValid`：cache[col] = 覆盖该列 cell 的首字符下标；仅行含宽/代理字符时分配（纯 ASCII 行边界=列号）；
  - `findStartOfColumn`：命中即 O(1)；未命中从水位所在 cell 继续扫描（摊还 O(1)），并回填沿途条目；`column > mColumns` 的选区边界情形保留原未缓存扫描（行为逐位一致）；
  - `wideDisplayCharacterStartingAt`：改为 cache 两读 + `WcWidth` 查宽（原为 O(n) 全行扫描）；
  - `setChar` 慢路径：每次写前先把 `wasExtraColForWideChar`/`overwritingWideCharInNextColumn` 两个预读提升到递归之前（递归不改变其答案）；所有 `findStartOfColumn` 读完后、首次改写前把水位截到首个被改列（递归 setChar 自行截自己的列）；`clear()` 复位水位。
- 踩坑记录（已修复，均有测试佐证）：
  1) 失效点最初放在读之前，读路径会把水位重新推过改动列 → 陈旧条目幸存；改为「读完再截」。
  2) 水位截断可能落在宽字符第二半（非 cell 边界），恢复扫描会越过目标列返回错误 cell；修复：恢复时先定位含 valid-1 的 cell，若目标落在其中直接返回，并把水位到 cell 末尾的间隙一并回填。
  3) 该 bug 不仅影响查找，还会让 `setChar` 读错 `oldStartOfColumnIndex` 导致**行内容本身被写坏**（差分测试抓到）。
- 回归测试：新增 `TerminalRowBoundaryCacheTest`（3 用例）：顺序 CJK 填充/覆盖、2 万次随机宽窄/代理/组合字符差分对比（每步与原未缓存参考实现对拍 + 周期性 clear）、copyInterval 自拷贝/异拷贝随机差分。全量 `./gradlew test` 绿（155 tests + 1 skip）。
- **指标（M-parse:cjk，同机同语料，JVM）**：3.87 → **30.8 MB/s（271ms → 34ms/MiB，≈8×）**；plain/ansi/tui/mixed 无回退。堆栈复采样：已无单一热点（`WcWidth.width` 两次二分查找、宽字符写入的尾部压缩 memmove 为固有成本，收益递减）。

**T3.3 · 读线程消息风暴收敛（本次顺手项）**
- `TerminalSession` 读线程：仅当队列中没有在途 `MSG_NEW_INPUT`（`hasMessages`）才发送，避免 flood 时每 4KiB 一次空唤醒；handler 批处理（T3.1）会自行 drain 后延迟重投，语义不变。

**T1.5 · 无障碍文本节流（本次顺手项，尾沿防抖）**
- `TerminalView.onScreenUpdated`：`setContentDescription(getText())` 改为 250ms 尾沿防抖（`removeCallbacks+postDelayed`）：输出持续时最多 ~4 次/秒整屏重建，输出停止后最后一次内容仍会被描述（不会丢终态）；纯光标移动路径原本就不触发。

**剩余可优化点（重评估后排序）**
1. **真机复测 M-frame**：T3.1+边界缓存后，flood/medium 场景应明显出帧（待真机跑 `perf/measure_gfx.sh` 落盘）；CJK flood 的 86.4% legacy janky 预期大幅下降（解析提速 ~8×）。
2. T4.3 resize 慢路径（列数变化逐字符重建，可改按 run 拷贝复用；旋转/分屏场景收益集中）。
3. T4.1 UI 线程永不阻塞写子进程（`ByteQueue.write` 的 `wait()` 路径；需确认粘贴语义后做）。
4. T4.4 `getWordAtLocation` 单遍扫描重写（点 URL 卡顿）。
5. T4.7 光标心跳生命周期加固（onDetachedFromWindow 补 stop）。
6. 其余（T4.5 ExtraKeys、T4.6 会话列表、T4.8 后台降载）收益小或需产品决策，维持不默认做。

### 2026-09-06 · git `d93d7f3d` · T4.1 ✅（非阻塞写入）

**改动**
- `ByteQueue.java`：
  - 新增 `writeNonBlocking(byte[], int, int)`：先写入环形缓冲区（有空间时），剩余数据溢出到有界（256 KiB）附属缓冲区；读线程 `read()` 在排空环形缓冲区后自动将溢出数据移入环形缓冲区（`drainOverflow()`），对读侧完全透明。
  - 溢出缓冲区满时丢弃最旧数据（防止子进程卡死时无界内存增长），同时保持 UI 线程永不阻塞。
  - 新增 `ByteQueueTest.testWriteNonBlocking`（15 字节写入 10 字节环形缓冲区，分块读回验证）和 `testWriteNonBlockingClosed`。
- `TerminalSession.java`：
  - `mTerminalToProcessIOQueue` 从 4 KiB 扩容到 64 KiB（与读侧队列一致）。
  - `write()` 改为调用 `writeNonBlocking()`。
- 验证：全量测试绿（155 tests）；真机冒烟：Termux 启动正常，`echo`/`seq` 输入正常，UI 响应。

**收益**
- 消除主线程在粘贴大文本时因子进程不读 stdin 导致的 `wait()` 阻塞 → 防止 ANR。
- 300+ KiB 总缓冲（64 KiB 环形 + 256 KiB 溢出）覆盖绝大多数粘贴场景；超过时丢弃最旧数据，UI 不卡。

**待办**
- T4.3 resize 慢路径优化（旋转/分屏卡顿）。
- WcWidth 解析端宽度缓存（CJK 解析再提速 10-20%，但渲染已是瓶颈）。
- 真机复测 M-frame 已在上一次提交完成（24ecc361）。

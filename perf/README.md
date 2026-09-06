# perf/ - 性能度量工具集

本目录是 `PERFORMANCE_OPTIMIZATION_PLAN.md` 的配套度量设施，供 M-parse / M-frame 等指标采集与追踪。

## 指标与采集方式

| 指标 | 含义 | 工具 |
|------|------|------|
| M-parse | emulator 解析吞吐（bytes/s） | `bench_parse.sh`（JVM，无需设备） |
| M-frame | 帧耗时/Janky% | `measure_gfx.sh`（真机，adb） |
| M-draw | 每帧 drawTextRun/measureText 等计数 | debug 探针（T0.3，未实施） |

## 结果记录

所有指标以 CSV 行形式追加到 `results.csv`，列结构：

```
timestamp,git_sha,device,phase,task_id,metric,value,unit,notes
```

- `device`：JVM 基准默认 `jvm-<hostname>`，可用 `TERMUX_DEVICE_LABEL` 覆盖；
- 真机测量请固定填写设备标识（如 `pixel6-android14`），以便跨设备对比。

## 语料（corpora）

语料由基准代码内确定性生成（固定种子，无随机抖动），不落大文件，见
`ParseThroughputBench`（terminal-emulator/src/test/java/com/termux/terminal/perf/）：

| corpus | 内容 | 模拟场景 |
|--------|------|----------|
| plain | 纯 ASCII 文本行 | `cat` 大文件 |
| ansi  | SGR 彩色日志行 | 编译/日志输出 |
| tui   | 全屏重绘 + 绝对寻址 + 周期性整屏清除 | tmux/zellij 重绘 |
| cjk   | 中文宽字符 + emoji 代理对 | 非 ASCII 内容 |
| mixed | 彩色日志 + `\r` 原地进度条 | 安装/构建类工具 |

每个语料默认 1 MiB，在独立新建的 80×24、2000 行 transcript 的 emulator 上按
64 KiB 分片喂入（与生产批处理一致）。报告 5 次测量（2 次预热）的中位耗时。

## 使用

```bash
# JVM 解析吞吐（首次会编译，之后增量很快）
perf/bench_parse.sh

# 真机帧测量（需要 adb 已连接且已安装 debug 版 Termux，见脚本内提示）
perf/measure_gfx.sh
```

## 注意事项

- JVM 数字受 CPU 频率/负载影响，做 before/after 对比时尽量同机、多次采样取趋势；
- `results.csv` 每行都有 `git_sha` 与时间戳，提交代码时请一并提交新的基准行，
  以便日后核对“哪个 commit 引入了吞吐变化”。

## 真机自动化要点（T0.2 实测踩坑）

- `adb shell input text` 遇空格会把后面的参数丢掉：空格要写成 `%s`，
  如 `seq%s5000000`、`echo%sT0OK`。
- 冷启动（force-stop 后）需先 `adb shell input tap <x> <y>` 点击终端区获得焦点，
  输入才会到达 PTY。
- 无法注入含引号/管道/分号的复杂命令——压力命令请用无空格单词（`seq N`、`yes`）。
- 大输出期间 `dumpsys gfxinfo` 可能返回 `Failure while dumping the app`：
  这是 app 主线程被输出饿死的直接信号，`measure_gfx.sh` 会记 `unserviceable=1`，
  不要当作脚本故障。

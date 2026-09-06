package com.termux.terminal.perf;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * JVM micro-benchmark measuring {@link TerminalEmulator#append(byte[], int)} parse throughput.
 *
 * <p>This is the source of the {@code M-parse} metric defined in
 * {@code PERFORMANCE_OPTIMIZATION_PLAN.md}. It feeds deterministic, representative
 * corpora (plain text / ANSI logs / full-screen TUI repaints / CJK / progress-bar
 * mixed output) through the emulator, exactly like the production input path, and
 * reports median bytes/second.
 *
 * <p>It is gated by the {@code termux.perf.parse} system property and must be
 * invoked via {@code perf/bench_parse.sh}; it never runs during a normal
 * {@code ./gradlew test} (skipped through an assumption).
 */
public final class ParseThroughputBench {

    /** Skip unless explicitly requested, so normal test runs stay fast. */
    private static boolean parseEnabled() {
        return "true".equals(System.getProperty("termux.perf.parse"));
    }

    @Test
    public void runParseThroughputBench() throws Exception {
        Assume.assumeTrue("Run with -Ptermux.perf.parse=true (see perf/bench_parse.sh)", parseEnabled());

        String outPath = System.getProperty("termux.perf.out");
        if (outPath == null || outPath.isEmpty())
            throw new IllegalStateException("Missing -Ptermux.perf.out=<abs path to perf/results.csv>");

        // Configuration (mirrors a typical device terminal).
        final int columns = 80;
        final int rows = 24;
        // null transcript rows => emulator default (2000), matching production.
        final Integer transcriptRows = null;

        // Each corpus targets ~1 MiB so single-run wall time stays small while
        // remaining representative of sustained output.
        final int targetBytes = 1024 * 1024;

        final List<Corpus> corpora = new ArrayList<>();
        corpora.add(new Corpus("plain", trimTo(targetBytes, makePlainCorpus(targetBytes))));
        corpora.add(new Corpus("ansi", trimTo(targetBytes, makeAnsiCorpus(targetBytes))));
        corpora.add(new Corpus("tui", trimTo(targetBytes, makeTuiCorpus(targetBytes, columns, rows))));
        corpora.add(new Corpus("cjk", trimTo(targetBytes, makeCjkCorpus(targetBytes))));
        corpora.add(new Corpus("mixed", trimTo(targetBytes, makeMixedCorpus(targetBytes))));

        final int warmupRounds = 2;
        final int measureRounds = 5;

        StringBuilder summary = new StringBuilder();
        PrintWriter csv = new PrintWriter(new FileWriter(new File(outPath), true));
        try {
            for (Corpus corpus : corpora) {
                // Warmup so JIT does not pollute the measured rounds.
                for (int i = 0; i < warmupRounds; i++)
                    parseOnce(corpus.bytes, columns, rows, transcriptRows);

                List<Long> timingsMs = new ArrayList<>(measureRounds);
                for (int i = 0; i < measureRounds; i++)
                    timingsMs.add(parseOnce(corpus.bytes, columns, rows, transcriptRows));
                Collections.sort(timingsMs);
                long medianMs = timingsMs.get(timingsMs.size() / 2);

                long bytesPerSecond = medianMs == 0 ? 0 : (long) (corpus.bytes.length * 1000.0 / medianMs);

                summary.append(String.format(
                    "%-6s %10d bytes, median %6d ms => %12d bytes/s%n",
                    corpus.name, corpus.bytes.length, medianMs, bytesPerSecond));

                writeCsvRow(csv, corpus.name, corpus.bytes.length, medianMs, bytesPerSecond);
            }
        } finally {
            csv.flush();
            csv.close();
        }

        // Machine readable summary is printed so `perf/bench_parse.sh` can surface it.
        System.out.println("=== ParseThroughputBench summary ===");
        System.out.print(summary);
        System.out.println("rows appended to " + outPath);
    }

    private static long parseOnce(byte[] bytes, int columns, int rows, Integer transcriptRows) {
        TerminalEmulator emulator = new TerminalEmulator(
            new NoOpTerminalOutput(), columns, rows, 13, 15, transcriptRows, null);

        // Chunked feeding mirrors production batching (TerminalSession handler drains
        // the pty queue in <=64 KiB slices). `append` takes (buffer, length) with no
        // offset, so slices are copied into a reusable chunk buffer.
        final int chunkSize = 64 * 1024;
        final byte[] chunk = new byte[chunkSize];
        long startNanos = System.nanoTime();
        for (int offset = 0; offset < bytes.length; offset += chunkSize) {
            int length = Math.min(chunkSize, bytes.length - offset);
            System.arraycopy(bytes, offset, chunk, 0, length);
            emulator.append(chunk, length);
        }
        long elapsedNanos = System.nanoTime() - startNanos;
        return Math.max(1, elapsedNanos / 1_000_000);
    }

    // ---------------------------------------------------------------------
    // Deterministic corpus generation
    // ---------------------------------------------------------------------

    private static byte[] trimTo(int targetBytes, byte[] bytes) {
        return bytes.length <= targetBytes ? bytes : java.util.Arrays.copyOf(bytes, targetBytes);
    }

    private static final class Corpus {
        final String name;
        final byte[] bytes;

        Corpus(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    private static void writeCsvRow(PrintWriter csv, String corpus, long bytes, long ms, long bytesPerSecond) {
        String ts = Instant.now().toString();
        String git = String.valueOf(System.getProperty("termux.perf.git", ""));
        String device = String.valueOf(System.getProperty("termux.perf.device", "jvm"));
        // Note: keep single line, no raw commas inside notes to keep the CSV trivial to parse.
        String notes = String.format("ms=%d bytes=%d cols=80 rows=24 transcript=2000 jdk=%s os=%s",
            ms, bytes, System.getProperty("java.version", "?"), System.getProperty("os.name", "?"));
        csv.printf("%s,%s,%s,T0.1,M-parse:%s,%d,bytes/s,%s%n",
            ts, git, device, corpus, bytesPerSecond, notes);
    }

    // --- plain: ASCII text lines, 78 columns + newline ----------------------

    private static byte[] makePlainCorpus(int targetBytes) {
        final int lineBytes = 80; // 78 chars + \r\n
        int lines = targetBytes / lineBytes;
        StringBuilder sb = new StringBuilder(lines * lineBytes);
        for (int i = 0; i < lines; i++) {
            String filler = "The quick brown fox jumps over the lazy dog 0123456789 abcdefghijklmnopqrstuvwxyz";
            String num = String.format("%07d", i);
            String base = filler + " " + num;
            // Exactly 78 printable chars per line.
            int keep = Math.min(78, base.length());
            sb.append(base, 0, keep);
            for (int pad = keep; pad < 78; pad++) sb.append(' ');
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // --- ansi: log lines with SGR colors, typical of build/log output -------

    private static byte[] makeAnsiCorpus(int targetBytes) {
        Random random = new Random(42);
        StringBuilder sb = new StringBuilder(targetBytes);
        String[] levels = {"INFO", "DEBUG", "WARN", "ERROR"};
        String[] colors = {"30", "31", "32", "33", "34", "35", "36", "37", "90", "91", "92", "93"};
        while (sb.length() < targetBytes) {
            String level = levels[random.nextInt(levels.length)];
            int color = Integer.parseInt(colors[random.nextInt(colors.length)]);
            sb.append("\u001b[").append(color).append("m")
              .append(String.format("2026-02-14 %02d:%02d:%02d.%03d", random.nextInt(24),
                  random.nextInt(60), random.nextInt(60), random.nextInt(1000)))
              .append(" ").append(level).append(" \u001b[0m")
              .append("Task ").append(random.nextInt(10000))
              .append(" processing payload chunk size=8192 retry=").append(random.nextInt(3))
              .append(" completed in ").append(random.nextInt(500)).append("ms")
              .append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // --- tui: full-screen repaints with absolute cursor addressing ----------
    // Emulates what tmux/zellij and similar send when repainting a screen.

    private static byte[] makeTuiCorpus(int targetBytes, int columns, int rows) {
        Random random = new Random(7);
        StringBuilder sb = new StringBuilder(targetBytes);
        int frame = 0;
        while (sb.length() < targetBytes) {
            sb.append("\u001b[H"); // cursor home
            for (int r = 0; r < rows; r++) {
                if (r != 0) sb.append("\u001b[").append(r + 1).append(";1H"); // absolute position
                int fg = 30 + random.nextInt(8);
                int bg = 40 + random.nextInt(8);
                sb.append("\u001b[").append(fg).append(";").append(bg).append("m");
                for (int c = 0; c < columns - 1; c++)
                    sb.append((char) ('!' + random.nextInt(90)));
                sb.append("\u001b[K"); // erase to end of line
            }
            sb.append("\u001b[0m");
            if (frame % 10 == 0) sb.append("\u001b[2J"); // occasional full clear
            frame++;
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // --- cjk: CJK wide characters + emoji (surrogate pairs) -----------------

    private static byte[] makeCjkCorpus(int targetBytes) {
        Random random = new Random(2026);
        String[] words = {
            "终端模拟器性能基准测试", "渲染管线优化计划", "脏行跟踪与行缓存",
            "缓存一致性", "中央处理器占用", "内存占用率", "用户体验",
            "字符宽度", "等宽字体", "滚动历史记录", "转义序列解析器",
            "单元测试回归", "真彩颜色支持", "组合字符", "宽字符处理",
            "Progress", "OpenGL", "Vulkan", "Canvas", "hwui"
        };
        String[] emoji = {"\uD83D\uDE80", "\uD83C\uDF89", "\u2705", "\u26A1"};
        StringBuilder sb = new StringBuilder(targetBytes);
        while (sb.length() < targetBytes) {
            for (int i = 0; i < 20 && sb.length() < targetBytes; i++) {
                sb.append(words[random.nextInt(words.length)]);
                sb.append(' ');
            }
            if (random.nextInt(10) == 0) sb.append(emoji[random.nextInt(emoji.length)]);
            sb.append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // --- mixed: busy logs + in-place progress bars using \r -----------------

    private static byte[] makeMixedCorpus(int targetBytes) {
        Random random = new Random(99);
        StringBuilder sb = new StringBuilder(targetBytes);
        while (sb.length() < targetBytes) {
            // A short burst of log lines followed by an in-place progress bar,
            // emulating tools like installers/builders that redraw a row.
            for (int l = 0; l < 8 && sb.length() < targetBytes; l++) {
                sb.append(String.format("2026-02-14 %02d:%02d:%02d", random.nextInt(24),
                    random.nextInt(60), random.nextInt(60)))
                  .append(random.nextBoolean() ? " \u001b[31mERROR\u001b[0m " : " \u001b[32mOK\u001b[0m ")
                  .append("step ").append(random.nextInt(1000)).append("\r\n");
            }
            for (int p = 0; p <= 100 && sb.length() < targetBytes; p += 5) {
                sb.append("\r[");
                int filled = p / 5;
                for (int i = 0; i < 20; i++) sb.append(i < filled ? '#' : '-');
                sb.append("] ").append(p).append('%');
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** No-op {@link TerminalOutput} so parsing never touches Android or threads. */
    private static final class NoOpTerminalOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) { }
        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
    }
}

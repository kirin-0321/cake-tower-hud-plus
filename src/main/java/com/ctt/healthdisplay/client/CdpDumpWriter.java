package com.ctt.healthdisplay.client;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * v7.0.6 · 客户端伤害探针调试日志文件 writer。
 *
 * <p>当 {@code ModConfig.clientDamageDebugChat=true} 时，{@link ClientDamageProbe}
 * 每秒把场上所有 {@code text_display} 实体的详细信息（id / bg / pos / text / nearest）
 * 写到 {@code <minecraft_root>/logs/ctt-cdp-dump.log}。
 *
 * <h2>设计要点</h2>
 * <ul>
 *   <li><b>追加模式</b>：每个 mod 启动 / 重新打开 debugChat 时，写一行 session 头分隔（不清空文件），
 *       便于对比多次会话；用户可以手动清空。</li>
 *   <li><b>懒打开</b>：第一次写入时才打开文件；用户从未开启 debug 时不创建文件。</li>
 *   <li><b>同步写</b>：每秒 1 次写入，性能可忽略；省 worker 线程开销。</li>
 *   <li><b>异常静默</b>：I/O 失败仅记录到 SLF4J，不打扰用户。</li>
 * </ul>
 */
public final class CdpDumpWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-cdp-dump");
    public static final CdpDumpWriter INSTANCE = new CdpDumpWriter();

    private static final Path LOG_PATH = FabricLoader.getInstance()
            .getGameDir().resolve("logs").resolve("ctt-cdp-dump.log");
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private BufferedWriter writer;
    private boolean opened;
    private boolean failed;

    private CdpDumpWriter() {}

    /**
     * 写一段 dump（一组相关行）。第一行通常是 session header，之后是 per-entity 行。
     *
     * @param lines 多行字符串，逐行写入；自动加换行符
     */
    public synchronized void writeLines(String... lines) {
        if (failed) return;
        if (!opened) {
            if (!openLazy()) return;
        }
        try {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
            writer.flush();
        } catch (IOException ex) {
            LOGGER.error("[CDP-dump] write failed, disabling further writes", ex);
            failed = true;
            closeQuietly();
        }
    }

    /** 写一个 session 分隔头（mod 启动 / debugChat 重新开启时调用）。 */
    public synchronized void writeSessionHeader(String reason) {
        String header = String.format(
                "===== CDP dump session %s · %s =====",
                LocalDateTime.now().format(TS_FMT), reason);
        writeLines("", header);
    }

    private boolean openLazy() {
        try {
            Path parent = LOG_PATH.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            writer = Files.newBufferedWriter(LOG_PATH, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            opened = true;
            LOGGER.info("[CDP-dump] log file ready: {}", LOG_PATH.toAbsolutePath());
            return true;
        } catch (IOException ex) {
            LOGGER.error("[CDP-dump] failed to open {}; disabling", LOG_PATH, ex);
            failed = true;
            return false;
        }
    }

    private void closeQuietly() {
        if (writer != null) {
            try { writer.close(); } catch (IOException ignored) {}
            writer = null;
        }
        opened = false;
    }

    /** 调用后下一次 {@link #writeLines} 会重新尝试打开文件。 */
    public synchronized void resetFailureFlag() {
        failed = false;
    }

    public Path getLogPath() {
        return LOG_PATH;
    }
}

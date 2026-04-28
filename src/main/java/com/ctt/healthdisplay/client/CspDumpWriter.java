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
 * v7.0.10 · 客户端关卡探针调试日志文件 writer。
 *
 * <p>当 {@code ModConfig.clientDamageDebugChat=true} 时，{@link ClientStageProbe}
 * 每秒把客户端可见的 scoreboard objective 列表 + 14 个 CTT fake-player 的 score +
 * 当前推断结果写到 {@code <minecraft_root>/logs/ctt-csp-dump.log}。
 *
 * <h2>用途</h2>
 * 服务端没装 mod 时若 HUD"位置"行始终显示"休息室"，可开启本 dump 排查：
 * <ul>
 *   <li>客户端有没有看到 {@code CTT} / {@code GameID} 这两个关键 objective</li>
 *   <li>{@code #Tier} / {@code #Floor} / {@code #Boss} 等 fake-player 的 score 是多少</li>
 *   <li>客户端可见 objective 总数 + 名字示例（用于判断地图哪些 objective 同步给了客户端）</li>
 * </ul>
 *
 * <p>设计要点与 {@link CdpDumpWriter} 完全对称（懒打开 / 追加模式 / 同步写 / 异常静默），
 * 仅文件名和 logger tag 不同。
 */
public final class CspDumpWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-csp-dump");
    public static final CspDumpWriter INSTANCE = new CspDumpWriter();

    private static final Path LOG_PATH = FabricLoader.getInstance()
            .getGameDir().resolve("logs").resolve("ctt-csp-dump.log");
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private BufferedWriter writer;
    private boolean opened;
    private boolean failed;

    private CspDumpWriter() {}

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
            LOGGER.error("[CSP-dump] write failed, disabling further writes", ex);
            failed = true;
            closeQuietly();
        }
    }

    public synchronized void writeSessionHeader(String reason) {
        String header = String.format(
                "===== CSP dump session %s · %s =====",
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
            LOGGER.info("[CSP-dump] log file ready: {}", LOG_PATH.toAbsolutePath());
            return true;
        } catch (IOException ex) {
            LOGGER.error("[CSP-dump] failed to open {}; disabling", LOG_PATH, ex);
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

    public synchronized void resetFailureFlag() {
        failed = false;
    }

    public Path getLogPath() {
        return LOG_PATH;
    }
}

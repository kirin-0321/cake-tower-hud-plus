package com.ctt.healthdisplay.health;

import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StatsData {

    private final List<Text> lines = new ArrayList<>();
    private boolean capturing = false;
    private boolean hideCapture = false;
    private boolean hasData = false;
    private boolean everReceived = false;
    private boolean gameNotStarted = false;
    private long captureStartTime;

    private int redHearts = 0;
    private int soulHearts = 0;
    private int blackHearts = 0;
    private int blueHearts = 0;
    private boolean hasHeartData = false;
    // 最后一次 capture 成功（"Game time" 收到）的挂钟时间，用作 fallback 数据过期判定。
    // 0 = 本 session 从未成功过；"无法触发"收到时也会归零。
    private long lastCaptureCompleteTimeMs = 0;

    private static final long CAPTURE_TIMEOUT_MS = 3000;
    private static final TextColor COLOR_RED = TextColor.fromFormatting(Formatting.RED);
    private static final TextColor COLOR_YELLOW = TextColor.fromFormatting(Formatting.YELLOW);
    private static final TextColor COLOR_BLACK = TextColor.fromFormatting(Formatting.BLACK);
    private static final TextColor COLOR_BLUE = TextColor.fromFormatting(Formatting.BLUE);
    private static final Pattern HEART_LINE_PATTERN = Pattern.compile("^(\\d+)\u2764$");

    public void markAutoTriggered() {
        hideCapture = true;
    }

    /**
     * Process a game message. Returns true if the message should be hidden from chat.
     */
    public boolean processMessage(Text message, boolean overlay) {
        if (overlay) return false;
        String raw = message.getString();

        if (capturing && System.currentTimeMillis() - captureStartTime > CAPTURE_TIMEOUT_MS) {
            capturing = false;
            hasData = !lines.isEmpty();
            if (hasData) everReceived = true;
        }

        if (raw.contains("\u65e0\u6cd5\u89e6\u53d1") || raw.contains("cannot trigger") || raw.contains("Can't trigger")) {
            gameNotStarted = true;
            hasData = false;
            lines.clear();
            capturing = false;
            // 游戏未开始：清掉残留心数据，避免 HealthData 用旧值显示出幻觉 HP 条。
            redHearts = 0;
            soulHearts = 0;
            blackHearts = 0;
            blueHearts = 0;
            hasHeartData = false;
            lastCaptureCompleteTimeMs = 0;
            return hideCapture;
        }

        if ((raw.contains("\u5df2\u89e6\u53d1") || raw.contains("Triggered")) && raw.contains("ViewStats")) {
            return hideCapture;
        }

        if (raw.contains("Your Stats") || raw.contains("\u7edf\u8ba1\u4fe1\u606f")) {
            capturing = true;
            captureStartTime = System.currentTimeMillis();
            lines.clear();
            hasData = false;
            gameNotStarted = false;
            redHearts = 0;
            soulHearts = 0;
            blackHearts = 0;
            blueHearts = 0;
            return hideCapture;
        }

        if (!capturing) return false;

        if (raw.contains("Hover over") || raw.contains("\u505c\u7559") || raw.contains("\u67e5\u770b\u5b8c\u6574")) {
            return hideCapture;
        }

        if (raw.contains("Game time") || raw.contains("\u6e38\u620f\u65f6\u95f4")) {
            capturing = false;
            hasData = true;
            everReceived = true;
            hasHeartData = (redHearts > 0 || soulHearts > 0 || blackHearts > 0 || blueHearts > 0);
            lastCaptureCompleteTimeMs = System.currentTimeMillis();
            boolean h = hideCapture;
            hideCapture = false;
            return h;
        }

        if (!raw.isBlank()) {
            lines.add(message.copy());
            tryParseHeartLine(message);
        }
        return hideCapture;
    }

    private void tryParseHeartLine(Text message) {
        String raw = message.getString().trim();
        Matcher m = HEART_LINE_PATTERN.matcher(raw);
        if (!m.matches()) return;

        int value = Integer.parseInt(m.group(1));

        final TextColor[] foundColor = {null};
        message.visit((style, text) -> {
            if (foundColor[0] == null && style.getColor() != null && !text.isBlank()) {
                foundColor[0] = style.getColor();
            }
            return Optional.empty();
        }, Style.EMPTY);

        if (foundColor[0] == null) return;

        if (foundColor[0].equals(COLOR_RED)) {
            redHearts = value;
        } else if (foundColor[0].equals(COLOR_YELLOW)) {
            soulHearts = value;
        } else if (foundColor[0].equals(COLOR_BLACK)) {
            blackHearts = value;
        } else if (foundColor[0].equals(COLOR_BLUE)) {
            blueHearts = value;
        }
    }

    public List<Text> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public boolean hasData() { return hasData; }
    public boolean isCapturing() { return capturing; }
    public boolean hasEverReceived() { return everReceived; }
    public boolean isGameNotStarted() { return gameNotStarted; }

    public boolean hasHeartData() { return hasHeartData; }
    public long getLastCaptureCompleteTimeMs() { return lastCaptureCompleteTimeMs; }
    public int getRedHearts() { return redHearts; }
    public int getSoulHearts() { return soulHearts; }
    public int getBlackHearts() { return blackHearts; }
    public int getBlueHearts() { return blueHearts; }

    /**
     * v8.4.0 · 服务端 {@code PlayerStatsPushBroadcaster} 推送入口。
     *
     * <p>把服务端构造好的 {@link com.ctt.healthdisplay.network.PlayerStatsPayload} 字段直接
     * 灌入本地 fields，<b>完全旁路</b>聊天消息 chat capture 路径
     * （{@link #processMessage}）。客户端只要装了本 mod，服务端也装了本 mod，
     * 主 HUD 属性面板 + 主血条四色心叠加完全由本入口驱动，
     * {@code /trigger ViewStats} 命令不再被客户端自动触发。
     *
     * <h2>语义对齐</h2>
     * <ul>
     *   <li>{@code lines.isEmpty()} = 服务端读出"非 CTT 地图 / 玩家未加入世界"
     *       → 设 {@code hasData=false}，HUD 显示"暂无属性数据"hint
     *       （与 chat 解析时的 isGameNotStarted 语义一致）。</li>
     *   <li>{@code lines.nonEmpty()} → 设 {@code hasData=true}、{@code everReceived=true}、
     *       {@code hasHeartData} = 四心之中任一 ≥1、{@code gameNotStarted=false}。</li>
     *   <li>{@code lastCaptureCompleteTimeMs} = 当前挂钟，让"癫狂 fallback"
     *       （{@link com.ctt.healthdisplay.health.HealthData#hasStatsFallback}）
     *       继续工作 —— 服务端推 = 一次成功的 ViewStats capture。</li>
     *   <li>{@code capturing} 强制清成 false：服务端路径不存在"等下一行"的状态。</li>
     * </ul>
     *
     * <h2>线程</h2>
     * <p>仅在客户端线程调用（receiver 已用 {@code MinecraftClient.execute} 切线程）。
     */
    public void applyServerSnapshot(int red, int soul, int black, int blue,
                                    java.util.List<Text> serverLines) {
        // 服务端 builder 返回 lines.isEmpty() 表示"非 CTT 地图 / 玩家未在世界" —— 等同于
        // chat 路径的 gameNotStarted。和 chat 一致地把心数清零，避免残留旧值。
        if (serverLines == null || serverLines.isEmpty()) {
            lines.clear();
            hasData = false;
            capturing = false;
            gameNotStarted = true;
            redHearts = 0;
            soulHearts = 0;
            blackHearts = 0;
            blueHearts = 0;
            hasHeartData = false;
            lastCaptureCompleteTimeMs = 0;
            return;
        }

        lines.clear();
        for (Text t : serverLines) {
            if (t != null) lines.add(t);
        }
        redHearts = red;
        soulHearts = soul;
        blackHearts = black;
        blueHearts = blue;
        hasHeartData = (red > 0 || soul > 0 || black > 0 || blue > 0);
        hasData = true;
        everReceived = true;
        capturing = false;
        gameNotStarted = false;
        lastCaptureCompleteTimeMs = System.currentTimeMillis();
    }
}

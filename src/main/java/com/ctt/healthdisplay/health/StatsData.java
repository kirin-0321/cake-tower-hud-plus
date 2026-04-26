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
}

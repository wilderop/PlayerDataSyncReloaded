package de.craftingstudiopro.playerDataSyncReloaded.common;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PeriodParser {
    private static final Pattern PATTERN = Pattern.compile("^(\\d+)\\s*([dhmsw])$", Pattern.CASE_INSENSITIVE);

    private PeriodParser() {}

    public static Duration parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("period is required");
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.matches("^\\d+$")) {
            value = value + "d";
        }
        Matcher matcher = PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("period must look like 7d, 24h, 1w, 30m");
        }
        long amount = Long.parseLong(matcher.group(1));
        if (amount <= 0) {
            throw new IllegalArgumentException("period must be positive");
        }
        return switch (matcher.group(2)) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            case "w" -> Duration.ofDays(amount * 7L);
            default -> throw new IllegalArgumentException("unknown period unit");
        };
    }
}

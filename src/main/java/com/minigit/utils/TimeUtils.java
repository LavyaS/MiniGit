package com.minigit.utils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Timestamp helpers. Commits store epoch milliseconds; display uses the local time zone. */
public final class TimeUtils {

    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private TimeUtils() {
    }

    public static long nowMillis() {
        return System.currentTimeMillis();
    }

    public static String formatLocal(long epochMillis) {
        return DISPLAY_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }
}

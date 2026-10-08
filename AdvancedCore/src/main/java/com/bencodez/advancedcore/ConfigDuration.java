package com.bencodez.advancedcore;

import java.util.Locale;

/** Strict parser for legacy numeric milliseconds and modern duration text. */
public final class ConfigDuration {
    private ConfigDuration() { }

    public static long parseMillis(Object raw) {
        if (raw instanceof Number) return checked(((Number) raw).doubleValue(), 1d);
        if (raw == null) throw new IllegalArgumentException("duration is missing");
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        int split = 0;
        while (split < value.length() && (Character.isDigit(value.charAt(split)) || value.charAt(split) == '.')) split++;
        if (split == 0 || split == value.length()) throw new IllegalArgumentException("duration requires a unit");
        double multiplier;
        String unit = value.substring(split);
        if (unit.equals("ms")) multiplier = 1d;
        else if (unit.equals("s")) multiplier = 1000d;
        else if (unit.equals("m")) multiplier = 60000d;
        else if (unit.equals("h")) multiplier = 3600000d;
        else if (unit.equals("d")) multiplier = 86400000d;
        else throw new IllegalArgumentException("unknown duration unit: " + unit);
        return checked(Double.parseDouble(value.substring(0, split)), multiplier);
    }

    /** Returns the value in the caller's legacy unit (numeric input uses that unit). */
    public static double read(Object raw, double legacyUnitMillis, double defaultValueInLegacyUnits) {
        if (!Double.isFinite(legacyUnitMillis) || legacyUnitMillis <= 0) throw new IllegalArgumentException("invalid duration base unit");
        if (raw == null) return defaultValueInLegacyUnits;
        if (raw instanceof Number) {
            double value = ((Number) raw).doubleValue();
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("invalid duration");
            return value;
        }
        return parseMillis(raw) / legacyUnitMillis;
    }
    public static int readInt(Object raw, long legacyUnitMillis, int defaultValue) {
        double value = read(raw, legacyUnitMillis, defaultValue);
        if (value > Integer.MAX_VALUE) throw new IllegalArgumentException("duration exceeds integer range");
        return (int) Math.ceil(value);
    }

    private static long checked(double amount, double multiplier) {
        double result = amount * multiplier;
        if (!Double.isFinite(result) || amount < 0 || result >= 0x1p63d) throw new IllegalArgumentException("invalid duration");
        return Math.round(result);
    }
}

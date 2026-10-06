package com.bencodez.advancedcore.api.user.userstorage.mysql;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MySQL physical numeric declarations; integer display width is not storage precision. */
final class RetainedNumericType {
    private static final Pattern TYPE = Pattern.compile(
        "^(TINYINT|SMALLINT|MEDIUMINT|INT|INTEGER|BIGINT|DEC|DECIMAL|NUMERIC|REAL|FLOAT|DOUBLE(?:\\s+PRECISION)?|BOOL|BOOLEAN|BIT)"
        + "(?:\\s*\\(\\s*(\\d{1,9})(?:\\s*,\\s*(\\d{1,9}))?\\s*\\))?((?:\\s+(?:UNSIGNED|ZEROFILL))*)$",
        Pattern.CASE_INSENSITIVE);
    private final String base;
    private final int[] parameters;
    private final boolean unsigned, zerofill, booleanAlias;
    private RetainedNumericType(String base, int[] parameters, boolean unsigned, boolean zerofill, boolean booleanAlias) {
        this.base=base; this.parameters=parameters; this.unsigned=unsigned; this.zerofill=zerofill; this.booleanAlias=booleanAlias;
    }
    static RetainedNumericType parse(String declaration) {
        if (declaration == null || declaration.length() > 256) return null;
        Matcher match=TYPE.matcher(declaration.trim());
        if (!match.matches()) return null;
        String base=match.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        int[] parameters=match.group(2)==null ? new int[0] : match.group(3)==null
            ? new int[]{Integer.parseInt(match.group(2))}
            : new int[]{Integer.parseInt(match.group(2)),Integer.parseInt(match.group(3))};
        if (base.equals("INTEGER")) base="INT";
        if (base.equals("DEC") || base.equals("NUMERIC")) base="DECIMAL";
        if (base.equals("DOUBLE PRECISION")) base="DOUBLE";
        boolean booleanAlias=base.equals("BOOL") || base.equals("BOOLEAN");
        if (booleanAlias) {base="TINYINT";parameters=new int[]{1};}
        String flags=match.group(4).toUpperCase(Locale.ROOT);
        boolean zerofill=flags.contains("ZEROFILL");
        return new RetainedNumericType(base,parameters,flags.contains("UNSIGNED") || zerofill,zerofill,booleanAlias);
    }
    boolean matches(String physical) {
        RetainedNumericType actual=parse(physical);
        if (actual==null || !base.equals(actual.base) || unsigned!=actual.unsigned || zerofill!=actual.zerofill) return false;
        if (booleanAlias && !Arrays.equals(actual.parameters,new int[]{1})) return false;
        if (integer()) return true;
        if (base.equals("DECIMAL")) return Arrays.equals(decimalParameters(),actual.decimalParameters());
        if (base.equals("BIT")) return Arrays.equals(parameters.length==0?new int[]{1}:parameters,
            actual.parameters.length==0?new int[]{1}:actual.parameters);
        return parameters.length==0 || Arrays.equals(parameters,actual.parameters);
    }
    /** Strict SQL mode does not reject fractional rounding or loss of float mantissa bits. */
    boolean needsExactValueProof(String physical) {
        RetainedNumericType actual=parse(physical);
        if (actual==null) return true;
        if (base.equals("DECIMAL") && actual.base.equals("DECIMAL"))
            return decimalParameters()[1] < actual.decimalParameters()[1];
        if (integer() && actual.integer()) return false;
        if (base.equals("DECIMAL") && actual.integer()) return false;
        // Other cross-family casts and BIT/float changes need a fenced value proof,
        // not an assumption that strict mode prevents mathematical rounding.
        return true;
    }
    /** SQL CAST is used only under an exclusive table fence, never as an unfenced preflight. */
    String exactDecimalCast(String physical) {
        RetainedNumericType actual=parse(physical);
        if (!base.equals("DECIMAL") || actual==null || !actual.base.equals("DECIMAL")) return null;
        int[] size=decimalParameters();
        if(size[0]<1 || size[0]>65 || size[1]<0 || size[1]>30 || size[1]>size[0]) return null;
        return "DECIMAL("+size[0]+","+size[1]+")";
    }
    private boolean integer() {
        return base.equals("TINYINT") || base.equals("SMALLINT") || base.equals("MEDIUMINT") || base.equals("INT") || base.equals("BIGINT");
    }
    private int[] decimalParameters() {
        return new int[]{parameters.length==0?10:parameters[0],parameters.length<2?0:parameters[1]};
    }
}

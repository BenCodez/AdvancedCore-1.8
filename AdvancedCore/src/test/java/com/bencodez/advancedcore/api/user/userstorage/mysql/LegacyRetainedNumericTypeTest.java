package com.bencodez.advancedcore.api.user.userstorage.mysql;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LegacyRetainedNumericTypeTest {
    @Test void integerDisplayWidthDoesNotForceMigrationButRangeAndFlagsDo() {
        assertTrue(RetainedNumericType.parse("INTEGER").matches("int(11)"));
        assertFalse(RetainedNumericType.parse("BIGINT").matches("int(11)"));
        assertFalse(RetainedNumericType.parse("INT UNSIGNED").matches("int(11)"));
        assertTrue(RetainedNumericType.parse("INT ZEROFILL").matches("int(10) unsigned zerofill"));
        assertFalse(RetainedNumericType.parse("INT UNSIGNED").matches("int(10) unsigned zerofill"));
    }
    @Test void decimalDefaultsAndExplicitPrecisionScaleAreCompared() {
        assertTrue(RetainedNumericType.parse("NUMERIC").matches("decimal(10,0)"));
        assertTrue(RetainedNumericType.parse("DECIMAL(12)").matches("decimal(12,0)"));
        assertFalse(RetainedNumericType.parse("DECIMAL(12,2)").matches("decimal(10,2)"));
        assertFalse(RetainedNumericType.parse("DECIMAL(12,2)").matches("decimal(12,3)"));
    }
    @Test void booleanAliasRequiresTinyintOneRatherThanAnyIntegerDisplayWidth() {
        assertTrue(RetainedNumericType.parse("BOOLEAN").matches("tinyint(1)"));
        assertFalse(RetainedNumericType.parse("BOOLEAN").matches("tinyint(4)"));
        assertTrue(RetainedNumericType.parse("TINYINT").matches("tinyint(4)"));
    }
    @Test void roundingProneChangesRequireValueEvidenceBeyondStrictSqlMode() {
        assertTrue(RetainedNumericType.parse("DECIMAL(12,2)").needsExactValueProof("decimal(12,3)"));
        assertFalse(RetainedNumericType.parse("DECIMAL(14,4)").needsExactValueProof("decimal(12,3)"));
        assertFalse(RetainedNumericType.parse("BIGINT").needsExactValueProof("int(11)"));
        assertTrue(RetainedNumericType.parse("BIGINT").needsExactValueProof("decimal(12,3)"));
        assertTrue(RetainedNumericType.parse("DOUBLE").needsExactValueProof("bigint(20)"));
    }
    @Test void bitLengthAndNonNumericDeclarationsRemainDistinct() {
        assertTrue(RetainedNumericType.parse("BIT").matches("bit(1)"));
        assertFalse(RetainedNumericType.parse("BIT(8)").matches("bit(1)"));
        assertNull(RetainedNumericType.parse("TEXT"));assertNull(RetainedNumericType.parse("INT; DROP TABLE Users"));
        assertNull(RetainedNumericType.parse("DECIMAL(999999999999999999999)"));
    }
}

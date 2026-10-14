package com.r3dzf.reader

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class XlsxNumberFormatTest {
    private fun shown(value: String, code: String, expected: String, date1904: Boolean = false) {
        val result = XlsxNumberFormatter(date1904).format(value, code)
        assertTrue("Unsupported: $code", result.supported)
        assertEquals(expected, result.text)
    }

    @Test fun roundsDecimalNumbersAndKeepsRequiredZeros() {
        shown("1234.567", "#,##0.00", "1,234.57")
        shown("-12.345", "0.00", "-12.35")
        shown("12.3", "0.00##", "12.30")
        shown("0", "0.00", "0.00")
        shown("42", "00000", "00042")
    }
    @Test fun distinguishesPercentTokensFromQuotedAndEscapedPercent() {
        shown("0.125", "0.0%", "12.5%")
        shown("0.125", "0.0\"%\"", "0.1%")
        shown("0.125", "0.0\\%", "0.1%")
    }
    @Test fun scalesThousandsAndMillionsAndPreservesArabicCurrency() {
        shown("1234567", "0.00,,", "1.23")
        shown("1234567", "#,##0,", "1,235")
        shown("1234.5", "\"ج.م \"#,##0.00", "ج.م 1,234.50")
        shown("12.3", "$0.00", "$12.30")
    }
    @Test fun selectsPositiveNegativeZeroSectionsAndTheirColor() {
        val formatter = XlsxNumberFormatter()
        val code = "0.0;[Red](0.0);\"—\";@"
        assertEquals("12.3", formatter.format("12.25", code).text)
        val negative = formatter.format("-12.25", code)
        assertEquals("(12.3)", negative.text)
        assertEquals(0xFF0000L, negative.colorRgb)
        assertEquals("—", formatter.format("0", code).text)
        shown("-42", "0;;0", "")
        shown("1", "\"A;B\"0", "A;B1")
    }
    @Test fun roundsScientificMantissaWithExponentCarry() {
        shown("1234", "0.00E+00", "1.23E+03")
        shown("0.00123", "0.00E+00", "1.23E-03")
        shown("9.999", "0.00E+00", "1.00E+01")
        shown("0", "0.00E+00", "0.00E+00")
    }
    @Test fun preservesGeneralAndTextFormatSavedValues() {
        shown("1E+12", "General", "1E+12")
        shown("123", "@", "123")
    }
    @Test fun preservesExcel1900LeapDayWithoutShiftingLaterDates() {
        shown("0", "yyyy-mm-dd", "1900-01-00")
        shown("1", "yyyy-mm-dd", "1900-01-01")
        shown("59", "yyyy-mm-dd", "1900-02-28")
        shown("60", "yyyy-mm-dd", "1900-02-29")
        shown("61", "yyyy-mm-dd", "1900-03-01")
        shown("45292", "yyyy-mm-dd", "2024-01-01")
    }
    @Test fun supportsThe1904EpochAndSameDateAcrossBothSystems() {
        shown("0", "yyyy-mm-dd", "1904-01-01", true)
        shown("43830", "yyyy-mm-dd", "2024-01-01", true)
        shown("60", "yyyy-mm-dd", "1904-03-01", true)
    }
    @Test fun distinguishesMonthFromMinutesInMixedDateTime() {
        shown("45292.5", "yyyy-mm-dd hh:mm:ss", "2024-01-01 12:00:00")
        shown("0.5", "h:mm AM/PM", "12:00 PM")
        shown("0", "h:mm AM/PM", "12:00 AM")
        shown("0.0625", "mm:ss", "30:00")
        shown("45292", "ddd, d-mmm-yyyy", "Mon, 1-Jan-2024")
        shown("45292", "dd.mm.yyyy", "01.01.2024")
    }
    @Test fun supportsElapsedTimeBeyond24HoursAndSubsecondPrecision() {
        shown("1.5", "[h]:mm:ss", "36:00:00")
        shown("1.5", "[m]:ss", "2160:00")
        shown("1.5", "[s]", "129600")
        shown("0.000015625", "ss.000", "01.350")
        shown("0.000015625", "mmss.0", "0001.4")
    }
    @Test fun timeRoundingCanCarryIntoTheNextDay() {
        shown("0.999999999", "yyyy-mm-dd hh:mm:ss", "1900-01-01 00:00:00")
        shown("0.999999999", "[h]:mm:ss", "24:00:00")
    }
    @Test fun unsupportedSyntaxKeepsSavedValueAndReportsFallback() {
        for (code in listOf("# ?/?", "[>=100]0;0", "[$-409]d-mmm-yy", "_(* #,##0_)", "0?", "[Color10]0", "##0.0E+0")) {
            val result = XlsxNumberFormatter().format("123.45", code)
            assertFalse(code, result.supported)
            assertEquals("123.45", result.text)
        }
        assertFalse(XlsxNumberFormatter().format("123", null).supported)
    }
    @Test fun malformedFormatsAndHugeNumbersRemainBounded() {
        for (code in listOf("\"unterminated", "0\\", "0;0;0;@;0", "0".repeat(513))) {
            assertFalse(XlsxNumberFormatter().format("1", code).supported)
        }
        for (value in listOf("1E+2147483647", "1E-2147483647", "NaN", "Infinity", "1".repeat(129))) {
            val result = XlsxNumberFormatter().format(value, "0.00")
            assertFalse(result.supported)
            assertEquals(value, result.text)
        }
    }
    @Test fun invalidDatesAreNeverWrappedOrTurnedIntoAnotherDate() {
        for (value in listOf("-1", "2958466", "1E100")) {
            assertFalse(XlsxNumberFormatter().format(value, "yyyy-mm-dd").supported)
        }
    }
    @Test fun renderingIsIndependentOfDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            shown("1234.5", "#,##0.00", "1,234.50")
            shown("45292", "d-mmm-yyyy", "1-Jan-2024")
        } finally { Locale.setDefault(previous) }
    }
}

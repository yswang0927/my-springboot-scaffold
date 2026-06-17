package com.myweb.util.jsonrepair;

/**
 * 翻译自: https://github.com/josdejong/jsonrepair/blob/main/src/utils/stringUtils.ts
 */
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class StrUtils {

    public static final int codeSpace = 0x20; // " "
    public static final int codeNewline = 0xa; // "\n"
    public static final int codeTab = 0x9; // "\t"
    public static final int codeReturn = 0xd; // "\r"

    // unicode spaces: https://jkorpela.fi/chars/spaces.html
    public static final int codeNonBreakingSpace = 0x00a0;
    public static final int codeMongolianVowelSeparator = 0x180e;
    public static final int codeEnQuad = 0x2000;
    public static final int codeZeroWidthSpace = 0x200b;
    public static final int codeNarrowNoBreakSpace = 0x202f;
    public static final int codeMediumMathematicalSpace = 0x205f;
    public static final int codeIdeographicSpace = 0x3000;
    public static final int codeZeroWidthNoBreakSpace = 0xfeff;

    // matches "https://" and other schemas
    public static final Pattern REGEX_URL_START = Pattern.compile("^(http|https|ftp|mailto|file|data|irc)://$");

    // matches all valid URL characters EXCEPT "[", "]", and ",", since that are important JSON delimiters
    public static final Pattern REGEX_URL_CHAR = Pattern.compile("^[A-Za-z0-9\\-._~:/?#@!$&'()*+;=]$");

    // alpha, number, minus, or opening bracket or brace
    private static final Pattern REGEX_START_OF_VALUE = Pattern.compile("^[\\[\\{\\w-]$");

    // Ends with comma or newline
    private static final Pattern REGEX_ENDS_WITH_COMMA_OR_NEWLINE = Pattern.compile("[,\n][ \t\r]*$");

    public static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }

    public static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    public static boolean isValidStringCharacter(char c) {
        return c >= '\u0020';
    }

    public static boolean isDelimiter(char c) {
        return ",:[]/{}()\n+".indexOf(c) != -1;
    }

    public static boolean isFunctionNameCharStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
    }

    public static boolean isFunctionNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$' || (c >= '0' && c <= '9');
    }

    public static boolean isUnquotedStringDelimiter(char c) {
        return ",[]/{}\n+".indexOf(c) != -1;
    }

    public static boolean isStartOfValue(char c) {
        return isQuote(c) || REGEX_START_OF_VALUE.matcher(String.valueOf(c)).matches();
    }

    public static boolean isControlCharacter(char c) {
        return c == '\n' || c == '\r' || c == '\t' || c == '\b' || c == '\f';
    }

    /**
     * Check if the given character is a whitespace character like space, tab, or
     * newline
     */
    public static boolean isWhitespace(CharSequence text, int index) {
        int code = text.charAt(index);
        return code == codeSpace || code == codeNewline || code == codeTab || code == codeReturn;
    }

    /**
     * Check if the given character is a whitespace character like space or tab,
     * but NOT a newline
     */
    public static boolean isWhitespaceExceptNewline(CharSequence text, int index) {
        int code = text.charAt(index);
        return code == codeSpace || code == codeTab || code == codeReturn;
    }

    /**
     * Check if the given character is a special whitespace character, some
     * unicode variant
     */
    public static boolean isSpecialWhitespace(CharSequence text, int index) {
        int code = text.charAt(index);
        return code == codeNonBreakingSpace ||
            code == codeMongolianVowelSeparator ||
            (code >= codeEnQuad && code <= codeZeroWidthSpace) ||
            code == codeNarrowNoBreakSpace ||
            code == codeMediumMathematicalSpace ||
            code == codeIdeographicSpace ||
            code == codeZeroWidthNoBreakSpace;
    }

    /**
     * Test whether the given character is a quote or double quote character.
     * Also tests for special variants of quotes.
     */
    public static boolean isQuote(char c) {
        return isDoubleQuoteLike(c) || isSingleQuoteLike(c);
    }

    /**
     * Test whether the given character is a double quote character.
     * Also tests for special variants of double quotes.
     */
    public static boolean isDoubleQuoteLike(char c) {
        return c == '"' || c == '\u201c' || c == '\u201d';
    }

    /**
     * Test whether the given character is a double quote character.
     * Does NOT test for special variants of double quotes.
     */
    public static boolean isDoubleQuote(char c) {
        return c == '"';
    }

    /**
     * Test whether the given character is a single quote character.
     * Also tests for special variants of single quotes.
     */
    public static boolean isSingleQuoteLike(char c) {
        return c == '\'' || c == '\u2018' || c == '\u2019' || c == '\u0060' || c == '\u00b4';
    }

    /**
     * Test whether the given character is a single quote character.
     * Does NOT test for special variants of single quotes.
     */
    public static boolean isSingleQuote(char c) {
        return c == '\'';
    }

    /**
     * Strip last occurrence of textToStrip from text (Overload without stripRemainingText)
     */
    public static String stripLastOccurrence(String text, String textToStrip) {
        return stripLastOccurrence(text, textToStrip, false);
    }

    /**
     * Strip last occurrence of textToStrip from text
     */
    public static String stripLastOccurrence(String text, String textToStrip, boolean stripRemainingText) {
        int index = text.lastIndexOf(textToStrip);
        if (index != -1) {
            // Note: Preserving the exact TS behavior of index + 1 (which removes only 1 char)
            return text.substring(0, index) + (stripRemainingText ? "" : text.substring(index + 1));
        }
        return text;
    }

    public static String insertBeforeLastWhitespace(String text, String textToInsert) {
        int index = text.length();

        if (index == 0 || !isWhitespace(text, index - 1)) {
            // no trailing whitespaces
            return text + textToInsert;
        }

        while (index > 0 && isWhitespace(text, index - 1)) {
            index--;
        }

        return text.substring(0, index) + textToInsert + text.substring(index);
    }

    public static String removeAtIndex(String text, int start, int count) {
        return text.substring(0, start) + text.substring(start + count);
    }

    /**
     * Test whether a string ends with a newline or comma character and optional whitespace
     */
    public static boolean endsWithCommaOrNewline(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        Matcher matcher = REGEX_ENDS_WITH_COMMA_OR_NEWLINE.matcher(text);
        return matcher.find();
    }
}

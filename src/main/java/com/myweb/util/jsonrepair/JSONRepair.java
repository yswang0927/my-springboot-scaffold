package com.myweb.util.jsonrepair;

import java.util.HashMap;
import java.util.Map;

/**
 * 将错误格式的JSON修复为正确格式.
 * <p>示例:</p>
 * <pre><code>
 * String json = """
 *            {"users": [{"name": "John", "age": 30}, {"name": "Jane"
 *            """;
 * try {
 *      String repairedJson = JSONRepair.repair(json);
 * } catch (JSONRepairException e) {
 *     // 如果无法修复则抛出此异常
 * }
 * </code></pre>
 *
 * <p>支持以下问题的修复:</p>
 * <pre>
 * 1. Add missing quotes around keys
 * 2. Add missing escape characters
 * 3. Add missing commas
 * 4. Add missing closing brackets
 * 5. Repair truncated JSON
 * 6. Replace single quotes with double quotes
 * 7. Replace special quote characters like “...” with regular double quotes
 * 8. Replace special white space characters with regular spaces
 * 9. Replace Python constants None, True, and False with null, true, and false
 * 10. Strip trailing commas
 * 11. Strip comments
 * 12. Strip fenced code blocks like  ```json and ```
 * 13. Strip ellipsis in arrays and objects like [1, 2, 3, ...]
 * 14. Strip JSONP notation like callback({ ... })
 * 15. Strip escape characters from an escaped string like {\"stringified\": \"content\"}
 * 16. Strip MongoDB data types like NumberLong(2) and ISODate("2012-12-19T06:01:17.171Z")
 * 17. Concatenate strings like "long text" + "more text on next line"
 * 18. Turn newline delimited JSON into a valid JSON array
 * </pre>
 *
 * 翻译自: https://github.com/josdejong/jsonrepair/blob/main/src/regular/jsonrepair.ts
 */
public class JSONRepair {

    private static final Map<Character, String> CONTROL_CHARACTERS = new HashMap<>();
    private static final Map<Character, String> ESCAPE_CHARACTERS = new HashMap<>();

    static {
        CONTROL_CHARACTERS.put('\b', "\\b");
        CONTROL_CHARACTERS.put('\f', "\\f");
        CONTROL_CHARACTERS.put('\n', "\\n");
        CONTROL_CHARACTERS.put('\r', "\\r");
        CONTROL_CHARACTERS.put('\t', "\\t");

        ESCAPE_CHARACTERS.put('"', "\"");
        ESCAPE_CHARACTERS.put('\\', "\\");
        ESCAPE_CHARACTERS.put('/', "/");
        ESCAPE_CHARACTERS.put('b', "\b");
        ESCAPE_CHARACTERS.put('f', "\f");
        ESCAPE_CHARACTERS.put('n', "\n");
        ESCAPE_CHARACTERS.put('r', "\r");
        ESCAPE_CHARACTERS.put('t', "\t");
    }

    private final String text;
    private int i = 0; // current index in text
    private String output = ""; // generated output

    private JSONRepair(String text) {
        this.text = text == null ? "" : text;
    }

    /**
     * Repair a string containing an invalid JSON document.
     */
    public static String repair(String text) {
        return new JSONRepair(text).doRepair();
    }

    // Safely get a character or return a null character '\0' equivalent to TS undefined
    private char charAt(int index) {
        if (index >= 0 && index < this.text.length()) {
            return this.text.charAt(index);
        }
        return '\0';
    }

    private String doRepair() {
        parseMarkdownCodeBlock(new String[]{"```", "[```", "{```"});

        boolean processed = parseValue();
        if (!processed) {
            throwUnexpectedEnd();
        }

        parseMarkdownCodeBlock(new String[]{"```", "```]", "```}"});

        boolean processedComma = parseCharacter(',');
        if (processedComma) {
            parseWhitespaceAndSkipComments(true);
        }

        if (StrUtils.isStartOfValue(charAt(i)) && StrUtils.endsWithCommaOrNewline(output)) {
            // start of a new value after end of the root level object: looks like
            // newline delimited JSON -> turn into a root level array
            if (!processedComma) {
                // repair missing comma
                output = StrUtils.insertBeforeLastWhitespace(output, ",");
            }
            parseNewlineDelimitedJSON();
        } else if (processedComma) {
            // repair: remove trailing comma
            output = StrUtils.stripLastOccurrence(output, ",");
        }

        // repair redundant end quotes
        while (charAt(i) == '}' || charAt(i) == ']') {
            i++;
            parseWhitespaceAndSkipComments(true);
        }

        if (i >= text.length()) {
            // reached the end of the document properly
            return output;
        }

        throwUnexpectedCharacter();
        return output; // Unreachable
    }

    private boolean parseValue() {
        parseWhitespaceAndSkipComments(true);
        boolean processed = parseObject() ||
            parseArray() ||
            parseString(false, -1) ||
            parseNumber() ||
            parseKeywords() ||
            parseUnquotedString(false) ||
            parseRegex();
        parseWhitespaceAndSkipComments(true);

        return processed;
    }

    private boolean parseWhitespaceAndSkipComments(boolean skipNewline) {
        int start = i;

        boolean changed = parseWhitespace(skipNewline);
        do {
            changed = parseComment();
            if (changed) {
                changed = parseWhitespace(skipNewline);
            }
        } while (changed);

        return i > start;
    }

    private boolean parseWhitespace(boolean skipNewline) {
        StringBuilder whitespace = new StringBuilder();

        while (i < text.length()) {
            boolean isSpace = skipNewline ? StrUtils.isWhitespace(text, i) : StrUtils.isWhitespaceExceptNewline(text, i);
            if (isSpace) {
                whitespace.append(charAt(i));
                i++;
            } else if (StrUtils.isSpecialWhitespace(text, i)) {
                // repair special whitespace
                whitespace.append(' ');
                i++;
            } else {
                break;
            }
        }

        if (whitespace.length() > 0) {
            output += whitespace.toString();
            return true;
        }

        return false;
    }

    private boolean parseComment() {
        // find a block comment '/* ... */'
        if (charAt(i) == '/' && charAt(i + 1) == '*') {
            while (i < text.length() && !atEndOfBlockComment(text, i)) {
                i++;
            }
            i += 2;
            return true;
        }

        // find a line comment '// ...'
        if (charAt(i) == '/' && charAt(i + 1) == '/') {
            while (i < text.length() && charAt(i) != '\n') {
                i++;
            }
            return true;
        }

        return false;
    }

    private boolean atEndOfBlockComment(String text, int index) {
        return (index + 1 < text.length()) && text.charAt(index) == '*' && text.charAt(index + 1) == '/';
    }

    private boolean parseMarkdownCodeBlock(String[] blocks) {
        // find and skip over a Markdown fenced code block:
        //     ``` ... ```
        // or
        //     ```json ... ```
        if (skipMarkdownCodeBlock(blocks)) {
            if (StrUtils.isFunctionNameCharStart(charAt(i))) {
                // strip the optional language specifier like "json"
                while (i < text.length() && StrUtils.isFunctionNameChar(charAt(i))) {
                    i++;
                }
            }
            parseWhitespaceAndSkipComments(true);
            return true;
        }
        return false;
    }

    private boolean skipMarkdownCodeBlock(String[] blocks) {
        parseWhitespace(true);

        for (String block : blocks) {
            int end = i + block.length();
            if (end <= text.length() && text.substring(i, end).equals(block)) {
                i = end;
                return true;
            }
        }
        return false;
    }

    private boolean parseCharacter(char c) {
        if (charAt(i) == c) {
            output += charAt(i);
            i++;
            return true;
        }
        return false;
    }

    private boolean skipCharacter(char c) {
        if (charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private boolean skipEscapeCharacter() {
        return skipCharacter('\\');
    }

    /**
     * Skip ellipsis like "[1,2,3,...]" or "[1,2,3,...,9]" or "[...,7,8,9]"
     * or a similar construct in objects.
     */
    private boolean skipEllipsis() {
        parseWhitespaceAndSkipComments(true);

        if (charAt(i) == '.' && charAt(i + 1) == '.' && charAt(i + 2) == '.') {
            i += 3;
            parseWhitespaceAndSkipComments(true);
            skipCharacter(',');
            return true;
        }
        return false;
    }

    /**
     * Parse an object like '{"key": "value"}'
     */
    private boolean parseObject() {
        if (charAt(i) == '{') {
            output += "{";
            i++;
            parseWhitespaceAndSkipComments(true);

            // repair: skip leading comma like in {, message: "hi"}
            if (skipCharacter(',')) {
                parseWhitespaceAndSkipComments(true);
            }

            boolean initial = true;
            while (i < text.length() && charAt(i) != '}') {
                boolean processedComma;
                if (!initial) {
                    processedComma = parseCharacter(',');
                    if (!processedComma) {
                        // repair missing comma
                        output = StrUtils.insertBeforeLastWhitespace(output, ",");
                    }
                    parseWhitespaceAndSkipComments(true);
                } else {
                    processedComma = true;
                    initial = false;
                }

                skipEllipsis();

                boolean processedKey = parseString(false, -1) || parseUnquotedString(true);
                if (!processedKey) {
                    char c = charAt(i);
                    if (c == '}' || c == '{' || c == ']' || c == '[' || c == '\0') {
                        // repair trailing comma
                        output = StrUtils.stripLastOccurrence(output, ",");
                    } else {
                        throwObjectKeyExpected();
                    }
                    break;
                }

                parseWhitespaceAndSkipComments(true);
                boolean processedColon = parseCharacter(':');
                boolean truncatedText = i >= text.length();

                if (!processedColon) {
                    if (StrUtils.isStartOfValue(charAt(i)) || truncatedText) {
                        // repair missing colon
                        output = StrUtils.insertBeforeLastWhitespace(output, ":");
                    } else {
                        throwColonExpected();
                    }
                }

                boolean processedValue = parseValue();
                if (!processedValue) {
                    if (processedColon || truncatedText) {
                        // repair missing object value
                        output += "null";
                    } else {
                        throwColonExpected();
                    }
                }
            }

            if (charAt(i) == '}') {
                output += "}";
                i++;
            } else {
                // repair missing end bracket
                output = StrUtils.insertBeforeLastWhitespace(output, "}");
            }

            return true;
        }

        return false;
    }

    /**
     * Parse an array like '["item1", "item2", ...]'
     */
    private boolean parseArray() {
        if (charAt(i) == '[') {
            output += "[";
            i++;
            parseWhitespaceAndSkipComments(true);

            // repair: skip leading comma like in [,1,2,3]
            if (skipCharacter(',')) {
                parseWhitespaceAndSkipComments(true);
            }

            boolean initial = true;
            while (i < text.length() && charAt(i) != ']') {
                if (!initial) {
                    boolean processedComma = parseCharacter(',');
                    if (!processedComma) {
                        // repair missing comma
                        output = StrUtils.insertBeforeLastWhitespace(output, ",");
                    }
                } else {
                    initial = false;
                }

                skipEllipsis();

                boolean processedValue = parseValue();
                if (!processedValue) {
                    // repair trailing comma
                    output = StrUtils.stripLastOccurrence(output, ",");
                    break;
                }
            }

            if (charAt(i) == ']') {
                output += "]";
                i++;
            } else {
                // repair missing closing array bracket
                output = StrUtils.insertBeforeLastWhitespace(output, "]");
            }

            return true;
        }

        return false;
    }

    /**
     * Parse and repair Newline Delimited JSON (NDJSON):
     * multiple JSON objects separated by a newline character
     */
    private void parseNewlineDelimitedJSON() {
        // repair NDJSON
        boolean initial = true;
        boolean processedValue = true;
        while (processedValue) {
            if (!initial) {
                // parse optional comma, insert when missing
                boolean processedComma = parseCharacter(',');
                if (!processedComma) {
                    // repair: add missing comma
                    output = StrUtils.insertBeforeLastWhitespace(output, ",");
                }
            } else {
                initial = false;
            }

            processedValue = parseValue();
        }

        if (!processedValue) {
            // repair: remove trailing comma
            output = StrUtils.stripLastOccurrence(output, ",");
        }

        // repair: wrap the output inside array brackets
        output = "[\n" + output + "\n]";
    }

    /**
     * Parse a string enclosed by double quotes "...". Can contain escaped quotes
     * Repair strings enclosed in single quotes or special quotes
     * Repair an escaped string
     *
     * The function can run in two stages:
     * - First, it assumes the string has a valid end quote
     * - If it turns out that the string does not have a valid end quote followed
     *   by a delimiter (which should be the case), the function runs again in a
     *   more conservative way, stopping the string at the first next delimiter
     *   and fixing the string by inserting a quote there, or stopping at a
     *   stop index detected in the first iteration.
     */
    private boolean parseString(boolean stopAtDelimiter, int stopAtIndex) {
        boolean skipEscapeChars = charAt(i) == '\\';
        if (skipEscapeChars) {
            // repair: remove the first escape character
            i++;
            skipEscapeChars = true;
        }

        if (StrUtils.isQuote(charAt(i))) {
            // double quotes are correct JSON,
            // single quotes come from JavaScript for example, we assume it will have a correct single end quote too
            // otherwise, we will match any double-quote-like start with a double-quote-like end,
            // or any single-quote-like start with a single-quote-like end
            char startQuote = charAt(i);
            int quoteType; // 0=double, 1=single, 2=singleLike, 3=doubleLike
            if (StrUtils.isDoubleQuote(startQuote)) {
                quoteType = 0;
            }
            else if (StrUtils.isSingleQuote(startQuote)) {
                quoteType = 1;
            }
            else if (StrUtils.isSingleQuoteLike(startQuote)) {
                quoteType = 2;
            }
            else {
                quoteType = 3;
            }

            int iBefore = i;
            int oBefore = output.length();

            String str = "\"";
            i++;

            while (true) {
                if (i >= text.length()) {
                    // end of text, we are missing an end quote

                    int iPrev = prevNonWhitespaceIndex(i - 1);
                    if (!stopAtDelimiter && StrUtils.isDelimiter(charAt(iPrev))) {
                        // if the text ends with a delimiter, like ["hello],
                        // so the missing end quote should be inserted before this delimiter
                        // retry parsing the string, stopping at the first next delimiter
                        i = iBefore;
                        output = output.substring(0, oBefore);
                        return parseString(true, -1);
                    }

                    // repair missing quote
                    str = StrUtils.insertBeforeLastWhitespace(str, "\"");
                    output += str;
                    return true;
                }

                if (i == stopAtIndex) {
                    // use the stop index detected in the first iteration, and repair end quote
                    str = StrUtils.insertBeforeLastWhitespace(str, "\"");
                    output += str;
                    return true;
                }

                char currentChar = charAt(i);
                boolean isEndQuoteMatch = false;
                switch (quoteType) {
                    case 0: isEndQuoteMatch = StrUtils.isDoubleQuote(currentChar); break;
                    case 1: isEndQuoteMatch = StrUtils.isSingleQuote(currentChar); break;
                    case 2: isEndQuoteMatch = StrUtils.isSingleQuoteLike(currentChar); break;
                    case 3: isEndQuoteMatch = StrUtils.isDoubleQuoteLike(currentChar); break;
                }

                if (isEndQuoteMatch) {
                    // end quote
                    // let us check what is before and after the quote to verify whether this is a legit end quote
                    int iQuote = i;
                    int oQuote = str.length();
                    str += "\"";
                    i++;
                    output += str;

                    parseWhitespaceAndSkipComments(false);

                    if (stopAtDelimiter
                        || i >= text.length()
                        || StrUtils.isDelimiter(charAt(i))
                        || StrUtils.isQuote(charAt(i))
                        || StrUtils.isDigit(charAt(i))
                    ) {
                        // The quote is followed by the end of the text, a delimiter,
                        // or a next value. So the quote is indeed the end of the string.
                        parseConcatenatedString();
                        return true;
                    }

                    int iPrevChar = prevNonWhitespaceIndex(iQuote - 1);
                    char prevChar = charAt(iPrevChar);

                    if (prevChar == ',') {
                        // A comma followed by a quote, like '{"a":"b,c,"d":"e"}'.
                        // We assume that the quote is a start quote, and that the end quote
                        // should have been located right before the comma but is missing.
                        i = iBefore;
                        output = output.substring(0, oBefore);
                        return parseString(false, iPrevChar);
                    }

                    if (StrUtils.isDelimiter(prevChar)) {
                        // This is not the right end quote: it is preceded by a delimiter,
                        // and NOT followed by a delimiter. So, there is an end quote missing
                        // parse the string again and then stop at the first next delimiter
                        i = iBefore;
                        output = output.substring(0, oBefore);
                        return parseString(true, -1);
                    }

                    output = output.substring(0, oBefore);
                    i = iQuote + 1;

                    // repair unescaped quote
                    str = str.substring(0, oQuote) + "\\" + str.substring(oQuote);

                }
                else if (stopAtDelimiter && StrUtils.isUnquotedStringDelimiter(charAt(i))) {
                    // we're in the mode to stop the string at the first delimiter
                    // because there is an end quote missing

                    // test start of an url like "https://..." (this would be parsed as a comment)
                    if (charAt(i - 1) == ':' && StrUtils.REGEX_URL_START.matcher(text.substring(iBefore + 1, Math.min(text.length(), i + 2))).matches()) {
                        while (i < text.length() && StrUtils.REGEX_URL_CHAR.matcher(String.valueOf(charAt(i))).matches()) {
                            str += charAt(i);
                            i++;
                        }
                    }

                    // repair missing quote
                    str = StrUtils.insertBeforeLastWhitespace(str, "\"");
                    output += str;
                    parseConcatenatedString();
                    return true;

                } else if (charAt(i) == '\\') {
                    // handle escaped content like \n or \u2605
                    char escapeChar = charAt(i + 1);
                    String escapeStr = ESCAPE_CHARACTERS.get(escapeChar);
                    if (escapeStr != null) {
                        str += text.substring(i, Math.min(text.length(), i + 2));
                        i += 2;
                    } else if (escapeChar == 'u') {
                        int j = 2;
                        while (j < 6 && StrUtils.isHex(charAt(i + j))) {
                            j++;
                        }

                        if (j == 6) {
                            str += text.substring(i, Math.min(text.length(), i + 6));
                            i += 6;
                        } else if (i + j >= text.length()) {
                            // repair invalid or truncated unicode char at the end of the text
                            // by removing the unicode char and ending the string here
                            i = text.length();
                        } else {
                            throwInvalidUnicodeCharacter();
                        }
                    } else if (escapeChar == '\n') {
                        // repair a backslash escaped newline (like in Bash scripts)
                        str += "\\n";
                        i += 2;
                    } else {
                        // repair invalid escape character: remove it
                        str += escapeChar;
                        i += 2;
                    }
                } else {
                    // handle regular characters
                    char c = charAt(i);

                    if (c == '"' && charAt(i - 1) != '\\') {
                        // repair unescaped double quote
                        str += "\\" + c;
                        i++;
                    } else if (StrUtils.isControlCharacter(c)) {
                        // unescaped control character
                        str += CONTROL_CHARACTERS.get(c);
                        i++;
                    } else {
                        if (!StrUtils.isValidStringCharacter(c)) {
                            throwInvalidCharacter(c);
                        }
                        str += c;
                        i++;
                    }
                }

                if (skipEscapeChars) {
                    // repair: skipped escape character (nothing to do)
                    skipEscapeCharacter();
                }
            }
        }

        return false;
    }

    /**
     * Repair concatenated strings like "hello" + "world", change this into "helloworld"
     */
    private boolean parseConcatenatedString() {
        boolean processed = false;

        parseWhitespaceAndSkipComments(true);
        while (charAt(i) == '+') {
            processed = true;
            i++;
            parseWhitespaceAndSkipComments(true);

            // repair: remove the end quote of the first string
            output = StrUtils.stripLastOccurrence(output, "\"", true);
            int start = output.length();
            boolean parsedStr = parseString(false, -1);
            if (parsedStr) {
                // repair: remove the start quote of the second string
                output = StrUtils.removeAtIndex(output, start, 1);
            } else {
                // repair: remove the + because it is not followed by a string
                output = StrUtils.insertBeforeLastWhitespace(output, "\"");
            }
        }

        return processed;
    }

    /**
     * Parse a number like 2.4 or 2.4e6
     */
    private boolean parseNumber() {
        int start = i;
        if (charAt(i) == '-') {
            i++;
            if (atEndOfNumber()) {
                repairNumberEndingWithNumericSymbol(start);
                return true;
            }
            if (!StrUtils.isDigit(charAt(i))) {
                i = start;
                return false;
            }
        }

        // Note that in JSON leading zeros like "00789" are not allowed.
        // We will allow all leading zeros here though and at the end of parseNumber
        // check against trailing zeros and repair that if needed.
        // Leading zeros can have meaning, so we should not clear them.
        while (StrUtils.isDigit(charAt(i))) {
            i++;
        }

        if (charAt(i) == '.') {
            i++;
            if (atEndOfNumber()) {
                repairNumberEndingWithNumericSymbol(start);
                return true;
            }
            if (!StrUtils.isDigit(charAt(i))) {
                i = start;
                return false;
            }
            while (StrUtils.isDigit(charAt(i))) {
                i++;
            }
        }

        if (charAt(i) == 'e' || charAt(i) == 'E') {
            i++;
            if (charAt(i) == '-' || charAt(i) == '+') {
                i++;
            }
            if (atEndOfNumber()) {
                repairNumberEndingWithNumericSymbol(start);
                return true;
            }
            if (!StrUtils.isDigit(charAt(i))) {
                i = start;
                return false;
            }
            while (StrUtils.isDigit(charAt(i))) {
                i++;
            }
        }

        // if we're not at the end of the number by this point, allow this to be parsed as another type
        if (!atEndOfNumber()) {
            i = start;
            return false;
        }

        if (i > start) {
            // repair a number with leading zeros like "00789"
            String num = text.substring(start, i);
            boolean hasInvalidLeadingZero = num.length() > 1 && num.charAt(0) == '0' && Character.isDigit(num.charAt(1));

            output += hasInvalidLeadingZero ? "\"" + num + "\"" : num;
            return true;
        }

        return false;
    }

    /**
     * Parse keywords true, false, null
     * Repair Python keywords True, False, None
     */
    private boolean parseKeywords() {
        return parseKeyword("true", "true") ||
            parseKeyword("false", "false") ||
            parseKeyword("null", "null") ||
            parseKeyword("True", "true") ||
            parseKeyword("False", "false") ||
            parseKeyword("None", "null");
    }

    private boolean parseKeyword(String name, String value) {
        if (text.length() >= i + name.length() && text.substring(i, i + name.length()).equals(name)) {
            output += value;
            i += name.length();
            return true;
        }
        return false;
    }

    /**
     * Repair an unquoted string by adding quotes around it
     * Repair a MongoDB function call like NumberLong("2")
     * Repair a JSONP function call like callback({...});
     */
    private boolean parseUnquotedString(boolean isKey) {
        // note that the symbol can end with whitespaces: we stop at the next delimiter
        // also, note that we allow strings to contain a slash / in order to support repairing regular expressions
        int start = i;

        if (StrUtils.isFunctionNameCharStart(charAt(i))) {
            while (i < text.length() && StrUtils.isFunctionNameChar(charAt(i))) {
                i++;
            }

            int j = i;
            while (j < text.length() && StrUtils.isWhitespace(text, j)) {
                j++;
            }

            if (charAt(j) == '(') {
                // repair a MongoDB function call like NumberLong("2")
                // repair a JSONP function call like callback({...});
                i = j + 1;

                parseValue();

                if (charAt(i) == ')') {
                    // repair: skip close bracket of function call
                    i++;
                    if (charAt(i) == ';') {
                        // repair: skip semicolon after JSONP call
                        i++;
                    }
                }

                return true;
            }
        }

        while (i < text.length() &&
            !StrUtils.isUnquotedStringDelimiter(charAt(i)) &&
            !StrUtils.isQuote(charAt(i)) &&
            (!isKey || charAt(i) != ':')) {
            i++;
        }

        // test start of an url like "https://..." (this would be parsed as a comment)
        if (charAt(i - 1) == ':' && StrUtils.REGEX_URL_START.matcher(text.substring(start, Math.min(text.length(), i + 2))).matches()) {
            while (i < text.length() && StrUtils.REGEX_URL_CHAR.matcher(String.valueOf(charAt(i))).matches()) {
                i++;
            }
        }

        if (i > start) {
            // repair unquoted string
            // also, repair undefined into null

            // first, go back to prevent getting trailing whitespaces in the string
            while (i > 0 && StrUtils.isWhitespace(text, i - 1)) {
                i--;
            }

            String symbol = text.substring(start, i);
            output += "undefined".equals(symbol) ? "null" : quoteString(symbol);

            if (charAt(i) == '"') {
                // we had a missing start quote, but now we encountered the end quote, so we can skip that one
                i++;
            }

            return true;
        }
        return false;
    }

    private boolean parseRegex() {
        if (charAt(i) == '/') {
            int start = i;
            i++;

            while (i < text.length() && (charAt(i) != '/' || charAt(i - 1) == '\\')) {
                i++;
            }
            i++;

            output += quoteString(text.substring(start, i));
            return true;
        }
        return false;
    }

    private int prevNonWhitespaceIndex(int start) {
        int prev = start;

        while (prev > 0 && prev < text.length() && StrUtils.isWhitespace(text, prev)) {
            prev--;
        }

        return prev;
    }

    private boolean atEndOfNumber() {
        return i >= text.length() || StrUtils.isDelimiter(charAt(i)) || StrUtils.isWhitespace(text, i);
    }

    private void repairNumberEndingWithNumericSymbol(int start) {
        // repair numbers cut off at the end
        // this will only be called when we end after a '.', '-', or 'e' and does not
        // change the number more than it needs to make it valid JSON
        output += text.substring(start, i) + "0";
    }

    private String quoteString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\b') sb.append("\\b");
            else if (c == '\f') sb.append("\\f");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else sb.append(c);
        }
        sb.append("\"");
        return sb.toString();
    }

    private void throwInvalidCharacter(char c) throws JSONRepairException {
        throw new JSONRepairException("Invalid character " + quoteString(String.valueOf(c)), i);
    }

    private void throwUnexpectedCharacter() throws JSONRepairException {
        throw new JSONRepairException("Unexpected character " + quoteString(String.valueOf(charAt(i))), i);
    }

    private void throwUnexpectedEnd() throws JSONRepairException {
        throw new JSONRepairException("Unexpected end of json string", text.length());
    }

    private void throwObjectKeyExpected() throws JSONRepairException {
        throw new JSONRepairException("Object key expected", i);
    }

    private void throwColonExpected() throws JSONRepairException {
        throw new JSONRepairException("Colon expected", i);
    }

    private void throwInvalidUnicodeCharacter() throws JSONRepairException {
        String chars = text.substring(i, Math.min(text.length(), i + 6));
        throw new JSONRepairException("Invalid unicode character \"" + chars + "\"", i);
    }
}

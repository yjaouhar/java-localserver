package utils;

import java.util.Stack;

public class JsonFormatValidator {

    public static class InnerJsonFormatValidator {
        public boolean status;
        public String message;
        public Integer index;

        public InnerJsonFormatValidator(boolean status, String message, Integer index) {
            this.status = status;
            this.message = message;
            this.index = index;
        }
    }

    // ========= PUBLIC ENTRY POINT =========
    public static InnerJsonFormatValidator isValidJsonFormat(String json) {

        // CASE 1: null
        if (json == null) {
            return new InnerJsonFormatValidator(false, "JSON is null", 0);
        }

        // CASE 1b: empty or whitespace only
        int firstNonWhitespace = firstNonWhitespaceIndex(json);
        if (firstNonWhitespace == -1) {
            return new InnerJsonFormatValidator(
                    false,
                    "JSON is empty or contains only whitespace",
                    0);
        }

        // CASE 2: root must start with '{' (config must be an object)
        char root = json.charAt(firstNonWhitespace);
        if (root != '{') {
            return new InnerJsonFormatValidator(
                    false,
                    "Config JSON must start with '{'",
                    firstNonWhitespace);
        }

        if (!quotesAreClosed(json)) {
            return new InnerJsonFormatValidator(false, "Quotes are not closed", null);
        }

        // CASE 3: braces/brackets must be balanced (ignoring those inside strings)
        if (!bracesAndBracketsBalanced(json)) {
            return new InnerJsonFormatValidator(false, "Braces and brackets are not balanced", null);
        }

        if (hasTrailingComma(json)) {
            return new InnerJsonFormatValidator(false, "Trailing comma detected", null);
        }
        // CASE 4: reject unknown characters outside strings
        Integer badCharIndex = findInvalidCharOutsideStrings(json);
        if (badCharIndex != null) {
            return new InnerJsonFormatValidator(
                    false,
                    "Invalid character outside strings: '" + json.charAt(badCharIndex) + "'",
                    badCharIndex);
        }

        return new InnerJsonFormatValidator(true, "JSON format looks valid", null);
    }

    // ========= CHECK 1: QUOTES =========
    private static boolean quotesAreClosed(String json) {
        boolean insideString = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                insideString = !insideString;
            }
        }
        return !insideString;
    }

    // ========= CHECK 2: {} and [] (IGNORE inside strings) =========
    private static boolean bracesAndBracketsBalanced(String json) {
        Stack<Character> stack = new Stack<>();
        boolean insideString = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            // toggle string state
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                insideString = !insideString;
                continue;
            }

            // ignore any structural chars inside strings
            if (insideString) {
                continue;
            }

            if (c == '{' || c == '[') {
                stack.push(c);
            } else if (c == '}') {
                if (stack.isEmpty() || stack.pop() != '{') {
                    return false;
                }
            } else if (c == ']') {
                if (stack.isEmpty() || stack.pop() != '[') {
                    return false;
                }
            }
        }
        return stack.isEmpty();
    }

    // ========= CHECK 3: TRAILING COMMAS =========
    private static boolean hasTrailingComma(String json) {
        boolean insideString = false;

        for (int i = 0; i < json.length() - 1; i++) {
            char c = json.charAt(i);

            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                insideString = !insideString;
            }

            if (!insideString && c == ',') {
                int j = i + 1;
                while (j < json.length() && isWhitespace(json.charAt(j))) {
                    j++;
                }
                if (j < json.length()) {
                    char next = json.charAt(j);
                    if (next == '}' || next == ']') {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ========= CHECK 4: INVALID CHARS OUTSIDE STRINGS =========
    private static Integer findInvalidCharOutsideStrings(String json) {
        boolean insideString = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                insideString = !insideString;
                continue;
            }

            if (insideString)
                continue;

            // allowed whitespace
            if (isWhitespace(c))
                continue;

            // allowed JSON structural chars
            if (c == '{' || c == '}' || c == '[' || c == ']' || c == ',' || c == ':')
                continue;

            // allow digits and minus (for numbers)
            if (c == '-' || isDigit(c))
                continue;

            // allow t/f/n (for true/false/null beginnings)
            if (c == 't' || c == 'f' || c == 'n' || c == 'r' || c == 'e' || c == 'l' || c == 'u' || c == 'a'
                    || c == 's')
                continue;

            // anything else is invalid
            return i;
        }
        return null;
    }

    // ========= HELPERS =========
    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\n' || c == '\t' || c == '\r';
    }

    private static int firstNonWhitespaceIndex(String json) {
        for (int i = 0; i < json.length(); i++) {
            if (!isWhitespace(json.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}

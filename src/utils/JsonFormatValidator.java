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

    private static class StructuralError {

        public final int index;
        public final String message;

        public StructuralError(int index, String message) {
            this.index = index;
            this.message = message;
        }
    }

    private enum Ctx {
        OBJECT, ARRAY
    }

    private enum Expect {
        // OBJECT
        OBJ_KEY_OR_END,
        OBJ_COLON,
        OBJ_VALUE,
        OBJ_COMMA_OR_END,
        // ARRAY
        ARR_VALUE_OR_END,
        ARR_COMMA_OR_END
    }

    public static InnerJsonFormatValidator isValidJsonFormat(String json) {

        if (json == null) {
            return new InnerJsonFormatValidator(false, "JSON is null", 0);
        }

        int firstNonWhitespace = firstNonWhitespaceIndex(json);
        if (firstNonWhitespace == -1) {
            return new InnerJsonFormatValidator(false, "JSON is empty or contains only whitespace", 0);
        }

        if (json.charAt(firstNonWhitespace) != '{') {
            return new InnerJsonFormatValidator(false, "Config JSON must start with '{'", firstNonWhitespace);
        }

        if (!quotesAreClosed(json)) {
            return new InnerJsonFormatValidator(false, "Quotes are not closed", null);
        }

        Integer braceErr = findBraceBracketErrorIndex(json);
        if (braceErr != null) {
            return new InnerJsonFormatValidator(false, "Braces/brackets mismatch or unmatched", braceErr);
        }

        Integer trailingIndex = findTrailingCommaIndex(json);
        if (trailingIndex != null) {
            return new InnerJsonFormatValidator(false, "Trailing comma before closing bracket/brace", trailingIndex);
        }

        StructuralError se = findStructuralError(json, firstNonWhitespace);
        if (se != null) {
            return new InnerJsonFormatValidator(false, se.message, se.index);
        }

        Integer badChar = findInvalidCharOutsideStrings(json);
        if (badChar != null) {
            return new InnerJsonFormatValidator(
                    false,
                    "Invalid character outside strings: '" + json.charAt(badChar) + "'",
                    badChar
            );
        }

        Integer extra = findExtraContentAfterRootObject(json, firstNonWhitespace);
        if (extra != null) {
            return new InnerJsonFormatValidator(false, "Extra content after root JSON object", extra);
        }

        return new InnerJsonFormatValidator(true, "JSON format looks valid", null);
    }

    // ========= CASE 3: QUOTES =========
    private static boolean quotesAreClosed(String json) {
        boolean inside = false;
        for (int i = 0; i < json.length(); i++) {
            if (json.charAt(i) == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inside = !inside;
            }
        }
        return !inside;
    }

    // ========= CASE 6: BRACES / BRACKETS (IGNORE inside strings) =========
    private static Integer findBraceBracketErrorIndex(String json) {
        Stack<Character> stack = new Stack<>();
        Stack<Integer> pos = new Stack<>();
        boolean inside = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inside = !inside;
                continue;
            }
            if (inside) {
                continue;
            }

            if (c == '{' || c == '[') {
                stack.push(c);
                pos.push(i);
            } else if (c == '}' || c == ']') {
                if (stack.isEmpty()) {
                    return i; 

                }
                char open = stack.pop();
                pos.pop();
                if ((c == '}' && open != '{') || (c == ']' && open != '[')) {
                    return i; 
                }
            }
        }

        if (!stack.isEmpty()) {
            return pos.peek();
        }
        return null;
    }

    // ========= CASE 7: TRAILING COMMA WITH INDEX =========
    private static Integer findTrailingCommaIndex(String json) {
        boolean inside = false;

        for (int i = 0; i < json.length() - 1; i++) {
            char c = json.charAt(i);

            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inside = !inside;
                continue;
            }
            if (inside) {
                continue;
            }

            if (c == ',') {
                int j = i + 1;
                while (j < json.length() && isWhitespace(json.charAt(j))) {
                    j++;
                }
                if (j < json.length()) {
                    char next = json.charAt(j);
                    if (next == '}' || next == ']') {
                        return i;
                    }
                }
            }
        }
        return null;
    }

    // ========= CASE 8 + 9: ':' and ',' rules + strict primitives =========
    private static StructuralError findStructuralError(String json, int rootStartIndex) {
        Stack<Ctx> ctx = new Stack<>();
        ctx.push(Ctx.OBJECT);

        Expect expect = Expect.OBJ_KEY_OR_END;

        for (int i = rootStartIndex + 1; i < json.length();) {
            char c = json.charAt(i);

            
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                int end = findStringEnd(json, i);
                if (end < 0) {
                    return new StructuralError(i, "Invalid string (missing closing quote)");
                }

                if (ctx.peek() == Ctx.OBJECT) {
                    if (expect == Expect.OBJ_KEY_OR_END) {
                        expect = Expect.OBJ_COLON;
                    } else if (expect == Expect.OBJ_VALUE) {
                        expect = Expect.OBJ_COMMA_OR_END;
                    } else {
                        return new StructuralError(i, "Unexpected string in object");
                    }
                } else {
                    if (expect == Expect.ARR_VALUE_OR_END) {
                        expect = Expect.ARR_COMMA_OR_END;
                    } else {
                        return new StructuralError(i, "Unexpected string in array");
                    }
                }

                i = end + 1;
                continue;
            }

            if (isWhitespace(c)) {
                i++;
                continue;
            }

            if (c == '{') {
                if (ctx.peek() == Ctx.OBJECT) {
                    if (expect != Expect.OBJ_VALUE) {
                        return new StructuralError(i, "Unexpected '{' (value expected after ':')");
                    }
                    ctx.push(Ctx.OBJECT);
                    expect = Expect.OBJ_KEY_OR_END;
                } else {
                    if (expect != Expect.ARR_VALUE_OR_END) {
                        return new StructuralError(i, "Unexpected '{' in array");
                    }
                    ctx.push(Ctx.OBJECT);
                    expect = Expect.OBJ_KEY_OR_END;
                }
                i++;
                continue;
            }

            if (c == '[') {
                if (ctx.peek() == Ctx.OBJECT) {
                    if (expect != Expect.OBJ_VALUE) {
                        return new StructuralError(i, "Unexpected '[' (value expected after ':')");
                    }
                    ctx.push(Ctx.ARRAY);
                    expect = Expect.ARR_VALUE_OR_END;
                } else {
                    if (expect != Expect.ARR_VALUE_OR_END) {
                        return new StructuralError(i, "Unexpected '[' in array");
                    }
                    ctx.push(Ctx.ARRAY);
                    expect = Expect.ARR_VALUE_OR_END;
                }
                i++;
                continue;
            }

            if (c == '}' || c == ']') {
                Ctx top = ctx.peek();

                if (c == '}' && top != Ctx.OBJECT) {
                    return new StructuralError(i, "Mismatched closing '}'");
                }
                if (c == ']' && top != Ctx.ARRAY) {
                    return new StructuralError(i, "Mismatched closing ']'");
                }

                if (top == Ctx.OBJECT) {
                    if (expect == Expect.OBJ_COLON || expect == Expect.OBJ_VALUE) {
                        return new StructuralError(i, "Object ended but key/value is incomplete");
                    }
                    if (expect != Expect.OBJ_KEY_OR_END && expect != Expect.OBJ_COMMA_OR_END) {
                        return new StructuralError(i, "Unexpected '}'");
                    }
                } else {
                    if (expect != Expect.ARR_VALUE_OR_END && expect != Expect.ARR_COMMA_OR_END) {
                        return new StructuralError(i, "Unexpected ']'");
                    }
                }

                ctx.pop();

                if (ctx.isEmpty()) {
                    return null;
                } else {
                    if (ctx.peek() == Ctx.OBJECT) {
                        expect = Expect.OBJ_COMMA_OR_END;
                    } else {
                        expect = Expect.ARR_COMMA_OR_END;
                    }
                }

                i++;
                continue;
            }

            if (c == ':') {
                if (ctx.peek() != Ctx.OBJECT) {
                    return new StructuralError(i, "':' is not allowed inside arrays");
                }
                if (expect != Expect.OBJ_COLON) {
                    return new StructuralError(i, "Unexpected ':' (colon position is wrong)");
                }
                expect = Expect.OBJ_VALUE;
                i++;
                continue;
            }

            
            if (c == ',') {
                if (ctx.peek() == Ctx.OBJECT) {
                    if (expect != Expect.OBJ_COMMA_OR_END) {
                        return new StructuralError(i, "Unexpected ',' in object");
                    }
                    expect = Expect.OBJ_KEY_OR_END;
                } else {
                    if (expect != Expect.ARR_COMMA_OR_END) {
                        return new StructuralError(i, "Unexpected ',' in array");
                    }
                    expect = Expect.ARR_VALUE_OR_END;
                }
                i++;
                continue;
            }

            if (ctx.peek() == Ctx.OBJECT) {
                if (expect != Expect.OBJ_VALUE) {
                    return new StructuralError(i, "Unexpected token in object (value must come after ':')");
                }
                int next = consumePrimitiveToken(json, i);
                if (next == i) {
                    return new StructuralError(i, "Invalid token");
                }

                String token = json.substring(i, next);
                if (!isValidConfigPrimitive(token)) {
                    return new StructuralError(i, "Invalid primitive token: " + token);
                }

                expect = Expect.OBJ_COMMA_OR_END;
                i = next;
                continue;
            } else {
                if (expect != Expect.ARR_VALUE_OR_END) {
                    return new StructuralError(i, "Unexpected token in array");
                }
                int next = consumePrimitiveToken(json, i);
                if (next == i) {
                    return new StructuralError(i, "Invalid token");
                }

                String token = json.substring(i, next);
                if (!isValidConfigPrimitive(token)) {
                    return new StructuralError(i, "Invalid primitive token: " + token);
                }

                expect = Expect.ARR_COMMA_OR_END;
                i = next;
                continue;
            }
        }

        return null;
    }

    private static int findStringEnd(String s, int startQuote) {
        for (int i = startQuote + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && s.charAt(i - 1) != '\\') {
                return i;
            }
        }
        return -1;
    }

    private static int consumePrimitiveToken(String s, int i) {
        int n = s.length();
        int start = i;
        while (i < n) {
            char c = s.charAt(i);
            if (isWhitespace(c) || c == ',' || c == ']' || c == '}' || c == ':') {
                break;
            }
            i++;
        }
        return (i == start) ? start : i;
    }

    // ========= CASE 9: CONFIG PRIMITIVES =========
    private static boolean isValidConfigPrimitive(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }

        if (token.equals("true") || token.equals("false") || token.equals("null")) {
            return true;
        }
        return isValidNonNegativeInteger(token);
    }

    private static boolean isValidNonNegativeInteger(String s) {
        int n = s.length();
        if (n == 0) {
            return false;
        }

        char first = s.charAt(0);
        if (first == '-' || first == '+') {
            return false;
        }

        for (int i = 0; i < n; i++) {
            if (!isDigit(s.charAt(i))) {
                return false;
            }
        }

        if (n > 1 && s.charAt(0) == '0') {
            return false;
        }

        return true;
    }

    private static Integer findInvalidCharOutsideStrings(String json) {
        boolean inside = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inside = !inside;
                continue;
            }
            if (inside) {
                continue;
            }

            if (isWhitespace(c)) {
                continue;
            }
            if ("{}[],:".indexOf(c) >= 0) {
                continue;
            }
            if (isDigit(c)) {
                continue;
            }

            // allow letters used in true/false/null
            if ("tfnrueals".indexOf(c) >= 0) {
                continue;
            }

            return i;
        }
        return null;
    }

    // ========= CASE 5: EXTRA CONTENT AFTER ROOT OBJECT =========
    private static Integer findExtraContentAfterRootObject(String json, int start) {
        boolean inside = false;
        int depth = 0;

        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);

            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inside = !inside;
                continue;
            }
            if (inside) {
                continue;
            }

            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    int j = i + 1;
                    while (j < json.length() && isWhitespace(json.charAt(j))) {
                        j++;
                    }
                    if (j < json.length()) {
                        return j;
                    }
                    return null;
                }
            }
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

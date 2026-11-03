import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON minimo per parlare con `claude -p --input-format stream-json --output-format stream-json`:
 * legge un evento per riga (oggetti, array, stringhe, numeri, true, false, null; un oggetto diventa
 * una Map, un array una List, un numero un Double) e scrive le stringhe dei messaggi.
 * Niente librerie: lo script di pulizia parte con `java <File>.java`, senza Maven.
 */
final class Json {

    private final String text;
    private int position;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.position != text.length())
            throw parser.failure("testo dopo il valore");
        return value;
    }

    //<editor-fold desc="Accesso ai campi">

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, String name) {
        if (value instanceof Map<?, ?> map)
            return (Map<String, Object>) map;
        throw new IllegalArgumentException(name + " non e' un oggetto JSON");
    }

    static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value instanceof String string ? string : null;
    }

    static long number(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value instanceof Double number ? number.longValue() : 0;
    }

    static double decimal(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value instanceof Double number ? number : 0;
    }

    static boolean bool(Map<String, Object> object, String key) {
        return Boolean.TRUE.equals(object.get(key));
    }

    static Map<String, Object> objectOrEmpty(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value instanceof Map<?, ?> ? object(value, key) : Map.of();
    }

    static List<Object> arrayOrEmpty(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (value instanceof List<?> list)
            return List.copyOf(list);
        return List.of();
    }

    //</editor-fold>

    //<editor-fold desc="Scrittura">

    static String quote(String text) {
        StringBuilder quoted = new StringBuilder(text.length() + 16).append('"');
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            switch (current) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                case '\b' -> quoted.append("\\b");
                case '\f' -> quoted.append("\\f");
                default -> {
                    if (current < 0x20)
                        quoted.append(String.format("\\u%04x", (int) current));
                    else
                        quoted.append(current);
                }
            }
        }
        return quoted.append('"').toString();
    }

    //</editor-fold>

    //<editor-fold desc="Lettura" defaultstate="collapsed">

    private Object readValue() {
        if (position >= text.length())
            throw failure("fine del testo inattesa");
        char current = text.charAt(position);
        return switch (current) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> object = new LinkedHashMap<>();
        position++;
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return object;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"')
                throw failure("chiave attesa");
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            object.put(key, readValue());
            skipWhitespace();
            char separator = next();
            if (separator == '}')
                return object;
            if (separator != ',')
                throw failure("',' o '}' attesi");
        }
    }

    private List<Object> readArray() {
        List<Object> array = new ArrayList<>();
        position++;
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return array;
        }
        while (true) {
            skipWhitespace();
            array.add(readValue());
            skipWhitespace();
            char separator = next();
            if (separator == ']')
                return array;
            if (separator != ',')
                throw failure("',' o ']' attesi");
        }
    }

    private String readString() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (true) {
            char current = next();
            if (current == '"')
                return value.toString();
            if (current != '\\') {
                value.append(current);
                continue;
            }
            char escaped = next();
            switch (escaped) {
                case '"', '\\', '/' -> value.append(escaped);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (position + 4 > text.length())
                        throw failure("escape \\u troncato");
                    value.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                    position += 4;
                }
                default -> throw failure("escape sconosciuto \\" + escaped);
            }
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!text.startsWith(literal, position))
            throw failure("valore sconosciuto");
        position += literal.length();
        return value;
    }

    private Double readNumber() {
        int start = position;
        while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0)
            position++;
        if (start == position)
            throw failure("valore atteso");
        return Double.parseDouble(text.substring(start, position));
    }

    private void skipWhitespace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position)))
            position++;
    }

    private char peek() {
        if (position >= text.length())
            throw failure("fine del testo inattesa");
        return text.charAt(position);
    }

    private char next() {
        char current = peek();
        position++;
        return current;
    }

    private void expect(char expected) {
        if (next() != expected)
            throw failure("'" + expected + "' atteso");
    }

    private IllegalArgumentException failure(String message) {
        return new IllegalArgumentException("JSON non valido alla posizione " + position + ": " + message);
    }

    //</editor-fold>
}

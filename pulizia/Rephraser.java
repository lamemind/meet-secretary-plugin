import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Il protocollo fra lo script di pulizia e il `claude -p` persistente: cosa gli si manda (system
 * prompt del meet, messaggio di un chunk, richiesta del titolo di una fascia) e come si legge la
 * risposta.
 *
 * Un chunk porta le righe del meet dopo il segnalibro. La risposta e' `incompleto`, oppure
 * `chiuso <k>` con un tratto per riga fino alla riga k: righe da-a, `fuffa` o il tema, l'etichetta
 * dell'argomento, il testo corretto. Il processo ha memoria: il contesto, i partecipanti e
 * l'argomento in corso si mandano solo al primo chunk dopo l'avvio, e i partecipanti nuovi quando
 * compaiono.
 */
final class Rephraser {

    static final String INCOMPLETE = "incompleto";
    static final String FUFFA = "fuffa";
    static final String OTHER_THEME = "altro";

    private static final Pattern CLOSED_HEADER = Pattern.compile("^chiuso\\s+#?(\\d+)\\s*$");
    private static final Pattern SEGMENT_START = Pattern.compile("^#?\\d");
    private static final Pattern RANGE = Pattern.compile("^#?(\\d+)(?:\\s*-\\s*#?(\\d+))?$");
    private static final Pattern SEGMENT_WITHOUT_TABS = Pattern.compile(
            "^#?(\\d+)(?:\\s*-\\s*#?(\\d+))?\\s+(?:lavoro\\s+)?(\\S+)\\s+(\\S+)\\s+(.+)$");
    private static final Pattern LABEL = Pattern.compile("^[\\p{Ll}\\p{N}]+(?:-[\\p{Ll}\\p{N}]+)*$");
    private static final Pattern TITLE_DECORATION = Pattern.compile("^(?:titolo\\s*:?\\s*|[#*>\"'«]+\\s*)+",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_TITLE_LENGTH = 120;

    record Line(int number, String time, String speaker, String text, int revises) {

        String asPromptLine() {
            String line = "#" + number + " " + time + " " + speaker + ": " + text;
            return revises > 0 ? line + " [rivede #" + revises + "]" : line;
        }
    }

    enum Outcome { INCOMPLETE, CLOSED, ERROR }

    record Segment(int from, int to, String theme, String label, String text) {

        boolean work() {
            return !theme.equals(FUFFA);
        }
    }

    record Reply(Outcome outcome, int closedThrough, List<Segment> segments, String error) {

        static Reply failed(String error) {
            return new Reply(Outcome.ERROR, 0, List.of(), error);
        }
    }

    /**
     * Cosa porta un chunk oltre alle righe nuove. context, participants e currentLabel sono vuoti
     * tranne al primo chunk dopo l'avvio del processo, newParticipants quando compare una voce.
     */
    record Chunk(List<Line> context, List<String> participants, List<String> newParticipants,
                 String currentLabel, List<Line> newLines, boolean forceClose) {
    }

    private Rephraser() {
    }

    //<editor-fold desc="Messaggi">

    static String chunkMessage(Chunk chunk) {
        StringBuilder message = new StringBuilder();
        if (!chunk.participants().isEmpty()) {
            message.append("PARTECIPANTI\n");
            chunk.participants().forEach(name -> message.append(name).append('\n'));
            message.append('\n');
        }
        if (!chunk.newParticipants().isEmpty()) {
            message.append("PARTECIPANTI NUOVI\n");
            chunk.newParticipants().forEach(name -> message.append(name).append('\n'));
            message.append('\n');
        }
        if (!chunk.context().isEmpty()) {
            message.append("CONTESTO\n");
            chunk.context().forEach(line -> message.append(line.asPromptLine()).append('\n'));
            message.append('\n');
        }
        if (chunk.currentLabel() != null)
            message.append("ARGOMENTO IN CORSO: ").append(chunk.currentLabel()).append("\n\n");
        message.append("RIGHE NUOVE\n");
        chunk.newLines().forEach(line -> message.append(line.asPromptLine()).append('\n'));
        if (chunk.forceClose())
            message.append("\nCHIUSURA FORZATA: chiudi fino alla riga ")
                    .append(chunk.newLines().getLast().number()).append(".\n");
        return message.toString();
    }

    /**
     * segmentTexts e' vuoto quando il processo ha visto tutta la fascia: allora basta la memoria.
     */
    static String titleMessage(int from, int to, String label, List<String> segmentTexts) {
        StringBuilder message = new StringBuilder("TITOLO ").append(from).append('-').append(to)
                .append(' ').append(label).append('\n');
        if (!segmentTexts.isEmpty()) {
            message.append("\nTESTO DELLA FASCIA\n");
            segmentTexts.forEach(text -> message.append(text).append('\n'));
        }
        return message.toString();
    }

    //</editor-fold>

    //<editor-fold desc="Risposta">

    /**
     * La risposta vale solo se i tratti coprono di seguito le righe dalla prima nuova fino a k,
     * senza buchi ne' sovrapposizioni, se ogni tema sta nella lista e se ogni tratto porta
     * un'etichetta. Sotto chiusura forzata k deve essere l'ultima riga nuova.
     *
     * Valgono lo stesso, perche' non tolgono niente: un tratto di una riga scritto `<n>` invece di
     * `<n>-<n>`, la forma `lavoro <tema>`, le righe di prosa dopo i tratti, che non cominciano con
     * un numero, i tratti che stanno tutti prima della prima riga nuova, e l'etichetta in maiuscolo.
     */
    static Reply parseReply(String replyText, List<Line> newLines, boolean forceClose, List<String> themes) {
        List<String> lines = replyText.strip().lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("```"))
                .toList();
        if (lines.isEmpty())
            return Reply.failed("risposta vuota");

        String header = lines.getFirst();
        if (header.equalsIgnoreCase(INCOMPLETE)) {
            if (lines.size() > 1)
                return Reply.failed("incompleto seguito da altre righe");
            if (forceClose)
                return Reply.failed("incompleto sotto chiusura forzata");
            return new Reply(Outcome.INCOMPLETE, 0, List.of(), null);
        }

        Matcher closed = CLOSED_HEADER.matcher(header);
        if (!closed.matches())
            return Reply.failed("prima riga ne' incompleto ne' chiuso <k>: " + abbreviate(header));
        int closedThrough = Integer.parseInt(closed.group(1));
        int firstNew = newLines.getFirst().number();
        int lastNew = newLines.getLast().number();
        if (closedThrough < firstNew || closedThrough > lastNew)
            return Reply.failed("chiuso " + closedThrough + " fuori dalle righe nuove " + firstNew + "-" + lastNew);
        if (forceClose && closedThrough != lastNew)
            return Reply.failed("chiusura forzata, ma chiuso " + closedThrough + " invece di " + lastNew);

        List<Segment> segments = new ArrayList<>();
        int expectedFrom = firstNew;
        for (String line : lines.subList(1, lines.size())) {
            if (!SEGMENT_START.matcher(line).find())
                continue;
            Segment segment = parseSegment(line);
            if (segment == null)
                return Reply.failed("tratto illeggibile: " + abbreviate(line));
            if (segment.to() < firstNew)
                continue;
            if (segment.from() != expectedFrom || segment.to() < segment.from())
                return Reply.failed("tratto " + segment.from() + "-" + segment.to() + ", atteso da " + expectedFrom);
            if (segment.work() && !themes.contains(segment.theme()) && !segment.theme().equals(OTHER_THEME))
                return Reply.failed("tema fuori lista: " + segment.theme());
            if (!LABEL.matcher(segment.label()).matches())
                return Reply.failed("etichetta illeggibile: " + abbreviate(segment.label()));
            segments.add(segment);
            expectedFrom = segment.to() + 1;
        }
        if (segments.isEmpty())
            return Reply.failed("chiuso " + closedThrough + " senza tratti");
        if (expectedFrom != closedThrough + 1)
            return Reply.failed("i tratti finiscono a " + (expectedFrom - 1) + ", chiuso " + closedThrough);
        return new Reply(Outcome.CLOSED, closedThrough, List.copyOf(segments), null);
    }

    private static Segment parseSegment(String line) {
        String[] fields = line.split("\t");
        if (fields.length >= 4) {
            Matcher range = RANGE.matcher(fields[0].strip());
            if (!range.matches())
                return null;
            String theme = fields[1].strip().replaceFirst("^lavoro\\s+", "");
            String text = String.join(" ", List.of(fields).subList(3, fields.length)).strip();
            return segment(range.group(1), range.group(2), theme, fields[2].strip(), text);
        }
        if (fields.length > 1)
            return null;
        Matcher segment = SEGMENT_WITHOUT_TABS.matcher(line);
        if (!segment.matches())
            return null;
        return segment(segment.group(1), segment.group(2), segment.group(3), segment.group(4), segment.group(5).strip());
    }

    private static Segment segment(String from, String to, String theme, String label, String text) {
        int first = Integer.parseInt(from);
        int last = to == null ? first : Integer.parseInt(to);
        return new Segment(first, last, theme.toLowerCase(Locale.ROOT), label.toLowerCase(Locale.ROOT), text);
    }

    /**
     * La prima riga non vuota, senza decorazioni (`Titolo:`, virgolette, markdown); null se la
     * risposta non e' un titolo.
     */
    static String parseTitle(String replyText) {
        String firstLine = replyText.strip().lines().map(String::strip).filter(line -> !line.isEmpty())
                .findFirst().orElse("");
        String title = TITLE_DECORATION.matcher(firstLine).replaceFirst("")
                .replaceAll("[\"'»*]+$", "").replace('\t', ' ').strip();
        if (title.isEmpty() || title.equalsIgnoreCase(INCOMPLETE) || CLOSED_HEADER.matcher(title).matches())
            return null;
        return title.length() <= MAX_TITLE_LENGTH ? title : title.substring(0, MAX_TITLE_LENGTH);
    }

    //</editor-fold>

    //<editor-fold desc="System prompt del meet">

    /**
     * Le istruzioni, il glossario e la lista dei temi coi loro keyword. Il prompt si aggiunge al
     * system prompt di Claude Code, sopra il CLAUDE.md del progetto, e si fissa all'avvio del
     * processo: cambiarlo vuol dire riavviarlo. I nomi dei partecipanti non ci stanno, arrivano nei
     * messaggi, cosi' una voce nuova non costa un riavvio.
     */
    static String systemPrompt(Path instructions, List<String> glossaryLines, Map<String, String> themes)
            throws IOException {
        StringBuilder prompt = new StringBuilder(Files.readString(instructions, StandardCharsets.UTF_8).strip());
        prompt.append("\n\n# GLOSSARIO\n\n");
        glossaryLines.forEach(line -> prompt.append(line).append('\n'));
        prompt.append("\n# TEMI\n\n");
        themes.forEach((name, keywords) -> prompt.append(name)
                .append(keywords.isEmpty() ? "" : ": " + keywords).append('\n'));
        prompt.append(OTHER_THEME).append(": lavoro che non sta in nessun tema della lista\n");
        return prompt.toString();
    }

    /**
     * Una riga per tema, `nome: keyword, keyword` oppure solo `nome`. Un tema che c'e' gia' prende
     * in coda i keyword nuovi.
     */
    static void mergeThemes(Map<String, String> themes, List<String> themeLines) {
        for (String line : themeLines) {
            int colon = line.indexOf(':');
            String name = (colon < 0 ? line : line.substring(0, colon)).strip().toLowerCase(Locale.ROOT);
            String keywords = colon < 0 ? "" : line.substring(colon + 1).strip();
            if (name.isEmpty() || !LABEL.matcher(name).matches())
                throw new IllegalArgumentException("nome di tema non valido: " + line);
            String known = themes.get(name);
            if (known == null || known.isEmpty())
                themes.put(name, keywords);
            else if (!keywords.isEmpty())
                themes.put(name, known + ", " + keywords);
        }
    }

    static Map<String, String> themes(List<String> themeLines) {
        Map<String, String> themes = new LinkedHashMap<>();
        mergeThemes(themes, themeLines);
        return themes;
    }

    /**
     * Le righe di un file di configurazione, senza le vuote e quelle che cominciano con #. Un file
     * assente da' una lista vuota.
     */
    static List<String> configurationLines(Path file) throws IOException {
        if (!Files.isRegularFile(file))
            return List.of();
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    //</editor-fold>

    //<editor-fold desc="Utilita'" defaultstate="collapsed">

    static String abbreviate(String text) {
        return text.length() <= 160 ? text : text.substring(0, 160) + "...";
    }

    //</editor-fold>
}

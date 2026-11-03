import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Gli eventi che non passano da claude: il nome del segretario e le parole chiave, cercati con una
 * regex sul testo grezzo, cosi' scattano in pochi secondi invece dei circa 20 della pulizia.
 *
 * Parola chiave: una riga di qualunque voce che contiene una parola della sezione `[parole]` del
 * file di settings, senza distinzione fra maiuscole e minuscole e a parola intera, da' un evento su
 * quella riga.
 *
 * Nome: conta solo sulle righe della voce dell'utente. Il turno dell'utente e' la sequenza delle sue
 * righe consecutive; finisce quando parla un'altra voce o dopo 3 s senza righe nuove. Un turno con
 * il nome da' un evento alla sua fine, con le righe di tutto il turno: cosi' l'evento porta la
 * richiesta intera, detta prima o dopo il nome, e non solo il nome. Senza voce dell'utente o senza
 * regex del nome, il nome non da' eventi.
 *
 * Parole, voce e regex si cambiano a pulizia accesa: valgono dalla prima riga letta dopo il cambio.
 */
final class Triggers {

    static final long SILENCE_MILLIS = 3_000;

    record Event(long millis, String kind, int from, int to, String detail) {
    }

    private String userVoice;
    private Pattern namePattern;
    private List<String> keywords = List.of();
    private List<Pattern> keywordPatterns = List.of();

    private int scannedThrough;
    private int turnFrom;
    private int turnTo;
    private long turnLastMillis;
    private boolean turnNamed;

    synchronized void setSecretary(String voice, Pattern pattern) {
        userVoice = voice;
        namePattern = pattern;
    }

    synchronized void resumeAfter(int lineNumber) {
        scannedThrough = lineNumber;
    }

    synchronized int scannedThrough() {
        return scannedThrough;
    }

    synchronized void setKeywords(List<String> words) {
        if (words.equals(keywords))
            return;
        keywords = List.copyOf(words);
        List<Pattern> patterns = new ArrayList<>();
        for (String word : words)
            patterns.add(Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word.toLowerCase(Locale.ROOT))
                    + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        keywordPatterns = List.copyOf(patterns);
    }

    /**
     * Le righe disponibili e non ancora lette, in ordine, poi il silenzio fino a nowMillis. L'ora di
     * un evento e' quella in cui la condizione si avvera: la riga della parola, la riga dell'altra
     * voce, o l'ultima riga dell'utente piu' 3 s.
     */
    synchronized List<Event> advance(MeetFeed feed, long nowMillis) {
        List<Event> events = new ArrayList<>();
        int available = feed.size();
        while (scannedThrough < available) {
            int number = scannedThrough + 1;
            Rephraser.Line line = feed.line(number);
            long lineMillis = feed.availableAt(number);
            closeTurnAfterSilence(lineMillis, events);
            boolean userLine = userVoice != null && line.speaker().equalsIgnoreCase(userVoice);
            if (!userLine)
                closeTurn(lineMillis, events);
            else
                extendTurn(line, lineMillis);
            String matched = matchedKeywords(line.text());
            if (matched != null)
                events.add(new Event(lineMillis, "parola", number, number, matched));
            scannedThrough = number;
        }
        closeTurnAfterSilence(nowMillis, events);
        return events;
    }

    //<editor-fold desc="Turno dell'utente">

    private void extendTurn(Rephraser.Line line, long lineMillis) {
        if (turnFrom == 0)
            turnFrom = line.number();
        turnTo = line.number();
        turnLastMillis = lineMillis;
        if (namePattern != null && namePattern.matcher(line.text()).find())
            turnNamed = true;
    }

    private void closeTurnAfterSilence(long nowMillis, List<Event> events) {
        if (turnFrom != 0 && nowMillis - turnLastMillis >= SILENCE_MILLIS)
            closeTurn(turnLastMillis + SILENCE_MILLIS, events);
    }

    private void closeTurn(long millis, List<Event> events) {
        if (turnFrom != 0 && turnNamed)
            events.add(new Event(millis, "nome", turnFrom, turnTo, userVoice));
        turnFrom = 0;
        turnTo = 0;
        turnNamed = false;
    }

    //</editor-fold>

    private String matchedKeywords(String text) {
        List<String> matched = new ArrayList<>();
        for (int index = 0; index < keywordPatterns.size(); index++)
            if (keywordPatterns.get(index).matcher(text).find())
                matched.add(keywords.get(index));
        return matched.isEmpty() ? null : String.join(", ", matched);
    }
}

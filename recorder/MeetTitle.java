import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Il nome dei file di un meet, dal titolo della finestra in cui stanno i sottotitoli:
 * <yyyyMMdd-HHmm>-<slug del titolo>, per esempio 20261023-1440-allineamento-settimanale.
 *
 * I titoli che Teams da' alle finestre di un meet, misurati:
 *   Riunione con <organizzatore> | Microsoft Teams                    riunione immediata
 *   Riunione nel canale <canale> | <team> | Microsoft Teams           meet di canale
 *   <oggetto> | Microsoft Teams                                       meet programmato
 *   <titolo del meet> | Finestra ancorata                             sottotitoli staccati
 *
 * Lo slug toglie le parti fisse (Microsoft Teams, Finestra ancorata, il prefisso Riunione con o
 * Riunione nel canale), gli accenti e tutto cio' che non e' una lettera o una cifra: restano lettere
 * minuscole, cifre e trattini, quindi nessun carattere che Windows rifiuta in un nome di file e
 * nessun punto, che chi legge la cartella dei meet usa per separare il meet dai file derivati.
 */
final class MeetTitle {

    static final String DOCKED_WINDOW_PART = "Finestra ancorata";
    static final String FALLBACK_SLUG = "meet";

    private static final List<String> FIXED_PARTS = List.of("Microsoft Teams", DOCKED_WINDOW_PART);
    private static final List<String> FIXED_PREFIXES = List.of("Riunione nel canale ", "Riunione con ");
    private static final String PART_SEPARATOR = "|";
    private static final int MAX_SLUG_LENGTH = 60;
    private static final DateTimeFormatter STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private MeetTitle() {
    }

    static String baseName(long startedMillis, String windowName) {
        return STAMP_FORMAT.format(Instant.ofEpochMilli(startedMillis).atZone(ZONE)) + "-" + slug(windowName);
    }

    /**
     * Il titolo del meet: il titolo della finestra senza la parte della finestra ancorata. La finestra
     * del meet e quella dei sottotitoli staccati hanno cosi' lo stesso titolo del meet.
     */
    static String meetTitleOf(String windowName) {
        if (windowName == null)
            return "";
        List<String> kept = new ArrayList<>();
        for (String part : splitParts(windowName))
            if (!part.equalsIgnoreCase(DOCKED_WINDOW_PART))
                kept.add(part);
        return String.join(" " + PART_SEPARATOR + " ", kept);
    }

    static String slug(String windowName) {
        if (windowName == null)
            return FALLBACK_SLUG;
        List<String> words = new ArrayList<>();
        for (String part : splitParts(windowName)) {
            if (FIXED_PARTS.stream().anyMatch(part::equalsIgnoreCase))
                continue;
            words.add(words.isEmpty() ? withoutFixedPrefix(part) : part);
        }
        String plain = Normalizer.normalize(String.join(" ", words), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-");
        String trimmed = trimDashes(plain);
        if (trimmed.length() > MAX_SLUG_LENGTH) {
            String cut = trimmed.substring(0, MAX_SLUG_LENGTH);
            int lastDash = cut.lastIndexOf('-');
            trimmed = trimDashes(lastDash > 0 ? cut.substring(0, lastDash) : cut);
        }
        return trimmed.isEmpty() ? FALLBACK_SLUG : trimmed;
    }

    //<editor-fold desc="Parti del titolo" defaultstate="collapsed">

    private static List<String> splitParts(String windowName) {
        List<String> parts = new ArrayList<>();
        for (String part : windowName.split("\\" + PART_SEPARATOR)) {
            String stripped = part.strip();
            if (!stripped.isEmpty())
                parts.add(stripped);
        }
        return parts;
    }

    private static String withoutFixedPrefix(String part) {
        for (String prefix : FIXED_PREFIXES)
            if (part.regionMatches(true, 0, prefix, 0, prefix.length()))
                return part.substring(prefix.length());
        return part;
    }

    private static String trimDashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-')
            start++;
        while (end > start && value.charAt(end - 1) == '-')
            end--;
        return value.substring(start, end);
    }

    //</editor-fold>
}

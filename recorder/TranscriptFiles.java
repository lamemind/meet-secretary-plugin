import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * I due file di un meet, nella cartella di uscita:
 * - <base>.tsv: una riga per frase chiusa, HH:mm:ss<TAB>nome<TAB>testo, UTF-8, fine riga LF. L'ora
 *   e' quella in cui la voce compare nei sottotitoli. Tab e a capo del testo diventano spazi. Una
 *   frase che Teams riscrive dopo che e' stata scritta torna come riga nuova con una quarta colonna,
 *   il numero della riga che sostituisce;
 * - <base>.diag.tsv: il diario del lettore, senza testo dei meet. Per ogni riga il motivo della
 *   chiusura e le due latenze (da quando la voce compare, da quando smette di cambiare), poi gli
 *   eventi del contenitore e le statistiche delle letture.
 *
 * I file nascono al primo uso, mai sopra un file che esiste gia'. Ogni riga va su disco subito
 * (flush), perche' chi segue il meet legge il file mentre cresce.
 */
final class TranscriptFiles implements CaptionTracker.Listener, AutoCloseable {

    private static final DateTimeFormatter LINE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DIAGNOSTIC_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String TRANSCRIPT_SUFFIX = ".tsv";
    private static final String DIAGNOSTICS_SUFFIX = ".diag.tsv";
    private static final int MAX_NAME_ATTEMPTS = 50;
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final Path directory;
    private final boolean echoLinesToConsole;
    private String baseName;
    private BufferedWriter transcript;
    private BufferedWriter diagnostics;
    private Path transcriptPath;
    private boolean closed;

    TranscriptFiles(Path directory, boolean echoLinesToConsole) {
        this.directory = directory;
        this.echoLinesToConsole = echoLinesToConsole;
    }

    //<editor-fold desc="Apertura">

    /**
     * Apre i due file col nome base dato; se esistono gia', aggiunge un suffisso -2, -3, ...
     */
    synchronized void open(String requestedBaseName) {
        if (transcript != null)
            return;
        for (int attempt = 1; attempt <= MAX_NAME_ATTEMPTS; attempt++) {
            String candidate = attempt == 1 ? requestedBaseName : requestedBaseName + "-" + attempt;
            Path candidateTranscript = directory.resolve(candidate + TRANSCRIPT_SUFFIX);
            Path candidateDiagnostics = directory.resolve(candidate + DIAGNOSTICS_SUFFIX);
            try {
                BufferedWriter openedTranscript = createNew(candidateTranscript);
                BufferedWriter openedDiagnostics;
                try {
                    openedDiagnostics = createNew(candidateDiagnostics);
                } catch (IOException diagnosticsFailure) {
                    openedTranscript.close();
                    Files.deleteIfExists(candidateTranscript);
                    throw diagnosticsFailure;
                }
                transcript = openedTranscript;
                diagnostics = openedDiagnostics;
                transcriptPath = candidateTranscript;
                baseName = candidate;
                return;
            } catch (FileAlreadyExistsException taken) {
                continue;
            } catch (IOException failure) {
                throw new UncheckedIOException("apertura dei file del meet in " + directory, failure);
            }
        }
        throw new IllegalStateException("nessun nome libero per " + requestedBaseName + " in " + directory);
    }

    boolean isOpen() {
        return transcript != null;
    }

    Path transcriptPath() {
        return transcriptPath;
    }

    String baseName() {
        return baseName;
    }

    //</editor-fold>

    //<editor-fold desc="Scrittura">

    @Override
    public synchronized void lineClosed(CaptionTracker.ClosedLine line) {
        if (closed)
            return;
        requireOpen(line.closedMillis());
        String transcriptLine = format(line.firstSeenMillis(), LINE_TIME_FORMAT) + "\t" + sanitize(line.speaker())
                + "\t" + sanitize(line.text()) + (line.revision() ? "\t" + line.revisedLine() : "");
        writeLine(transcript, transcriptLine);
        writeLine(diagnostics, format(line.closedMillis(), DIAGNOSTIC_TIME_FORMAT) + "\triga\tn=" + line.number()
                + " motivo=" + line.reason().label
                + " da_comparsa_ms=" + (line.closedMillis() - line.firstSeenMillis())
                + " da_ultima_modifica_ms=" + (line.closedMillis() - line.lastChangeMillis())
                + (line.revision() ? " revisione=si sostituisce=" + line.revisedLine() : ""));
        if (echoLinesToConsole)
            Log.info(String.format("riga %d  [%s, %.1f s]%s  %s: %s", line.number(), line.reason().label,
                    (line.closedMillis() - line.firstSeenMillis()) / 1000.0,
                    line.revision() ? " sostituisce " + line.revisedLine() : "", line.speaker(), line.text()));
    }

    @Override
    public synchronized void trackerEvent(long nowMillis, String kind, String detail) {
        event(nowMillis, kind, detail);
    }

    synchronized void event(long nowMillis, String kind, String detail) {
        if (closed)
            return;
        requireOpen(nowMillis);
        writeLine(diagnostics, format(nowMillis, DIAGNOSTIC_TIME_FORMAT) + "\t" + kind + "\t" + sanitize(detail));
    }

    @Override
    public synchronized void close() {
        if (closed)
            return;
        closed = true;
        try {
            if (transcript != null)
                transcript.close();
            if (diagnostics != null)
                diagnostics.close();
        } catch (IOException failure) {
            throw new UncheckedIOException("chiusura dei file del meet", failure);
        }
    }

    //</editor-fold>

    //<editor-fold desc="Formati" defaultstate="collapsed">

    static String format(long millis, DateTimeFormatter formatter) {
        return formatter.format(Instant.ofEpochMilli(millis).atZone(ZONE));
    }

    static String sanitize(String value) {
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    /**
     * Chi usa i file li apre col nome del meet prima della prima riga; se non l'ha fatto, i file
     * nascono col nome di riserva invece di perdere la riga.
     */
    private void requireOpen(long nowMillis) {
        if (transcript == null)
            open(MeetTitle.baseName(nowMillis, null));
    }

    private static BufferedWriter createNew(Path path) throws IOException {
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private static void writeLine(BufferedWriter writer, String line) {
        try {
            writer.write(line);
            writer.write('\n');
            writer.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException("scrittura sul file del meet", failure);
        }
    }

    //</editor-fold>
}

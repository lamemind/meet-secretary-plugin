import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replay di un registro <stamp>-watch.tsv di watch-teams-captions.ps1 (probe di T18) dentro
 * CaptionTracker: prova le regole di chiusura sui meet gia' registrati, senza Teams.
 *
 * Il registro porta una riga per ogni giro in cui qualcosa cambia:
 *   HH:mm:ss.fff <TAB> stato:via:titolo-finestra <TAB> voci=<n> <TAB> runtimeId|nome|testo ...
 * oppure no-container, o error. Il testo di una voce sono i Text dopo il nome uniti da " / ": un
 * badge in testa (Non verificato) si separa dal testo.
 *
 * Il replay gira a giri di 250 ms come il lettore dal vivo, piu' un giro esatto all'istante di ogni
 * riga del registro: fra due righe la fotografia resta quella dell'ultima. L'epoca e' via:titolo,
 * cioe' la finestra che porta il contenitore: lo stato della finestra (ridotta, massimizzata) non la
 * cambia.
 *
 * Scrive <stem>-replay.tsv e <stem>-replay.diag.tsv nella cartella di uscita, e stampa il riepilogo
 * con le righe consegnate due volte.
 */
final class CaptionReplay {

    private static final long ROUND_MILLIS = 250;
    private static final long TAIL_MILLIS = CaptionTracker.ORPHAN_GRACE_MILLIS + 1_000;
    private static final String VOICE_COUNT_PREFIX = "voci=";
    private static final String TEXT_JOINER = " / ";
    private static final String WATCH_SUFFIX = "-watch.tsv";

    private record Snapshot(long atMillis, String epochKey, List<CaptionTracker.Voice> voices) {
    }

    private CaptionReplay() {
    }

    static int run(Path watchFile, Path outputDirectory) throws IOException {
        List<Snapshot> snapshots = parse(watchFile);
        if (snapshots.isEmpty()) {
            Log.info("registro vuoto: " + watchFile);
            return 2;
        }

        Files.createDirectories(outputDirectory);
        String stem = watchFile.getFileName().toString();
        if (stem.endsWith(WATCH_SUFFIX))
            stem = stem.substring(0, stem.length() - WATCH_SUFFIX.length());

        Map<CaptionTracker.CloseReason, Integer> reasons = new EnumMap<>(CaptionTracker.CloseReason.class);
        Map<String, Integer> deliveries = new LinkedHashMap<>();

        try (TranscriptFiles files = new TranscriptFiles(outputDirectory, false)) {
            files.open(stem + "-replay");
            CaptionTracker tracker = new CaptionTracker(new CaptionTracker.Listener() {
                @Override
                public void lineClosed(CaptionTracker.ClosedLine line) {
                    files.lineClosed(line);
                    reasons.merge(line.reason(), 1, Integer::sum);
                    deliveries.merge(line.speaker() + "\t" + line.text(), 1, Integer::sum);
                }

                @Override
                public void trackerEvent(long nowMillis, String kind, String detail) {
                    files.trackerEvent(nowMillis, kind, detail);
                    Log.info("evento " + kind + ": " + detail);
                }
            });

            long endMillis = snapshots.getLast().atMillis() + TAIL_MILLIS;
            int nextSnapshot = 0;
            Snapshot current = null;
            long roundMillis = snapshots.getFirst().atMillis();
            while (roundMillis <= endMillis) {
                while (nextSnapshot < snapshots.size() && snapshots.get(nextSnapshot).atMillis() <= roundMillis)
                    current = snapshots.get(nextSnapshot++);

                if (current == null || current.epochKey() == null)
                    tracker.observeWithoutContainer(roundMillis);
                else
                    tracker.observe(roundMillis, current.epochKey(), current.voices());

                long nextRound = roundMillis + ROUND_MILLIS;
                if (nextSnapshot < snapshots.size() && snapshots.get(nextSnapshot).atMillis() < nextRound)
                    nextRound = snapshots.get(nextSnapshot).atMillis();
                roundMillis = nextRound;
            }
            tracker.shutdown(endMillis);

            Log.info("replay di " + watchFile.getFileName() + ": " + snapshots.size() + " righe di registro, "
                    + tracker.deliveredCount() + " righe consegnate, motivi " + reasons
                    + ", revisioni=" + tracker.revisionCount() + ", ridisegnate=" + tracker.redrawnCount()
                    + ", aperte scartate=" + tracker.droppedOpenCount());
            deliveries.forEach((key, count) -> {
                if (count > 1)
                    Log.info("CONSEGNATA " + count + " VOLTE: " + key.replace('\t', ':'));
            });
            Log.info("righe in " + files.transcriptPath());
        }
        return 0;
    }

    //<editor-fold desc="Lettura del registro" defaultstate="collapsed">

    private static List<Snapshot> parse(Path watchFile) throws IOException {
        LocalDate day = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();
        List<Snapshot> snapshots = new ArrayList<>();

        for (String line : Files.readAllLines(watchFile, StandardCharsets.UTF_8)) {
            if (line.isBlank())
                continue;
            String[] fields = line.split("\t", -1);
            long atMillis = LocalTime.parse(fields[0]).atDate(day).atZone(zone).toInstant().toEpochMilli();

            if (fields.length < 3 || !fields[2].startsWith(VOICE_COUNT_PREFIX)) {
                snapshots.add(new Snapshot(atMillis, null, List.of()));
                continue;
            }

            List<CaptionTracker.Voice> voices = new ArrayList<>();
            for (int index = 3; index < fields.length; index++) {
                String[] parts = fields[index].split("\\|", 3);
                if (parts.length < 2)
                    continue;
                List<String> texts = new ArrayList<>();
                texts.add(parts[1]);
                String rest = parts.length == 3 ? parts[2] : "";
                for (String badge : CaptionSource.SPEAKER_BADGES) {
                    if (rest.startsWith(badge + TEXT_JOINER)) {
                        texts.add(badge);
                        rest = rest.substring(badge.length() + TEXT_JOINER.length());
                    }
                }
                texts.add(rest);
                voices.add(CaptionSource.voiceFrom(parts[0], texts));
            }
            snapshots.add(new Snapshot(atMillis, epochKeyOf(fields[1]), voices));
        }
        return snapshots;
    }

    private static String epochKeyOf(String windowLabel) {
        int stateEnd = windowLabel.indexOf(':');
        return stateEnd < 0 ? windowLabel : windowLabel.substring(stateEnd + 1);
    }

    //</editor-fold>
}

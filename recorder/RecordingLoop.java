import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Il ciclo del registratore: aspetta un meet coi sottotitoli accesi, ne legge le voci con UI
 * Automation e scrive un file per meet, finche' la finestra del meet non si chiude. Poi aspetta il
 * meet successivo. Gira su un thread suo, che entra nell'apartment COM multithread.
 *
 * Senza meet il ciclo cerca il pannello dei sottotitoli in tutte le finestre di Teams ogni
 * IDLE_SEARCH_INTERVAL_MILLIS: una ricerca costa circa 200 ms, e l'app resta accesa tutto il giorno.
 * Le voci restano nel pannello per minuti, quindi le prime frasi non si perdono: arrivano solo con
 * qualche secondo di ritardo sull'ora della riga. Un meet si apre solo su un pannello riconosciuto,
 * col pulsante captions-panel-dismiss-button o col nome dei sottotitoli: un genitore di voci, fuori
 * da un meet, non apre niente.
 *
 * Durante un meet un giro ogni 250 ms: un FindAllBuildCache sul solo contenitore. Un contenitore
 * vuoto o assente si rimette in discussione al massimo una volta al secondo, con una ricerca da capo
 * (T18 DLV6), e la stessa ricerca dice se le finestre del meet ci sono ancora. Le finestre del meet
 * sono quelle in cui si e' trovato il contenitore, piu', all'apertura, quelle con lo stesso titolo
 * del meet (la finestra del meet e quella dei sottotitoli staccati). Il meet finisce quando nessuna
 * c'e' piu' da MEET_GONE_GRACE_MILLIS, o subito quando il pannello compare in un'altra finestra
 * mentre le sue non ci sono piu': due meet di fila danno due file. Con una finestra del meet ancora
 * aperta, un pannello in un'altra finestra entra nello stesso meet: due meet sovrapposti finiscono
 * in un file solo, ma nessuna frase si perde.
 *
 * Alla fine di un meet le voci aperte si scrivono col testo che hanno, e il diario chiude con la
 * riga fine. Fermare la registrazione chiude il meet allo stesso modo.
 */
final class RecordingLoop implements Runnable {

    enum Status {
        STOPPED("fermo"),
        WAITING("in attesa di un meet"),
        RECORDING("registra"),
        NO_CAPTIONS("sottotitoli spenti"),
        EMPTY("sottotitoli vuoti"),
        FAILED("errore, riprovo");

        final String label;

        Status(String label) {
            this.label = label;
        }
    }

    /**
     * Lo stato da mostrare: il file del meet aperto e le righe scritte, i secondi di contenitore
     * vuoto quando lo stato e' EMPTY, l'errore quando e' FAILED.
     */
    record Snapshot(Status status, Path meetFile, int lines, long emptySeconds, String detail) {

        String describe() {
            return switch (status) {
                case RECORDING, NO_CAPTIONS -> status.label + ", " + lines + " righe";
                case EMPTY -> status.label + " da " + emptySeconds + " s, " + lines + " righe";
                case STOPPED, WAITING, FAILED -> status.label;
            };
        }
    }

    interface StatusListener {

        void statusChanged(Snapshot snapshot);
    }

    static final long DEFAULT_POLL_INTERVAL_MILLIS = 250;
    static final long MIN_POLL_INTERVAL_MILLIS = 50;
    static final long MAX_POLL_INTERVAL_MILLIS = 5_000;

    private static final long IDLE_SEARCH_INTERVAL_MILLIS = 5_000;
    private static final long IDLE_STATUS_INTERVAL_MILLIS = 600_000;
    private static final long LOCATE_RETRY_MILLIS = 1_000;
    private static final long STATUS_INTERVAL_MILLIS = 30_000;
    private static final long EMPTY_BEFORE_DIAGNOSIS_MILLIS = 5_000;
    private static final long EMPTY_ALERT_MILLIS = 5_000;
    private static final long DIAGNOSIS_INTERVAL_MILLIS = 10_000;
    private static final long MEET_GONE_GRACE_MILLIS = 5_000;
    private static final long RESTART_AFTER_FAILURE_MILLIS = 30_000;
    private static final long STOPPED_WAIT_MILLIS = 1_000;
    private static final long SHUTDOWN_LOCK_WAIT_MILLIS = 2_000;

    static final String END_WINDOW_CLOSED = "finestra-chiusa";
    static final String END_STOPPED = "fermato";
    static final String END_EXIT = "uscita";
    static final String END_FAILURE = "errore";

    /**
     * Un meet: i suoi file, il suo tracker, le sue finestre e le misure da scrivere nel diario.
     */
    private static final class MeetSession {

        final TranscriptFiles files;
        final CaptionTracker tracker;
        final Set<String> windowKeys = new LinkedHashSet<>();
        final ReadStatistics windowStatistics = new ReadStatistics();
        final ReadStatistics totalStatistics = new ReadStatistics();
        final ReadStatistics searchStatistics = new ReadStatistics();
        long nextStatusAtMillis;
        int lastVoiceCount;
        long emptySinceMillis = -1;
        long nextDiagnosisAtMillis;
        long windowsMissingSinceMillis = -1;

        MeetSession(TranscriptFiles files, long nowMillis) {
            this.files = files;
            this.tracker = new CaptionTracker(files);
            this.nextStatusAtMillis = nowMillis + STATUS_INTERVAL_MILLIS;
        }
    }

    private final Path outputDirectory;
    private final long pollIntervalMillis;
    private final boolean console;
    private final StatusListener listener;
    private final ReentrantLock roundLock = new ReentrantLock();
    private final Object wakeUp = new Object();
    private final ReadStatistics idleSearchStatistics = new ReadStatistics();
    private volatile boolean recordingWanted = true;
    private volatile boolean exitRequested;
    private volatile Snapshot lastPublished;
    private volatile List<String> teamsWindowNames = List.of();
    private volatile String failureDetail;
    private MeetSession session;
    private CaptionSource.Located located;
    private long nextLocateAtMillis;
    private long nextIdleStatusAtMillis;
    private int lastTeamsWindowCount = -1;
    private int meetsRecorded;

    RecordingLoop(Path outputDirectory, long pollIntervalMillis, boolean console, StatusListener listener) {
        this.outputDirectory = outputDirectory;
        this.pollIntervalMillis = pollIntervalMillis;
        this.console = console;
        this.listener = listener;
    }

    //<editor-fold desc="Comandi da fuori">

    void requestStart() {
        recordingWanted = true;
        wake();
    }

    void requestStop() {
        recordingWanted = false;
        wake();
    }

    void requestExit() {
        exitRequested = true;
        wake();
    }

    Snapshot lastSnapshot() {
        return lastPublished;
    }

    List<String> teamsWindowNames() {
        return teamsWindowNames;
    }

    Path outputDirectory() {
        return outputDirectory;
    }

    /**
     * Dallo shutdown hook (Ctrl+C, chiusura della sessione di Windows): chiude i file del meet aperto
     * senza chiamate COM, che su un thread fuori dall'apartment non si fanno. Il ciclo, se e' in un
     * giro, ha SHUTDOWN_LOCK_WAIT_MILLIS per finirlo.
     */
    void finishFromShutdownHook() {
        exitRequested = true;
        wake();
        try {
            if (roundLock.tryLock(SHUTDOWN_LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                try {
                    if (session != null)
                        closeSessionFiles(System.currentTimeMillis(), END_EXIT);
                } finally {
                    roundLock.unlock();
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    //</editor-fold>

    //<editor-fold desc="Ciclo">

    /**
     * Il ciclo vive finche' non arriva l'uscita. Un errore inatteso chiude il meet aperto, si scrive
     * nel log, e dopo RESTART_AFTER_FAILURE_MILLIS il ciclo riparte con un UI Automation nuovo:
     * l'app resta accesa tutto il giorno e non deve morire per un errore di un giro.
     */
    @Override
    public void run() {
        Com.initializeMultithreadedApartment();
        while (!exitRequested) {
            try (Uia.Automation automation = Uia.Automation.create();
                 CaptionSource source = new CaptionSource(automation)) {
                failureDetail = null;
                loop(source);
            } catch (RuntimeException | Error failure) {
                Log.failure("ciclo del registratore caduto, riparto fra " + RESTART_AFTER_FAILURE_MILLIS / 1000 + " s",
                        failure);
                long nowMillis = System.currentTimeMillis();
                roundLock.lock();
                try {
                    endSessionQuietly(nowMillis);
                    failureDetail = String.valueOf(failure);
                    publish(nowMillis);
                } finally {
                    roundLock.unlock();
                }
                waitFor(RESTART_AFTER_FAILURE_MILLIS);
            }
        }
    }

    private void loop(CaptionSource source) {
        nextIdleStatusAtMillis = System.currentTimeMillis() + IDLE_STATUS_INTERVAL_MILLIS;
        while (!exitRequested) {
            long roundStartMillis = System.currentTimeMillis();
            roundLock.lock();
            try {
                if (recordingWanted) {
                    runRound(source, roundStartMillis);
                    reportStatusIfDue(roundStartMillis);
                } else {
                    endSession(roundStartMillis, END_STOPPED);
                    nextLocateAtMillis = 0;
                }
                publish(roundStartMillis);
            } finally {
                roundLock.unlock();
            }
            long elapsedMillis = System.currentTimeMillis() - roundStartMillis;
            waitFor(recordingWanted ? Math.max(0, pollIntervalMillis - elapsedMillis) : STOPPED_WAIT_MILLIS);
        }

        long nowMillis = System.currentTimeMillis();
        roundLock.lock();
        try {
            endSession(nowMillis, END_EXIT);
            publish(nowMillis);
        } finally {
            roundLock.unlock();
        }
    }

    /**
     * Un giro. Senza meet si cerca il pannello dei sottotitoli. Con un meet, senza contenitore lo si
     * cerca, al massimo una volta al secondo; col contenitore se ne leggono le voci, e un contenitore
     * vuoto si rimette in discussione, al massimo una volta al secondo, perche' puo' essere:
     * - quello che Teams svuota mentre sposta i sottotitoli in una finestra loro;
     * - un contenitore che resta nell'albero, vuoto, mentre le voci compaiono in un altro;
     * - lo stesso contenitore, che letto dall'elemento tenuto torna vuoto e letto da un elemento
     *   nuovo porta le voci;
     * - il contenitore di una finestra chiusa, che si legge vuoto senza errori.
     */
    private void runRound(CaptionSource source, long nowMillis) {
        if (exitRequested)
            return;
        if (session == null) {
            searchForMeet(source, nowMillis);
            if (session == null)
                return;
        }

        if (located == null && nowMillis >= nextLocateAtMillis)
            searchDuringMeet(source, nowMillis);
        if (session == null)
            return;
        if (located == null) {
            session.lastVoiceCount = 0;
            session.tracker.observeWithoutContainer(nowMillis);
            return;
        }

        try {
            List<CaptionTracker.Voice> voices = timedRead(source);
            if (voices.isEmpty() && nowMillis >= nextLocateAtMillis) {
                CaptionSource.Located before = located;
                searchDuringMeet(source, nowMillis);
                if (session == null)
                    return;
                if (located == null) {
                    session.tracker.observeWithoutContainer(nowMillis);
                    return;
                }
                if (located != before)
                    voices = timedRead(source);
            }
            session.lastVoiceCount = voices.size();
            session.tracker.observe(nowMillis, located.epochKey(), voices);
            diagnoseIfEmptyTooLong(source, nowMillis, voices.isEmpty());
        } catch (Com.ComException containerGone) {
            event(nowMillis, "contenitore", "perso: " + containerGone.getMessage());
            Log.info("contenitore dei sottotitoli perso (" + containerGone.getMessage() + "), lo cerco di nuovo");
            closeQuietly(located);
            located = null;
            session.lastVoiceCount = 0;
            session.emptySinceMillis = -1;
            session.tracker.observeWithoutContainer(nowMillis);
        }
    }

    //</editor-fold>

    //<editor-fold desc="Inizio e fine di un meet">

    private void searchForMeet(CaptionSource source, long nowMillis) {
        if (nowMillis < nextLocateAtMillis)
            return;
        nextLocateAtMillis = nowMillis + IDLE_SEARCH_INTERVAL_MILLIS;
        CaptionSource.Search search = timedSearch(source, false, idleSearchStatistics, nowMillis);
        rememberTeamsWindows(search);
        search.chosen().ifPresent(found -> openSession(nowMillis, found, search));
    }

    /**
     * Una ricerca durante un meet, col contenitore assente o vuoto. Decide se il meet e' finito, se
     * si passa a un altro contenitore o se si resta su quello tenuto. Un genitore di voci vale solo
     * in una finestra del meet; in un'altra finestra serve il pannello riconosciuto.
     */
    private void searchDuringMeet(CaptionSource source, long nowMillis) {
        nextLocateAtMillis = nowMillis + LOCATE_RETRY_MILLIS;
        CaptionSource.Search search = timedSearch(source, true, session.searchStatistics, nowMillis);
        rememberTeamsWindows(search);
        if (!search.complete())
            return;

        CaptionSource.Located found = search.chosen().orElse(null);
        boolean foundInMeet = found != null && session.windowKeys.contains(found.windowKey());
        if (found != null && !foundInMeet && !opensMeet(found)) {
            found.close();
            found = null;
        }

        boolean meetWindowAlive = search.windows().stream()
                .anyMatch(window -> session.windowKeys.contains(window.key()));
        if (meetWindowAlive)
            session.windowsMissingSinceMillis = -1;
        else if (session.windowsMissingSinceMillis < 0)
            session.windowsMissingSinceMillis = nowMillis;

        if (!meetWindowAlive) {
            boolean otherMeet = found != null && !foundInMeet;
            boolean goneLongEnough = nowMillis - session.windowsMissingSinceMillis >= MEET_GONE_GRACE_MILLIS;
            if (otherMeet || goneLongEnough) {
                endSession(nowMillis, END_WINDOW_CLOSED);
                if (found != null)
                    openSession(nowMillis, found, search);
                return;
            }
        }

        if (found != null && !foundInMeet)
            session.windowKeys.add(found.windowKey());

        if (located == null) {
            if (found != null) {
                located = found;
                announceContainer(nowMillis, "trovato", search.report());
            }
            return;
        }
        String change = containerChange(search, found);
        if (change != null) {
            located.close();
            located = found;
            announceContainer(nowMillis, change, search.report());
        } else if (found != null) {
            found.close();
        }
    }

    private void openSession(long nowMillis, CaptionSource.Located found, CaptionSource.Search search) {
        closeQuietly(located);
        TranscriptFiles files = new TranscriptFiles(outputDirectory, console);
        files.open(MeetTitle.baseName(nowMillis, found.windowName()));
        session = new MeetSession(files, nowMillis);
        meetsRecorded++;
        String meetTitle = MeetTitle.meetTitleOf(found.windowName());
        session.windowKeys.add(found.windowKey());
        for (CaptionSource.TeamsWindow window : search.windows())
            if (MeetTitle.meetTitleOf(window.name()).equals(meetTitle))
                session.windowKeys.add(window.key());
        located = found;
        nextLocateAtMillis = nowMillis + LOCATE_RETRY_MILLIS;
        Log.info("meet aperto: " + files.transcriptPath());
        announceContainer(nowMillis, "trovato", search.report());
    }

    /**
     * Chiude il meet aperto: file e riferimenti COM. Dopo la fine si cerca subito il meet
     * successivo.
     */
    private void endSession(long nowMillis, String reason) {
        if (session == null)
            return;
        closeSessionFiles(nowMillis, reason);
        closeQuietly(located);
        located = null;
        nextLocateAtMillis = nowMillis;
    }

    private void endSessionQuietly(long nowMillis) {
        try {
            endSession(nowMillis, END_FAILURE);
        } catch (RuntimeException closeFailure) {
            Log.failure("chiusura del meet dopo l'errore", closeFailure);
            session = null;
            located = null;
        }
    }

    /**
     * Le voci aperte si scrivono, il diario chiude con la riga fine, i file si chiudono. Nessuna
     * chiamata COM: la usa anche lo shutdown hook.
     */
    private void closeSessionFiles(long nowMillis, String reason) {
        MeetSession ending = session;
        session = null;
        ending.tracker.shutdown(nowMillis);
        String summary = summary(ending);
        ending.files.event(nowMillis, "fine", "motivo=" + reason + " " + summary);
        ending.files.close();
        Log.info("meet chiuso (" + reason + "): " + ending.files.transcriptPath() + ", " + summary);
    }

    private static boolean opensMeet(CaptionSource.Located candidate) {
        return CaptionSource.VIA_DISMISS_BUTTON.equals(candidate.via())
                || CaptionSource.VIA_CONTAINER_NAME.equals(candidate.via());
    }

    //</editor-fold>

    //<editor-fold desc="Contenitore e letture">

    private CaptionSource.Search timedSearch(CaptionSource source, boolean voiceParentAllowed,
                                             ReadStatistics statistics, long nowMillis) {
        long startNanos = System.nanoTime();
        try {
            return source.search(voiceParentAllowed);
        } catch (Com.ComException searchFailed) {
            if (session != null)
                event(nowMillis, "ricerca", "fallita: " + searchFailed.getMessage());
            else
                Log.info("ricerca fallita: " + searchFailed.getMessage());
            return CaptionSource.Search.failed();
        } finally {
            statistics.record((System.nanoTime() - startNanos) / 1_000);
        }
    }

    /**
     * Il cambio da fare col contenitore tenuto vuoto: «cambiato» verso un altro contenitore,
     * «rinnovato» verso un elemento nuovo dello stesso contenitore, null se si resta.
     */
    private String containerChange(CaptionSource.Search search, CaptionSource.Located relocated) {
        if (relocated == null)
            return null;
        boolean sameContainer = relocated.epochKey().equals(located.epochKey());
        if (relocated.voiceCount() > 0)
            return sameContainer ? "rinnovato" : "cambiato";
        boolean heldContainerGone = !search.candidateKeys().contains(located.epochKey());
        return !sameContainer && heldContainerGone ? "cambiato" : null;
    }

    private List<CaptionTracker.Voice> timedRead(CaptionSource source) {
        long startNanos = System.nanoTime();
        List<CaptionTracker.Voice> voices = source.readVoices(located.container());
        long elapsedMicros = (System.nanoTime() - startNanos) / 1_000;
        session.windowStatistics.record(elapsedMicros);
        session.totalStatistics.record(elapsedMicros);
        return voices;
    }

    /**
     * Un contenitore che resta vuoto mentre il meet va avanti e' il caso da capire (T18 DLV6): ogni
     * DIAGNOSIS_INTERVAL_MILLIS, dopo EMPTY_BEFORE_DIAGNOSIS_MILLIS di vuoto, il diario riporta cosa
     * c'e' sotto il contenitore e dove stanno le voci.
     */
    private void diagnoseIfEmptyTooLong(CaptionSource source, long nowMillis, boolean empty) {
        if (!empty) {
            session.emptySinceMillis = -1;
            return;
        }
        if (session.emptySinceMillis < 0) {
            session.emptySinceMillis = nowMillis;
            session.nextDiagnosisAtMillis = nowMillis + EMPTY_BEFORE_DIAGNOSIS_MILLIS;
            return;
        }
        if (nowMillis < session.nextDiagnosisAtMillis)
            return;
        session.nextDiagnosisAtMillis = nowMillis + DIAGNOSIS_INTERVAL_MILLIS;
        String diagnosis;
        try {
            diagnosis = "vuoto da " + (nowMillis - session.emptySinceMillis) / 1000 + " s, " + source.diagnose(located);
        } catch (Com.ComException diagnosisFailed) {
            diagnosis = "diagnosi fallita: " + diagnosisFailed.getMessage();
        }
        event(nowMillis, "diagnosi", diagnosis);
        if (console)
            Log.info("diagnosi: " + diagnosis);
    }

    private void announceContainer(long nowMillis, String what, String searchReport) {
        String detail = what + " via=" + located.via() + " finestra=" + located.windowName()
                + " runtimeId=" + located.epochKey() + " voci=" + located.voiceCount()
                + " finestre=[" + searchReport + "]";
        event(nowMillis, "contenitore", detail);
        Log.info("contenitore dei sottotitoli " + what + " (" + located.via() + ", voci=" + located.voiceCount()
                + ")" + (console ? " in: " + located.windowName() : ""));
    }

    //</editor-fold>

    //<editor-fold desc="Stato, diario e attese" defaultstate="collapsed">

    private void publish(long nowMillis) {
        Snapshot snapshot = currentSnapshot(nowMillis);
        if (snapshot.equals(lastPublished))
            return;
        lastPublished = snapshot;
        listener.statusChanged(snapshot);
    }

    private Snapshot currentSnapshot(long nowMillis) {
        if (failureDetail != null)
            return new Snapshot(Status.FAILED, null, 0, 0, failureDetail);
        if (!recordingWanted || exitRequested)
            return new Snapshot(Status.STOPPED, null, 0, 0, null);
        if (session == null)
            return new Snapshot(Status.WAITING, null, 0, 0, null);
        Path meetFile = session.files.transcriptPath();
        int lines = session.tracker.deliveredCount();
        if (located == null)
            return new Snapshot(Status.NO_CAPTIONS, meetFile, lines, 0, null);
        long emptyMillis = session.emptySinceMillis < 0 ? 0 : nowMillis - session.emptySinceMillis;
        if (emptyMillis >= EMPTY_ALERT_MILLIS)
            return new Snapshot(Status.EMPTY, meetFile, lines, emptyMillis / 1000, null);
        return new Snapshot(Status.RECORDING, meetFile, lines, 0, null);
    }

    private void reportStatusIfDue(long nowMillis) {
        if (session == null) {
            reportIdleStatusIfDue(nowMillis);
            return;
        }
        if (nowMillis < session.nextStatusAtMillis)
            return;
        session.nextStatusAtMillis = nowMillis + STATUS_INTERVAL_MILLIS;
        String status = "contenitore=" + (located == null ? "no" : located.via())
                + " voci=" + session.lastVoiceCount
                + " aperte=" + session.tracker.openVoiceCount()
                + " righe=" + session.tracker.deliveredCount()
                + " " + session.windowStatistics.describe()
                + " " + session.searchStatistics.describe("ricerche");
        if (console)
            Log.info("stato: " + status);
        event(nowMillis, "stato", status);
        session.windowStatistics.reset();
        session.searchStatistics.reset();
    }

    /**
     * Senza meet il log riporta ogni IDLE_STATUS_INTERVAL_MILLIS quanto costano le ricerche: e' il
     * costo dell'app accesa tutto il giorno.
     */
    private void reportIdleStatusIfDue(long nowMillis) {
        if (nowMillis < nextIdleStatusAtMillis)
            return;
        nextIdleStatusAtMillis = nowMillis + IDLE_STATUS_INTERVAL_MILLIS;
        Log.info("a riposo: finestre di Teams=" + teamsWindowNames.size() + " meet registrati=" + meetsRecorded + " "
                + idleSearchStatistics.describe("ricerche"));
        idleSearchStatistics.reset();
    }

    /**
     * Le finestre di Teams viste dall'ultima ricerca, per lo stato a richiesta. Nel log va il loro
     * numero quando cambia, e nel modo console anche i titoli: un meet aperto senza sottotitoli si
     * distingue cosi' da un meet che il registratore non vede.
     */
    private void rememberTeamsWindows(CaptionSource.Search search) {
        if (!search.complete())
            return;
        List<String> names = search.windows().stream().map(CaptionSource.TeamsWindow::name).toList();
        teamsWindowNames = names;
        if (names.size() == lastTeamsWindowCount)
            return;
        lastTeamsWindowCount = names.size();
        Log.info("finestre di Teams: " + names.size()
                + (console ? " (" + (names.isEmpty() ? "nessuna" : String.join("  |  ", names)) + ")" : ""));
    }

    private static String summary(MeetSession meet) {
        return "righe=" + meet.tracker.deliveredCount()
                + " revisioni=" + meet.tracker.revisionCount()
                + " ridisegnate=" + meet.tracker.redrawnCount()
                + " aperte_scartate=" + meet.tracker.droppedOpenCount()
                + " " + meet.totalStatistics.describe();
    }

    private void event(long nowMillis, String kind, String detail) {
        if (session != null)
            session.files.event(nowMillis, kind, detail);
    }

    private static void closeQuietly(CaptionSource.Located reference) {
        if (reference == null)
            return;
        try {
            reference.close();
        } catch (RuntimeException releaseFailure) {
            Log.info("rilascio del contenitore fallito: " + releaseFailure);
        }
    }

    private void wake() {
        synchronized (wakeUp) {
            wakeUp.notifyAll();
        }
    }

    private void waitFor(long millis) {
        if (millis <= 0 || exitRequested)
            return;
        synchronized (wakeUp) {
            try {
                wakeUp.wait(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exitRequested = true;
            }
        }
    }

    /**
     * Durata delle letture e delle ricerche: quanto costa un giro.
     */
    static final class ReadStatistics {

        private long count;
        private long totalMicros;
        private long maxMicros;

        void record(long micros) {
            count++;
            totalMicros += micros;
            maxMicros = Math.max(maxMicros, micros);
        }

        void reset() {
            count = 0;
            totalMicros = 0;
            maxMicros = 0;
        }

        String describe() {
            return describe("letture");
        }

        String describe(String countLabel) {
            if (count == 0)
                return countLabel + "=0";
            return String.format("%s=%d media_ms=%.1f max_ms=%.1f", countLabel, count, totalMicros / 1000.0 / count,
                    maxMicros / 1000.0);
        }
    }

    //</editor-fold>
}

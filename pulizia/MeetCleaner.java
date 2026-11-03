import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Lo script di pulizia di un meet: segue il file `<stamp>.tsv` scritto dal lettore dei sottotitoli e,
 * attraverso un solo `claude -p` persistente con cwd sul progetto, ne scrive accanto la versione
 * pulita.
 *
 *   <stamp>.rif.tsv           un tratto per riga: da, a, ora, fuffa o tema, etichetta, testo
 *                             corretto, righe riviste
 *   <stamp>.indice.tsv        una fascia per riga: ora da, ora a, etichetta, titolo, da, a
 *   <stamp>.eventi.tsv        un evento per riga: ora, tipo (tema, nome, parola, fine), da, a, dettaglio
 *   <stamp>.segnalibro-pulizia  l'ultima riga pulita e l'ultima letta dai trigger, per ripartire
 *
 * Ogni 10 s, se ci sono righe dopo il segnalibro, le manda a claude e aspetta il `result` prima del
 * chunk successivo. Su `chiuso <k>` appende i tratti e porta il segnalibro su k; su `incompleto` lo
 * lascia fermo. Tetto: quando la riga aperta piu' vecchia aspetta da 60 s, o le righe aperte sono
 * 20, chiede la chiusura forzata. All'aggancio in ritardo pulisce prima l'arretrato a blocchi di 60
 * righe, poi passa al giro. I tratti consecutivi con la stessa etichetta formano una fascia; alla
 * chiusura di una fascia chiede il titolo. Un tratto di lavoro con un tema del file di settings da'
 * un evento; nome del segretario e parole chiave passano da Triggers, senza claude.
 *
 * Tre modi. Dal vivo (il diario non ha ancora `fine`) segue il file e finisce dopo la riga `fine`
 * del diario. Concluso (il diario ha `fine`) pulisce tutto a blocchi ed esce. Replay (`--replay`)
 * rifa' il meet registrato con un orologio simulato: una riga arriva all'ora in cui il diario dice
 * che il lettore l'ha scritta, e la durata vera delle chiamate sposta l'orologio.
 *
 * Temi, parole chiave, glossario e nome del segretario stanno nel file di settings del progetto,
 * `.meet-secretary/settings.ini` (ProjectSettings); la voce dell'utente sta nella chiave `voce` di
 * %LOCALAPPDATA%\meet-recorder\config.properties, accanto a `cartella`. Lo script li rilegge a ogni
 * giro: un cambio entra nella pulizia senza rilanciarla. Il plugin non porta temi ne' glossario.
 *
 * Il processo claude si riavvia quando cambia il system prompt (temi o glossario del file di
 * settings), ogni N chunk, o quando il contesto e' cresciuto oltre una soglia; dopo il riavvio il
 * primo chunk porta contesto, partecipanti e argomento in corso.
 *
 * Una pulizia sola per meet: una seconda istanza sullo stesso meet esce subito con 4.
 *
 * Dalla cartella del progetto, col JDK 25, attraverso il launcher meet-secretary/bin/meet-clean:
 *   meet-clean [--meet <stamp|file.tsv>] [--replay] [--tag <nome>] [--model <m>] [--effort <livello>] ...
 */
public class MeetCleaner {

    private static final long TICK_MILLIS = 10_000;
    private static final long CAP_AGE_MILLIS = 60_000;
    private static final int CAP_LINES = 20;
    private static final int MAX_LIVE_CHUNK_LINES = 60;
    private static final int BACKLOG_BLOCK_LINES = 60;
    private static final int CONTEXT_LINES = 4;
    private static final int ERRORS_BEFORE_FORCING = 2;
    private static final int ERRORS_BEFORE_RAW = 3;
    private static final long TRIGGER_POLL_MILLIS = 250;
    private static final long WATCHDOG_POLL_MILLIS = 2_000;
    private static final int MAX_TITLE_TEXT_CHARS = 6_000;
    private static final int MAX_ANCESTORS = 6;
    private static final long DIARY_ALIVE_MILLIS = 120_000;

    static final String RAW_THEME = "grezzo";
    static final int EXIT_ALREADY_RUNNING = 4;
    private static final String MEET_DIRECTORY_KEY = "cartella";
    private static final String USER_VOICE_KEY = "voce";
    private static final Pattern MEET_FILE_NAME = Pattern.compile("^[^.]+\\.tsv$");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    enum Mode { LIVE, CONCLUDED, REPLAY }

    /**
     * userVoiceArgument e nameRegexArgument vengono da --voce-utente e --nome, e quando ci sono
     * battono il file; null quando mancano.
     */
    record Options(Path meetFile, Path meetDirectory, Path projectDirectory, Path configDirectory, Path settingsFile,
                   boolean replay, String tag, ClaudeSession.Settings claude, int restartChunks,
                   long restartContextGrowth, String userVoiceArgument, String nameRegexArgument, int maxTurns,
                   List<Long> watchedPids) {
    }

    record Outputs(Path segments, Path index, Path events, Path bookmark, Path prompt, Path calls, Path replies,
                   Path summary, Path lock, Path pid) {
    }

    private static final class Band {
        final int from;
        int to;
        final String label;
        final List<String> texts = new ArrayList<>();

        Band(Rephraser.Segment segment) {
            from = segment.from();
            to = segment.to();
            label = segment.label();
            texts.add(segment.text());
        }

        void extend(Rephraser.Segment segment) {
            to = segment.to();
            texts.add(segment.text());
        }
    }

    record TurnRecord(int number, long startMillis, String kind, int firstLine, int lastLine, String forcedBy,
                      Rephraser.Outcome outcome, int closedThrough, int segments, ClaudeSession.Turn turn,
                      long skippedTicks, String restart, String error) {
    }

    private static FileLock meetLock;

    private final Options options;
    private final Mode mode;
    private final Outputs outputs;
    private final MeetFeed feed;
    private final Triggers triggers;

    private BufferedWriter segmentsWriter;
    private BufferedWriter indexWriter;
    private BufferedWriter eventsWriter;
    private BufferedWriter callsWriter;
    private BufferedWriter repliesWriter;

    private long simulatedMillis;
    private int bookmark;
    private int backlogEnd;
    private int errorsOnFirst;
    private int incompleteOnFirst;

    private ClaudeSession session;
    private boolean sessionBroken;
    private String sessionPrompt;
    private List<String> sessionThemes = List.of();
    private int sessionStartLine;
    private boolean sessionIntroPending;
    private int chunksInSession;
    private long sessionFirstContext = -1;
    private long lastContext;
    private String pendingRestartReason;
    private final Set<String> participantsSent = new LinkedHashSet<>();

    private ProjectSettings settings = ProjectSettings.empty();
    private String settingsDescription;
    private boolean settingsUnreadableWarned;
    private final Set<String> warnedThemeLines = new LinkedHashSet<>();
    private String appliedVoice;
    private String appliedNameRegex;
    private boolean nameActive;
    private boolean secretaryApplied;
    private Band openBand;
    private final Deque<Band> bandsAwaitingTitle = new ArrayDeque<>();

    private final List<TurnRecord> turns = new ArrayList<>();
    private final List<Triggers.Event> events = new ArrayList<>();
    private final Map<String, Integer> restarts = new LinkedHashMap<>();
    private final Map<Integer, Long> closedMillis = new HashMap<>();
    private final List<long[]> sessionContexts = new ArrayList<>();
    private boolean endEventWritten;
    private int segmentsWritten;
    private int rawLines;
    private int bandsWritten;

    private MeetCleaner(Options options, Mode mode, Outputs outputs, MeetFeed feed, Triggers triggers) {
        this.options = options;
        this.mode = mode;
        this.outputs = outputs;
        this.feed = feed;
        this.triggers = triggers;
    }

    //<editor-fold desc="Orchestrazione">

    public static void main(String[] args) throws Exception {
        Options options;
        try {
            options = parseOptions(args);
        } catch (IllegalArgumentException invalidArguments) {
            System.out.println(invalidArguments.getMessage());
            System.out.println(usage());
            System.exit(2);
            return;
        }
        Path meetDirectory = options.meetFile().toAbsolutePath().getParent();
        String stamp = baseName(options.meetFile());
        Path diary = meetDirectory.resolve(stamp + ".diag.tsv");
        Mode mode = options.replay() ? Mode.REPLAY : diaryHasEnd(diary) ? Mode.CONCLUDED : Mode.LIVE;
        Outputs outputs = outputs(meetDirectory, stamp, options.tag());
        if (!lockMeet(outputs)) {
            System.out.println("pulizia gia' in corso su " + options.meetFile().getFileName() + " (pid "
                    + readPid(outputs.pid()) + "): esco");
            System.exit(EXIT_ALREADY_RUNNING);
            return;
        }
        MeetFeed feed = mode == Mode.LIVE ? MeetFeed.live(options.meetFile(), diary)
                : MeetFeed.recorded(options.meetFile(), diary);

        MeetCleaner cleaner = new MeetCleaner(options, mode, outputs, feed, new Triggers());
        Runtime.getRuntime().addShutdownHook(new Thread(cleaner::killSession));
        System.exit(cleaner.run());
    }

    private int run() throws IOException {
        openWriters();
        if (mode != Mode.REPLAY)
            restore();
        startWatchdog();
        System.out.println("pulizia di " + options.meetFile().getFileName() + "  --  modo " + mode.name().toLowerCase(Locale.ROOT)
                + ", modello " + options.claude().model() + (options.claude().effort() == null ? "" : ", effort " + options.claude().effort())
                + ", progetto " + options.projectDirectory() + ", segnalibro " + bookmark);
        System.out.println("file di settings: " + options.settingsFile()
                + (Files.isRegularFile(options.settingsFile()) ? "" : " (assente: nessun tema, nessuna parola, nessun nome)"));

        reloadSettings();
        long startMillis;
        if (mode == Mode.LIVE) {
            feed.refresh(wallMillis());
            startMillis = wallMillis();
            backlogEnd = feed.size();
            startTriggerThread();
        } else if (mode == Mode.CONCLUDED) {
            feed.refresh(Long.MAX_VALUE);
            backlogEnd = feed.size();
            simulatedMillis = feed.size() == 0 ? 0 : feed.availableAt(feed.size());
            startMillis = simulatedMillis;
            writeEvents(triggers.advance(feed, Long.MAX_VALUE));
        } else {
            simulatedMillis = feed.nextArrivalMillis() == Long.MAX_VALUE ? 0 : feed.nextArrivalMillis();
            startMillis = simulatedMillis;
            backlogEnd = 0;
        }
        if (backlogEnd > bookmark)
            System.out.println("arretrato: righe " + (bookmark + 1) + "-" + backlogEnd);

        try {
            loop(startMillis);
            finish();
        } finally {
            killSession();
            closeWriters();
        }
        String summary = summary();
        Files.writeString(outputs.summary(), summary, StandardCharsets.UTF_8);
        System.out.println();
        System.out.print(summary);
        return 0;
    }

    private void loop(long tickBaseMillis) throws IOException {
        long tickMillis = tickBaseMillis;
        while (options.maxTurns() == 0 || turns.size() < options.maxTurns()) {
            long nowMillis = now();
            reloadSettings();
            refreshFeed(nowMillis);
            int available = feed.size();
            if (bookmark >= available) {
                if (feed.finished())
                    return;
                long nextTick = mode == Mode.REPLAY
                        ? tickAtOrAfter(tickBaseMillis, feed.nextArrivalMillis())
                        : tickAtOrAfter(tickBaseMillis, nowMillis + 1);
                waitUntil(nextTick);
                tickMillis = nextTick;
                continue;
            }

            boolean backlog = bookmark + 1 <= backlogEnd;
            runChunk(available, nowMillis, tickMillis, tickBaseMillis);
            processTitles();
            long endMillis = now();
            if (backlog) {
                tickMillis = endMillis;
                continue;
            }
            long nextTick = tickAtOrAfter(tickBaseMillis, endMillis);
            if (nextTick <= tickMillis)
                nextTick = tickMillis + TICK_MILLIS;
            waitUntil(nextTick);
            tickMillis = nextTick;
        }
    }

    private void finish() throws IOException {
        if (feed.finished())
            writeEvents(triggers.advance(feed, now() + Triggers.SILENCE_MILLIS));
        if (openBand != null) {
            bandsAwaitingTitle.add(openBand);
            openBand = null;
        }
        processTitles();
        if (feed.finished() && bookmark >= feed.size() && !endEventWritten)
            writeEvents(List.of(new Triggers.Event(now(), "fine", 1, bookmark, "righe=" + bookmark)));
        saveBookmark();
    }

    //</editor-fold>

    //<editor-fold desc="Chunk">

    private void runChunk(int available, long nowMillis, long tickMillis, long tickBaseMillis) throws IOException {
        int first = bookmark + 1;
        boolean backlog = first <= backlogEnd;
        int last = Math.min(available, first + (backlog ? BACKLOG_BLOCK_LINES : MAX_LIVE_CHUNK_LINES) - 1);
        String forcedBy = forcedBy(first, last, available, backlog, nowMillis);
        boolean forced = forcedBy != null;

        String restart = ensureSession();
        List<Rephraser.Line> newLines = feed.range(first, last);
        Rephraser.Chunk chunk = chunk(newLines, forced);
        String message = Rephraser.chunkMessage(chunk);
        long startMillis = now();
        ClaudeSession.Turn turn = send(message);

        Rephraser.Reply reply = turn.failed() ? Rephraser.Reply.failed(turn.error())
                : Rephraser.parseReply(turn.text(), newLines, forced, sessionThemes);
        long endMillis = now();
        long nextTick = tickAtOrAfter(tickBaseMillis, endMillis);
        long skippedTicks = backlog ? 0 : Math.max(0, (Math.max(nextTick, tickMillis + TICK_MILLIS) - tickMillis) / TICK_MILLIS - 1);

        TurnRecord record = new TurnRecord(turns.size() + 1, startMillis, "chunk", first, last, forcedBy,
                reply.outcome(), reply.closedThrough(), reply.segments().size(), turn, skippedTicks, restart,
                reply.error());
        recordTurn(record, message);

        switch (reply.outcome()) {
            case CLOSED -> {
                applySegments(reply.segments(), endMillis);
                for (int number = first; number <= reply.closedThrough(); number++)
                    closedMillis.put(number, endMillis);
                bookmark = reply.closedThrough();
                errorsOnFirst = 0;
                incompleteOnFirst = 0;
                saveBookmark();
            }
            case INCOMPLETE -> incompleteOnFirst++;
            case ERROR -> {
                errorsOnFirst++;
                if (forced && errorsOnFirst >= ERRORS_BEFORE_RAW) {
                    writeRaw(newLines, endMillis);
                    for (int number = first; number <= last; number++)
                        closedMillis.put(number, endMillis);
                    bookmark = last;
                    errorsOnFirst = 0;
                    incompleteOnFirst = 0;
                    saveBookmark();
                }
            }
        }
    }

    private String forcedBy(int first, int last, int available, boolean backlog, long nowMillis) {
        if (feed.finished() && last == available)
            return "fine";
        if (errorsOnFirst >= ERRORS_BEFORE_FORCING)
            return "errori";
        if (backlog)
            return incompleteOnFirst >= 1 ? "arretrato" : null;
        if (last - first + 1 >= CAP_LINES)
            return "righe";
        if (nowMillis - feed.availableAt(first) >= CAP_AGE_MILLIS)
            return "attesa";
        return null;
    }

    /**
     * Al primo chunk dopo l'avvio del processo: le ultime righe gia' pulite come contesto, tutte le
     * voci viste fin qui, l'argomento della fascia aperta. Poi solo le voci nuove.
     */
    private Rephraser.Chunk chunk(List<Rephraser.Line> newLines, boolean forced) {
        Set<String> voices = new LinkedHashSet<>();
        if (sessionIntroPending) {
            List<Rephraser.Line> context = bookmark == 0 ? List.of()
                    : feed.range(Math.max(1, bookmark - CONTEXT_LINES + 1), bookmark);
            feed.range(1, newLines.getLast().number()).forEach(line -> voices.add(line.speaker()));
            participantsSent.clear();
            participantsSent.addAll(voices);
            return new Rephraser.Chunk(context, List.copyOf(voices), List.of(),
                    openBand == null ? null : openBand.label, newLines, forced);
        }
        newLines.forEach(line -> {
            if (participantsSent.add(line.speaker()))
                voices.add(line.speaker());
        });
        return new Rephraser.Chunk(List.of(), List.of(), List.copyOf(voices), null, newLines, forced);
    }

    private ClaudeSession.Turn send(String message) {
        ClaudeSession.Turn turn = session.send(message);
        if (mode != Mode.LIVE)
            simulatedMillis += turn.millis();
        if (turn.failed()) {
            sessionBroken = true;
            return turn;
        }
        sessionIntroPending = false;
        chunksInSession++;
        lastContext = turn.contextTokens();
        if (sessionFirstContext < 0)
            sessionFirstContext = turn.contextTokens();
        long[] contexts = sessionContexts.getLast();
        if (contexts[0] == 0)
            contexts[0] = turn.contextTokens();
        contexts[1] = turn.contextTokens();
        contexts[2]++;
        return turn;
    }

    //</editor-fold>

    //<editor-fold desc="Tratti, fasce ed eventi">

    /**
     * Ogni tema del file di settings e' attivo: un tratto di lavoro con un tema del system prompt da'
     * un evento, `altro` no. Il system prompt porta i temi del file letti all'inizio del chunk.
     */
    private void applySegments(List<Rephraser.Segment> segments, long endMillis) throws IOException {
        Map<String, int[]> themeRanges = new LinkedHashMap<>();
        for (Rephraser.Segment segment : segments) {
            writeSegment(segment);
            addToBand(segment);
            if (segment.work() && sessionThemes.contains(segment.theme()))
                themeRanges.merge(segment.theme(), new int[] {segment.from(), segment.to()},
                        (known, added) -> new int[] {Math.min(known[0], added[0]), Math.max(known[1], added[1])});
        }
        List<Triggers.Event> themeEvents = new ArrayList<>();
        themeRanges.forEach((theme, range) -> themeEvents.add(new Triggers.Event(
                mode == Mode.CONCLUDED ? feed.availableAt(range[1]) : endMillis, "tema", range[0], range[1], theme)));
        writeEvents(themeEvents);
    }

    /**
     * Dopo tre risposte scartate sotto chiusura forzata le righe entrano nel .rif.tsv col testo
     * grezzo e il tema `grezzo`: nessuna riga resta senza tratto.
     */
    private void writeRaw(List<Rephraser.Line> lines, long endMillis) throws IOException {
        StringBuilder text = new StringBuilder();
        lines.forEach(line -> text.append(text.isEmpty() ? "" : " ").append(line.speaker()).append(": ").append(line.text()));
        String label = openBand == null ? RAW_THEME : openBand.label;
        Rephraser.Segment segment = new Rephraser.Segment(lines.getFirst().number(), lines.getLast().number(),
                RAW_THEME, label, text.toString());
        rawLines += lines.size();
        writeSegment(segment);
        addToBand(segment);
    }

    private void writeSegment(Rephraser.Segment segment) throws IOException {
        StringBuilder revised = new StringBuilder();
        for (Rephraser.Line line : feed.range(segment.from(), segment.to()))
            if (line.revises() > 0)
                revised.append(revised.isEmpty() ? "" : ",").append(line.revises());
        writeLine(segmentsWriter, segment.from() + "\t" + segment.to() + "\t" + feed.line(segment.from()).time()
                + "\t" + segment.theme() + "\t" + segment.label() + "\t" + segment.text().replace('\t', ' ')
                + (revised.isEmpty() ? "" : "\t" + revised));
        segmentsWritten++;
    }

    private void addToBand(Rephraser.Segment segment) {
        if (openBand == null) {
            openBand = new Band(segment);
        } else if (openBand.label.equals(segment.label())) {
            openBand.extend(segment);
        } else {
            bandsAwaitingTitle.add(openBand);
            openBand = new Band(segment);
        }
    }

    /**
     * Il titolo di ogni fascia chiusa. Il processo ricorda le fasce che ha visto per intero; per una
     * fascia cominciata prima del suo avvio riceve anche i testi corretti, al piu' gli ultimi 6.000
     * caratteri. Una risposta che non e' un titolo lascia come titolo l'etichetta.
     */
    private void processTitles() throws IOException {
        while (!bandsAwaitingTitle.isEmpty()) {
            Band band = bandsAwaitingTitle.poll();
            String restart = ensureSession();
            List<String> texts = band.from < sessionStartLine ? lastTexts(band.texts) : List.of();
            String message = Rephraser.titleMessage(band.from, band.to, band.label, texts);
            long startMillis = now();
            ClaudeSession.Turn turn = send(message);
            String title = turn.failed() ? null : Rephraser.parseTitle(turn.text());
            String error = turn.failed() ? turn.error() : title == null ? "titolo illeggibile" : null;
            recordTurn(new TurnRecord(turns.size() + 1, startMillis, "titolo", band.from, band.to, null,
                    error == null ? Rephraser.Outcome.CLOSED : Rephraser.Outcome.ERROR, band.to, 0, turn, 0,
                    restart, error), message);
            writeLine(indexWriter, feed.line(band.from).time() + "\t" + feed.line(band.to).time() + "\t" + band.label
                    + "\t" + (title == null ? band.label : title) + "\t" + band.from + "\t" + band.to);
            bandsWritten++;
        }
    }

    private static List<String> lastTexts(List<String> texts) {
        List<String> kept = new ArrayList<>();
        int characters = 0;
        for (int index = texts.size() - 1; index >= 0 && characters < MAX_TITLE_TEXT_CHARS; index--) {
            kept.addFirst(texts.get(index));
            characters += texts.get(index).length();
        }
        return kept;
    }

    private synchronized void writeEvents(List<Triggers.Event> newEvents) throws IOException {
        for (Triggers.Event event : newEvents) {
            events.add(event);
            writeLine(eventsWriter, clock(event.millis()) + "\t" + event.kind() + "\t" + event.from() + "\t"
                    + event.to() + "\t" + event.detail());
            System.out.println(clock(event.millis()) + " evento " + event.kind() + " " + event.from() + "-" + event.to());
        }
    }

    //</editor-fold>

    //<editor-fold desc="Processo claude">

    /**
     * Avvia o riavvia il processo quando serve, e dice perche'; null se resta quello di prima.
     */
    private String ensureSession() throws IOException {
        Map<String, String> themes = validThemes(settings.lines(ProjectSettings.THEMES));
        String prompt = Rephraser.systemPrompt(configFile("istruzioni.md"),
                settings.lines(ProjectSettings.GLOSSARY), themes);

        String reason;
        if (session == null)
            reason = "avvio";
        else if (sessionBroken || !session.alive())
            reason = "processo";
        else if (!prompt.equals(sessionPrompt))
            reason = "prompt";
        else if (options.restartChunks() > 0 && chunksInSession >= options.restartChunks())
            reason = "chunk";
        else if (sessionFirstContext >= 0 && lastContext - sessionFirstContext >= options.restartContextGrowth())
            reason = "contesto";
        else
            return null;

        if (session != null) {
            System.out.println(clock(now()) + " riavvio di claude: " + reason + " (turni " + session.turns()
                    + ", contesto " + thousands(lastContext) + ")");
            if (sessionBroken)
                session.destroyTree();
            else
                session.close();
        }
        Files.writeString(outputs.prompt(), prompt, StandardCharsets.UTF_8);
        session = ClaudeSession.start(options.claude(), options.projectDirectory(), outputs.prompt().toAbsolutePath());
        sessionBroken = false;
        sessionPrompt = prompt;
        sessionThemes = List.copyOf(themes.keySet());
        sessionStartLine = bookmark + 1;
        sessionIntroPending = true;
        chunksInSession = 0;
        sessionFirstContext = -1;
        lastContext = 0;
        sessionContexts.add(new long[3]);
        restarts.merge(reason, 1, Integer::sum);
        return reason;
    }

    private void killSession() {
        ClaudeSession current = session;
        if (current != null && current.alive())
            current.destroyTree();
    }

    //</editor-fold>

    //<editor-fold desc="Impostazioni e ripresa">

    /**
     * Rilegge a ogni giro il file di settings e la voce dell'utente. Parole chiave, voce e nome
     * valgono subito, nei trigger; temi e glossario al chunk dopo, che riavvia claude perche' il
     * system prompt e' cambiato. Un file che non si legge lascia le impostazioni di prima, con un
     * avviso; un file assente le svuota.
     */
    private void reloadSettings() {
        try {
            settings = ProjectSettings.read(options.settingsFile());
            settingsUnreadableWarned = false;
        } catch (IOException unreadable) {
            if (!settingsUnreadableWarned)
                System.out.println(clock(now()) + " impostazioni illeggibili, tengo quelle di prima: " + unreadable);
            settingsUnreadableWarned = true;
        }
        triggers.setKeywords(settings.lines(ProjectSettings.KEYWORDS));
        applySecretary();
        String description = describeSettings();
        if (!description.equals(settingsDescription)) {
            System.out.println((settingsDescription == null ? "" : clock(now()) + " ") + "impostazioni: " + description);
            settingsDescription = description;
        }
    }

    /**
     * La voce dell'utente dalla chiave `voce` di config.properties, la regex del nome dalla sezione
     * [segretario]; --voce-utente e --nome li battono. Una regex che non compila spegne il nome, con
     * un avviso, invece di fermare la pulizia.
     */
    private void applySecretary() {
        String voice = options.userVoiceArgument() != null ? options.userVoiceArgument()
                : recorderConfiguration().get(USER_VOICE_KEY);
        String regex = options.nameRegexArgument() != null ? options.nameRegexArgument()
                : settings.value(ProjectSettings.SECRETARY, "nome");
        voice = voice == null || voice.isBlank() ? null : voice.strip();
        regex = regex == null || regex.isBlank() ? null : regex;
        if (secretaryApplied && Objects.equals(voice, appliedVoice) && Objects.equals(regex, appliedNameRegex))
            return;
        Pattern pattern = null;
        if (regex != null)
            try {
                pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            } catch (PatternSyntaxException invalid) {
                System.out.println(clock(now()) + " regex del nome non valida, nome spento: "
                        + invalid.getDescription() + " in " + regex);
            }
        triggers.setSecretary(voice, pattern);
        appliedVoice = voice;
        appliedNameRegex = regex;
        nameActive = pattern != null;
        secretaryApplied = true;
    }

    /**
     * I temi della sezione [temi], `nome` o `nome: keyword, keyword`: un nome di tema non valido si
     * scarta con un avviso, invece di fermare la pulizia per un errore di battitura.
     */
    private Map<String, String> validThemes(List<String> themeLines) {
        Map<String, String> themes = new LinkedHashMap<>();
        for (String line : themeLines) {
            try {
                Rephraser.mergeThemes(themes, List.of(line));
            } catch (IllegalArgumentException invalid) {
                if (warnedThemeLines.add(line))
                    System.out.println("temi del file di settings: " + invalid.getMessage() + ", riga ignorata");
            }
        }
        return themes;
    }

    private String describeSettings() {
        List<String> keywords = settings.lines(ProjectSettings.KEYWORDS);
        Set<String> themes = validThemes(settings.lines(ProjectSettings.THEMES)).keySet();
        return "temi " + (themes.isEmpty() ? "nessuno" : String.join(", ", themes))
                + "; parole " + (keywords.isEmpty() ? "nessuna" : String.join(", ", keywords))
                + "; righe di glossario " + settings.lines(ProjectSettings.GLOSSARY).size()
                + "; nome " + (nameActive ? appliedNameRegex : "spento")
                + " sulla voce " + (appliedVoice == null ? "non configurata" : appliedVoice);
    }

    /**
     * Riparte da dove si era fermato: il segnalibro e' il piu' alto fra quello salvato e l'ultima
     * riga del .rif.tsv, e i tratti dopo l'ultima fascia dell'indice riformano la fascia aperta.
     */
    private void restore() throws IOException {
        int savedBookmark = 0;
        int savedTrigger = -1;
        if (Files.exists(outputs.bookmark()))
            for (String line : Files.readAllLines(outputs.bookmark(), StandardCharsets.UTF_8)) {
                if (line.startsWith("pulizia="))
                    savedBookmark = Integer.parseInt(line.substring("pulizia=".length()).strip());
                if (line.startsWith("trigger="))
                    savedTrigger = Integer.parseInt(line.substring("trigger=".length()).strip());
            }
        int lastIndexed = 0;
        if (Files.exists(outputs.index()))
            for (String line : Files.readAllLines(outputs.index(), StandardCharsets.UTF_8)) {
                String[] fields = line.split("\t");
                if (fields.length >= 6)
                    lastIndexed = Math.max(lastIndexed, Integer.parseInt(fields[5]));
            }
        int lastSegment = 0;
        if (Files.exists(outputs.segments()))
            for (String line : Files.readAllLines(outputs.segments(), StandardCharsets.UTF_8)) {
                String[] fields = line.split("\t");
                if (fields.length < 6 || !fields[0].matches("\\d+"))
                    continue;
                Rephraser.Segment segment = new Rephraser.Segment(Integer.parseInt(fields[0]),
                        Integer.parseInt(fields[1]), fields[3], fields[4], fields[5]);
                lastSegment = Math.max(lastSegment, segment.to());
                if (segment.from() > lastIndexed)
                    addToBand(segment);
            }
        bookmark = Math.max(savedBookmark, lastSegment);
        triggers.resumeAfter(savedTrigger >= 0 ? savedTrigger : bookmark);
        if (Files.exists(outputs.events()))
            try (Stream<String> lines = Files.lines(outputs.events(), StandardCharsets.UTF_8)) {
                endEventWritten = lines.anyMatch(line -> line.split("\t").length >= 2 && line.split("\t")[1].equals("fine"));
            }
    }

    /**
     * Una pulizia sola per meet: due script sullo stesso meet appenderebbero due volte gli stessi
     * tratti. Il lucchetto e' `<stamp>.pulizia.lock`, tenuto finche' il processo vive e rilasciato da
     * Windows anche quando il processo muore senza chiudere; accanto, `<stamp>.pulizia.pid` porta il
     * PID per chi vuole sapere se la pulizia gira (meet-catchup --status).
     */
    private static boolean lockMeet(Outputs outputs) throws IOException {
        FileChannel channel = FileChannel.open(outputs.lock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException alreadyLockedHere) {
            lock = null;
        }
        if (lock == null) {
            channel.close();
            return false;
        }
        meetLock = lock;
        Files.writeString(outputs.pid(), ProcessHandle.current().pid() + "\n", StandardCharsets.UTF_8);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.deleteIfExists(outputs.pid());
            } catch (IOException ignored) {
            }
        }));
        return true;
    }

    private static String readPid(Path pidFile) {
        try {
            return Files.readString(pidFile, StandardCharsets.UTF_8).strip();
        } catch (IOException unreadable) {
            return "?";
        }
    }

    private synchronized void saveBookmark() throws IOException {
        Path temporary = outputs.bookmark().resolveSibling(outputs.bookmark().getFileName() + ".tmp");
        Files.writeString(temporary, "pulizia=" + bookmark + "\ntrigger=" + triggers.scannedThrough() + "\n",
                StandardCharsets.UTF_8);
        Files.move(temporary, outputs.bookmark(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    //</editor-fold>

    //<editor-fold desc="Orologio, trigger e sorveglianza">

    private long now() {
        return mode == Mode.LIVE ? wallMillis() : simulatedMillis;
    }

    private void refreshFeed(long nowMillis) throws IOException {
        if (mode == Mode.LIVE)
            return;
        feed.refresh(nowMillis);
        if (mode == Mode.REPLAY)
            writeEvents(triggers.advance(feed, nowMillis));
    }

    private void waitUntil(long targetMillis) throws IOException {
        if (mode != Mode.LIVE) {
            simulatedMillis = Math.max(simulatedMillis, targetMillis);
            return;
        }
        while (wallMillis() < targetMillis) {
            try {
                Thread.sleep(Math.min(1_000, targetMillis - wallMillis()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            feed.refresh(wallMillis());
            if (feed.finished() && bookmark >= feed.size())
                return;
        }
    }

    /**
     * Dal vivo i trigger girano su un thread loro, ogni 250 ms, perche' una chiamata a claude dura
     * secondi: il nome detto dall'utente non aspetta la pulizia.
     */
    private void startTriggerThread() {
        Thread.ofPlatform().daemon().name("trigger").start(() -> {
            while (true) {
                try {
                    long nowMillis = wallMillis();
                    feed.refresh(nowMillis);
                    int scannedBefore = triggers.scannedThrough();
                    writeEvents(triggers.advance(feed, nowMillis));
                    if (triggers.scannedThrough() != scannedBefore)
                        saveBookmark();
                    Thread.sleep(TRIGGER_POLL_MILLIS);
                } catch (InterruptedException interrupted) {
                    return;
                } catch (IOException | RuntimeException failure) {
                    System.out.println("trigger: " + failure.getMessage());
                    try {
                        Thread.sleep(TRIGGER_POLL_MILLIS * 4);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
            }
        });
    }

    /**
     * Lo script muore con chi l'ha lanciato. Lanciato in background da Claude Code, su Windows alla
     * chiusura della sessione resterebbe vivo, e col suo claude continuerebbe a spendere: si ferma
     * appena uno dei processi sorvegliati non c'e' piu'. Sono i processi sopra di lui che Java vede,
     * fino al primo claude.exe compreso, piu' i PID di --sorveglia: Java non risale oltre la prima
     * bash di Git Bash, e le shell sopra gliele passa il launcher.
     */
    private void startWatchdog() {
        List<ProcessHandle> ancestors = new ArrayList<>();
        Optional<ProcessHandle> parent = ProcessHandle.current().parent();
        while (parent.isPresent() && ancestors.size() < MAX_ANCESTORS) {
            ProcessHandle handle = parent.get();
            ancestors.add(handle);
            String command = handle.info().command().orElse("").toLowerCase(Locale.ROOT);
            if (command.endsWith("claude.exe"))
                break;
            parent = handle.parent();
        }
        for (long pid : options.watchedPids())
            ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ancestors::add);
        StringBuilder watched = new StringBuilder();
        ancestors.forEach(handle -> watched.append(watched.isEmpty() ? "" : ", ").append(handle.pid()).append(' ')
                .append(Path.of(handle.info().command().orElse("?")).getFileName()));
        System.out.println("sorveglio: " + watched);
        Thread.ofPlatform().daemon().name("watchdog").start(() -> {
            while (true) {
                for (ProcessHandle ancestor : ancestors)
                    if (!ancestor.isAlive()) {
                        System.out.println("il processo " + ancestor.pid() + " che ha lanciato lo script non c'e' piu': esco");
                        killSession();
                        Runtime.getRuntime().halt(3);
                    }
                try {
                    Thread.sleep(WATCHDOG_POLL_MILLIS);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        });
    }

    private static long wallMillis() {
        return LocalTime.now().toNanoOfDay() / 1_000_000;
    }

    private static long tickAtOrAfter(long startMillis, long millis) {
        long elapsed = millis - startMillis;
        long ticks = (elapsed + TICK_MILLIS - 1) / TICK_MILLIS;
        return startMillis + Math.max(0, ticks) * TICK_MILLIS;
    }

    //</editor-fold>

    //<editor-fold desc="Sintesi" defaultstate="collapsed">

    private String summary() {
        StringBuilder text = new StringBuilder();
        int lineCount = feed.size();
        long meetMillis = lineCount == 0 ? 0 : feed.availableAt(lineCount) - feed.availableAt(1);
        List<TurnRecord> chunks = turns.stream().filter(turn -> turn.kind().equals("chunk")).toList();
        List<TurnRecord> titles = turns.stream().filter(turn -> turn.kind().equals("titolo")).toList();
        long incomplete = chunks.stream().filter(turn -> turn.outcome() == Rephraser.Outcome.INCOMPLETE).count();
        long errors = chunks.stream().filter(turn -> turn.outcome() == Rephraser.Outcome.ERROR).count();
        double totalCost = turns.stream().mapToDouble(turn -> turn.turn().costUsd()).sum();

        line(text, "configurazione", "modo " + mode.name().toLowerCase(Locale.ROOT) + ", modello " + options.claude().model()
                + (options.claude().effort() == null ? "" : ", effort " + options.claude().effort())
                + ", riavvio ogni " + options.restartChunks() + " chunk o a +" + thousands(options.restartContextGrowth())
                + " token di contesto");
        line(text, "righe del meet", lineCount + ", " + format(meetMillis / 60_000.0) + " minuti fra la prima e l'ultima"
                + ", pulite fino alla " + bookmark);
        line(text, "turni", turns.size() + ": chunk " + chunks.size() + ", titoli " + titles.size());
        line(text, "chunk", "chiuso " + (chunks.size() - incomplete - errors) + ", incompleto " + incomplete
                + " (" + percent(incomplete, chunks.size()) + "), errore " + errors + " (" + percent(errors, chunks.size()) + ")");
        Map<String, Long> forced = new LinkedHashMap<>();
        chunks.stream().filter(turn -> turn.forcedBy() != null).forEach(turn -> forced.merge(turn.forcedBy(), 1L, Long::sum));
        line(text, "chiusure forzate", forced.isEmpty() ? "nessuna" : forced.toString());
        line(text, "righe col testo grezzo", String.valueOf(rawLines));
        line(text, "righe nuove per chunk", "media " + format(chunks.stream()
                .mapToInt(turn -> turn.lastLine() - turn.firstLine() + 1).average().orElse(0)) + ", massimo "
                + chunks.stream().mapToInt(turn -> turn.lastLine() - turn.firstLine() + 1).max().orElse(0));
        line(text, "durata del chunk", distribution(chunks, turn -> turn.turn().millis()));
        line(text, "durata del titolo", distribution(titles, turn -> turn.turn().millis()));
        line(text, "chunk oltre un giro", chunks.stream().filter(turn -> turn.turn().millis() > TICK_MILLIS).count()
                + " (" + percent(chunks.stream().filter(turn -> turn.turn().millis() > TICK_MILLIS).count(), chunks.size())
                + "), giri saltati " + chunks.stream().mapToLong(TurnRecord::skippedTicks).sum());
        line(text, "tool usati", turns.stream().mapToInt(turn -> turn.turn().toolCalls()).sum() + " in "
                + turns.stream().filter(turn -> turn.turn().toolCalls() > 0).count() + " turni");
        line(text, "costo", String.format(Locale.ROOT, "%.4f USD in tutto, %.5f a turno, %.4f al minuto di meet, %.2f all'ora",
                totalCost, turns.isEmpty() ? 0 : totalCost / turns.size(),
                meetMillis == 0 ? 0 : totalCost / (meetMillis / 60_000.0),
                meetMillis == 0 ? 0 : totalCost / (meetMillis / 3_600_000.0)));
        line(text, "token a turno (media)", String.format(Locale.ROOT,
                "ingresso %.0f, cache scritti %.0f, cache letti %.0f, uscita %.0f di cui thinking %.0f",
                average(turn -> turn.turn().inputTokens()), average(turn -> turn.turn().cacheWriteTokens()),
                average(turn -> turn.turn().cacheReadTokens()), average(turn -> turn.turn().outputTokens()),
                average(turn -> turn.turn().thinkingTokens())));
        StringBuilder contexts = new StringBuilder();
        for (long[] session : sessionContexts)
            contexts.append(contexts.isEmpty() ? "" : "; ").append(session[2]).append(" turni, da ")
                    .append(thousands(session[0])).append(" a ").append(thousands(session[1]))
                    .append(session[2] > 1 ? ", +" + thousands((session[1] - session[0]) / (session[2] - 1)) + " a turno" : "");
        line(text, "contesto per processo", contexts.toString());
        line(text, "riavvii", restarts.toString());
        if (mode != Mode.CONCLUDED) {
            line(text, "ritardo dalla scrittura della riga alla chiusura", lineDelays(number -> feed.availableAt(number)));
            line(text, "ritardo dalla comparsa nei sottotitoli alla chiusura",
                    lineDelays(number -> MeetFeed.millisOfDay(feed.line(number).time())));
        }
        long workSegments = 0;
        Map<String, Integer> themes = new LinkedHashMap<>();
        try {
            for (String row : Files.readAllLines(outputs.segments(), StandardCharsets.UTF_8)) {
                String[] fields = row.split("\t");
                if (fields.length < 6)
                    continue;
                themes.merge(fields[3], 1, Integer::sum);
                if (!fields[3].equals(Rephraser.FUFFA))
                    workSegments++;
            }
        } catch (IOException ignored) {
        }
        line(text, "tratti", segmentsWritten + " scritti in questo giro; nel file, lavoro " + workSegments + ", per tema " + themes);
        line(text, "fasce", bandsWritten + " scritte in questo giro");
        Map<String, Long> eventCounts = new LinkedHashMap<>();
        events.forEach(event -> eventCounts.merge(event.kind(), 1L, Long::sum));
        line(text, "eventi", eventCounts.isEmpty() ? "nessuno" : eventCounts.toString());
        for (String kind : List.of("nome", "parola", "tema")) {
            long[] latencies = events.stream().filter(event -> event.kind().equals(kind))
                    .mapToLong(event -> event.millis() - MeetFeed.millisOfDay(feed.line(event.to()).time()))
                    .sorted().toArray();
            if (latencies.length > 0)
                line(text, "ritardo dalla comparsa all'evento " + kind, millisDistribution(latencies));
        }
        StringBuilder errorText = new StringBuilder();
        turns.stream().filter(turn -> turn.error() != null)
                .forEach(turn -> errorText.append("\n  turno ").append(turn.number()).append(": ").append(turn.error()));
        line(text, "errori", errorText.isEmpty() ? "nessuno" : errorText.toString());
        return text.toString();
    }

    private double average(ToLongFunction<TurnRecord> field) {
        return turns.stream().mapToLong(field).average().orElse(0);
    }

    private static String distribution(List<TurnRecord> records, ToLongFunction<TurnRecord> field) {
        return millisDistribution(records.stream().mapToLong(field).sorted().toArray());
    }

    private String lineDelays(ToLongFunction<Integer> fromMillis) {
        long[] values = closedMillis.entrySet().stream()
                .mapToLong(entry -> entry.getValue() - fromMillis.applyAsLong(entry.getKey()))
                .sorted().toArray();
        return millisDistribution(values);
    }

    private static String millisDistribution(long[] sorted) {
        if (sorted.length == 0)
            return "nessun valore";
        double mean = Arrays.stream(sorted).average().orElse(0);
        return String.format(Locale.ROOT, "p10 %.1f s, mediana %.1f s, p90 %.1f s, massimo %.1f s, media %.1f s",
                percentile(sorted, 0.10) / 1000.0, percentile(sorted, 0.50) / 1000.0,
                percentile(sorted, 0.90) / 1000.0, sorted[sorted.length - 1] / 1000.0, mean / 1000.0);
    }

    private static long percentile(long[] sorted, double fraction) {
        int index = (int) Math.ceil(fraction * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    //</editor-fold>

    //<editor-fold desc="Scrittura e console" defaultstate="collapsed">

    private void openWriters() throws IOException {
        boolean fresh = mode == Mode.REPLAY;
        segmentsWriter = writer(outputs.segments(), fresh);
        indexWriter = writer(outputs.index(), fresh);
        eventsWriter = writer(outputs.events(), fresh);
        boolean callsExisted = !fresh && Files.exists(outputs.calls());
        callsWriter = writer(outputs.calls(), fresh);
        if (!callsExisted)
            writeLine(callsWriter, "turno\tora\ttipo\tprima\tultima\tnuove\tforzata\tesito\tchiuso\ttratti\tms"
                    + "\tdurata_ms\tapi_ms\tcosto_usd\tin\tcache_scritti\tcache_letti\tout\tthinking\tcontesto"
                    + "\tchiamate_api\ttool\tgiri_saltati\triavvio\terrore");
        repliesWriter = writer(outputs.replies(), fresh);
    }

    private void closeWriters() throws IOException {
        for (BufferedWriter writer : List.of(segmentsWriter, indexWriter, eventsWriter, callsWriter, repliesWriter))
            writer.close();
    }

    private void recordTurn(TurnRecord record, String message) throws IOException {
        turns.add(record);
        ClaudeSession.Turn turn = record.turn();
        writeLine(callsWriter, record.number() + "\t" + clock(record.startMillis()) + "\t" + record.kind() + "\t"
                + record.firstLine() + "\t" + record.lastLine() + "\t" + (record.lastLine() - record.firstLine() + 1)
                + "\t" + (record.forcedBy() == null ? "" : record.forcedBy())
                + "\t" + record.outcome().name().toLowerCase(Locale.ROOT)
                + "\t" + (record.outcome() == Rephraser.Outcome.CLOSED ? record.closedThrough() : "")
                + "\t" + record.segments() + "\t" + turn.millis() + "\t" + turn.durationMillis() + "\t" + turn.apiMillis()
                + "\t" + String.format(Locale.ROOT, "%.6f", turn.costUsd()) + "\t" + turn.inputTokens()
                + "\t" + turn.cacheWriteTokens() + "\t" + turn.cacheReadTokens() + "\t" + turn.outputTokens()
                + "\t" + turn.thinkingTokens() + "\t" + turn.contextTokens() + "\t" + turn.apiCalls() + "\t" + turn.toolCalls()
                + "\t" + record.skippedTicks() + "\t" + (record.restart() == null ? "" : record.restart())
                + "\t" + (record.error() == null ? "" : record.error().replace('\t', ' ')));
        writeLine(repliesWriter, "=== turno " + record.number() + " alle " + clock(record.startMillis()) + ", " + record.kind()
                + (record.forcedBy() == null ? "" : ", chiusura forzata per " + record.forcedBy())
                + (record.restart() == null ? "" : ", claude avviato per " + record.restart()));
        writeLine(repliesWriter, message.strip());
        writeLine(repliesWriter, "--- risposta");
        writeLine(repliesWriter, turn.text().strip());
        if (record.error() != null)
            writeLine(repliesWriter, "--- errore: " + record.error());
        writeLine(repliesWriter, "");

        String outcome = switch (record.outcome()) {
            case CLOSED -> record.kind().equals("titolo") ? "titolo" : "chiuso " + record.closedThrough() + " (" + record.segments() + " tratti)";
            case INCOMPLETE -> "incompleto";
            case ERROR -> "ERRORE " + record.error();
        };
        System.out.printf(Locale.ROOT, "%s %-6s %d-%d%s  %s  %.1f s  %.4f USD  contesto %s%s%n", clock(record.startMillis()),
                record.kind(), record.firstLine(), record.lastLine(),
                record.forcedBy() == null ? "" : " forzata/" + record.forcedBy(), outcome, turn.millis() / 1000.0,
                turn.costUsd(), thousands(turn.contextTokens()),
                record.restart() == null ? "" : "  [claude avviato: " + record.restart() + "]");
    }

    private static BufferedWriter writer(Path path, boolean truncate) throws IOException {
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                truncate ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND);
    }

    private static void writeLine(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.write('\n');
        writer.flush();
    }

    private static void line(StringBuilder text, String label, String value) {
        text.append(label).append(": ").append(value).append('\n');
    }

    private static String percent(long part, long total) {
        return total == 0 ? "0%" : String.format(Locale.ROOT, "%.0f%%", 100.0 * part / total);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String thousands(long tokens) {
        return String.format(Locale.ROOT, "%.1fk", tokens / 1000.0);
    }

    private static String clock(long millisOfDay) {
        long bounded = Math.max(0, Math.min(millisOfDay, 86_399_999L));
        return CLOCK.format(LocalTime.ofNanoOfDay(bounded * 1_000_000));
    }

    //</editor-fold>

    //<editor-fold desc="Argomenti e file" defaultstate="collapsed">

    private Path configFile(String name) {
        return options.configDirectory().resolve(name);
    }

    private static Outputs outputs(Path meetDirectory, String stamp, String tag) {
        String base = tag == null ? stamp : stamp + "." + tag;
        return new Outputs(meetDirectory.resolve(base + ".rif.tsv"), meetDirectory.resolve(base + ".indice.tsv"),
                meetDirectory.resolve(base + ".eventi.tsv"), meetDirectory.resolve(base + ".segnalibro-pulizia"),
                meetDirectory.resolve(base + ".pulizia.prompt.md"), meetDirectory.resolve(base + ".pulizia.chiamate.tsv"),
                meetDirectory.resolve(base + ".pulizia.risposte.txt"), meetDirectory.resolve(base + ".pulizia.sintesi.txt"),
                meetDirectory.resolve(base + ".pulizia.lock"), meetDirectory.resolve(base + ".pulizia.pid"));
    }

    /**
     * Le chiavi di %LOCALAPPDATA%\meet-recorder\config.properties, il file della persona sulla
     * macchina: `cartella`, la cartella dei meet, e `voce`, il nome dell'utente come lo scrive Teams
     * nei sottotitoli. Una riga per chiave, chiave=valore, righe # di commento, senza escape: per
     * questo non si legge con java.util.Properties, che dei backslash dei path fa escape. Senza file,
     * nessuna chiave.
     */
    private static Map<String, String> recorderConfiguration() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path configFile = (localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Local") : Path.of(localAppData))
                .resolve("meet-recorder").resolve("config.properties");
        Map<String, String> values = new HashMap<>();
        try {
            for (String rawLine : new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8).lines().toList()) {
                String line = rawLine.strip();
                if (line.startsWith("﻿"))
                    line = line.substring(1).strip();
                int separator = line.indexOf('=');
                if (line.startsWith("#") || line.startsWith("!") || separator <= 0)
                    continue;
                values.put(line.substring(0, separator).strip(), line.substring(separator + 1).strip());
            }
        } catch (IOException noConfiguration) {
        }
        return values;
    }

    /**
     * La cartella dei meet di meet-recorder, la chiave `cartella` di config.properties. Senza file,
     * senza chiave o con un path non assoluto vale il default del registratore, %USERPROFILE%\meet-teams.
     */
    private static Path defaultMeetDirectory() {
        String configured = recorderConfiguration().get(MEET_DIRECTORY_KEY);
        if (configured != null && !configured.isEmpty() && Path.of(configured).isAbsolute())
            return Path.of(configured);
        String profile = System.getenv("USERPROFILE");
        return (profile == null || profile.isBlank() ? Path.of(System.getProperty("user.home")) : Path.of(profile))
                .resolve("meet-teams");
    }

    private static boolean diaryHasEnd(Path diary) throws IOException {
        if (!Files.exists(diary))
            return false;
        try (Stream<String> lines = Files.lines(diary, StandardCharsets.UTF_8)) {
            return lines.anyMatch(line -> line.split("\t", 3).length >= 2 && line.split("\t", 3)[1].equals("fine"));
        }
    }

    /**
     * Il meet in corso: il file `<stamp>.tsv` piu' recente della cartella il cui diario non ha la
     * riga `fine` ed e' stato scritto negli ultimi 2 minuti. Il lettore scrive la riga `stato` ogni
     * 30 s; un diario fermo senza `fine` e' quello di un lettore caduto o chiuso senza fermarlo.
     */
    private static Path meetInProgress(Path meetDirectory) throws IOException {
        long freshSince = System.currentTimeMillis() - DIARY_ALIVE_MILLIS;
        try (Stream<Path> files = Files.list(meetDirectory)) {
            List<Path> candidates = files.filter(path -> MEET_FILE_NAME.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
            for (Path candidate : candidates) {
                Path diary = meetDirectory.resolve(baseName(candidate) + ".diag.tsv");
                if (Files.exists(diary) && !diaryHasEnd(diary)
                        && Files.getLastModifiedTime(diary).toMillis() >= freshSince)
                    return candidate;
            }
        }
        throw new IllegalArgumentException("nessun meet in corso in " + meetDirectory
                + ": nessun diario senza la riga fine scritto negli ultimi 2 minuti");
    }

    private static String baseName(Path meetFile) {
        String name = meetFile.getFileName().toString();
        return name.endsWith(".tsv") ? name.substring(0, name.length() - ".tsv".length()) : name;
    }

    private static Options parseOptions(String[] args) {
        Path meetFile = null;
        Path meetDirectory = defaultMeetDirectory();
        Path projectDirectory = Path.of("").toAbsolutePath();
        Path configDirectory = null;
        boolean replay = false;
        String tag = null;
        String model = "sonnet";
        String effort = "low";
        String claudeExecutable = "claude";
        int restartChunks = 200;
        long restartContextGrowth = 40_000;
        String userVoice = null;
        String nameRegex = null;
        int maxTurns = 0;
        List<Long> watchedPids = new ArrayList<>();
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (argument.equals("--replay")) {
                replay = true;
                continue;
            }
            if (index + 1 >= args.length)
                throw new IllegalArgumentException("valore mancante per " + argument);
            String value = args[++index];
            switch (argument) {
                case "--meet" -> meetFile = Path.of(value);
                case "--cartella" -> meetDirectory = Path.of(value);
                case "--progetto" -> projectDirectory = Path.of(value).toAbsolutePath();
                case "--config" -> configDirectory = Path.of(value).toAbsolutePath();
                case "--tag" -> tag = value;
                case "--model" -> model = value;
                case "--effort" -> effort = value.isBlank() || value.equals("default") ? null : value;
                case "--claude" -> claudeExecutable = value;
                case "--riavvio-chunk" -> restartChunks = Integer.parseInt(value);
                case "--riavvio-contesto" -> restartContextGrowth = Long.parseLong(value);
                case "--voce-utente" -> userVoice = value;
                case "--nome" -> nameRegex = value;
                case "--max-turni" -> maxTurns = Integer.parseInt(value);
                case "--sorveglia" -> {
                    for (String pid : value.split(","))
                        if (pid.strip().matches("\\d+"))
                            watchedPids.add(Long.parseLong(pid.strip()));
                }
                default -> throw new IllegalArgumentException("argomento sconosciuto: " + argument);
            }
        }
        if (configDirectory == null)
            throw new IllegalArgumentException("--config e' obbligatorio (il launcher lo passa)");
        if (!Files.isRegularFile(configDirectory.resolve("istruzioni.md")))
            throw new IllegalArgumentException("istruzioni.md assente in " + configDirectory);
        if (!Files.isDirectory(projectDirectory))
            throw new IllegalArgumentException("cartella del progetto assente: " + projectDirectory);
        try {
            if (meetFile == null)
                meetFile = meetInProgress(meetDirectory);
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("cartella dei meet illeggibile: " + meetDirectory);
        }
        if (!Files.isRegularFile(meetFile))
            throw new IllegalArgumentException("file del meet assente: " + meetFile);
        if (replay && tag == null)
            tag = "replay";
        Path claudeSettingsFile = configDirectory.resolve("settings.json");
        return new Options(meetFile.toAbsolutePath(), meetDirectory, projectDirectory, configDirectory,
                ProjectSettings.file(projectDirectory), replay, tag,
                new ClaudeSession.Settings(claudeExecutable, model, effort, claudeSettingsFile, "Read,Grep,Glob"),
                restartChunks, restartContextGrowth, userVoice, nameRegex, maxTurns, List.copyOf(watchedPids));
    }

    private static String usage() {
        return "uso: meet-clean [--meet <file.tsv>] [--cartella <dir>] [--progetto <dir>] [--replay] [--tag <nome>]"
                + " [--model <m>] [--effort <livello>|default] [--riavvio-chunk <n>] [--riavvio-contesto <token>]"
                + " [--voce-utente <nome>] [--nome <regex>] [--max-turni <n>] [--claude <eseguibile>]"
                + " [--sorveglia <pid,pid>] --config <dir>";
    }

    //</editor-fold>
}

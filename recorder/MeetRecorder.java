import java.awt.EventQueue;
import java.awt.SystemTray;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JOptionPane;

/**
 * Il registratore dei meet Teams: legge i sottotitoli live con UI Automation, chiamata da Java con
 * FFM, e scrive un file per meet, una riga per frase chiusa col nome di chi parla. Non chiama Claude
 * e non sa niente di temi, parole chiave o eventi: registra ogni meet coi sottotitoli accesi, e cosa
 * ne legge Claude lo decide chi segue la cartella dei meet.
 *
 * Modi d'uso, col JDK 25 (meet-recorder.bat li lancia):
 *   javaw MeetRecorder.java [--out <cartella>]                       app del tray, avviata al login
 *   java MeetRecorder.java --console [--out <cartella>] [--seconds <n>] [--interval-ms <n>]
 *                                                                    stesso ciclo, senza tray, in console
 *   java MeetRecorder.java --replay <registro-watch.tsv> [--out <cartella>]
 *                                                                    replay di un registro del probe
 *   java MeetRecorder.java --imposta-cartella <cartella>             scrive la cartella dei meet
 *                                                                    nella configurazione ed esce
 *   java MeetRecorder.java --controlla-acceso                        esce con 0 se nessun recorder
 *                                                                    e' acceso, con 3 se uno lo e'
 *
 * La cartella dei meet sta in %LOCALAPPDATA%\meet-recorder\config.properties (RecorderConfig); log
 * e lucchetto dell'istanza nella stessa cartella. Un'istanza sola per utente: l'app del tray e il
 * modo console si escludono, perche' due registratori sullo stesso meet scriverebbero due file.
 */
public class MeetRecorder {

    private static final String LOG_FILE_NAME = "meet-recorder.log";
    private static final String PREVIOUS_LOG_FILE_NAME = "meet-recorder.log.1";
    private static final String LOCK_FILE_NAME = "meet-recorder.lock";
    private static final long MAX_LOG_BYTES = 5_000_000;
    private static final int EXIT_RECORDER_RUNNING = 3;

    enum Mode { TRAY, CONSOLE, REPLAY, SET_MEET_DIRECTORY, CHECK_RUNNING }

    record Options(Mode mode, Path outputDirectory, Path argumentPath, long seconds, long pollIntervalMillis) {
    }

    private static FileChannel instanceLockChannel;

    public static void main(String[] args) throws Exception {
        Options options;
        try {
            options = parseOptions(args);
        } catch (IllegalArgumentException invalidArguments) {
            System.out.println(invalidArguments.getMessage());
            System.out.println("uso: javaw MeetRecorder.java [--out <cartella>]");
            System.out.println("     java MeetRecorder.java --console [--out <cartella>] [--seconds <n>] [--interval-ms <n>]");
            System.out.println("     java MeetRecorder.java --replay <registro-watch.tsv> [--out <cartella>]");
            System.out.println("     java MeetRecorder.java --imposta-cartella <cartella>");
            System.out.println("     java MeetRecorder.java --controlla-acceso");
            System.exit(2);
            return;
        }

        int exitCode = switch (options.mode()) {
            case REPLAY -> CaptionReplay.run(options.argumentPath(), options.outputDirectory());
            case SET_MEET_DIRECTORY -> setMeetDirectory(options.argumentPath());
            case CHECK_RUNNING -> checkRunning();
            case CONSOLE -> runConsole(options);
            case TRAY -> runTray(options);
        };
        System.exit(exitCode);
    }

    //<editor-fold desc="App del tray">

    /**
     * Senza console: il log va in un file, e un errore d'avvio si mostra con una finestra di
     * messaggio, altrimenti chi lancia l'app non vede niente.
     */
    private static int runTray(Options options) throws Exception {
        Path applicationDirectory = RecorderConfig.applicationDirectory();
        Files.createDirectories(applicationDirectory);
        redirectOutputToLog(applicationDirectory.resolve(LOG_FILE_NAME));
        Log.banner("meet-recorder avviato, Java " + Runtime.version() + ", app del tray");

        if (!acquireInstanceLock(applicationDirectory)) {
            Log.info("un altro meet-recorder e' gia' attivo, esco");
            showMessage("meet-recorder e' gia' attivo: l'icona sta nel tray, anche fra le icone nascoste.");
            return 1;
        }
        if (!SystemTray.isSupported()) {
            Log.info("il tray di sistema non e' disponibile, esco");
            showMessage("meet-recorder non puo' partire: il tray di sistema non e' disponibile.");
            return 1;
        }

        Path outputDirectory = meetDirectory(options);
        AtomicReference<TrayUi> trayReference = new AtomicReference<>();
        RecordingLoop loop = new RecordingLoop(outputDirectory, options.pollIntervalMillis(), false, snapshot -> {
            TrayUi tray = trayReference.get();
            if (tray != null)
                tray.statusChanged(snapshot);
        });
        TrayUi tray = new TrayUi(loop, loop::requestExit);
        AtomicReference<Exception> installFailure = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            try {
                tray.install();
            } catch (Exception failure) {
                installFailure.set(failure);
            }
        });
        if (installFailure.get() != null) {
            Log.failure("icona del tray non installata", installFailure.get());
            showMessage("meet-recorder non puo' partire: icona del tray non installata (" + installFailure.get() + ").");
            return 1;
        }
        trayReference.set(tray);

        Thread loopThread = new Thread(loop, "meet-recorder-loop");
        Runtime.getRuntime().addShutdownHook(new Thread(loop::finishFromShutdownHook, "meet-recorder-shutdown"));
        loopThread.start();
        loopThread.join();

        EventQueue.invokeAndWait(tray::remove);
        Log.info("meet-recorder chiuso");
        return 0;
    }

    private static void redirectOutputToLog(Path logFile) throws IOException {
        if (Files.exists(logFile) && Files.size(logFile) > MAX_LOG_BYTES)
            Files.move(logFile, logFile.resolveSibling(PREVIOUS_LOG_FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
        PrintStream log = new PrintStream(new FileOutputStream(logFile.toFile(), true), true, StandardCharsets.UTF_8);
        System.setOut(log);
        System.setErr(log);
    }

    private static void showMessage(String message) {
        try {
            JOptionPane.showMessageDialog(null, message, "meet-recorder", JOptionPane.INFORMATION_MESSAGE);
        } catch (RuntimeException headless) {
            Log.info("messaggio non mostrato: " + headless);
        }
    }

    //</editor-fold>

    //<editor-fold desc="Modo console">

    /**
     * Lo stesso ciclo dell'app del tray, in console: le righe del meet e lo stato si stampano, e
     * Invio, Ctrl+C o --seconds fermano il registratore. Serve a provarlo e a vedere cosa legge.
     */
    private static int runConsole(Options options) throws IOException {
        if (!acquireInstanceLock(RecorderConfig.applicationDirectory())) {
            Log.info("un altro meet-recorder e' gia' attivo (app del tray o console): fermalo prima");
            return 1;
        }
        Path outputDirectory = meetDirectory(options);
        Log.info("meet-recorder  --  Java " + Runtime.version() + ", uscita in " + outputDirectory
                + ", un giro ogni " + options.pollIntervalMillis() + " ms"
                + (options.seconds() > 0 ? ", per " + options.seconds() + " s" : "")
                + "  --  Invio o Ctrl+C per fermare");

        AtomicReference<RecordingLoop.Status> lastStatus = new AtomicReference<>();
        RecordingLoop loop = new RecordingLoop(outputDirectory, options.pollIntervalMillis(), true, snapshot -> {
            if (lastStatus.getAndSet(snapshot.status()) != snapshot.status())
                Log.info("stato del registratore: " + snapshot.describe());
        });
        Runtime.getRuntime().addShutdownHook(new Thread(loop::finishFromShutdownHook, "meet-recorder-shutdown"));
        startConsoleStopListener(loop);
        if (options.seconds() > 0)
            startStopTimer(loop, options.seconds());
        loop.run();
        return 0;
    }

    /**
     * Invio nella console ferma il registratore. Uno stdin chiuso (lancio senza console) non lo
     * ferma: fine dello stream non e' una richiesta di stop.
     */
    private static void startConsoleStopListener(RecordingLoop loop) {
        Thread listener = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                if (input.readLine() != null)
                    loop.requestExit();
            } catch (IOException ignored) {
                return;
            }
        }, "meet-recorder-stdin");
        listener.setDaemon(true);
        listener.start();
    }

    private static void startStopTimer(RecordingLoop loop, long seconds) {
        Thread timer = new Thread(() -> {
            try {
                Thread.sleep(seconds * 1000);
                loop.requestExit();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "meet-recorder-timer");
        timer.setDaemon(true);
        timer.start();
    }

    //</editor-fold>

    //<editor-fold desc="Configurazione, istanza e argomenti" defaultstate="collapsed">

    private static int setMeetDirectory(Path meetDirectory) throws IOException {
        Path absolute = meetDirectory.toAbsolutePath().normalize();
        Files.createDirectories(absolute);
        RecorderConfig.writeMeetDirectory(absolute);
        System.out.println("cartella dei meet: " + absolute + " (in " + RecorderConfig.configFile() + ")");
        return 0;
    }

    private static Path meetDirectory(Options options) throws IOException {
        Path outputDirectory = options.outputDirectory() != null
                ? options.outputDirectory()
                : RecorderConfig.loadMeetDirectory();
        Files.createDirectories(outputDirectory);
        Log.info("cartella dei meet: " + outputDirectory);
        return outputDirectory;
    }

    /**
     * Il lucchetto resta preso finche' il processo vive: il canale sta in un campo statico, perche'
     * un canale non piu' raggiungibile si chiude da solo e libera il lucchetto.
     */
    private static boolean acquireInstanceLock(Path applicationDirectory) throws IOException {
        Files.createDirectories(applicationDirectory);
        FileChannel channel = FileChannel.open(applicationDirectory.resolve(LOCK_FILE_NAME), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock();
        if (lock == null) {
            channel.close();
            return false;
        }
        instanceLockChannel = channel;
        return true;
    }

    /**
     * Il controllo che meet-recorder.bat installa fa prima di ricopiare i sorgenti: un recorder
     * acceso tiene il lucchetto dell'istanza. Il lucchetto si prova e si rilascia subito, senza
     * tenerlo; senza file del lucchetto nessun recorder e' mai partito, e il file non si crea.
     */
    private static int checkRunning() throws IOException {
        Path lockFile = RecorderConfig.applicationDirectory().resolve(LOCK_FILE_NAME);
        if (Files.exists(lockFile)) {
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    System.out.println("meet-recorder acceso");
                    return EXIT_RECORDER_RUNNING;
                }
                lock.release();
            }
        }
        System.out.println("meet-recorder spento");
        return 0;
    }

    private static Options parseOptions(String[] args) {
        Mode mode = Mode.TRAY;
        Path outputDirectory = null;
        Path argumentPath = null;
        long seconds = 0;
        long pollIntervalMillis = RecordingLoop.DEFAULT_POLL_INTERVAL_MILLIS;

        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            switch (argument) {
                case "--console" -> mode = Mode.CONSOLE;
                case "--replay" -> {
                    mode = Mode.REPLAY;
                    argumentPath = Path.of(valueAfter(args, index++));
                }
                case "--imposta-cartella" -> {
                    mode = Mode.SET_MEET_DIRECTORY;
                    argumentPath = Path.of(valueAfter(args, index++));
                }
                case "--controlla-acceso" -> mode = Mode.CHECK_RUNNING;
                case "--out" -> outputDirectory = Path.of(valueAfter(args, index++));
                case "--seconds" -> seconds = parsePositive(valueAfter(args, index++), argument);
                case "--interval-ms" -> pollIntervalMillis = parsePositive(valueAfter(args, index++), argument);
                default -> throw new IllegalArgumentException("argomento sconosciuto: " + argument);
            }
        }

        if (pollIntervalMillis < RecordingLoop.MIN_POLL_INTERVAL_MILLIS
                || pollIntervalMillis > RecordingLoop.MAX_POLL_INTERVAL_MILLIS)
            throw new IllegalArgumentException("--interval-ms fra " + RecordingLoop.MIN_POLL_INTERVAL_MILLIS + " e "
                    + RecordingLoop.MAX_POLL_INTERVAL_MILLIS);
        if (seconds > 0 && mode != Mode.CONSOLE)
            throw new IllegalArgumentException("--seconds vale solo col modo --console");
        if (mode == Mode.REPLAY && outputDirectory == null && argumentPath.toAbsolutePath().getParent() != null)
            outputDirectory = argumentPath.toAbsolutePath().getParent();
        if (outputDirectory != null)
            outputDirectory = outputDirectory.toAbsolutePath().normalize();
        return new Options(mode, outputDirectory, argumentPath, seconds, pollIntervalMillis);
    }

    private static String valueAfter(String[] args, int index) {
        if (index + 1 >= args.length)
            throw new IllegalArgumentException("manca il valore dopo " + args[index]);
        return args[index + 1];
    }

    private static long parsePositive(String value, String argument) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0)
                throw new IllegalArgumentException(argument + " vuole un numero positivo: " + value);
            return parsed;
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(argument + " vuole un numero: " + value);
        }
    }

    //</editor-fold>
}

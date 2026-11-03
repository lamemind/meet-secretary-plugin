import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Le righe di log del registratore, con l'ora davanti. Vanno su System.out: nel modo console e' la
 * console, nell'app del tray e' il file di log, perche' MeetRecorder ridirige System.out li'.
 *
 * La prima riga di ogni giorno porta anche la data: l'app del tray resta accesa da un giorno
 * all'altro, col PC in sospensione la notte, e un'ora sola non dice di quale giorno e' la riga.
 *
 * Il log non porta il testo dei meet: le frasi stanno solo nel file del meet.
 */
final class Log {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static LocalDate lastLoggedDay;

    private Log() {
    }

    static synchronized void info(String message) {
        LocalDateTime now = LocalDateTime.now();
        boolean firstLineOfTheDay = !now.toLocalDate().equals(lastLoggedDay);
        lastLoggedDay = now.toLocalDate();
        System.out.println(now.format(firstLineOfTheDay ? DAY_FORMAT : TIME_FORMAT) + "  " + message);
    }

    /**
     * Una riga con la data intera, per l'avvio, anche quando non e' la prima del giorno: nel file di
     * log dell'app del tray separa un avvio dal precedente.
     */
    static synchronized void banner(String message) {
        LocalDateTime now = LocalDateTime.now();
        lastLoggedDay = now.toLocalDate();
        System.out.println(now.format(DAY_FORMAT) + "  " + message);
    }

    static synchronized void failure(String message, Throwable failure) {
        info(message + ": " + failure);
        failure.printStackTrace(System.out);
    }
}

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * La configurazione del registratore, in %LOCALAPPDATA%\meet-recorder\config.properties.
 *
 * Il formato e' una chiave per riga, chiave=valore, righe # di commento, senza escape: un path
 * Windows si scrive coi suoi backslash, e uno script bash che vuole sapere dove stanno i meet lo
 * legge cosi' com'e'. Per questo il file non passa da java.util.Properties, che dei backslash fa
 * escape.
 *
 * Chiavi:
 *   cartella   dove il registratore scrive i file dei meet, path assoluto. Default
 *              %USERPROFILE%\meet-teams: nel profilo utente e non in Documenti, che una policy
 *              dell'IT puo' ridirigere su OneDrive senza avviso.
 */
final class RecorderConfig {

    static final String MEET_DIRECTORY_KEY = "cartella";
    static final String APPLICATION_FOLDER = "meet-recorder";
    static final String CONFIG_FILE_NAME = "config.properties";
    static final String DEFAULT_MEET_FOLDER = "meet-teams";

    private static final List<String> HEADER = List.of(
            "# meet-recorder: configurazione del registratore dei meet Teams.",
            "# Una chiave per riga, chiave=valore, senza escape: i backslash dei path restano come sono.",
            "# cartella: dove il registratore scrive i file dei meet, path assoluto, fuori da ogni cartella sincronizzata.");

    private RecorderConfig() {
    }

    /**
     * %LOCALAPPDATA%\meet-recorder: configurazione, log e lucchetto dell'istanza. Mai rediretta su
     * OneDrive e mai sincronizzata.
     */
    static Path applicationDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Local")
                : Path.of(localAppData);
        return base.resolve(APPLICATION_FOLDER);
    }

    static Path configFile() {
        return applicationDirectory().resolve(CONFIG_FILE_NAME);
    }

    static Path defaultMeetDirectory() {
        String profile = System.getenv("USERPROFILE");
        Path base = profile == null || profile.isBlank() ? Path.of(System.getProperty("user.home")) : Path.of(profile);
        return base.resolve(DEFAULT_MEET_FOLDER);
    }

    /**
     * La cartella dei meet. Senza file di configurazione lo scrive col default; con la chiave assente
     * o non assoluta usa il default e lo dice nel log, senza riscrivere il file di chi l'ha scritto.
     */
    static Path loadMeetDirectory() throws IOException {
        Path file = configFile();
        if (!Files.exists(file)) {
            Path defaultDirectory = defaultMeetDirectory();
            writeMeetDirectory(defaultDirectory);
            Log.info("configurazione creata: " + file + ", cartella dei meet " + defaultDirectory);
            return defaultDirectory;
        }
        String value = read(file).get(MEET_DIRECTORY_KEY);
        if (value == null || value.isBlank()) {
            Log.info("configurazione senza la chiave " + MEET_DIRECTORY_KEY + " (" + file + "): uso "
                    + defaultMeetDirectory());
            return defaultMeetDirectory();
        }
        Path configured = Path.of(value);
        if (!configured.isAbsolute()) {
            Log.info("cartella dei meet non assoluta in " + file + " (" + value + "): uso " + defaultMeetDirectory());
            return defaultMeetDirectory();
        }
        return configured.normalize();
    }

    /**
     * Scrive la cartella dei meet, tenendo le altre chiavi del file se c'e' gia'.
     */
    static void writeMeetDirectory(Path meetDirectory) throws IOException {
        Path file = configFile();
        Map<String, String> values = Files.exists(file) ? read(file) : new LinkedHashMap<>();
        values.put(MEET_DIRECTORY_KEY, meetDirectory.toAbsolutePath().normalize().toString());
        StringBuilder content = new StringBuilder();
        for (String line : HEADER)
            content.append(line).append('\n');
        values.forEach((key, value) -> content.append(key).append('=').append(value).append('\n'));
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    //<editor-fold desc="Lettura del file" defaultstate="collapsed">

    private static Map<String, String> read(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = rawLine.strip();
            if (line.startsWith("﻿"))
                line = line.substring(1).strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!"))
                continue;
            int separator = line.indexOf('=');
            if (separator <= 0)
                continue;
            values.put(line.substring(0, separator).strip(), line.substring(separator + 1).strip());
        }
        return values;
    }

    //</editor-fold>
}

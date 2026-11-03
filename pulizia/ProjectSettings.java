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
 * Il file di settings del segretario per un progetto: `.meet-secretary/settings.ini` nella cartella
 * del progetto, cioe' la cartella di lavoro della pulizia, o quella di `--progetto`.
 *
 * Il file e' a sezioni, `[nome]` su una riga, una voce per riga; le righe vuote e quelle che
 * cominciano con # non contano, come le righe prima della prima sezione e quelle di una sezione
 * sconosciuta.
 *
 *   [temi]        un tema per riga, `nome` oppure `nome: keyword, keyword`. Ogni tema e' attivo
 *   [parole]      una parola chiave per riga
 *   [glossario]   una voce di glossario per riga, per esempio `iuat → UAT`
 *   [segretario]  `nome: <regex del nome del segretario>`, `segretario: <il nome come lo dice l'utente>`
 *   [segui]       `timeout: <secondi>`, `batch: <secondi>`, per meet-follow
 *
 * Il file e' personale: lo crea lo script meet-settings, che lo tiene fuori da git. Senza file ogni
 * sezione e' vuota, nome del segretario compreso.
 *
 * Il file si legge come byte e si decodifica in UTF-8 con sostituzione: un file salvato in un'altra
 * codifica da' qualche carattere sbagliato, non un errore che ferma la pulizia.
 */
final class ProjectSettings {

    static final String DIRECTORY = ".meet-secretary";
    static final String FILE_NAME = "settings.ini";

    static final String THEMES = "temi";
    static final String KEYWORDS = "parole";
    static final String GLOSSARY = "glossario";
    static final String SECRETARY = "segretario";

    private static final Pattern SECTION_HEADER = Pattern.compile("^\\[\\s*([^\\]]+?)\\s*\\]$");
    private static final char BYTE_ORDER_MARK = '﻿';

    private final Map<String, List<String>> sections;

    private ProjectSettings(Map<String, List<String>> sections) {
        this.sections = sections;
    }

    static Path file(Path projectDirectory) {
        return projectDirectory.resolve(DIRECTORY).resolve(FILE_NAME);
    }

    static ProjectSettings empty() {
        return new ProjectSettings(Map.of());
    }

    /**
     * Le voci del file, sezione per sezione; un file assente da' le sezioni vuote.
     */
    static ProjectSettings read(Path file) throws IOException {
        if (!Files.isRegularFile(file))
            return empty();
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Map<String, List<String>> sections = new LinkedHashMap<>();
        List<String> current = null;
        for (String rawLine : content.lines().toList()) {
            String line = rawLine.strip();
            if (!line.isEmpty() && line.charAt(0) == BYTE_ORDER_MARK)
                line = line.substring(1).strip();
            if (line.isEmpty() || line.startsWith("#"))
                continue;
            Matcher header = SECTION_HEADER.matcher(line);
            if (header.matches()) {
                current = sections.computeIfAbsent(header.group(1).toLowerCase(Locale.ROOT), name -> new ArrayList<>());
                continue;
            }
            if (current != null)
                current.add(line);
        }
        sections.replaceAll((name, lines) -> List.copyOf(lines));
        return new ProjectSettings(Map.copyOf(sections));
    }

    /**
     * Le voci di una sezione, in ordine; vuota se la sezione non c'e'.
     */
    List<String> lines(String section) {
        return sections.getOrDefault(section, List.of());
    }

    /**
     * Il valore della prima voce `chiave: valore` della sezione, senza spazi ai lati; null se la
     * chiave non c'e', vuoto se c'e' senza valore.
     */
    String value(String section, String key) {
        for (String line : lines(section)) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).strip().toLowerCase(Locale.ROOT).equals(key))
                return line.substring(colon + 1).strip();
        }
        return null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProjectSettings settings && settings.sections.equals(sections);
    }

    @Override
    public int hashCode() {
        return sections.hashCode();
    }
}

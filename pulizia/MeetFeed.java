import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Le righe del file del meet, ognuna con l'ora in cui e' diventata disponibile (millisecondi del
 * giorno). Una riga e' `HH:mm:ss<TAB>voce<TAB>testo`, piu' una quarta colonna, il numero della riga
 * che rivede, quando Teams ha riscritto una frase gia' scritta.
 *
 * Due sorgenti. Dal vivo il file cresce: ogni refresh legge i byte nuovi, e una riga e' disponibile
 * dall'ora in cui la si legge; il meet e' finito quando il diario del lettore scrive la riga `fine`.
 * Registrato, il file e' gia' completo: una riga e' disponibile dall'ora in cui il diario dice che il
 * lettore l'ha scritta, e refresh rilascia le righe fino all'ora data.
 */
final class MeetFeed {

    private static final Pattern DIARY_LINE_NUMBER = Pattern.compile("^n=(\\d+)\\b");

    private final Path meetFile;
    private final Path diaryFile;
    private final boolean recorded;
    private final List<Rephraser.Line> lines = new ArrayList<>();
    private final List<Long> availableMillis = new ArrayList<>();

    private long meetOffset;
    private long diaryOffset;
    private final ByteArrayOutputStream meetPartial = new ByteArrayOutputStream();
    private final ByteArrayOutputStream diaryPartial = new ByteArrayOutputStream();
    private boolean readerFinished;
    private boolean readerFinishedAndDrained;

    private List<Rephraser.Line> recordedLines = List.of();
    private long[] recordedAvailableMillis = new long[0];

    private MeetFeed(Path meetFile, Path diaryFile, boolean recorded) {
        this.meetFile = meetFile;
        this.diaryFile = diaryFile;
        this.recorded = recorded;
    }

    static MeetFeed live(Path meetFile, Path diaryFile) {
        return new MeetFeed(meetFile, diaryFile, false);
    }

    static MeetFeed recorded(Path meetFile, Path diaryFile) throws IOException {
        MeetFeed feed = new MeetFeed(meetFile, diaryFile, true);
        feed.readRecorded();
        return feed;
    }

    //<editor-fold desc="Lettura">

    synchronized void refresh(long nowMillis) throws IOException {
        if (recorded) {
            while (lines.size() < recordedLines.size() && recordedAvailableMillis[lines.size()] <= nowMillis) {
                availableMillis.add(recordedAvailableMillis[lines.size()]);
                lines.add(recordedLines.get(lines.size()));
            }
            return;
        }
        boolean finishedBeforeRead = readerFinished;
        if (!readerFinished && Files.exists(diaryFile))
            for (String diaryLine : readNewLines(diaryFile, true)) {
                String[] fields = diaryLine.split("\t", 3);
                if (fields.length >= 2 && fields[1].equals("fine"))
                    readerFinished = true;
            }
        if (Files.exists(meetFile))
            for (String meetLine : readNewLines(meetFile, false)) {
                lines.add(parse(meetLine, lines.size() + 1));
                availableMillis.add(nowMillis);
            }
        if (readerFinished && !finishedBeforeRead)
            readerFinishedAndDrained = true;
    }

    /**
     * Le righe complete scritte dopo l'ultima lettura; una riga senza a capo resta in attesa.
     */
    private List<String> readNewLines(Path file, boolean diary) throws IOException {
        long offset = diary ? diaryOffset : meetOffset;
        ByteArrayOutputStream partial = diary ? diaryPartial : meetPartial;
        List<String> complete = new ArrayList<>();
        try (RandomAccessFile reader = new RandomAccessFile(file.toFile(), "r")) {
            long length = reader.length();
            if (length <= offset)
                return complete;
            byte[] bytes = new byte[(int) Math.min(length - offset, 1 << 20)];
            reader.seek(offset);
            reader.readFully(bytes);
            offset += bytes.length;
            int lineStart = 0;
            for (int index = 0; index < bytes.length; index++) {
                if (bytes[index] != '\n')
                    continue;
                partial.write(bytes, lineStart, index - lineStart);
                complete.add(partial.toString(StandardCharsets.UTF_8).replace("\r", ""));
                partial.reset();
                lineStart = index + 1;
            }
            partial.write(bytes, lineStart, bytes.length - lineStart);
        }
        if (diary)
            diaryOffset = offset;
        else
            meetOffset = offset;
        return complete;
    }

    private void readRecorded() throws IOException {
        List<String> meetLines = Files.readAllLines(meetFile, StandardCharsets.UTF_8);
        List<Rephraser.Line> parsed = new ArrayList<>();
        for (String meetLine : meetLines)
            parsed.add(parse(meetLine, parsed.size() + 1));
        recordedLines = List.copyOf(parsed);
        recordedAvailableMillis = new long[parsed.size()];
        for (int index = 0; index < parsed.size(); index++)
            recordedAvailableMillis[index] = millisOfDay(parsed.get(index).time());
        if (Files.exists(diaryFile)) {
            for (String diaryLine : Files.readAllLines(diaryFile, StandardCharsets.UTF_8)) {
                String[] fields = diaryLine.split("\t", 3);
                if (fields.length < 3 || !fields[1].equals("riga"))
                    continue;
                Matcher number = DIARY_LINE_NUMBER.matcher(fields[2]);
                if (!number.find())
                    continue;
                int lineNumber = Integer.parseInt(number.group(1));
                if (lineNumber >= 1 && lineNumber <= parsed.size())
                    recordedAvailableMillis[lineNumber - 1] = millisOfDay(fields[0]);
            }
        }
        for (int index = 1; index < recordedAvailableMillis.length; index++)
            recordedAvailableMillis[index] = Math.max(recordedAvailableMillis[index], recordedAvailableMillis[index - 1]);
    }

    /**
     * Il numero di una riga e' il suo numero nel file, perche' eventi, tratti e segnalibri lo citano:
     * una riga malformata resta, con voce `?` e il testo intero.
     */
    private static Rephraser.Line parse(String meetLine, int number) {
        String[] fields = meetLine.split("\t", 4);
        if (fields.length < 3 || !fields[0].matches("\\d\\d:\\d\\d:\\d\\d"))
            return new Rephraser.Line(number, "00:00:00", "?", meetLine.replace('\t', ' ').strip(), 0);
        int revises = 0;
        if (fields.length == 4 && fields[3].strip().matches("\\d+"))
            revises = Integer.parseInt(fields[3].strip());
        String text = fields.length == 4 && revises == 0 ? fields[2] + " " + fields[3] : fields[2];
        return new Rephraser.Line(number, fields[0], fields[1], text.strip(), revises);
    }

    //</editor-fold>

    //<editor-fold desc="Accesso">

    synchronized int size() {
        return lines.size();
    }

    synchronized Rephraser.Line line(int number) {
        return lines.get(number - 1);
    }

    synchronized List<Rephraser.Line> range(int from, int to) {
        return List.copyOf(lines.subList(from - 1, to));
    }

    synchronized long availableAt(int number) {
        return availableMillis.get(number - 1);
    }

    synchronized int totalRecorded() {
        return recorded ? recordedLines.size() : lines.size();
    }

    /**
     * Nessuna riga arrivera' piu': registrato, tutte rilasciate; dal vivo, il diario ha scritto
     * `fine` e il file del meet e' stato letto dopo.
     */
    synchronized boolean finished() {
        return recorded ? lines.size() == recordedLines.size() : readerFinishedAndDrained;
    }

    /**
     * L'ora della prossima riga registrata non ancora rilasciata; dal vivo non si sa.
     */
    synchronized long nextArrivalMillis() {
        if (recorded && lines.size() < recordedLines.size())
            return recordedAvailableMillis[lines.size()];
        return Long.MAX_VALUE;
    }

    //</editor-fold>

    static long millisOfDay(String time) {
        return LocalTime.parse(time).toNanoOfDay() / 1_000_000;
    }
}

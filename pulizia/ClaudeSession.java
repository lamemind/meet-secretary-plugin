import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Un `claude -p` persistente: un solo processo per molti turni, con memoria. Legge da stdin un
 * messaggio utente per riga (stream-json) e risponde a ognuno con un evento `result` su stdout.
 *
 * Gira con cwd sul progetto, quindi carica il suo CLAUDE.md; senza hook (`disableAllHooks` nel file
 * di settings, che spegne anche quelli dei plugin), senza server MCP, coi soli tool di lettura, e
 * con le istruzioni del meet in coda al system prompt. `--permission-prompts none` nega da se' cio'
 * che chiederebbe un permesso: in stream-json la domanda arriverebbe a questo script, che non
 * risponde, e il turno resterebbe fermo.
 *
 * Un messaggio alla volta: un messaggio mandato mentre un turno e' in corso si fonde nel turno dopo.
 */
final class ClaudeSession implements AutoCloseable {

    private static final long TURN_TIMEOUT_SECONDS = 180;
    private static final Object END_OF_STREAM = new Object();

    record Settings(String executable, String model, String effort, Path settingsFile, String tools) {
    }

    /**
     * Le misure di un turno. costUsd e' la differenza del `total_cost_usd` cumulativo della
     * sessione; contextTokens e' l'ingresso dell'ultima chiamata API del turno (non in cache, letto
     * e scritto in cache), cioe' quanto contesto il processo porta in quel momento.
     */
    record Turn(String text, String error, long millis, long durationMillis, long apiMillis, double costUsd,
                long inputTokens, long cacheWriteTokens, long cacheReadTokens, long outputTokens,
                long thinkingTokens, long contextTokens, int apiCalls, int toolCalls) {

        boolean failed() {
            return error != null;
        }

        static Turn failed(String error, long millis) {
            return new Turn("", error, millis, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private final Process process;
    private final OutputStream input;
    private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
    private final StringBuilder errorText = new StringBuilder();
    private double lastTotalCostUsd;
    private int turns;

    private ClaudeSession(Process process) {
        this.process = process;
        this.input = process.getOutputStream();
    }

    //<editor-fold desc="Avvio e chiusura">

    static ClaudeSession start(Settings settings, Path workingDirectory, Path appendedSystemPrompt)
            throws IOException {
        List<String> command = new ArrayList<>(List.of(settings.executable(), "-p",
                "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                "--settings", settings.settingsFile().toString(),
                "--strict-mcp-config",
                "--tools", settings.tools(), "--allowedTools", settings.tools(),
                "--permission-prompts", "none",
                "--disable-slash-commands",
                "--no-session-persistence",
                "--append-system-prompt-file", appendedSystemPrompt.toString(),
                "--model", settings.model()));
        if (settings.effort() != null && !settings.effort().isBlank())
            command.addAll(List.of("--effort", settings.effort()));
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        Map<String, String> environment = builder.environment();
        environment.remove("LOOM_TASK");
        environment.remove("PROJECT_ROOT");
        environment.remove("PTYXIS_PROFILE");
        ClaudeSession session = new ClaudeSession(builder.start());
        session.startReaders();
        return session;
    }

    private void startReaders() {
        Thread.ofPlatform().daemon().name("claude-stdout").start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null)
                    events.add(line);
            } catch (IOException ignored) {
            }
            events.add(END_OF_STREAM);
        });
        Thread.ofPlatform().daemon().name("claude-stderr").start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (errorText) {
                        if (errorText.length() < 4000)
                            errorText.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
            }
        });
    }

    boolean alive() {
        return process.isAlive();
    }

    int turns() {
        return turns;
    }

    /**
     * Chiude stdin, cosi' claude finisce da se'; dopo qualche secondo lo uccide.
     */
    @Override
    public void close() {
        try {
            input.close();
        } catch (IOException ignored) {
        }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS))
                destroyTree();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            destroyTree();
        }
    }

    void destroyTree() {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    //</editor-fold>

    //<editor-fold desc="Turno">

    /**
     * Manda un messaggio e aspetta il suo `result`. Un processo morto, un turno oltre il timeout o un
     * `result` in errore danno un Turn fallito: il processo non e' piu' affidabile, e chi chiama lo
     * riavvia.
     */
    Turn send(String userMessage) {
        long startedNanos = System.nanoTime();
        String line = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":" + Json.quote(userMessage) + "}}\n";
        try {
            input.write(line.getBytes(StandardCharsets.UTF_8));
            input.flush();
        } catch (IOException failure) {
            return Turn.failed("scrittura verso claude: " + failure.getMessage() + stderrTail(), elapsed(startedNanos));
        }
        turns++;

        int apiCalls = 0;
        int toolCalls = 0;
        Map<String, Object> lastApiUsage = Map.of();
        String lastMessageId = null;
        long deadlineNanos = startedNanos + TimeUnit.SECONDS.toNanos(TURN_TIMEOUT_SECONDS);
        while (true) {
            Object event;
            try {
                event = events.poll(Math.max(1, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Turn.failed("interrotto", elapsed(startedNanos));
            }
            if (event == null)
                return Turn.failed("nessun result entro " + TURN_TIMEOUT_SECONDS + " s", elapsed(startedNanos));
            if (event == END_OF_STREAM)
                return Turn.failed("claude e' uscito" + stderrTail(), elapsed(startedNanos));

            Map<String, Object> object;
            try {
                object = Json.object(Json.parse(((String) event).strip()), "evento");
            } catch (IllegalArgumentException unreadable) {
                continue;
            }
            String type = Json.string(object, "type");
            if ("assistant".equals(type)) {
                Map<String, Object> message = Json.objectOrEmpty(object, "message");
                String messageId = Json.string(message, "id");
                if (messageId == null || !messageId.equals(lastMessageId)) {
                    apiCalls++;
                    lastMessageId = messageId;
                }
                lastApiUsage = Json.objectOrEmpty(message, "usage");
                for (Object block : Json.arrayOrEmpty(message, "content"))
                    if (block instanceof Map<?, ?> content && "tool_use".equals(content.get("type")))
                        toolCalls++;
            } else if ("result".equals(type)) {
                return turn(object, lastApiUsage, apiCalls, toolCalls, elapsed(startedNanos));
            }
        }
    }

    private Turn turn(Map<String, Object> result, Map<String, Object> lastApiUsage, int apiCalls, int toolCalls,
                      long millis) {
        Map<String, Object> usage = Json.objectOrEmpty(result, "usage");
        double totalCost = Json.decimal(result, "total_cost_usd");
        double cost = totalCost - lastTotalCostUsd;
        lastTotalCostUsd = totalCost;
        long contextTokens = Json.number(lastApiUsage, "input_tokens")
                + Json.number(lastApiUsage, "cache_read_input_tokens")
                + Json.number(lastApiUsage, "cache_creation_input_tokens");
        String text = Json.string(result, "result");
        String error = null;
        if (Json.bool(result, "is_error") || !"success".equals(Json.string(result, "subtype")) || text == null)
            error = "result in errore: " + Json.string(result, "subtype") + " "
                    + Rephraser.abbreviate(String.valueOf(text).strip());
        return new Turn(text == null ? "" : text, error, millis,
                Json.number(result, "duration_ms"), Json.number(result, "duration_api_ms"), cost,
                Json.number(usage, "input_tokens"), Json.number(usage, "cache_creation_input_tokens"),
                Json.number(usage, "cache_read_input_tokens"), Json.number(usage, "output_tokens"),
                Json.number(Json.objectOrEmpty(usage, "output_tokens_details"), "thinking_tokens"),
                contextTokens, apiCalls, toolCalls);
    }

    //</editor-fold>

    //<editor-fold desc="Utilita'" defaultstate="collapsed">

    private String stderrTail() {
        synchronized (errorText) {
            String text = errorText.toString().strip();
            return text.isEmpty() ? "" : ": " + Rephraser.abbreviate(text.lines().reduce((first, second) -> second).orElse(""));
        }
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    //</editor-fold>
}

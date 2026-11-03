import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Decide quando una voce dei sottotitoli e' una frase chiusa, e la consegna una volta sola. Non sa
 * niente di UI Automation: riceve a ogni giro la fotografia delle voci del contenitore, nell'ordine
 * in cui Teams le mostra, e l'istante del giro. Lo stesso codice gira dal vivo e in replay sui
 * registri del probe.
 *
 * Il comportamento di Teams su cui poggia (referto del probe, T18 DLV1):
 * - la voce corrente tiene lo stesso RuntimeId mentre Teams ne riscrive il testo;
 * - il testo finale finisce con . ? ! e dopo il punto la voce non cambia piu';
 * - una voce puo' restare senza punteggiatura finale quando dopo di lei parte un'altra voce;
 * - il contenitore tiene solo le ultime 20-23 voci e toglie le vecchie anche senza voci nuove;
 * - staccando i sottotitoli in una finestra loro, Teams ridisegna tutta la storia con RuntimeId nuovi.
 *
 * Non in ogni meet il punto e' finale: in un meet a tre voci del 27/10 Teams ha messo il punto anche
 * sul testo provvisorio, e ha riscritto la frase allungandola ogni 250-500 ms, ogni volta col punto.
 * Chiusa al primo punto, la frase tornava come revisione a ogni riscrittura: 138 revisioni su 185
 * righe in 90 s. Il 96% delle riscritture e' arrivato entro 2 s dalla precedente: il punto chiude
 * quindi solo una voce ferma da PUNCTUATION_SETTLE_MILLIS.
 *
 * Le regole di chiusura, nell'ordine:
 * - punteggiatura finale: il testo finisce con . ? ! o con i puntini, e la voce non cambia da
 *   PUNCTUATION_SETTLE_MILLIS;
 * - successiva: dopo la voce c'e' una voce con testo, e la voce non cambia da SUCCESSOR_SETTLE_MILLIS;
 * - ferma: la voce non cambia da STALE_MILLIS, anche senza successiva;
 * - sparita: la voce manca dal contenitore da ORPHAN_GRACE_MILLIS senza che arrivi un contenitore
 *   nuovo. Copre la fine del meet e il contenitore chiuso, dove l'ultima voce resta provvisoria.
 *
 * Un contenitore nuovo apre un'epoca. Le voci aperte dell'epoca vecchia si scartano, perche' il
 * contenitore nuovo le ridisegna; le voci ridisegnate che coincidono per nome e testo con righe gia'
 * consegnate si consumano senza riconsegnarle, entro REDRAW_WINDOW_MILLIS dall'apertura.
 *
 * Teams ridisegna la storia anche dentro lo stesso contenitore: dopo il distacco dei sottotitoli,
 * nel meet dal vivo di T18 DLV3, la finestra staccata ha ridisegnato le voci altre due volte, con
 * RuntimeId nuovi, 21 e 29 s dopo l'apertura dell'epoca. Un giro in cui compaiono almeno
 * REDRAW_BATCH_MIN_VOICES voci nuove gia' chiuse e' un ridisegno: nel parlato una voce nasce
 * provvisoria, e due voci nuove chiuse nello stesso giro di 250 ms non si sono mai viste. Le voci di
 * quel giro che coincidono con righe consegnate si consumano; le altre, frasi vecchie che il
 * lettore non aveva visto, si consegnano. La finestra dura il solo giro del ridisegno, perche' una
 * risposta breve detta di nuovo subito dopo («Si'.») non si perda.
 *
 * Una voce consegnata che poi cambia testo e' un'anomalia: si riapre, e alla nuova chiusura si
 * consegna di nuovo, segnata come revisione col numero della riga che sostituisce. Meglio una frase
 * ripetuta che una persa. Una voce consumata da un ridisegno sostituisce la riga con cui coincideva.
 *
 * Un tracker vale per un meet: il numero di una riga consegnata e' il suo numero di riga nel file
 * del meet, da 1.
 */
final class CaptionTracker {

    static final long PUNCTUATION_SETTLE_MILLIS = 2_000;
    static final long SUCCESSOR_SETTLE_MILLIS = 2_000;
    static final long STALE_MILLIS = 6_000;
    static final long ORPHAN_GRACE_MILLIS = 3_000;
    static final long REDRAW_WINDOW_MILLIS = 5_000;
    static final int REDRAW_BATCH_MIN_VOICES = 3;
    static final int RECENT_LINES_KEPT = 200;

    private static final char KEY_SEPARATOR = '\u001f';

    enum CloseReason {
        PUNCTUATION("punto"),
        SUCCESSOR("successiva"),
        STALE("ferma"),
        VANISHED("sparita"),
        SHUTDOWN("chiusura");

        final String label;

        CloseReason(String label) {
            this.label = label;
        }
    }

    /**
     * Una voce come la vede un giro: identita' nel contenitore, nome di chi parla, testo.
     */
    record Voice(String runtimeId, String speaker, String text) {
    }

    /**
     * Una frase chiusa. revisedLine e' il numero della riga che la frase sostituisce, 0 se la frase e'
     * nuova.
     */
    record ClosedLine(int number, long firstSeenMillis, long lastChangeMillis, long closedMillis, String speaker,
                      String text, CloseReason reason, int revisedLine) {

        boolean revision() {
            return revisedLine > 0;
        }
    }

    /**
     * Una riga consegnata di recente: la chiave nome+testo con cui un ridisegno la riconosce, e il
     * suo numero.
     */
    private record DeliveredLine(String key, int number) {
    }

    interface Listener {

        void lineClosed(ClosedLine line);

        void trackerEvent(long nowMillis, String kind, String detail);
    }

    private static final class TrackedVoice {

        final String runtimeId;
        final long firstSeenMillis;
        final boolean bornDuringRedraw;
        String speaker;
        String text;
        long lastChangeMillis;
        long missingSinceMillis = -1;
        boolean delivered;
        boolean deliveredBefore;
        int deliveredLineNumber;

        TrackedVoice(Voice voice, long nowMillis, boolean bornDuringRedraw) {
            this.runtimeId = voice.runtimeId();
            this.firstSeenMillis = nowMillis;
            this.bornDuringRedraw = bornDuringRedraw;
            this.speaker = voice.speaker();
            this.text = voice.text();
            this.lastChangeMillis = nowMillis;
        }
    }

    private final Listener listener;
    private final Map<String, TrackedVoice> voicesOfEpoch = new LinkedHashMap<>();
    private final ArrayDeque<DeliveredLine> recentDeliveredLines = new ArrayDeque<>();
    private final Map<String, ArrayDeque<Integer>> redrawCandidates = new HashMap<>();
    private String epochKey;
    private long redrawUntilMillis = -1;
    private int deliveredCount;
    private int revisionCount;
    private int redrawnCount;
    private int droppedOpenCount;

    CaptionTracker(Listener listener) {
        this.listener = listener;
    }

    //<editor-fold desc="Un giro">

    /**
     * Un giro con il contenitore letto: epochKey lo identifica, voices sono le voci nell'ordine
     * dell'albero. Le voci senza testo si saltano: Teams mostra per mezzo secondo una voce vuota
     * prima di quella vera.
     */
    void observe(long nowMillis, String observedEpochKey, List<Voice> voices) {
        if (!Objects.equals(observedEpochKey, epochKey))
            startEpoch(nowMillis, observedEpochKey);
        else
            startRedrawIfBatch(nowMillis, voices);

        List<TrackedVoice> ordered = trackVoices(nowMillis, voices);
        closeSettledVoices(nowMillis, ordered);
        closeVanishedVoices(nowMillis, ordered);
    }

    /**
     * Un giro senza contenitore: tutte le voci dell'epoca risultano sparite, e quelle ancora aperte
     * si consegnano se il contenitore non torna entro ORPHAN_GRACE_MILLIS.
     */
    void observeWithoutContainer(long nowMillis) {
        closeVanishedVoices(nowMillis, List.of());
    }

    /**
     * Fine del lettore: le voci ancora aperte si consegnano col testo che hanno.
     */
    void shutdown(long nowMillis) {
        for (TrackedVoice voice : voicesOfEpoch.values())
            if (!voice.delivered)
                deliver(voice, nowMillis, CloseReason.SHUTDOWN);
    }

    //</editor-fold>

    //<editor-fold desc="Epoche e voci ridisegnate">

    private void startEpoch(long nowMillis, String newEpochKey) {
        if (epochKey != null) {
            int openVoices = 0;
            for (TrackedVoice voice : voicesOfEpoch.values())
                if (!voice.delivered)
                    openVoices++;
            droppedOpenCount += openVoices;
            loadRedrawCandidates();
            redrawUntilMillis = nowMillis + REDRAW_WINDOW_MILLIS;
            listener.trackerEvent(nowMillis, "epoca", "contenitore nuovo, voci aperte scartate=" + openVoices
                    + ", righe confrontabili=" + recentDeliveredLines.size());
        }
        voicesOfEpoch.clear();
        epochKey = newEpochKey;
    }

    /**
     * Ridisegno nello stesso contenitore: la finestra si apre per il giro corrente, o resta quella
     * dell'epoca se e' ancora aperta. I candidati si ricaricano da tutte le righe recenti, perche'
     * ogni ridisegno ripete la storia, anche le righe gia' consumate da un ridisegno precedente.
     */
    private void startRedrawIfBatch(long nowMillis, List<Voice> voices) {
        int newClosedVoices = 0;
        Set<String> counted = new HashSet<>();
        for (Voice voice : voices)
            if (!voice.text().isBlank() && !voicesOfEpoch.containsKey(voice.runtimeId())
                    && endsSentence(voice.text()) && counted.add(voice.runtimeId()))
                newClosedVoices++;
        if (newClosedVoices < REDRAW_BATCH_MIN_VOICES)
            return;

        loadRedrawCandidates();
        redrawUntilMillis = Math.max(redrawUntilMillis, nowMillis);
        listener.trackerEvent(nowMillis, "ridisegno", "stesso contenitore, voci nuove chiuse=" + newClosedVoices
                + ", righe confrontabili=" + recentDeliveredLines.size());
    }

    private void loadRedrawCandidates() {
        redrawCandidates.clear();
        for (DeliveredLine line : recentDeliveredLines)
            redrawCandidates.computeIfAbsent(line.key(), key -> new ArrayDeque<>()).addLast(line.number());
    }

    /**
     * Il numero della riga consegnata con cui la voce ridisegnata coincide, nell'ordine in cui le
     * righe uguali sono state scritte; 0 se la voce non e' un ridisegno di una riga recente.
     */
    private int consumeRedrawCandidate(TrackedVoice voice, long nowMillis) {
        if (!voice.bornDuringRedraw || nowMillis > redrawUntilMillis)
            return 0;
        String key = deliveredKey(voice.speaker, voice.text);
        ArrayDeque<Integer> lineNumbers = redrawCandidates.get(key);
        if (lineNumbers == null)
            return 0;
        int lineNumber = lineNumbers.removeFirst();
        if (lineNumbers.isEmpty())
            redrawCandidates.remove(key);
        return lineNumber;
    }

    //</editor-fold>

    //<editor-fold desc="Regole di chiusura">

    private List<TrackedVoice> trackVoices(long nowMillis, List<Voice> voices) {
        List<TrackedVoice> ordered = new ArrayList<>();
        Set<String> seenThisRound = new HashSet<>();
        boolean duringRedraw = nowMillis <= redrawUntilMillis;

        for (Voice voice : voices) {
            if (voice.text().isBlank() || !seenThisRound.add(voice.runtimeId()))
                continue;

            TrackedVoice tracked = voicesOfEpoch.get(voice.runtimeId());
            if (tracked == null) {
                tracked = new TrackedVoice(voice, nowMillis, duringRedraw);
                voicesOfEpoch.put(voice.runtimeId(), tracked);
            } else if (!tracked.text.equals(voice.text()) || !tracked.speaker.equals(voice.speaker())) {
                tracked.text = voice.text();
                tracked.speaker = voice.speaker();
                tracked.lastChangeMillis = nowMillis;
                if (tracked.delivered) {
                    tracked.delivered = false;
                    tracked.deliveredBefore = true;
                    listener.trackerEvent(nowMillis, "revisione", "voce " + tracked.runtimeId
                            + " cambiata dopo la consegna, si riapre");
                }
            }
            tracked.missingSinceMillis = -1;

            if (!tracked.delivered) {
                int redrawnLine = consumeRedrawCandidate(tracked, nowMillis);
                if (redrawnLine > 0) {
                    tracked.delivered = true;
                    tracked.deliveredLineNumber = redrawnLine;
                    redrawnCount++;
                }
            }
            ordered.add(tracked);
        }
        return ordered;
    }

    private void closeSettledVoices(long nowMillis, List<TrackedVoice> ordered) {
        for (int index = 0; index < ordered.size(); index++) {
            TrackedVoice voice = ordered.get(index);
            if (voice.delivered)
                continue;

            long unchangedMillis = nowMillis - voice.lastChangeMillis;
            boolean followedByVoiceWithText = index < ordered.size() - 1;

            if (endsSentence(voice.text) && unchangedMillis >= PUNCTUATION_SETTLE_MILLIS)
                deliver(voice, nowMillis, CloseReason.PUNCTUATION);
            else if (followedByVoiceWithText && unchangedMillis >= SUCCESSOR_SETTLE_MILLIS)
                deliver(voice, nowMillis, CloseReason.SUCCESSOR);
            else if (unchangedMillis >= STALE_MILLIS)
                deliver(voice, nowMillis, CloseReason.STALE);
        }
    }

    private void closeVanishedVoices(long nowMillis, List<TrackedVoice> present) {
        Set<String> presentIds = new HashSet<>();
        for (TrackedVoice voice : present)
            presentIds.add(voice.runtimeId);

        for (TrackedVoice voice : voicesOfEpoch.values()) {
            if (voice.delivered || presentIds.contains(voice.runtimeId))
                continue;
            if (voice.missingSinceMillis < 0)
                voice.missingSinceMillis = nowMillis;
            else if (nowMillis - voice.missingSinceMillis >= ORPHAN_GRACE_MILLIS)
                deliver(voice, nowMillis, CloseReason.VANISHED);
        }
    }

    static boolean endsSentence(String text) {
        String trimmed = text.strip();
        return trimmed.endsWith(".") || trimmed.endsWith("?") || trimmed.endsWith("!") || trimmed.endsWith("…");
    }

    //</editor-fold>

    //<editor-fold desc="Consegna e contatori" defaultstate="collapsed">

    /**
     * Una voce riaperta sostituisce la riga in cui era stata consegnata l'ultima volta, o quella con
     * cui un ridisegno l'aveva fatta coincidere.
     */
    private void deliver(TrackedVoice voice, long nowMillis, CloseReason reason) {
        int revisedLine = voice.deliveredBefore ? voice.deliveredLineNumber : 0;
        voice.delivered = true;
        deliveredCount++;
        voice.deliveredLineNumber = deliveredCount;
        if (voice.deliveredBefore)
            revisionCount++;
        recentDeliveredLines.addLast(new DeliveredLine(deliveredKey(voice.speaker, voice.text), deliveredCount));
        while (recentDeliveredLines.size() > RECENT_LINES_KEPT)
            recentDeliveredLines.removeFirst();
        listener.lineClosed(new ClosedLine(deliveredCount, voice.firstSeenMillis, voice.lastChangeMillis, nowMillis,
                voice.speaker, voice.text, reason, revisedLine));
    }

    private static String deliveredKey(String speaker, String text) {
        return speaker + KEY_SEPARATOR + text;
    }

    int deliveredCount() {
        return deliveredCount;
    }

    int revisionCount() {
        return revisionCount;
    }

    int redrawnCount() {
        return redrawnCount;
    }

    int droppedOpenCount() {
        return droppedOpenCount;
    }

    int openVoiceCount() {
        int open = 0;
        for (TrackedVoice voice : voicesOfEpoch.values())
            if (!voice.delivered)
                open++;
        return open;
    }

    //</editor-fold>
}

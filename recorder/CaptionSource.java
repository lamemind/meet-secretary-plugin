import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Trova il contenitore dei sottotitoli nelle finestre di Teams e ne legge le voci, con UI Automation.
 *
 * Dove stanno i sottotitoli (referto del probe, T18 DLV1): un Group per voce, con la classe Fluent UI
 * fui-ChatMessageCompact__body, e dentro due o tre Text: il nome di chi parla, a volte un badge
 * (Non verificato, per un ospite non autenticato), il testo. Il contenitore si ritrova dal pulsante
 * captions-panel-dismiss-button, id HTML stabile e indipendente dalla lingua: e' il suo nonno. Nella
 * finestra dei sottotitoli staccati quel pulsante non c'e', e il contenitore si ritrova dal nome.
 *
 * Una ricerca raccoglie tutti i candidati di ogni finestra, non il primo: in due meet dal vivo il
 * contenitore del primo captions-panel-dismiss-button si e' letto vuoto per minuti, mentre la
 * finestra del meet portava venti voci altrove (T18 DLV6). Candidati, in ordine di preferenza: il
 * nonno di ogni captions-panel-dismiss-button, ogni Group col nome dei sottotitoli, e, se nessuno
 * dei due porta voci, il genitore dell'ultima voce della finestra. Vince il candidato con piu' voci.
 * Ogni finestra si percorre una volta sola, con una condizione che unisce pulsante, nome e voce.
 *
 * Una lettura e' un solo FindAllBuildCache sul contenitore: le voci arrivano con RuntimeId, classe e
 * figli gia' in cache, in un viaggio solo verso il processo di Teams.
 */
final class CaptionSource implements AutoCloseable {

    static final String DISMISS_BUTTON_AUTOMATION_ID = "captions-panel-dismiss-button";
    static final List<String> CONTAINER_NAMES = List.of("Sottotitoli in tempo reale", "Live captions");
    static final String VOICE_CLASS_MARKER = "ChatMessageCompact__body";
    static final Set<String> SPEAKER_BADGES = Set.of("Non verificato", "Unverified");
    static final String TEAMS_EXECUTABLE = "ms-teams.exe";
    static final String TEAMS_WINDOW_MARKER = "Microsoft Teams";
    private static final int DIAGNOSTIC_CLASSES_SHOWN = 12;
    private static final long TEAMS_PROCESS_REFRESH_MILLIS = 30_000;

    static final String VIA_DISMISS_BUTTON = "dismiss";
    static final String VIA_CONTAINER_NAME = "nome";
    static final String VIA_VOICE_PARENT = "voce";

    /**
     * Il contenitore trovato: epochKey e' il suo RuntimeId, e cambia quando Teams sposta i sottotitoli
     * in un'altra finestra. windowKey e' il RuntimeId della finestra di primo livello che lo porta, e
     * resta lo stesso per tutta la vita della finestra. voiceCount sono le voci lette al momento della
     * ricerca.
     */
    record Located(Uia.Element container, String epochKey, String via, String windowKey, String windowName,
                   int voiceCount) implements AutoCloseable {

        @Override
        public void close() {
            container.close();
        }
    }

    /**
     * Una finestra di primo livello di Teams: RuntimeId e titolo.
     */
    record TeamsWindow(String key, String name) {
    }

    /**
     * Esito di una ricerca: il candidato scelto, se c'e', i RuntimeId di tutti i candidati visti, per
     * il diario la descrizione di ogni finestra di Teams con voci o candidati, e tutte le finestre di
     * Teams viste. complete e' falso quando la ricerca e' fallita: le finestre non sono state contate,
     * e la loro assenza non vuol dire che il meet e' finito.
     */
    record Search(Optional<Located> chosen, Set<String> candidateKeys, String report, List<TeamsWindow> windows,
                  boolean complete) {

        static Search failed() {
            return new Search(Optional.empty(), Set.of(), "", List.of(), false);
        }
    }

    private final Uia.Element root;
    private final Uia.TreeWalker rawWalker;
    private final Uia.Condition rawViewCondition;
    private final Uia.Condition trueCondition;
    private final Uia.Condition voiceCondition;
    private final Uia.Condition anchorCondition;

    /**
     * Vero se CreatePropertyConditionEx ha accettato la ricerca per sottostringa sulla classe; falso
     * se voiceCondition e' la condizione sul tipo Group, e la classe si filtra a ogni voce.
     */
    private final boolean voiceConditionMatchesClass;
    private final Uia.CacheRequest windowCache;
    private final Uia.CacheRequest anchorCache;
    private final Uia.CacheRequest voiceCache;
    private final Uia.CacheRequest diagnosticCache;
    private final List<Uia.ComReference> ownedReferences = new ArrayList<>();
    private Set<Long> teamsProcessIds = Set.of();
    private long teamsProcessIdsRefreshAtMillis;

    CaptionSource(Uia.Automation automation) {
        root = own(automation.rootElement());
        rawWalker = own(automation.rawViewWalker());
        rawViewCondition = own(automation.rawViewCondition());
        trueCondition = own(automation.trueCondition());

        Uia.Condition classCondition;
        boolean substringAccepted;
        try {
            classCondition = automation.propertyContains(Uia.CLASS_NAME_PROPERTY, VOICE_CLASS_MARKER);
            substringAccepted = true;
        } catch (Com.ComException substringNotSupported) {
            classCondition = automation.propertyEquals(Uia.CONTROL_TYPE_PROPERTY, Uia.GROUP_CONTROL_TYPE);
            substringAccepted = false;
        }
        voiceConditionMatchesClass = substringAccepted;
        voiceCondition = own(classCondition);
        anchorCondition = own(anchorCondition(automation, voiceCondition));

        windowCache = own(automation.cacheRequest())
                .addProperty(Uia.RUNTIME_ID_PROPERTY)
                .addProperty(Uia.PROCESS_ID_PROPERTY)
                .addProperty(Uia.NAME_PROPERTY);

        anchorCache = own(automation.cacheRequest())
                .addProperty(Uia.AUTOMATION_ID_PROPERTY)
                .addProperty(Uia.CLASS_NAME_PROPERTY)
                .addProperty(Uia.CONTROL_TYPE_PROPERTY)
                .addProperty(Uia.NAME_PROPERTY)
                .treeFilter(rawViewCondition);

        voiceCache = own(automation.cacheRequest())
                .addProperty(Uia.RUNTIME_ID_PROPERTY)
                .addProperty(Uia.CLASS_NAME_PROPERTY)
                .addProperty(Uia.CONTROL_TYPE_PROPERTY)
                .addProperty(Uia.NAME_PROPERTY)
                .treeScope(Uia.TREE_SCOPE_ELEMENT | Uia.TREE_SCOPE_CHILDREN)
                .treeFilter(rawViewCondition);

        diagnosticCache = own(automation.cacheRequest())
                .addProperty(Uia.CLASS_NAME_PROPERTY)
                .addProperty(Uia.CONTROL_TYPE_PROPERTY)
                .treeFilter(rawViewCondition);
    }

    //<editor-fold desc="Ricerca del contenitore">

    /**
     * Cerca il contenitore in ogni finestra di primo livello dei processi di Teams. Una finestra conta
     * come di Teams se il processo e' ms-teams.exe, o se il titolo porta Microsoft Teams: il nome
     * dell'eseguibile di un pacchetto MSIX puo' non essere leggibile. Fra tutti i candidati vince
     * quello con piu' voci; a parita', il primo trovato.
     *
     * voiceParentAllowed decide se il genitore dell'ultima voce vale come candidato. Serve a
     * recuperare le voci durante un meet, quando nessun contenitore riconosciuto le porta (T18 DLV6);
     * fuori da un meet un genitore di voci non apre niente, perche' solo il pannello dei sottotitoli,
     * col suo pulsante o col suo nome, dice che un meet coi sottotitoli c'e'.
     */
    Search search(boolean voiceParentAllowed) {
        Set<Long> teamsProcessIds = teamsProcessIds();
        List<Located> candidates = new ArrayList<>();
        List<String> windowReports = new ArrayList<>();
        List<TeamsWindow> teamsWindows = new ArrayList<>();
        try {
            try (Uia.ElementArray windows = root.findAllBuildCache(Uia.TREE_SCOPE_CHILDREN, trueCondition, windowCache)) {
                int windowCount = windows.length();
                for (int index = 0; index < windowCount; index++) {
                    try (Uia.Element window = windows.get(index)) {
                        String windowName = window.cachedName();
                        if (!isTeamsWindow(window.cachedProcessId(), windowName, teamsProcessIds))
                            continue;
                        TeamsWindow teamsWindow = new TeamsWindow(window.cachedRuntimeId(), windowName);
                        teamsWindows.add(teamsWindow);
                        collectCandidates(window, teamsWindow, voiceParentAllowed, candidates)
                                .ifPresent(windowReports::add);
                    }
                }
            }
        } catch (RuntimeException failure) {
            candidates.forEach(Located::close);
            throw failure;
        }

        Located chosen = null;
        Set<String> candidateKeys = new LinkedHashSet<>();
        for (Located candidate : candidates) {
            candidateKeys.add(candidate.epochKey());
            if (chosen == null || candidate.voiceCount() > chosen.voiceCount())
                chosen = candidate;
        }
        for (Located candidate : candidates)
            if (candidate != chosen)
                candidate.close();
        return new Search(Optional.ofNullable(chosen), candidateKeys, String.join(" | ", windowReports),
                List.copyOf(teamsWindows), true);
    }

    /**
     * Un solo percorso della finestra, con la condizione che unisce pulsante, nome e voce. Aggiunge
     * i candidati della finestra e restituisce la riga di diario della finestra, se la finestra ha
     * voci o candidati.
     */
    private Optional<String> collectCandidates(Uia.Element window, TeamsWindow teamsWindow, boolean voiceParentAllowed,
                                               List<Located> candidates) {
        String windowName = teamsWindow.name();
        int firstOfWindow = candidates.size();
        int voicesInWindow = 0;
        Uia.Element lastVoice = null;
        try {
            try (Uia.ElementArray anchors = window.findAllBuildCache(Uia.TREE_SCOPE_DESCENDANTS, anchorCondition,
                    anchorCache)) {
                int anchorCount = anchors.length();
                for (int index = 0; index < anchorCount; index++) {
                    Uia.Element anchor = anchors.get(index);
                    boolean anchorHandedOver = false;
                    try {
                        if (DISMISS_BUTTON_AUTOMATION_ID.equals(anchor.cachedAutomationId())) {
                            addCandidate(candidates, grandparentOf(anchor), VIA_DISMISS_BUTTON, teamsWindow);
                        } else if (anchor.cachedClassName().contains(VOICE_CLASS_MARKER)) {
                            voicesInWindow++;
                            if (lastVoice != null)
                                lastVoice.close();
                            lastVoice = anchor;
                            anchorHandedOver = true;
                        } else if (anchor.cachedControlType() == Uia.GROUP_CONTROL_TYPE
                                && CONTAINER_NAMES.contains(anchor.cachedName())) {
                            anchorHandedOver = true;
                            addCandidate(candidates, anchor, VIA_CONTAINER_NAME, teamsWindow);
                        }
                    } finally {
                        if (!anchorHandedOver)
                            anchor.close();
                    }
                }
            }

            List<Located> windowCandidates = candidates.subList(firstOfWindow, candidates.size());
            boolean voicesInCandidates = windowCandidates.stream().anyMatch(candidate -> candidate.voiceCount() > 0);
            if (voiceParentAllowed && lastVoice != null && !voicesInCandidates)
                addCandidate(candidates, rawWalker.parent(lastVoice), VIA_VOICE_PARENT, teamsWindow);
        } finally {
            if (lastVoice != null)
                lastVoice.close();
        }

        List<Located> ofWindow = candidates.subList(firstOfWindow, candidates.size());
        if (voicesInWindow == 0 && ofWindow.isEmpty())
            return Optional.empty();
        StringBuilder report = new StringBuilder(windowName).append(": voci=").append(voicesInWindow);
        for (Located candidate : ofWindow)
            report.append("; ").append(describe(candidate));
        return Optional.of(report.toString());
    }

    /**
     * Prende possesso di container. Lo scarta se e' nullo o se un candidato con lo stesso RuntimeId
     * c'e' gia': nella finestra del meet il nonno del pulsante e' anche il Group col nome.
     */
    private void addCandidate(List<Located> candidates, Uia.Element container, String via, TeamsWindow window) {
        if (container == null)
            return;
        try {
            String runtimeId = container.runtimeId();
            for (Located candidate : candidates) {
                if (candidate.epochKey().equals(runtimeId)) {
                    container.close();
                    return;
                }
            }
            int voiceCount = readVoices(container).size();
            candidates.add(new Located(container, runtimeId, via, window.key(), window.name(), voiceCount));
        } catch (RuntimeException failure) {
            container.close();
            throw failure;
        }
    }

    private Uia.Element grandparentOf(Uia.Element element) {
        try (Uia.Element parent = rawWalker.parent(element)) {
            return parent == null ? null : rawWalker.parent(parent);
        }
    }

    private static String describe(Located candidate) {
        String visibility;
        try {
            visibility = candidate.container().currentIsOffscreen() ? "fuori_schermo" : "a_schermo";
        } catch (Com.ComException unreadable) {
            visibility = "visibilita_illeggibile";
        }
        return candidate.via() + " " + candidate.epochKey() + " voci=" + candidate.voiceCount() + " " + visibility;
    }

    private static boolean isTeamsWindow(int processId, String windowName, Set<Long> teamsProcessIds) {
        return teamsProcessIds.contains((long) processId) || windowName.contains(TEAMS_WINDOW_MARKER);
    }

    /**
     * La scansione di tutti i processi costa 100-160 ms sul PC aziendale, piu' della ricerca in una
     * finestra: si rifa' al massimo ogni TEAMS_PROCESS_REFRESH_MILLIS. Un processo di Teams nato nel
     * frattempo si riconosce comunque dal titolo della finestra.
     */
    private Set<Long> teamsProcessIds() {
        long nowMillis = System.currentTimeMillis();
        if (nowMillis < teamsProcessIdsRefreshAtMillis)
            return teamsProcessIds;
        Set<Long> processIds = new HashSet<>();
        ProcessHandle.allProcesses().forEach(process -> {
            String command = process.info().command().orElse("").toLowerCase(Locale.ROOT);
            if (command.endsWith("\\" + TEAMS_EXECUTABLE))
                processIds.add(process.pid());
        });
        teamsProcessIds = processIds;
        teamsProcessIdsRefreshAtMillis = nowMillis + TEAMS_PROCESS_REFRESH_MILLIS;
        return processIds;
    }

    //</editor-fold>

    //<editor-fold desc="Lettura delle voci">

    /**
     * Le voci del contenitore, nell'ordine dell'albero. Un errore COM qui vuol dire contenitore non
     * piu' valido (finestra chiusa, sottotitoli spostati): lo gestisce il chiamante.
     */
    List<CaptionTracker.Voice> readVoices(Uia.Element container) {
        List<CaptionTracker.Voice> voices = new ArrayList<>();
        try (Uia.ElementArray bodies = container.findAllBuildCache(Uia.TREE_SCOPE_DESCENDANTS, voiceCondition, voiceCache)) {
            int bodyCount = bodies.length();
            for (int index = 0; index < bodyCount; index++) {
                try (Uia.Element body = bodies.get(index)) {
                    if (!voiceConditionMatchesClass && !body.cachedClassName().contains(VOICE_CLASS_MARKER))
                        continue;
                    voices.add(voiceFrom(body.cachedRuntimeId(), cachedTextsOf(body)));
                }
            }
        }
        return voices;
    }

    /**
     * Diagnosi di un contenitore che si legge vuoto: quanti elementi ha sotto e di che classe, e cosa
     * trova adesso una ricerca da capo, finestra per finestra: voci, candidati, voci di ogni
     * candidato, se il candidato e' a schermo. Voci nella finestra e zero in ogni candidato vogliono
     * dire che Teams le mostra in un posto che il lettore non sa ritrovare.
     */
    String diagnose(Located located) {
        StringBuilder report = new StringBuilder();
        try (Uia.ElementArray descendants = located.container()
                .findAllBuildCache(Uia.TREE_SCOPE_DESCENDANTS, trueCondition, diagnosticCache)) {
            int count = descendants.length();
            Set<String> classes = new LinkedHashSet<>();
            for (int index = 0; index < count && classes.size() < DIAGNOSTIC_CLASSES_SHOWN; index++) {
                try (Uia.Element element = descendants.get(index)) {
                    classes.add(shortClassName(element.cachedClassName()) + "/" + element.cachedControlType());
                }
            }
            report.append("sotto_contenitore=").append(count).append(" classi=").append(classes);
        }

        Search fresh = search(true);
        report.append(" ricerca_da_capo=").append(fresh.chosen()
                .map(found -> found.via() + (found.epochKey().equals(located.epochKey())
                        ? " stesso contenitore"
                        : " altro contenitore " + found.epochKey() + " in " + found.windowName())
                        + " voci=" + found.voiceCount())
                .orElse("nessun contenitore"));
        report.append(" finestre=[").append(fresh.report().isEmpty() ? "finestra assente" : fresh.report()).append(']');
        fresh.chosen().ifPresent(Located::close);
        return report.toString();
    }

    private static String shortClassName(String className) {
        String firstToken = className.strip().split("\\s+", 2)[0];
        return firstToken.isEmpty() ? "-" : firstToken;
    }

    private static List<String> cachedTextsOf(Uia.Element body) {
        List<String> texts = new ArrayList<>();
        try (Uia.ElementArray children = body.cachedChildren()) {
            int childCount = children.length();
            for (int index = 0; index < childCount; index++) {
                try (Uia.Element child = children.get(index)) {
                    if (child.cachedControlType() != Uia.TEXT_CONTROL_TYPE)
                        continue;
                    String name = child.cachedName();
                    if (!name.isBlank())
                        texts.add(name);
                }
            }
        }
        return texts;
    }

    /**
     * Nome = primo Text, testo = l'ultimo Text dopo il nome che non sia un badge. Il badge di un ospite
     * nella finestra staccata arriva mezzo secondo dopo il testo: fra un giro e l'altro la voce passa
     * da [nome, testo] a [nome, badge, testo], e nome e testo restano gli stessi.
     */
    static CaptionTracker.Voice voiceFrom(String runtimeId, List<String> texts) {
        String speaker = texts.isEmpty() ? "" : texts.get(0);
        String text = "";
        for (int index = texts.size() - 1; index >= 1; index--) {
            if (!SPEAKER_BADGES.contains(texts.get(index))) {
                text = texts.get(index);
                break;
            }
        }
        return new CaptionTracker.Voice(runtimeId, speaker, text);
    }

    //</editor-fold>

    //<editor-fold desc="Condizioni e riferimenti posseduti" defaultstate="collapsed">

    /**
     * Pulsante dei sottotitoli, oppure Group col nome dei sottotitoli, oppure voce: le tre ancore della
     * ricerca in una condizione sola, cosi' la finestra si percorre una volta.
     */
    private static Uia.Condition anchorCondition(Uia.Automation automation, Uia.Condition voiceCondition) {
        try (Uia.Condition dismiss = automation.propertyEquals(Uia.AUTOMATION_ID_PROPERTY, DISMISS_BUTTON_AUTOMATION_ID);
             Uia.Condition named = namedContainerCondition(automation);
             Uia.Condition dismissOrNamed = automation.or(dismiss, named)) {
            return automation.or(dismissOrNamed, voiceCondition);
        }
    }

    private static Uia.Condition namedContainerCondition(Uia.Automation automation) {
        Uia.Condition anyName = null;
        for (String name : CONTAINER_NAMES) {
            Uia.Condition nameCondition = automation.propertyEquals(Uia.NAME_PROPERTY, name);
            if (anyName == null) {
                anyName = nameCondition;
                continue;
            }
            Uia.Condition combined = automation.or(anyName, nameCondition);
            anyName.close();
            nameCondition.close();
            anyName = combined;
        }
        try (Uia.Condition isGroup = automation.propertyEquals(Uia.CONTROL_TYPE_PROPERTY, Uia.GROUP_CONTROL_TYPE)) {
            return automation.and(anyName, isGroup);
        } finally {
            anyName.close();
        }
    }

    private <T extends Uia.ComReference> T own(T reference) {
        ownedReferences.add(reference);
        return reference;
    }

    @Override
    public void close() {
        for (int index = ownedReferences.size() - 1; index >= 0; index--)
            ownedReferences.get(index).close();
        ownedReferences.clear();
    }

    //</editor-fold>
}

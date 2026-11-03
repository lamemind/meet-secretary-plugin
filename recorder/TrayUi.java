import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * L'icona del registratore nel tray di Windows. Il colore dice lo stato: grigia ferma, blu in attesa
 * di un meet, verde mentre registra, gialla coi sottotitoli spenti durante un meet, rossa col
 * contenitore dei sottotitoli vuoto da qualche secondo, viola scuro dopo un errore. Il rosso e' il
 * segnale del buco dei sottotitoli (T18 DLV6): se qualcuno parla e l'icona e' rossa, staccare i
 * sottotitoli riporta le frasi prima che Teams le tolga. Anche un lungo silenzio la fa diventare
 * rossa, perche' Teams toglie le voci vecchie anche quando nessuno parla.
 *
 * Il menu porta solo la registrazione: stato, avvia, ferma, esci. Lo stato si mostra con una
 * notifica di Windows, solo quando lo si chiede: una notifica automatica comparirebbe anche sullo
 * schermo condiviso durante un meet.
 */
final class TrayUi implements RecordingLoop.StatusListener {

    private static final String APPLICATION_NAME = "meet-recorder";
    private static final int ICON_SIZE = 64;
    private static final DateTimeFormatter STARTED_FORMAT = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private final RecordingLoop loop;
    private final Runnable exitAction;
    private final String startedAt = LocalDateTime.now().format(STARTED_FORMAT);
    private final Map<RecordingLoop.Status, Image> icons = new EnumMap<>(RecordingLoop.Status.class);
    private TrayIcon trayIcon;
    private MenuItem startItem;
    private MenuItem stopItem;
    private RecordingLoop.Status shownStatus;

    TrayUi(RecordingLoop loop, Runnable exitAction) {
        this.loop = loop;
        this.exitAction = exitAction;
        for (RecordingLoop.Status status : RecordingLoop.Status.values())
            icons.put(status, drawIcon(status));
    }

    //<editor-fold desc="Icona e menu">

    /**
     * Da chiamare sul thread degli eventi AWT.
     */
    void install() throws AWTException {
        PopupMenu menu = new PopupMenu();
        MenuItem statusItem = new MenuItem("Stato");
        statusItem.addActionListener(event -> showStatus());
        startItem = new MenuItem("Avvia");
        startItem.addActionListener(event -> loop.requestStart());
        stopItem = new MenuItem("Ferma");
        stopItem.addActionListener(event -> loop.requestStop());
        MenuItem exitItem = new MenuItem("Esci");
        exitItem.addActionListener(event -> exitAction.run());
        menu.add(statusItem);
        menu.addSeparator();
        menu.add(startItem);
        menu.add(stopItem);
        menu.addSeparator();
        menu.add(exitItem);

        trayIcon = new TrayIcon(icons.get(RecordingLoop.Status.WAITING), APPLICATION_NAME, menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(event -> showStatus());
        SystemTray.getSystemTray().add(trayIcon);
        apply(new RecordingLoop.Snapshot(RecordingLoop.Status.WAITING, null, 0, 0, null));
    }

    void remove() {
        if (trayIcon != null)
            SystemTray.getSystemTray().remove(trayIcon);
    }

    @Override
    public void statusChanged(RecordingLoop.Snapshot snapshot) {
        EventQueue.invokeLater(() -> apply(snapshot));
    }

    private void apply(RecordingLoop.Snapshot snapshot) {
        if (trayIcon == null)
            return;
        if (snapshot.status() != shownStatus) {
            trayIcon.setImage(icons.get(snapshot.status()));
            if (snapshot.status() == RecordingLoop.Status.FAILED)
                trayIcon.displayMessage(APPLICATION_NAME, "Il registratore si e' fermato per un errore e riprova da solo: "
                        + snapshot.detail(), TrayIcon.MessageType.ERROR);
            shownStatus = snapshot.status();
        }
        trayIcon.setToolTip(APPLICATION_NAME + ": " + snapshot.describe());
        boolean stopped = snapshot.status() == RecordingLoop.Status.STOPPED;
        startItem.setEnabled(stopped);
        stopItem.setEnabled(!stopped);
    }

    private void showStatus() {
        RecordingLoop.Snapshot snapshot = loop.lastSnapshot();
        if (snapshot == null)
            return;
        StringBuilder message = new StringBuilder(snapshot.describe());
        if (snapshot.meetFile() != null)
            message.append("\nfile: ").append(snapshot.meetFile().getFileName());
        message.append("\ncartella: ").append(loop.outputDirectory());
        List<String> teamsWindows = loop.teamsWindowNames();
        message.append("\nfinestre di Teams: ").append(teamsWindows.size());
        message.append("\nattivo dal ").append(startedAt);
        trayIcon.displayMessage(APPLICATION_NAME, message.toString(), TrayIcon.MessageType.NONE);
    }

    //</editor-fold>

    //<editor-fold desc="Testi e disegno" defaultstate="collapsed">

    /**
     * Un disco col colore dello stato e un segno bianco: quadrato fermo, anello in attesa, punto
     * mentre registra, barra coi sottotitoli spenti, punto esclamativo col contenitore vuoto, croce
     * dopo un errore. Si disegna a 64 px e Windows lo riduce alla misura del tray.
     */
    private static Image drawIcon(RecordingLoop.Status status) {
        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(colorOf(status));
            graphics.fillOval(2, 2, ICON_SIZE - 4, ICON_SIZE - 4);
            graphics.setColor(Color.WHITE);
            int center = ICON_SIZE / 2;
            switch (status) {
                case STOPPED -> graphics.fillRect(center - 12, center - 12, 24, 24);
                case WAITING -> {
                    graphics.setStroke(new BasicStroke(7f));
                    graphics.drawOval(center - 14, center - 14, 28, 28);
                }
                case RECORDING -> graphics.fillOval(center - 13, center - 13, 26, 26);
                case NO_CAPTIONS -> graphics.fillRect(center - 16, center - 5, 32, 10);
                case EMPTY -> {
                    graphics.fillRect(center - 5, center - 20, 10, 26);
                    graphics.fillOval(center - 6, center + 10, 12, 12);
                }
                case FAILED -> {
                    graphics.setStroke(new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    graphics.drawLine(center - 13, center - 13, center + 13, center + 13);
                    graphics.drawLine(center + 13, center - 13, center - 13, center + 13);
                }
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static Color colorOf(RecordingLoop.Status status) {
        return switch (status) {
            case STOPPED -> new Color(0x757575);
            case WAITING -> new Color(0x1565C0);
            case RECORDING -> new Color(0x2E7D32);
            case NO_CAPTIONS -> new Color(0xF9A825);
            case EMPTY -> new Color(0xC62828);
            case FAILED -> new Color(0x4A148C);
        };
    }

    //</editor-fold>
}

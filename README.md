# meet-secretary

Plugin di Claude Code che fa da segretario durante i meet Teams.

- **meet-recorder** (`recorder/`) — app del tray, parte al login di Windows e registra i sottotitoli live di Teams in un file per meet.
- **follow-meet** (skill) — si aggancia al meet in corso dalla sessione Claude Code del progetto di cui il meet parla: pulisce i sottotitoli, si sveglia sui temi attivi, sul proprio nome e sulle parole chiave, risponde alle domande da tastiera.

## Requisiti

- Windows, con Teams desktop e i sottotitoli live accesi nel meet
- Claude Code con Git Bash
- JDK 25 o superiore

## Installazione

```
claude plugin marketplace add lamemind/meet-secretary-plugin
claude plugin install meet-secretary@meet-secretary
```

L'installazione guidata del recorder (JDK, cartella dei meet, avvio al login, aggiornamento) è in sviluppo. Fino ad allora il recorder si installa a mano con `recorder\meet-recorder.bat installa [cartella dei meet]`.

## Configurazione

- `%LOCALAPPDATA%\meet-recorder\config.properties` — della persona, vale in ogni progetto. Chiave `cartella`: dove il recorder scrive i meet, default `%USERPROFILE%\meet-teams`. Chiave `voce`: il tuo nome come lo scrive Teams nei sottotitoli, per esempio `ROSSI Mario`; senza, il nome del segretario non lo sveglia.
- `.meet-secretary/settings.ini` nella cartella del progetto — temi, parole chiave, glossario, nome del segretario e tempi del follower di quel progetto. Lo crea la skill al primo `/follow-meet` e lo cambia su richiesta in chat; si può anche modificare a mano, e la pulizia lo rilegge ogni 10 s. Il file è personale: la skill lo mette in `.git/info/exclude` del progetto. Il plugin non porta temi di default.

## Dati dei meet

I file dei meet restano nella cartella dei meet, sul PC di chi registra. Il segretario li legge solo con gli script del plugin e non li copia altrove.

## Segnalazioni

Le issue di questo repo sono disattivate: un log o un estratto di un meet in una issue pubblica sarebbe leggibile da chiunque. Le segnalazioni vanno a chi ti ha dato il plugin, sul canale del team.

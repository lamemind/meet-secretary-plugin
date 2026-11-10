# meet-secretary

Plugin di Claude Code che fa da segretario durante i meet Teams. Ha due pezzi:

- **meet-recorder** (`recorder/`) — un'app del tray di Windows, l'area delle icone accanto all'orologio. Parte da sola a ogni login e registra i sottotitoli live di Teams in un file per meet.
- **follow-meet** — una skill di Claude Code, cioè un comando che si lancia scrivendo `/` nella chat di Claude Code. Si aggancia al meet in corso dalla sessione Claude Code del progetto di cui il meet parla: pulisce i sottotitoli, si sveglia sui temi attivi, sul proprio nome e sulle parole chiave, risponde alle domande da tastiera.

Il recorder registra anche con Claude Code spento: i meet restano nella cartella dei meet, e il segretario li legge quando serve.

## Cosa serve

- **Windows 10 o 11.**
- **Teams desktop, in italiano o in inglese.** Il recorder trova i sottotitoli cercando il loro riquadro per nome, «Sottotitoli in tempo reale» o «Live captions». Con Teams in un'altra lingua non lo trova e non registra niente, senza segnalare errori.
- **Claude Code**, l'assistente di Anthropic che lavora nel terminale, con un account che lo include. Si installa dalla [pagina ufficiale](https://code.claude.com/docs/en/setup). In un terminale aperto dopo l'installazione, `claude --version` stampa la versione.
- **[Git for Windows](https://git-scm.com/downloads/win).** Porta Git Bash, la shell dove girano gli script del plugin e da cui Claude Code segue il meet.
- **Un JDK 25 o superiore**, il kit di sviluppo Java. Il recorder e la pulizia partono dal sorgente e lo compilano, quindi un JRE non basta. Va bene qualunque distribuzione, per esempio [Eclipse Temurin](https://adoptium.net/): l'installer `.msi` lo mette sotto `C:\Program Files`, dove il plugin lo cerca. Se il JDK sta altrove, vedi [Il JDK non si trova](#il-jdk-non-si-trova).

## Installazione

Una volta sola, nell'ordine.

**1. Apri un terminale.** Premi `Win+R`, il tasto Windows e la R insieme: si apre la finestra **Esegui**. Scrivi `cmd` e premi Invio. Si apre il Prompt dei comandi, una finestra nera col cursore.

**2. Aggiungi il marketplace.** Un marketplace è il catalogo da cui Claude Code scarica i plugin: quello di meet-secretary è questo repo GitHub. Incolla nel terminale col tasto destro del mouse, poi premi Invio:

```
claude plugin marketplace add lamemind/meet-secretary-plugin
```

Quando va bene, stampa `Successfully added marketplace: meet-secretary`.

**3. Installa il plugin.** Nello stesso terminale:

```
claude plugin install meet-secretary@meet-secretary
```

Quando va bene, stampa `Successfully installed plugin: meet-secretary@meet-secretary (scope: user)`: il plugin vale in tutti i tuoi progetti.

**4. Accendi l'aggiornamento automatico.** Per i marketplace che non sono di Anthropic è spento, e senza il plugin resta alla versione di oggi. Nello stesso terminale scrivi `claude` e premi Invio: parte una sessione di Claude Code. Al primo avvio Claude Code ti chiede il login nel browser e se ti fidi della cartella in cui sei: rispondi di sì. Poi:

1. scrivi `/plugin` e premi Invio
2. col tasto Tab vai sulla scheda **Marketplaces**
3. scegli `meet-secretary` con le frecce e premi Invio
4. scegli **Enable auto-update** e premi Invio
5. esci dal pannello con Esc, e da Claude Code con `/exit`

**5. Installa il recorder.** Chiudi il terminale. Premi `Win+R`, incolla questa riga intera, virgolette comprese, e premi Invio:

```
cmd /k ""%USERPROFILE%\.claude\plugins\marketplaces\meet-secretary\recorder\meet-recorder.bat" installa"
```

Windows sostituisce da solo `%USERPROFILE%` con la tua cartella utente. Si apre una finestra che resta aperta sul risultato. Quando va bene, mostra righe come queste:

```
JDK 25.0.1: C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot
Recorder copiato in C:\Users\<tu>\AppData\Local\meet-recorder\app.
Avvio al login: HKCU\Software\Microsoft\Windows\CurrentVersion\Run, voce meet-recorder.
meet-recorder avviato: l'icona compare nel tray, anche fra le icone nascoste.
Log: C:\Users\<tu>\AppData\Local\meet-recorder\meet-recorder.log
```

Il recorder lavora da una sua copia, in `%LOCALAPPDATA%\meet-recorder\app\`, e parte da lì a ogni login. Una riga che comincia con `[ERRORE]` dice cosa manca: un JDK ([Il JDK non si trova](#il-jdk-non-si-trova)), oppure un recorder già acceso da chiudere ([Aggiornamenti](#aggiornamenti)).

**6. Controlla che sia acceso.** L'icona di meet-recorder sta nel tray. Se non la vedi, apri la freccia `^` delle icone nascoste. Un clic sull'icona mostra lo stato; il tasto destro apre il menu, con **Stato**, **Avvia**, **Ferma** ed **Esci**. Il log del recorder si apre con `Win+R` e questa riga:

```
notepad %LOCALAPPDATA%\meet-recorder\meet-recorder.log
```

Una riga in fondo come `2026-11-10 09:01:18  meet-recorder avviato, Java 25.0.1+8-LTS, app del tray` dice che il recorder è partito.

## A ogni meet

- **Accendi i sottotitoli live.** Il recorder li legge ma non li accende. Nei controlli del meet apri **Altre azioni**, i tre puntini `…`, e scegli **Sottotitoli**; nelle versioni di Teams che li raggruppano, la voce sta in un sottomenu. I sottotitoli li vedi solo tu. Le versioni recenti di Teams li accendono anche da sole a ogni meet: cerca l'opzione fra le impostazioni del riquadro dei sottotitoli o in **Impostazioni → Accessibilità**. Se l'opzione non c'è, la tua organizzazione non la permette o la tua versione di Teams non l'ha ancora: accendili a mano a ogni meet.
- Il recorder scrive un file per meet nella cartella dei meet, `%USERPROFILE%\meet-teams` se non ne hai scelta un'altra ([Configurazione](#configurazione)).
- Per il segretario, apri Claude Code nella cartella del progetto di cui il meet parla e scrivi `/meet-secretary:follow-meet`.

## Aggiornamenti

**Il plugin si aggiorna da solo**, con l'aggiornamento automatico acceso al passo 4. All'avvio di una sessione Claude Code scarica la versione nuova e scrive `Plugin updated: meet-secretary · Run /reload-plugins to apply`. La sessione successiva usa la versione nuova; `/reload-plugins` la carica subito. L'aggiornamento porta skill, script e pulizia.

**Il recorder non si aggiorna da solo.** La sua copia in `%LOCALAPPDATA%\meet-recorder\app\` resta com'è finché non la rifai tu, con il comando dell'installazione. Fuori da un meet:

1. tasto destro sull'icona di meet-recorder nel tray, poi **Esci**
2. `Win+R`, la riga del passo 5, Invio

Il recorder va chiuso prima: se è acceso, `installa` si rifiuta e scrive `[ERRORE] meet-recorder e' acceso: chiudilo dal menu dell'icona nel tray (Esci) e rilancia.` La riga del passo 5 installa il recorder della copia del marketplace sul tuo PC. Claude Code la rinnova all'avvio di ogni sessione quando l'aggiornamento automatico è acceso; senza, rinnovala prima con `claude plugin marketplace update meet-secretary` in un terminale. Rifare l'installazione non toglie niente: configurazione, log e meet registrati restano dove sono.

**Dopo un cambio di JDK** rifai gli stessi due passi. L'avvio al login contiene il path del JDK con cui hai installato il recorder. Se installi un JDK nuovo in un'altra cartella e togli il vecchio, al login il recorder non parte finché non rilanci la riga del passo 5, che sceglie il JDK con la versione più alta fra quelli che trova.

## Il JDK non si trova

Senza un JDK 25 o superiore, `installa` e la pulizia si fermano con un messaggio che elenca le cartelle dove il JDK si cerca:

- le cartelle degli installer sotto `C:\Program Files`: `Java`, `Eclipse Adoptium`, `Microsoft`, `Zulu`, `Amazon Corretto`, `BellSoft`
- `%USERPROFILE%\.jdks`, dove IntelliJ IDEA scarica i JDK
- `%USERPROFILE%\dev-tools`
- la cartella della variabile d'ambiente `JAVA_HOME`

Il PATH non conta. Un JDK installato altrove si trova con `JAVA_HOME` impostata sulla sua cartella, quella che contiene `bin\javac.exe`. Nel menu Start cerca «variabili d'ambiente», apri la voce per il tuo account e aggiungi la variabile `JAVA_HOME` con quella cartella come valore. Poi rilancia la riga del passo 5 da un `Win+R` nuovo: le finestre già aperte non vedono la variabile.

## Configurazione

- `%LOCALAPPDATA%\meet-recorder\config.properties` — tua, vale in ogni progetto.
  - Chiave `cartella`: dove il recorder scrive i meet, default `%USERPROFILE%\meet-teams`. Per sceglierne un'altra all'installazione, aggiungila dopo `installa`, fra virgolette:
    ```
    cmd /k ""%USERPROFILE%\.claude\plugins\marketplaces\meet-secretary\recorder\meet-recorder.bat" installa "D:\meet""
    ```
  - Chiave `voce`: il tuo nome come lo scrive Teams nei sottotitoli, per esempio `ROSSI Mario`. Senza, il nome del segretario non lo sveglia. Si imposta anche dalla chat di follow-meet: «il mio nome in Teams è ROSSI Mario».
- `.meet-secretary/settings.ini` nella cartella del progetto — temi, parole chiave, glossario, nome del segretario e tempi del follower di quel progetto. Lo crea la skill al primo `/meet-secretary:follow-meet` e lo cambia su richiesta in chat; si può anche modificare a mano, e la pulizia lo rilegge ogni 10 s. Il file è personale: la skill lo mette in `.git/info/exclude` del progetto. Il plugin non porta temi di default.

## Dati dei meet

I file dei meet restano nella cartella dei meet, sul PC di chi registra. Il segretario li legge solo con gli script del plugin e non li copia altrove.

## Segnalazioni

Le issue di questo repo sono disattivate: un log o un estratto di un meet in una issue pubblica sarebbe leggibile da chiunque. Le segnalazioni vanno a chi ti ha dato il plugin, sul canale del team.

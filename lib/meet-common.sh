# meet-common.sh — funzioni comuni agli script del segretario (meet-clean, meet-follow, meet-catchup,
# meet-settings). Si carica con `source`; non stampa niente e non esce da sola, tranne meet_die.
#
# Un meet e' il file <stamp>.tsv scritto da meet-recorder nella cartella dei meet, col diario
# <stamp>.diag.tsv accanto. Lo stamp e' il nome del file senza .tsv, senza punti: tutti i file derivati
# (.rif.tsv, .eventi.tsv, .segnalibro-segui, ...) cominciano con lo stamp seguito da un punto.

MEET_DIARY_ALIVE_SECONDS=120

meet_die() {
    echo "$*" >&2
    exit 2
}

# Un path Windows (C:\meet) diventa un path di Git Bash (/c/meet); un path gia' di
# Git Bash resta com'e'.
meet_unix_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -u "$1"
    else
        printf '%s\n' "$1"
    fi
}

meet_windows_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$1"
    else
        printf '%s\n' "$1"
    fi
}

# Toglie gli spazi ai lati del valore della variabile nominata, senza subshell: su Git Bash ogni $(...)
# e' un fork da decine di millisecondi.
meet_trim_var() {
    local -n meet_trimmed="$1"
    meet_trimmed="${meet_trimmed#"${meet_trimmed%%[![:space:]]*}"}"
    meet_trimmed="${meet_trimmed%"${meet_trimmed##*[![:space:]]}"}"
}

# %LOCALAPPDATA%\meet-recorder\config.properties: il file della persona sulla macchina, che scrive
# meet-recorder. Chiavi: `cartella`, la cartella dei meet, e `voce`, il nome dell'utente come lo scrive
# Teams nei sottotitoli.
meet_recorder_config_file() {
    local local_app_data="${LOCALAPPDATA:-${USERPROFILE:-$HOME}/AppData/Local}"
    printf '%s/meet-recorder/config.properties\n' "$(meet_unix_path "$local_app_data")"
}

# Il valore di una chiave di config.properties: una riga per chiave, chiave=valore, senza escape, i
# backslash dei path restano come sono. Con la chiave ripetuta vale l'ultima, come per il registratore.
# Ritorna 1 se la chiave non c'e'.
meet_recorder_config_value() {
    local wanted="$1" config_file line key value found=1 configured=""
    config_file="$(meet_recorder_config_file)"
    [ -f "$config_file" ] || return 1
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%$'\r'}"
        line="${line#$'\xef\xbb\xbf'}"
        meet_trim_var line
        case "$line" in ''|'#'*|'!'*) continue ;; esac
        key="${line%%=*}"
        [ "$key" = "$line" ] && continue
        value="${line#*=}"
        meet_trim_var key
        [ "$key" = "$wanted" ] || continue
        meet_trim_var value
        configured="$value"
        found=0
    done < "$config_file"
    printf '%s\n' "$configured"
    return $found
}

# Il file di settings del segretario per un progetto: .meet-secretary/settings.ini nella cartella del
# progetto, la cartella corrente se non e' data. Sezioni [temi], [parole], [glossario], [segretario],
# [segui], una voce per riga, # per i commenti; lo legge anche la pulizia (ProjectSettings.java).
MEET_SETTINGS_RELATIVE=".meet-secretary/settings.ini"
MEET_DEFAULT_BATCH_SECONDS=60
meet_settings_file() {
    local project_dir="${1:-$PWD}"
    project_dir="$(meet_unix_path "$project_dir")"
    printf '%s/%s\n' "${project_dir%/}" "$MEET_SETTINGS_RELATIVE"
}

# Le voci di una sezione del file di settings, una per riga, senza spazi ai lati, senza righe vuote e
# commenti. Le righe prima della prima sezione e quelle di una sezione sconosciuta non contano; due
# sezioni con lo stesso nome si sommano. Un file assente non da' niente.
meet_settings_lines() {
    local file="$1" section="$2"
    [ -f "$file" ] || return 0
    LC_ALL=C awk -v wanted="$section" '
        {
            line = $0
            sub(/\r$/, "", line)
            if (NR == 1) sub(/^\xef\xbb\xbf/, "", line)
            sub(/^[ \t]+/, "", line)
            sub(/[ \t]+$/, "", line)
        }
        line == "" || line ~ /^#/ { next }
        line ~ /^\[[^]]+\]$/ {
            name = substr(line, 2, length(line) - 2)
            gsub(/^[ \t]+|[ \t]+$/, "", name)
            current = tolower(name)
            next
        }
        current == wanted { print line }' "$file"
}

# Il valore della prima voce `chiave: valore` di una sezione, senza spazi ai lati; vuoto se la voce c'e'
# senza valore. Ritorna 1 se la chiave non c'e'.
meet_settings_value() {
    local file="$1" section="$2" wanted="$3" line key value
    while IFS= read -r line; do
        case "$line" in *:*) ;; *) continue ;; esac
        key="${line%%:*}"
        meet_trim_var key
        [ "${key,,}" = "$wanted" ] || continue
        value="${line#*:}"
        meet_trim_var value
        printf '%s\n' "$value"
        return 0
    done < <(meet_settings_lines "$file" "$section")
    return 1
}

# La cartella dei meet: la chiave `cartella` di config.properties. Senza file o senza chiave vale il
# default del registratore, %USERPROFILE%\meet-teams.
meet_default_dir() {
    local configured
    configured="$(meet_recorder_config_value cartella)" || configured=""
    if [ -n "$configured" ]; then
        meet_unix_path "$configured"
    else
        printf '%s/meet-teams\n' "$(meet_unix_path "${USERPROFILE:-$HOME}")"
    fi
}

meet_diary_has_end() {
    [ -f "$1" ] && grep -qE $'^[^\t]*\tfine(\t|\r?$)' "$1"
}

# Il meet in corso: il <stamp>.tsv piu' recente per nome il cui diario non ha la riga `fine` ed e'
# stato scritto negli ultimi 2 minuti. Il registratore scrive la riga `stato` ogni 30 s: un diario
# fermo senza `fine` e' quello di un registratore caduto, non di un meet in corso.
meet_in_progress() {
    local dir="$1" candidate name diary now_seconds diary_seconds
    now_seconds="$(date +%s)"
    while IFS= read -r candidate; do
        name="${candidate##*/}"
        diary="${candidate%.tsv}.diag.tsv"
        [ -f "$diary" ] || continue
        meet_diary_has_end "$diary" && continue
        diary_seconds="$(date -r "$diary" +%s 2>/dev/null)" || continue
        (( now_seconds - diary_seconds <= MEET_DIARY_ALIVE_SECONDS )) || continue
        printf '%s\n' "$candidate"
        return 0
    done < <(ls -1 "$dir" 2>/dev/null | grep -E '^[^.]+\.tsv$' | sort -r | sed "s|^|$dir/|")
    return 1
}

# Risolve il meet da uno stamp, da un path del .tsv o, senza argomento, dal meet in corso. Imposta
# MEET_DIR, MEET_STAMP, MEET_FILE (il .tsv), MEET_DIARY e MEET_BASE (cartella/stamp, il prefisso dei
# file derivati). Ritorna 1 col motivo in MEET_ERROR.
meet_resolve() {
    local requested="$1" dir="$2"
    [ -n "$dir" ] || dir="$(meet_default_dir)"
    dir="$(meet_unix_path "$dir")"
    dir="${dir%/}"
    MEET_ERROR=""
    if [ -z "$requested" ]; then
        [ -d "$dir" ] || { MEET_ERROR="cartella dei meet non trovata: $dir"; return 1; }
        MEET_FILE="$(meet_in_progress "$dir")" || {
            MEET_ERROR="nessun meet in corso in $dir: nessun diario senza la riga fine scritto negli ultimi 2 minuti"
            return 1
        }
    else
        case "$requested" in
            */*|*\\*|*.tsv) MEET_FILE="$(meet_unix_path "$requested")" ;;
            *)              MEET_FILE="$dir/$requested.tsv" ;;
        esac
        case "$MEET_FILE" in *.tsv) ;; *) MEET_FILE="$MEET_FILE.tsv" ;; esac
        [ -f "$MEET_FILE" ] || { MEET_ERROR="file del meet non trovato: $MEET_FILE"; return 1; }
    fi
    MEET_DIR="${MEET_FILE%/*}"
    MEET_STAMP="${MEET_FILE##*/}"
    MEET_STAMP="${MEET_STAMP%.tsv}"
    case "$MEET_STAMP" in *.*) MEET_ERROR="non e' il file di un meet (lo stamp ha un punto): $MEET_FILE"; return 1 ;; esac
    MEET_BASE="$MEET_DIR/$MEET_STAMP"
    MEET_DIARY="$MEET_BASE.diag.tsv"
    return 0
}

# Le righe complete di un file che un altro processo sta scrivendo: la riga senza a capo in coda e'
# ancora a meta', e si legge al giro dopo.
meet_complete_lines() {
    local file="$1"
    [ -f "$file" ] || { echo 0; return; }
    wc -l < "$file" | tr -d '[:space:]'
}

# Il valore di una chiave chiave=valore in un file di segnalibro, o il default.
meet_bookmark_value() {
    local file="$1" key="$2" default="$3" line
    if [ -f "$file" ]; then
        while IFS= read -r line || [ -n "$line" ]; do
            line="${line%$'\r'}"
            if [ "${line%%=*}" = "$key" ]; then
                printf '%s\n' "${line#*=}"
                return
            fi
        done < "$file"
    fi
    printf '%s\n' "$default"
}

# Scrive un file per intero passando da un temporaneo, cosi' chi lo legge non lo trova mai a meta'.
# Il temporaneo porta il PID: due script che scrivono lo stesso file non si rubano il temporaneo.
meet_write_atomic() {
    local file="$1" content="$2"
    printf '%s' "$content" > "$file.tmp.$$" && mv -f "$file.tmp.$$" "$file"
}

# Vivo il processo Windows col PID dato e col nome dato (java.exe).
meet_windows_process_alive() {
    local pid="$1" image="$2"
    [[ "$pid" =~ ^[0-9]+$ ]] || return 1
    tasklist //FI "PID eq $pid" //NH //FO CSV 2>/dev/null | grep -qi "\"$image\""
}

# Il JDK con la versione piu' alta dalla 25 in su, cercato come fa meet-recorder.bat (le cartelle di
# installa-strumenti.bat, poi JAVA_HOME). Un JDK vale se ha il file release, java.exe e javac.exe: il
# lancio da sorgente usa il compilatore. Imposta MEET_JDK_HOME e MEET_JDK_VERSION.
MEET_JDK_MIN_MAJOR=25
meet_find_jdk() {
    MEET_JDK_HOME=""
    MEET_JDK_VERSION=""
    local best_major=0 root candidate version major
    local program_files
    program_files="$(meet_unix_path "${ProgramFiles:-C:\\Program Files}")"
    local profile
    profile="$(meet_unix_path "${USERPROFILE:-$HOME}")"
    local roots=("$program_files/Java" "$program_files/Eclipse Adoptium" "$program_files/Microsoft"
                 "$program_files/Zulu" "$program_files/Amazon Corretto" "$program_files/BellSoft"
                 "$profile/.jdks" "$profile/dev-tools")
    local candidates=()
    for root in "${roots[@]}"; do
        [ -d "$root" ] || continue
        for candidate in "$root"/*/; do
            [ -d "$candidate" ] && candidates+=("${candidate%/}")
        done
    done
    [ -n "${JAVA_HOME:-}" ] && candidates+=("$(meet_unix_path "$JAVA_HOME")")
    for candidate in "${candidates[@]}"; do
        [ -f "$candidate/release" ] && [ -f "$candidate/bin/java.exe" ] && [ -f "$candidate/bin/javac.exe" ] || continue
        version="$(grep -m1 '^JAVA_VERSION=' "$candidate/release" | cut -d= -f2 | tr -d '"\r')"
        [ -n "$version" ] || continue
        major="${version%%.*}"
        [ "$major" = "1" ] && major="$(echo "$version" | cut -d. -f2)"
        [[ "$major" =~ ^[0-9]+$ ]] || continue
        (( major >= MEET_JDK_MIN_MAJOR && major > best_major )) || continue
        best_major=$major
        MEET_JDK_HOME="$candidate"
        MEET_JDK_VERSION="$version"
    done
    [ -n "$MEET_JDK_HOME" ]
}

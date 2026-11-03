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

# Un path Windows (C:\meet) diventa un path di Git Bash (/c/meet); un path gia' di Git Bash resta com'e'.
# Le due funzioni *_var convertono la variabile nominata, senza subshell. Un path con la lettera del
# disco si converte in bash, gli altri con cygpath: su Git Bash ogni processo costa decine di
# millisecondi.
meet_unix_path_var() {
    local -n _mp_value="$1"
    if [[ "$_mp_value" =~ ^([A-Za-z]):[\\/](.*)$ ]]; then
        local _mp_drive="${BASH_REMATCH[1],,}" _mp_rest="${BASH_REMATCH[2]}"
        _mp_value="/$_mp_drive/${_mp_rest//\\//}"
    elif [[ "$_mp_value" == *\\* ]] && command -v cygpath >/dev/null 2>&1; then
        _mp_value="$(cygpath -u "$_mp_value")"
    fi
}

meet_windows_path_var() {
    local -n _mw_value="$1"
    if [[ "$_mw_value" =~ ^/([A-Za-z])(/(.*))?$ ]]; then
        local _mw_drive="${BASH_REMATCH[1]^^}" _mw_rest="${BASH_REMATCH[3]}"
        _mw_value="$_mw_drive:\\${_mw_rest//\//\\}"
    elif [[ "$_mw_value" == /* ]] && command -v cygpath >/dev/null 2>&1; then
        _mw_value="$(cygpath -w "$_mw_value")"
    fi
}

meet_unix_path() {
    local _mp_path="$1"
    meet_unix_path_var _mp_path
    printf '%s\n' "$_mp_path"
}

meet_windows_path() {
    local _mw_path="$1"
    meet_windows_path_var _mw_path
    printf '%s\n' "$_mw_path"
}

# Toglie gli spazi ai lati del valore della variabile nominata, senza subshell.
meet_trim_var() {
    local -n _mt_value="$1"
    _mt_value="${_mt_value#"${_mt_value%%[![:space:]]*}"}"
    _mt_value="${_mt_value%"${_mt_value##*[![:space:]]}"}"
}

# %LOCALAPPDATA%\meet-recorder\config.properties: il file della persona sulla macchina, che scrive
# meet-recorder. Chiavi: `cartella`, la cartella dei meet, e `voce`, il nome dell'utente come lo scrive
# Teams nei sottotitoli. meet_recorder_config_file_var mette il path nella variabile nominata.
meet_recorder_config_file_var() {
    local -n _mc_file="$1"
    _mc_file="${LOCALAPPDATA:-${USERPROFILE:-$HOME}/AppData/Local}"
    meet_unix_path_var _mc_file
    _mc_file="${_mc_file%/}/meet-recorder/config.properties"
}

meet_recorder_config_file() {
    local _mc_path
    meet_recorder_config_file_var _mc_path
    printf '%s\n' "$_mc_path"
}

# Il valore di una chiave di config.properties, nella variabile nominata: una riga per chiave,
# chiave=valore, senza escape, i backslash dei path restano come sono. Con la chiave ripetuta vale
# l'ultima, come per il registratore. Ritorna 1 se la chiave non c'e'.
meet_recorder_config_get() {
    local _mc_wanted="$1" _mc_config _mc_line _mc_key _mc_found=1
    local -n _mc_result="$2"
    _mc_result=""
    meet_recorder_config_file_var _mc_config
    [ -f "$_mc_config" ] || return 1
    while IFS= read -r _mc_line || [ -n "$_mc_line" ]; do
        _mc_line="${_mc_line%$'\r'}"
        _mc_line="${_mc_line#$'\xef\xbb\xbf'}"
        meet_trim_var _mc_line
        case "$_mc_line" in ''|'#'*|'!'*) continue ;; esac
        _mc_key="${_mc_line%%=*}"
        [ "$_mc_key" = "$_mc_line" ] && continue
        meet_trim_var _mc_key
        [ "$_mc_key" = "$_mc_wanted" ] || continue
        _mc_result="${_mc_line#*=}"
        meet_trim_var _mc_result
        _mc_found=0
    done < "$_mc_config"
    return $_mc_found
}

# Il file di settings del segretario per un progetto: .meet-secretary/settings.ini nella cartella del
# progetto. Sezioni [temi], [parole], [glossario], [segretario], [segui], una voce per riga, # per i
# commenti; lo legge anche la pulizia, con le stesse regole (ProjectSettings.java).
MEET_SETTINGS_RELATIVE=".meet-secretary/settings.ini"
MEET_DEFAULT_BATCH_SECONDS=60
declare -gA MEET_SETTINGS=()
MEET_SETTINGS_FILE=""

# Legge il file di settings del progetto in MEET_SETTINGS: una chiave per sezione, col nome in
# minuscolo, e le sue voci una per riga, senza spazi ai lati. Le righe vuote e i commenti non contano,
# e nemmeno le righe prima della prima sezione; due sezioni con lo stesso nome si sommano. Un file
# assente da' le sezioni vuote. Senza cartella vale la cartella corrente; il path del file va in
# MEET_SETTINGS_FILE.
meet_settings_load() {
    local _ms_dir="${1:-$PWD}" _ms_line _ms_section="" _ms_first=1
    local _ms_header='^\[([^]]+)\]$'
    meet_unix_path_var _ms_dir
    MEET_SETTINGS_FILE="${_ms_dir%/}/$MEET_SETTINGS_RELATIVE"
    MEET_SETTINGS=()
    [ -f "$MEET_SETTINGS_FILE" ] || return 0
    while IFS= read -r _ms_line || [ -n "$_ms_line" ]; do
        _ms_line="${_ms_line%$'\r'}"
        if (( _ms_first )); then
            _ms_line="${_ms_line#$'\xef\xbb\xbf'}"
            _ms_first=0
        fi
        meet_trim_var _ms_line
        [[ -z "$_ms_line" || "$_ms_line" == '#'* ]] && continue
        if [[ "$_ms_line" =~ $_ms_header ]]; then
            _ms_section="${BASH_REMATCH[1]}"
            meet_trim_var _ms_section
            _ms_section="${_ms_section,,}"
            continue
        fi
        [ -n "$_ms_section" ] && MEET_SETTINGS[$_ms_section]+="$_ms_line"$'\n'
    done < "$MEET_SETTINGS_FILE"
    return 0
}

# Le voci di una sezione caricata, nell'array nominato.
meet_settings_lines_into() {
    local -n _ms_lines="$2"
    local _ms_joined="${MEET_SETTINGS[$1]:-}"
    _ms_lines=()
    [ -n "$_ms_joined" ] && mapfile -t _ms_lines <<< "${_ms_joined%$'\n'}"
    return 0
}

# Il valore della prima voce `chiave: valore` di una sezione caricata, senza spazi ai lati, nella
# variabile nominata; vuoto se la voce c'e' senza valore. Ritorna 1 se la chiave non c'e'.
meet_settings_value_into() {
    local _ms_wanted="$2" _ms_entry _ms_key
    local -n _ms_value="$3"
    _ms_value=""
    while IFS= read -r _ms_entry; do
        [[ "$_ms_entry" == *:* ]] || continue
        _ms_key="${_ms_entry%%:*}"
        meet_trim_var _ms_key
        [ "${_ms_key,,}" = "$_ms_wanted" ] || continue
        _ms_value="${_ms_entry#*:}"
        meet_trim_var _ms_value
        return 0
    done <<< "${MEET_SETTINGS[$1]:-}"
    return 1
}

# Le voci di una sezione caricata unite da un separatore, nella variabile nominata; il testo dato se
# la sezione e' vuota.
meet_settings_join_into() {
    local _ms_separator="$2" _ms_empty="$3" _ms_entry _ms_joined=""
    local -n _ms_text="$4"
    local -a _ms_entries
    meet_settings_lines_into "$1" _ms_entries
    for _ms_entry in "${_ms_entries[@]}"; do
        _ms_joined+="${_ms_joined:+$_ms_separator}$_ms_entry"
    done
    _ms_text="${_ms_joined:-$_ms_empty}"
}

# La cartella dei meet: la chiave `cartella` di config.properties. Senza file o senza chiave vale il
# default del registratore, %USERPROFILE%\meet-teams.
meet_default_dir() {
    local configured
    meet_recorder_config_get cartella configured || configured=""
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

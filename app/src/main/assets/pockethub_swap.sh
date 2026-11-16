#!/system/bin/sh

FILE=/data/swapfile
TEMP=/data/.pockethub-swapfile.new
BACKUP=/data/.pockethub-swapfile.old
PROGRESS=/data/local/tmp/pockethub-swap-progress
PRIORITY=5
MODE=${1:-status}
SIZE_GB=${2:-0}

set_phase() { echo "$1" > "$PROGRESS"; }
clear_phase() { rm -f "$PROGRESS"; }

case "$MODE" in
  status)
    FILE_KB=$(awk '$1 == "/data/swapfile" {print $3}' /proc/swaps)
    ZRAM_KB=$(awk '$1 == "/dev/block/zram0" {print $3}' /proc/swaps)
    FILE_BYTES=$(stat -c %s "$FILE" 2>/dev/null || echo 0)
    PHASE=$(cat "$PROGRESS" 2>/dev/null)
    echo "FILE_KB=${FILE_KB:-0} ZRAM_KB=${ZRAM_KB:-0} FILE_BYTES=$FILE_BYTES PHASE=${PHASE:-IDLE}"
    echo RESULT=OK
    ;;

  boot)
    clear_phase
    if [ ! -f "$FILE" ]; then
      echo RESULT=ERROR:SWAPFILE_MISSING
    elif grep -q '^/data/swapfile ' /proc/swaps; then
      echo RESULT=OK
    elif swapon -p "$PRIORITY" "$FILE"; then
      echo RESULT=OK
    else
      echo RESULT=ERROR:SWAPON_FAILED
    fi
    ;;

  apply)
    set_phase CHECKING
    case "$SIZE_GB" in
      ''|*[!0-9]*) clear_phase; echo RESULT=ERROR:INVALID_SIZE; exit 0 ;;
    esac
    if [ "$SIZE_GB" -lt 0 ] || [ "$SIZE_GB" -gt 16 ]; then
      clear_phase
      echo RESULT=ERROR:INVALID_SIZE
      exit 0
    fi

    ACTIVE=$(grep -q '^/data/swapfile ' /proc/swaps && echo 1 || echo 0)
    USED_KB=$(awk '$1 == "/data/swapfile" {print $4}' /proc/swaps)
    USED_KB=${USED_KB:-0}
    AVAILABLE_KB=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
    AVAILABLE_KB=${AVAILABLE_KB:-0}

    if [ "$ACTIVE" = 1 ] && [ "$USED_KB" -gt 0 ] && [ "$AVAILABLE_KB" -lt "$((USED_KB + 262144))" ]; then
      echo RESULT=ERROR:NOT_ENOUGH_FREE_RAM_TO_RESIZE
      clear_phase
      exit 0
    fi

    if [ "$SIZE_GB" -eq 0 ]; then
      set_phase DISABLING
      if [ "$ACTIVE" = 1 ] && ! swapoff "$FILE"; then
        clear_phase
        echo RESULT=ERROR:SWAPOFF_FAILED
        exit 0
      fi
      rm -f "$FILE" "$TEMP" "$BACKUP"
      clear_phase
      echo RESULT=OK
      exit 0
    fi

    TARGET_KB=$((SIZE_GB * 1024 * 1024))
    FREE_KB=$(df -Pk /data | awk 'END {print $4}')
    FREE_KB=${FREE_KB:-0}
    if [ "$FREE_KB" -lt "$((TARGET_KB + 262144))" ]; then
      echo RESULT=ERROR:NOT_ENOUGH_STORAGE
      clear_phase
      exit 0
    fi

    CURRENT_KB=$(awk '$1 == "/data/swapfile" {print $3}' /proc/swaps)
    CURRENT_KB=${CURRENT_KB:-0}
    if [ "$ACTIVE" = 1 ] && [ "$CURRENT_KB" -ge "$((TARGET_KB - 1024))" ] && [ "$CURRENT_KB" -le "$((TARGET_KB + 1024))" ]; then
      clear_phase
      echo RESULT=OK
      exit 0
    fi

    if [ "$ACTIVE" = 1 ] && ! swapoff "$FILE"; then
      clear_phase
      echo RESULT=ERROR:SWAPOFF_FAILED
      exit 0
    fi

    rm -f "$TEMP" "$BACKUP"
    set_phase ALLOCATING
    if ! dd if=/dev/zero of="$TEMP" bs=1M count="$((SIZE_GB * 1024))" conv=fsync; then
      rm -f "$TEMP"
      if [ "$ACTIVE" = 1 ]; then swapon -p "$PRIORITY" "$FILE"; fi
      clear_phase
      echo RESULT=ERROR:FILE_ALLOCATION_FAILED
      exit 0
    fi
    set_phase PREPARING
    if ! chmod 600 "$TEMP" || ! mkswap "$TEMP"; then
      rm -f "$TEMP"
      if [ "$ACTIVE" = 1 ]; then swapon -p "$PRIORITY" "$FILE"; fi
      clear_phase
      echo RESULT=ERROR:MKSWAP_FAILED
      exit 0
    fi

    if [ -e "$FILE" ] && ! mv "$FILE" "$BACKUP"; then
      rm -f "$TEMP"
      if [ "$ACTIVE" = 1 ]; then swapon -p "$PRIORITY" "$FILE"; fi
      clear_phase
      echo RESULT=ERROR:BACKUP_FAILED
      exit 0
    fi
    if ! mv "$TEMP" "$FILE"; then
      [ -e "$BACKUP" ] && mv "$BACKUP" "$FILE"
      if [ "$ACTIVE" = 1 ]; then swapon -p "$PRIORITY" "$FILE"; fi
      clear_phase
      echo RESULT=ERROR:RENAME_FAILED
      exit 0
    fi
    set_phase ACTIVATING
    if ! swapon -p "$PRIORITY" "$FILE"; then
      rm -f "$FILE"
      [ -e "$BACKUP" ] && mv "$BACKUP" "$FILE"
      if [ "$ACTIVE" = 1 ]; then swapon -p "$PRIORITY" "$FILE"; fi
      clear_phase
      echo RESULT=ERROR:SWAPON_FAILED
      exit 0
    fi
    rm -f "$BACKUP"
    clear_phase
    echo RESULT=OK
    ;;

  *)
    echo RESULT=ERROR:INVALID_ACTION
    ;;
esac

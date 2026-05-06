#!/system/bin/sh
set -e

REGION="${1:-}"
case "$REGION" in
    us|stock) ;;
    *) echo "usage: $0 {us|stock}" >&2; exit 1 ;;
esac

REPO_RAW="${F21_BANDS_RAW:-https://raw.githubusercontent.com/alltechdev/f21_bands_swap/feat/termux-one-liner}"

WORK="${HOME:-/data/local/tmp}/.f21bands"
mkdir -p "$WORK" || {
    echo "ERROR: cannot create work dir $WORK" >&2
    exit 1
}

unset LD_PRELOAD

NEED=""
command -v curl >/dev/null 2>&1 || NEED="$NEED curl"
command -v tar  >/dev/null 2>&1 || NEED="$NEED tar"
command -v xz   >/dev/null 2>&1 || NEED="$NEED xz-utils"

if [ -n "$NEED" ]; then
    APT=/data/data/com.termux/files/usr/bin/apt
    if [ -x "$APT" ]; then
        echo "[1/7] Installing missing Termux packages:$NEED"
        DEBIAN_FRONTEND=noninteractive "$APT" install -y \
            -o Dpkg::Options::=--force-confold \
            -o Dpkg::Options::=--force-confdef \
            $NEED </dev/null
    else
        echo "ERROR: missing tools$NEED and apt not available. Install them first." >&2
        exit 1
    fi
else
    echo "[1/7] Termux packages curl/tar/xz already present, skipping install."
fi

SU=""
for p in /product/bin/su /system/bin/su /system/xbin/su /sbin/su /vendor/bin/su; do
    [ -x "$p" ] && { SU="$p"; break; }
done
if [ -z "$SU" ]; then
    echo "ERROR: no real su binary found. Magisk root must be installed." >&2
    exit 1
fi

echo "[2/7] Requesting Magisk root (click Allow on the prompt if it appears)..."
"$SU" -c id || {
    echo "ERROR: 'su -c id' failed. Magisk grant denied?" >&2
    exit 1
}

echo "[3/7] Downloading $REGION bands (~21 MiB compressed)..."
curl -fsSL "$REPO_RAW/bands/$REGION.tar.xz" -o "$WORK/bands.tar.xz"

echo "[4/7] Extracting partition images (~270 MiB uncompressed)..."
tar -xJf "$WORK/bands.tar.xz" -C "$WORK/"
rm -f "$WORK/bands.tar.xz"

for f in md1img_a.bin nvcfg.bin nvdata.bin nvram.bin; do
    [ -f "$WORK/$f" ] || {
        echo "ERROR: blob $f missing after extract; archive may be corrupt." >&2
        exit 1
    }
done

echo "[5/7] Downloading helper binary and swap script..."
curl -fsSL "$REPO_RAW/tools/mmc_probe" -o "$WORK/mmc_probe"
curl -fsSL "$REPO_RAW/tools/swap.sh"   -o "$WORK/swap.sh"
chmod +x "$WORK/mmc_probe" "$WORK/swap.sh" 2>/dev/null || true

echo "[6/7] Staging blobs to /sdcard and tools to /data/local/tmp (requires root)..."
cat > "$WORK/.stage.sh" << EOS
#!/system/bin/sh
set -e
cp "$WORK/md1img_a.bin" /sdcard/md1img_a.bin
cp "$WORK/nvcfg.bin"    /sdcard/nvcfg.bin
cp "$WORK/nvdata.bin"   /sdcard/nvdata.bin
cp "$WORK/nvram.bin"    /sdcard/nvram.bin
cp "$WORK/mmc_probe"    /data/local/tmp/mmc_probe
cp "$WORK/swap.sh"      /data/local/tmp/swap.sh
chmod 755 /data/local/tmp/mmc_probe /data/local/tmp/swap.sh
EOS
chmod +x "$WORK/.stage.sh"
"$SU" -c "sh '$WORK/.stage.sh'"

echo "[7/7] Running swap. Device will sysrq-reboot in ~6 seconds."
echo "      After boot, verify with:  service call iphonesubinfo 1"
echo
"$SU" -mm -c 'sh /data/local/tmp/swap.sh'

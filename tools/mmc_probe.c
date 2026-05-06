/*
 * mmc_probe -- minimal MMC_IOC_CMD helper for F21 Pro band swap.
 *
 * Sends specific JEDEC eMMC commands the kernel block layer doesn't expose
 * to userspace: SEND_EXT_CSD (CMD8), SWITCH (CMD6), SEND_WRITE_PROT_TYPE
 * (CMD31), CLR_WRITE_PROT (CMD29), SET_WRITE_PROT (CMD28). Diagnostic
 * READ_SINGLE_BLOCK / WRITE_BLOCK (CMD17 / CMD24) included for completeness;
 * those time out on F21 Pro's running-Android kernel for normal user-area
 * sectors and aren't on the band-swap success path.
 *
 * Build (Android NDK, arm64, API 30+ to avoid the static-link TLS-alignment
 * issue against API 21-23 bionic):
 *
 *   $ANDROID_HOME/ndk/<ver>/toolchains/llvm/prebuilt/linux-x86_64/bin/\
 *       aarch64-linux-android30-clang -O2 -o mmc_probe mmc_probe.c
 *
 * See tools/build.sh for an autodetecting wrapper.
 *
 * Run under `su -mm` (mount-master). Plain `su` drops into the magisk:s0
 * SELinux domain which can't open /dev/block/mmcblk0; mount-master can.
 *
 * License: same as the rest of the f21_bands_swap repo.
 */

#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <linux/mmc/ioctl.h>

#define MMC_GO_IDLE_STATE         0
#define MMC_SWITCH                6
#define MMC_SEND_EXT_CSD          8
#define MMC_READ_SINGLE_BLOCK     17
#define MMC_WRITE_BLOCK           24
#define MMC_SET_WRITE_PROT        28
#define MMC_CLR_WRITE_PROT        29
#define MMC_SEND_WRITE_PROT_TYPE  31

#define MMC_RSP_PRESENT (1 << 0)
#define MMC_RSP_CRC     (1 << 2)
#define MMC_RSP_BUSY    (1 << 3)
#define MMC_RSP_OPCODE  (1 << 4)
#define MMC_CMD_BC      (2 << 5)
#define MMC_CMD_AC      (0 << 5)
#define MMC_CMD_ADTC    (1 << 5)
#define MMC_RSP_NONE    (0)
#define MMC_RSP_R1      (MMC_RSP_PRESENT | MMC_RSP_CRC | MMC_RSP_OPCODE)
#define MMC_RSP_R1B     (MMC_RSP_PRESENT | MMC_RSP_CRC | MMC_RSP_OPCODE | MMC_RSP_BUSY)

static void decode_r1(unsigned int r) {
    printf("  R1 = 0x%08x", r);
    if (r & 0x80000000u) printf(" OUT_OF_RANGE");
    if (r & 0x40000000u) printf(" ADDRESS_ERROR");
    if (r & 0x20000000u) printf(" BLOCK_LEN_ERROR");
    if (r & 0x08000000u) printf(" ERASE_PARAM");
    if (r & 0x04000000u) printf(" WP_VIOLATION");
    if (r & 0x02000000u) printf(" CARD_IS_LOCKED");
    if (r & 0x01000000u) printf(" LOCK_UNLOCK_FAILED");
    if (r & 0x00800000u) printf(" COM_CRC_ERROR");
    if (r & 0x00400000u) printf(" ILLEGAL_COMMAND");
    if (r & 0x00080000u) printf(" ERROR");
    if (r & 0x00010000u) printf(" CID_CSD_OVERWRITE");
    if (r & 0x00008000u) printf(" WP_ERASE_SKIP");
    int state = (r >> 9) & 0xf;
    static const char *states[] = {
        "idle", "ready", "ident", "stby", "tran", "data",
        "rcv", "prg", "dis", "btst", "slp"
    };
    if (state < 11) printf(" state=%s", states[state]);
    printf("\n");
}

static void usage(const char *p) {
    fprintf(stderr,
        "usage:\n"
        "  %s <dev> ext_csd                              # read EXT_CSD, print WP-relevant fields\n"
        "  %s <dev> read_wp <sector>                     # CMD31 SEND_WRITE_PROT_TYPE\n"
        "  %s <dev> clear_wp <sector>                    # CMD29 CLR_WRITE_PROT\n"
        "  %s <dev> set_wp <sector>                      # CMD28 SET_WRITE_PROT\n"
        "  %s <dev> switch <access> <index> <value>      # CMD6 SWITCH\n"
        "                                                #   access: 0=cmd-set, 1=set-bits,\n"
        "                                                #          2=clear-bits, 3=write-byte\n"
        "  %s <dev> reinit                               # CMD0 GO_IDLE_STATE -- forces eMMC\n"
        "                                                #   into idle and lets the kernel's\n"
        "                                                #   timeout-recovery re-init the card,\n"
        "                                                #   which resets the volatile session\n"
        "                                                #   state including the per-power-cycle\n"
        "                                                #   CMD29 honor quota.\n"
        "  %s <dev> read1 <sector>                       # CMD17 SINGLE_BLOCK_READ (diagnostic)\n"
        "  %s <dev> writepat <sector> <byte>             # CMD24 WRITE_BLOCK pat (diagnostic)\n",
        p, p, p, p, p, p, p, p);
}

int main(int argc, char **argv) {
    if (argc < 3) { usage(argv[0]); return 1; }
    int fd = open(argv[1], O_RDWR);
    if (fd < 0) { perror("open"); return 1; }
    const char *cmd = argv[2];

    if (!strcmp(cmd, "ext_csd")) {
        unsigned char ext[512] = {0};
        struct mmc_ioc_cmd c = {0};
        c.opcode = MMC_SEND_EXT_CSD;
        c.flags = MMC_RSP_R1 | MMC_CMD_ADTC;
        c.blksz = 512;
        c.blocks = 1;
        mmc_ioc_cmd_set_data(c, ext);
        if (ioctl(fd, MMC_IOC_CMD, &c) < 0) { perror("ext_csd"); return 1; }
        printf("USR_WP[171]=0x%02x  HC_WP_GRP_SIZE[221]=0x%02x  "
               "HC_ERASE_GRP_SIZE[224]=0x%02x  PARTITION_SETTING_COMPLETED[155]=0x%02x  "
               "ERASE_GROUP_DEF[175]=0x%02x  BOOT_WP[173]=0x%02x\n",
               ext[171], ext[221], ext[224], ext[155], ext[175], ext[173]);
        return 0;
    }

    if (!strcmp(cmd, "reinit")) {
        struct mmc_ioc_cmd c = {0};
        c.write_flag = 0;
        c.opcode = MMC_GO_IDLE_STATE;
        c.arg = 0;
        c.flags = MMC_RSP_NONE | MMC_CMD_BC;
        int r = ioctl(fd, MMC_IOC_CMD, &c);
        printf("CMD0 GO_IDLE_STATE: ioctl=%d (errno=%d ETIMEDOUT=110 is expected)\n",
               r, r < 0 ? errno : 0);
        unsigned char ext[512] = {0};
        struct mmc_ioc_cmd c2 = {0};
        c2.opcode = MMC_SEND_EXT_CSD;
        c2.flags = MMC_RSP_R1 | MMC_CMD_ADTC;
        c2.blksz = 512;
        c2.blocks = 1;
        mmc_ioc_cmd_set_data(c2, ext);
        int csd_ok = 0;
        int last_errno = 0;
        int waited_ms = 0;
        for (int i = 0; i < 30; i++) {
            usleep(200 * 1000);
            waited_ms += 200;
            if (ioctl(fd, MMC_IOC_CMD, &c2) == 0) { csd_ok = 1; break; }
            last_errno = errno;
        }
        if (!csd_ok) {
            errno = last_errno;
            fprintf(stderr, "post-reinit ext_csd read failed after %dms: ", waited_ms);
            perror(NULL);
            return 1;
        }
        printf("post-reinit USR_WP[171]=0x%02x BOOT_WP[173]=0x%02x (recovered in %dms)\n",
               ext[171], ext[173], waited_ms);
        return 0;
    }

    if (!strcmp(cmd, "switch") && argc >= 6) {
        unsigned int access = strtoul(argv[3], NULL, 0);
        unsigned int index  = strtoul(argv[4], NULL, 0);
        unsigned int value  = strtoul(argv[5], NULL, 0);
        unsigned int arg = (access << 24) | (index << 16) | (value << 8) | 0x01;
        struct mmc_ioc_cmd c = {0};
        c.write_flag = 1;
        c.opcode = MMC_SWITCH;
        c.arg = arg;
        c.flags = MMC_RSP_R1B | MMC_CMD_AC;
        if (ioctl(fd, MMC_IOC_CMD, &c) < 0) { perror("switch"); return 1; }
        printf("SWITCH access=%u index=%u value=0x%02x  arg=0x%08x:\n",
               access, index, value, arg);
        decode_r1(c.response[0]);
        return 0;
    }

    if (!strcmp(cmd, "read_wp") && argc >= 4) {
        unsigned long sec = strtoul(argv[3], NULL, 0);
        unsigned char r[8] = {0};
        struct mmc_ioc_cmd c = {0};
        c.opcode = MMC_SEND_WRITE_PROT_TYPE;
        c.arg = sec;
        c.flags = MMC_RSP_R1 | MMC_CMD_ADTC;
        c.blksz = 8;
        c.blocks = 1;
        mmc_ioc_cmd_set_data(c, r);
        if (ioctl(fd, MMC_IOC_CMD, &c) < 0) { perror("read_wp"); return 1; }
        printf("WP @ %lu: ", sec);
        for (int i = 0; i < 8; i++) printf("%02x ", r[i]);
        printf("\n");
        decode_r1(c.response[0]);
        return 0;
    }

    if (!strcmp(cmd, "clear_wp") && argc >= 4) {
        unsigned long sec = strtoul(argv[3], NULL, 0);
        struct mmc_ioc_cmd c = {0};
        c.write_flag = 1;
        c.opcode = MMC_CLR_WRITE_PROT;
        c.arg = sec;
        c.flags = MMC_RSP_R1B | MMC_CMD_AC;
        if (ioctl(fd, MMC_IOC_CMD, &c) < 0) { perror("clear_wp"); return 1; }
        printf("CLR_WRITE_PROT @ %lu:\n", sec);
        decode_r1(c.response[0]);
        return 0;
    }

    if (!strcmp(cmd, "set_wp") && argc >= 4) {
        unsigned long sec = strtoul(argv[3], NULL, 0);
        struct mmc_ioc_cmd c = {0};
        c.write_flag = 1;
        c.opcode = MMC_SET_WRITE_PROT;
        c.arg = sec;
        c.flags = MMC_RSP_R1B | MMC_CMD_AC;
        if (ioctl(fd, MMC_IOC_CMD, &c) < 0) { perror("set_wp"); return 1; }
        printf("SET_WRITE_PROT @ %lu:\n", sec);
        decode_r1(c.response[0]);
        return 0;
    }

    if (!strcmp(cmd, "read1") && argc >= 4) {
        unsigned long sec = strtoul(argv[3], NULL, 0);
        unsigned char b[512] = {0};
        struct mmc_ioc_cmd c = {0};
        c.opcode = MMC_READ_SINGLE_BLOCK;
        c.arg = sec;
        c.flags = MMC_RSP_R1 | MMC_CMD_ADTC;
        c.blksz = 512;
        c.blocks = 1;
        mmc_ioc_cmd_set_data(c, b);
        if (ioctl(fd, MMC_IOC_CMD, &c) < 0) { perror("read1"); return 1; }
        printf("READ_SINGLE_BLOCK @ %lu:\n", sec);
        decode_r1(c.response[0]);
        for (int i = 0; i < 32; i++) printf("%02x ", b[i]);
        printf("...\n");
        return 0;
    }

    if (!strcmp(cmd, "writepat") && argc >= 5) {
        unsigned long sec = strtoul(argv[3], NULL, 0);
        unsigned char pat = (unsigned char)strtoul(argv[4], NULL, 0);
        unsigned char b[512];
        memset(b, pat, 512);
        struct mmc_ioc_cmd c = {0};
        c.write_flag = 1;
        c.opcode = MMC_WRITE_BLOCK;
        c.arg = sec;
        c.flags = MMC_RSP_R1 | MMC_CMD_ADTC;
        c.blksz = 512;
        c.blocks = 1;
        mmc_ioc_cmd_set_data(c, b);
        if (ioctl(fd, MMC_IOC_CMD, &c) < 0) { perror("writepat"); return 1; }
        printf("CMD24 WRITE_BLOCK @ %lu pat=0x%02x:\n", sec, pat);
        decode_r1(c.response[0]);
        return 0;
    }

    usage(argv[0]);
    return 1;
}

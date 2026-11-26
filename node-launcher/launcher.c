/*
 * Tiny launcher that runs Node.js (nodejs-mobile's libnode.so) as a normal process:
 *
 *     libstremionode.so <script.js> [args...]
 *
 * It is packaged as a "lib*.so" in the APK so Android extracts it, executable, into the app's
 * native library directory (the one place an app may exec its own binaries from). Running the
 * Stremio streaming server in its own process means a crash there can't take the app down, and
 * the server can be stopped/restarted (node::Start can only run once per process).
 */
#include <stdlib.h>
#include <string.h>

/* node::Start(int, char**) from libnode.so (C++ symbol). */
extern int _ZN4node5StartEiPPc(int argc, char **argv);

int main(int argc, char **argv) {
    /* Node requires all argv strings to live in one contiguous block. */
    size_t total = 0;
    for (int i = 0; i < argc; i++) total += strlen(argv[i]) + 1;
    char *block = calloc(total, 1);
    char **args = calloc((size_t)argc + 1, sizeof(char *));
    if (!block || !args) return 70;
    char *p = block;
    for (int i = 0; i < argc; i++) {
        size_t n = strlen(argv[i]) + 1;
        memcpy(p, argv[i], n);
        args[i] = p;
        p += n;
    }
    return _ZN4node5StartEiPPc(argc, args);
}

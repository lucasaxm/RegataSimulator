package com.boatarde.regatasimulator.migration;

import java.nio.file.Path;

/** Explicit fresh-target restore; never loads profiles, environment files or application bootstrap. */
public final class RestoreCli {
    private RestoreCli() { }
    public static void main(String[] args) {
        if (args.length!=5 || !args[0].equals("--ack-all-writers-stopped") || !args[1].equals("true") || !args[2].equals("restore")) {
            System.err.println("Explicit writer-freeze acknowledgment, bundle and fresh target required"); System.exit(2);
        }
        try { RecoveryBundle.restore(Path.of(args[3]),Path.of(args[4])); }
        catch (Exception e) { System.err.println("Restore refused/failed; preserve bundle and inspect candidate without resuming writers"); System.exit(2); }
    }
}
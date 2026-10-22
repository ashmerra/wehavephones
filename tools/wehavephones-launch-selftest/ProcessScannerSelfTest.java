package com.wehavephones.mobile;

import java.util.concurrent.TimeUnit;

/**
 * Host test for ProcessScanner/WeHavePhonesLauncherReaper against the real /proc:
 * spawns processes whose argv[0] looks like Wine's WoW and Battle.net command
 * lines (bash exec -a) and checks detection. Regression for /proc files
 * reporting length 0 (the scanner used to return no processes at all).
 */
public final class ProcessScannerSelfTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        java.lang.Process wow = spawn("C:\\Program Files (x86)\\World of Warcraft\\_retail_\\Wow-ARM64.exe");
        java.lang.Process bnet = spawn("C:\\Program Files (x86)\\Battle.net\\Battle.net.exe");
        try {
            TimeUnit.MILLISECONDS.sleep(500);
            check("sameUidProcesses sees processes", !ProcessScanner.sameUidProcesses().isEmpty());
            ProcessScanner.Entry found = ProcessScanner.findWowProcess();
            check("findWowProcess finds Wow-ARM64.exe", found != null && found.pid == (int)wow.pid());
            check("isLauncherRunning sees Battle.net.exe", ProcessScanner.isLauncherRunning());
            check("guest processes lock container switching without an Activity", ProcessScanner.isWineSessionRunning());
            check("WoW is never a launcher target", found != null && !WeHavePhonesLauncherReaper.isLauncherProcess(found));
            check("reapNow closes only the launcher", WeHavePhonesLauncherReaper.reapNow() >= 1);
            TimeUnit.MILLISECONDS.sleep(300);
            check("launcher gone", !bnet.isAlive());
            check("game alive", wow.isAlive());
        }
        finally {
            wow.destroyForcibly();
            bnet.destroyForcibly();
        }
        TimeUnit.MILLISECONDS.sleep(300);
        check("reapNow refuses without a game", WeHavePhonesLauncherReaper.reapNow() == 0);
        checkClassification();
        checkLeftovers();
        check("zombie wineserver does not lock switching", !ProcessScanner.isWineSessionProcess(
                new ProcessScanner.Entry(1, "", "wineserver", "")));
        check("live wineserver locks switching", ProcessScanner.isWineSessionProcess(
                new ProcessScanner.Entry(1, "/opt/wine/bin/wineserver", "wineserver", "/opt/wine/bin/wineserver")));
        check("Android shell mentioning a game does not lock switching", !ProcessScanner.isWineSessionProcess(
                new ProcessScanner.Entry(1, "sh -c Wow.exe", "sh", "/system/bin/sh")));
        System.out.println("[process-scanner-selftest] " + failures + " failure(s)");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * A real zombie named like a Wine service (the kernel takes the name from
     * the executed file, so sleep runs through a winedevice.exe symlink): bash
     * forks it and then execs into sleep, which never reaps it. The live
     * process of the same name is part of a session and must not be listed.
     */
    private static void checkLeftovers() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("wehavephones-leftover");
        java.nio.file.Path service = dir.resolve("winedevice.exe");
        java.nio.file.Files.createSymbolicLink(service, java.nio.file.Paths.get("/bin/sleep"));
        java.lang.Process holder = new ProcessBuilder("bash", "-c",
                "(exec \"$0\" 0) & exec \"$0\" 5", service.toString()).start();
        try {
            TimeUnit.MILLISECONDS.sleep(500);
            int holderPid = (int)holder.pid();
            boolean zombieListed = false, liveListed = false;
            for (ProcessScanner.Entry entry : ProcessScanner.leftoverProcesses()) {
                if (entry.pid == holderPid) liveListed = true;
                else if (ProcessScanner.processState(entry.pid) == 'Z') zombieListed = true;
            }
            check("zombie Wine service is a leftover", zombieListed);
            check("live Wine service is not a leftover", !liveListed);
        }
        finally {
            holder.destroyForcibly();
            java.nio.file.Files.deleteIfExists(service);
            java.nio.file.Files.deleteIfExists(dir);
        }
        check("zombie without a guest name is not a leftover", !ProcessScanner.isLeftoverProcess(
                new ProcessScanner.Entry(1, "", "sh", ""), 'Z', ""));
        check("process with a command line is not a leftover", !ProcessScanner.isLeftoverProcess(
                new ProcessScanner.Entry(1, "C:\\windows\\system32\\rpcss.exe", "rpcss.exe"), 'Z', ""));
        check("sleeping Wine service is not a leftover", !ProcessScanner.isLeftoverProcess(
                new ProcessScanner.Entry(1, "", "plugplay.exe", ""), 'S', ""));
        check("renamed zombie whose threads run Battle.net is a leftover", ProcessScanner.isLeftoverProcess(
                new ProcessScanner.Entry(1, "", "Main", ""), 'Z', "Battle.net.exe\u0000--disable-gpu\u0000"));
        check("renamed zombie with no thread left is not a leftover", !ProcessScanner.isLeftoverProcess(
                new ProcessScanner.Entry(1, "", "Main", ""), 'Z', ""));
        check("zombie shell whose arguments mention an exe is not a leftover", !ProcessScanner.isLeftoverProcess(
                new ProcessScanner.Entry(1, "", "sh", ""), 'Z', "/system/bin/sh\u0000-c\u0000Wow.exe\u0000"));
        checkRenamedLeftover();
    }

    /**
     * The S23 shape: Battle.net's leader thread renames itself "Main" and
     * exits while another thread lives on, so the leader is a zombie with no
     * command line. python3 reproduces it with prctl(PR_SET_NAME) and
     * pthread_exit on the main thread. The worker ends the process after 5 s.
     */
    private static void checkRenamedLeftover() throws Exception {
        String script = "import ctypes, threading, time\n"
                + "libc = ctypes.CDLL(None)\n"
                + "threading.Thread(target=time.sleep, args=(5,)).start()\n"
                + "libc.prctl(15, b'Main', 0, 0, 0)\n"
                + "libc.pthread_exit(None)\n";
        java.lang.Process holder = new ProcessBuilder("bash", "-c", "exec -a \"$0\" python3 -c \"$1\"",
                "C:\\Program Files (x86)\\Battle.net\\Battle.net.exe", script).start();
        try {
            TimeUnit.MILLISECONDS.sleep(800);
            int pid = (int)holder.pid();
            check("renamed leader is a zombie", ProcessScanner.processState(pid) == 'Z');
            boolean listed = false;
            for (ProcessScanner.Entry entry : ProcessScanner.leftoverProcesses()) {
                if (entry.pid == pid) listed = true;
            }
            check("renamed Battle.net zombie with a live thread is a leftover", listed);
        }
        finally {
            holder.destroyForcibly();
        }
    }

    /** Process shapes seen on the S23 (ps ARGS + comm); flags must not change the verdict. */
    private static void checkClassification() {
        String bnetDir = "C:\\Program Files (x86)\\Battle.net\\";
        check("main Battle.net with --exec=\"launch WoW\" is a launcher", launcher(
                "Battle.net.exe", "Battle.net.exe --disable-gpu --autostarted --exec=launch WoW", "CrBrowserMain"));
        check("Battle.net install request is a launcher", launcher(
                "Battle.net.exe", "Battle.net.exe --game=wow_classic_beta --install", "Battle.net.exe"));
        check("CEF helper is a launcher", launcher(
                bnetDir + "Battle.net.exe", bnetDir + "Battle.net.exe --type=gpu-process", "CrGpuMain"));
        check("Agent is a launcher", launcher(
                "C:\\ProgramData\\Battle.net\\Agent\\Agent.exe", "C:\\ProgramData\\Battle.net\\Agent\\Agent.exe --session=1", "Agent.exe"));
        check("argv[0] holding the whole command line", launcher(
                "Battle.net.exe --exec=\"launch WoW\"", "Battle.net.exe --exec=\"launch WoW\"", "CrBrowserMain"));
        String wow = "C:/Program Files (x86)/World of Warcraft/_retail_/Wow-ARM64.exe";
        check("WoW is not a launcher", !launcher(wow, wow + " -launcherlogin -uid wow", "Wow-ARM64.exe"));
        check("WoW with its arguments in argv[0] is not a launcher",
                !launcher(wow + " -launcherlogin -uid wow", wow + " -launcherlogin -uid wow", "Wow-ARM64.exe"));
        check("cmd.exe running the exec bat is not a launcher", !launcher(
                "C:\\windows\\system32\\cmd.exe", "C:\\windows\\system32\\cmd.exe /c C:\\wclaunch.bat", "cmd.exe"));
        check("a shell whose arguments start Battle.net is not a launcher", !launcher(
                "/system/bin/sh", "/system/bin/sh -c cd " + bnetDir + " && Battle.net.exe", "sh"));
        check("winhandler is not a launcher", !launcher(
                "C:\\windows\\winhandler.exe", "C:\\windows\\winhandler.exe wfm.exe", "winhandler.exe"));
        check("wineserver is not a launcher", !launcher("/opt/wine/bin/wineserver", "/opt/wine/bin/wineserver", "wineserver"));
        check("main Battle.net is Battle.net", WeHavePhonesLauncherReaper.isBattleNetProcess(new ProcessScanner.Entry(1,
                "Battle.net.exe --exec=launch WoW", "CrBrowserMain", "Battle.net.exe")));
        check("CEF helper is Battle.net", WeHavePhonesLauncherReaper.isBattleNetProcess(new ProcessScanner.Entry(1,
                bnetDir + "Battle.net.exe --type=gpu-process", "CrGpuMain", bnetDir + "Battle.net.exe")));
        check("Agent is not Battle.net", !WeHavePhonesLauncherReaper.isBattleNetProcess(new ProcessScanner.Entry(1,
                "C:\\ProgramData\\Battle.net\\Agent\\Agent.exe --session=1", "Agent.exe", "C:\\ProgramData\\Battle.net\\Agent\\Agent.exe")));
        check("zombie Battle.net is gone", !WeHavePhonesLauncherReaper.isBattleNetProcess(new ProcessScanner.Entry(1,
                "", "Battle.net.exe", "")));
    }

    private static boolean launcher(String argv0, String cmdline, String comm) {
        return WeHavePhonesLauncherReaper.isLauncherProcess(new ProcessScanner.Entry(1, cmdline, comm, argv0));
    }

    private static java.lang.Process spawn(String argv0) throws Exception {
        return new ProcessBuilder("bash", "-c", "exec -a \"$0\" sleep 60", argv0).start();
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "ok   " : "FAIL ") + name);
    }
}

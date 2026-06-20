import java.util.Scanner;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {
    static Path currentDir = Paths.get(System.getProperty("user.dir"));
    static Map<String, String> envVars = new HashMap<>(System.getenv());

    static class Job {
        int number;
        long pid;
        String command;
        Process process;

        Job(int number, long pid, String command, Process process) {
            this.number = number;
            this.pid = pid;
            this.command = command;
            this.process = process;
        }

        boolean isAlive() {
            return process.isAlive();
        }
    }

    static List<Job> backgroundJobs = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        Set<String> builtins = Set.of("echo", "exit", "type", "pwd", "cd", "jobs", "export");

        while (true) {
            reapDoneJobs(System.out);

            System.out.print("$ ");
            System.out.flush();

            String input = scanner.nextLine();
            List<String> tokens = tokenize(input);
            if (tokens.isEmpty()) continue;

            boolean isBackground = !tokens.isEmpty() && tokens.get(tokens.size() - 1).equals("&");
            if (isBackground) {
                tokens = new ArrayList<>(tokens);
                tokens.remove(tokens.size() - 1);
            }

            if (tokens.isEmpty()) continue;

            List<List<String>> pipelineSegments = splitOnPipe(tokens);

            if (pipelineSegments.size() > 1) {
                runPipeline(pipelineSegments, isBackground);
                continue;
            }

            String stdoutFile = null;
            String stderrFile = null;
            boolean appendStdout = false;
            boolean appendStderr = false;
            List<String> cmdTokens = new ArrayList<>();

            for (int i = 0; i < tokens.size(); i++) {
                String t = tokens.get(i);
                if ((t.equals(">") || t.equals("1>")) && i + 1 < tokens.size()) {
                    stdoutFile = tokens.get(++i);
                    appendStdout = false;
                } else if ((t.equals(">>") || t.equals("1>>")) && i + 1 < tokens.size()) {
                    stdoutFile = tokens.get(++i);
                    appendStdout = true;
                } else if (t.equals("2>") && i + 1 < tokens.size()) {
                    stderrFile = tokens.get(++i);
                    appendStderr = false;
                } else if (t.equals("2>>") && i + 1 < tokens.size()) {
                    stderrFile = tokens.get(++i);
                    appendStderr = true;
                } else {
                    cmdTokens.add(t);
                }
            }

            if (cmdTokens.isEmpty()) continue;
            String cmd = cmdTokens.get(0);

            PrintStream outStream = System.out;
            PrintStream errStream = System.err;

            if (stdoutFile != null) {
                File f = new File(stdoutFile);
                if (!f.isAbsolute()) f = new File(currentDir.toFile(), stdoutFile);
                outStream = new PrintStream(new FileOutputStream(f, appendStdout));
            }

            if (stderrFile != null) {
                File f = new File(stderrFile);
                if (!f.isAbsolute()) f = new File(currentDir.toFile(), stderrFile);
                errStream = new PrintStream(new FileOutputStream(f, appendStderr));
            }

            if (cmd.equals("exit") || cmd.equals("exit 0")) {
                System.exit(0);

            } else if (cmd.equals("echo")) {
                List<String> echoArgs = cmdTokens.subList(1, cmdTokens.size());
                outStream.println(String.join(" ", echoArgs));

            } else if (cmd.equals("pwd")) {
                outStream.println(currentDir.toAbsolutePath());

            } else if (cmd.equals("cd")) {
                String target = cmdTokens.size() > 1 ? cmdTokens.get(1) : "~";

                if (target.equals("~")) {
                    String home = envVars.get("HOME");
                    if (home == null) {
                        home = System.getProperty("user.home");
                    }
                    target = home;
                }

                Path newDir;
                if (Paths.get(target).isAbsolute()) {
                    newDir = Paths.get(target);
                } else {
                    newDir = currentDir.resolve(target);
                }

                newDir = newDir.normalize();

                if (newDir.toFile().isDirectory()) {
                    currentDir = newDir;
                } else {
                    errStream.println("cd: " + target + ": No such file or directory");
                }

            } else if (cmd.equals("export")) {
                for (int i = 1; i < cmdTokens.size(); i++) {
                    String assignment = cmdTokens.get(i);
                    int eq = assignment.indexOf('=');
                    if (eq != -1) {
                        String key = assignment.substring(0, eq);
                        String value = assignment.substring(eq + 1);
                        value = expandVariables(value);
                        envVars.put(key, value);
                    }
                }

            } else if (cmd.equals("jobs")) {
                printJobs(outStream, false);

            } else if (cmd.equals("type")) {
                if (cmdTokens.size() < 2) {
                    continue;
                }

                String target = cmdTokens.get(1);

                if (builtins.contains(target)) {
                    outStream.println(target + " is a shell builtin");
                } else {
                    String path = findInPath(target);
                    if (path != null) {
                        outStream.println(target + " is " + path);
                    } else {
                        outStream.println(target + ": not found");
                    }
                }

            } else {
                String path = findInPath(cmd);

                if (path != null) {
                    ProcessBuilder pb = new ProcessBuilder(cmdTokens);
                    pb.environment().clear();
                    pb.environment().putAll(envVars);
                    pb.directory(currentDir.toFile());

                    if (stdoutFile != null) {
                        File outFile = new File(stdoutFile);
                        if (!outFile.isAbsolute()) {
                            outFile = new File(currentDir.toFile(), stdoutFile);
                        }
                        pb.redirectOutput(
                            appendStdout
                                ? ProcessBuilder.Redirect.appendTo(outFile)
                                : ProcessBuilder.Redirect.to(outFile)
                        );
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }

                    if (stderrFile != null) {
                        File errFile = new File(stderrFile);
                        if (!errFile.isAbsolute()) {
                            errFile = new File(currentDir.toFile(), stderrFile);
                        }
                        pb.redirectError(
                            appendStderr
                                ? ProcessBuilder.Redirect.appendTo(errFile)
                                : ProcessBuilder.Redirect.to(errFile)
                        );
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }

                    Process p = pb.start();

                    if (isBackground) {
                        int jobNum = nextJobNumber();
                        long pid = p.pid();
                        String cmdString = String.join(" ", cmdTokens);
                        backgroundJobs.add(new Job(jobNum, pid, cmdString, p));
                        System.out.println("[" + jobNum + "] " + pid);
                    } else {
                        p.waitFor();
                    }

                } else {
                    errStream.println(cmd + ": command not found");
                }
            }

            if (stdoutFile != null) {
                outStream.close();
            }

            if (stderrFile != null && stderrFile != stdoutFile) {
                errStream.close();
            }
        }
    }

    static String expandVariables(String value) {
        if (value.contains("$PATH")) {
            String currentPath = envVars.getOrDefault("PATH", "");
            value = value.replace("$PATH", currentPath);
        }
        return value;
    }

    static List<List<String>> splitOnPipe(List<String> tokens) {
        List<List<String>> segments = new ArrayList<>();
        List<String> current = new ArrayList<>();

        for (String t : tokens) {
            if (t.equals("|")) {
                segments.add(current);
                current = new ArrayList<>();
            } else {
                current.add(t);
            }
        }
        segments.add(current);

        return segments;
    }

    static void runPipeline(List<List<String>> segments, boolean isBackground) throws Exception {
        List<ProcessBuilder> builders = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            List<String> segment = segments.get(i);
            if (segment.isEmpty()) continue;

            ProcessBuilder pb = new ProcessBuilder(segment);
            pb.environment().clear();
            pb.environment().putAll(envVars);
            pb.directory(currentDir.toFile());
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);

            if (i == segments.size() - 1) {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }

            builders.add(pb);
        }

        if (builders.isEmpty()) return;

        List<Process> processes = ProcessBuilder.startPipeline(builders);
        Process last = processes.get(processes.size() - 1);

        if (isBackground) {
            int jobNum = nextJobNumber();
            long pid = last.pid();
            String cmdString = pipelineToString(segments);
            backgroundJobs.add(new Job(jobNum, pid, cmdString, last));
            System.out.println("[" + jobNum + "] " + pid);
        } else {
            for (Process p : processes) {
                p.waitFor();
            }
        }
    }

    static String pipelineToString(List<List<String>> segments) {
        List<String> parts = new ArrayList<>();
        for (List<String> segment : segments) {
            parts.add(String.join(" ", segment));
        }
        return String.join(" | ", parts);
    }

    static int nextJobNumber() {
        if (backgroundJobs.isEmpty()) {
            return 1;
        }
        int highest = 0;
        for (Job job : backgroundJobs) {
            if (job.number > highest) {
                highest = job.number;
            }
        }
        return highest + 1;
    }

    static void reapDoneJobs(PrintStream outStream) {
        printJobs(outStream, true);
    }

    static void printJobs(PrintStream outStream, boolean reapOnly) {
        int n = backgroundJobs.size();
        int last = n - 1;
        int secondLast = n - 2;

        List<Job> remaining = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            Job job = backgroundJobs.get(i);
            String marker = (i == last) ? "+" : (i == secondLast) ? "-" : " ";

            if (job.isAlive()) {
                remaining.add(job);
                if (!reapOnly) {
                    String status = String.format("%-24s", "Running");
                    outStream.println("[" + job.number + "]" + marker + " " + status + job.command + " &");
                }
            } else {
                String status = String.format("%-24s", "Done");
                outStream.println("[" + job.number + "]" + marker + " " + status + job.command);
            }
        }

        backgroundJobs = remaining;
    }

    static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingle = false;
        boolean inDouble = false;

        int i = 0;

        while (i < input.length()) {
            char c = input.charAt(i);

            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                } else {
                    current.append(c);
                }
            } else if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                } else if (c == '\\') {
                    if (i + 1 < input.length()) {
                        char next = input.charAt(i + 1);
                        if (next == '"' || next == '\\' || next == '$' || next == '`' || next == '\n') {
                            current.append(next);
                            i++;
                        } else {
                            current.append(c);
                        }
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '\'') {
                    inSingle = true;
                } else if (c == '"') {
                    inDouble = true;
                } else if (c == '\\') {
                    if (i + 1 < input.length()) {
                        current.append(input.charAt(i + 1));
                        i++;
                    }
                } else if (c == '|') {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                    tokens.add("|");
                } else if (c == ' ' || c == '\t') {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            }

            i++;
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    static String findInPath(String cmd) {
        String pathEnv = envVars.get("PATH");

        if (pathEnv == null) {
            return null;
        }

        for (String dir : pathEnv.split(File.pathSeparator)) {
            File f = new File(dir, cmd);
            if (f.exists() && f.canExecute()) {
                return f.getAbsolutePath();
            }

            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                File exe = new File(dir, cmd + ".exe");
                if (exe.exists() && exe.canExecute()) {
                    return exe.getAbsolutePath();
                }
            }
        }

        return null;
    }
}
import java.util.Scanner;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {
    static Path currentDir = Paths.get(System.getProperty("user.dir"));

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        Set<String> builtins = Set.of("echo", "exit", "type", "pwd", "cd", "jobs");

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            String input = scanner.nextLine();
            List<String> tokens = tokenize(input);
            if (tokens.isEmpty()) continue;

            // Parse redirections
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

            // Setup output streams
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
                    String home = System.getenv("HOME");
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

            } else if (cmd.equals("jobs")) {
                // Empty implementation for AF2 stage

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
                    pb.environment().put("PATH", System.getenv("PATH"));
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
                    p.waitFor();

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
        String pathEnv = System.getenv("PATH");

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
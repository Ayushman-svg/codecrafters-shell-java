import java.util.Scanner;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.io.File;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        Set<String> builtins = Set.of("echo", "exit", "type", "pwd", "cd");
        
        while (true) {
            System.out.print("$ ");
            System.out.flush();
            
            String input = scanner.nextLine().trim();
            String[] parts = input.split("\\s+");
            String cmd = parts[0];

            if (cmd.equals("exit") || input.equals("exit 0")) {
                System.exit(0);
            } else if (cmd.equals("echo")) {
                System.out.println(input.substring(5));
            } else if (cmd.equals("type")) {
                String target = parts[1];
                if (builtins.contains(target)) {
                    System.out.println(target + " is a shell builtin");
                } else {
                    String path = findInPath(target);
                    if (path != null) {
                        System.out.println(target + " is " + path);
                    } else {
                        System.out.println(target + ": not found");
                    }
                }
            } else {
                // Try to run as external program
                String path = findInPath(cmd);
                if (path != null) {
                    List<String> command = new ArrayList<>();
                    command.add(path);
                    for (int i = 1; i < parts.length; i++) {
                        command.add(parts[i]);
                    }
                    ProcessBuilder pb = new ProcessBuilder(command);
                    pb.inheritIO();
                    Process p = pb.start();
                    p.waitFor();
                } else {
                    System.out.println(cmd + ": command not found");
                }
            }
        }
    }

    static String findInPath(String cmd) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            File f = new File(dir, cmd);
            if (f.exists() && f.canExecute()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }
}
import java.util.Scanner;
import java.util.Set;
import java.io.File;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        Set<String> builtins = Set.of("echo", "exit", "type", "pwd", "cd");
        
        while (true) {
            System.out.print("$ ");
            System.out.flush();
            
            String input = scanner.nextLine().trim();
            
            if (input.equals("exit") || input.equals("exit 0")) {
                System.exit(0);
            } else if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
            } else if (input.startsWith("type ")) {
                String cmd = input.substring(5).trim();
                if (builtins.contains(cmd)) {
                    System.out.println(cmd + " is a shell builtin");
                } else {
                    String path = findInPath(cmd);
                    if (path != null) {
                        System.out.println(cmd + " is " + path);
                    } else {
                        System.out.println(cmd + ": not found");
                    }
                }
            } else {
                System.out.println(input + ": command not found");
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
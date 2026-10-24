package net.strata.launcher.launch;

import java.nio.file.Path;
import java.util.List;

public record LaunchArguments(List<String> command, Path workingDirectory) {
    public LaunchArguments {
        command = List.copyOf(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("Launch command cannot be empty.");
        }
        workingDirectory = workingDirectory.toAbsolutePath().normalize();
    }

    @Override
    public String toString() {
        java.util.List<String> safeCommand = new java.util.ArrayList<>(command);
        for (int index = 0; index < safeCommand.size(); index++) {
            String argument = safeCommand.get(index);
            if (argument.equals("--accessToken") || argument.equals("--auth_access_token")
                    || argument.equals("--session")) {
                if (index + 1 < safeCommand.size()) {
                    safeCommand.set(++index, "<redacted>");
                }
            } else if (argument.startsWith("token:")) {
                safeCommand.set(index, "token:<redacted>");
            } else if (argument.startsWith("--accessToken=")
                    || argument.startsWith("--auth_access_token=")) {
                safeCommand.set(index, argument.substring(0, argument.indexOf('=') + 1) + "<redacted>");
            }
        }
        return "LaunchArguments[command=" + safeCommand
                + ", workingDirectory=" + workingDirectory + "]";
    }
}

package org.leaktk.jenkins_plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jenkins.security.MasterToSlaveCallable;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.DataBoundConstructor;

public class LeakTKBuilder extends Builder implements SimpleBuildStep {

    private final boolean scanSource;
    private final boolean scanTarget;
    private final boolean scanTest;
    private final boolean scanConsoleOutput;
    private final boolean scanEnvironmentVariables;

    @DataBoundConstructor
    public LeakTKBuilder(
            boolean scanConsoleOutput,
            boolean scanEnvironmentVariables,
            boolean scanSource,
            boolean scanTarget,
            boolean scanTest) {
        this.scanConsoleOutput = scanConsoleOutput;
        this.scanEnvironmentVariables = scanEnvironmentVariables;
        this.scanSource = scanSource;
        this.scanTarget = scanTarget;
        this.scanTest = scanTest;
    }

    public boolean isScanTest() {
        return scanTest;
    }

    public boolean isScanSource() {
        return scanSource;
    }

    public boolean isScanTarget() {
        return scanTarget;
    }

    public boolean isScanConsoleOutput() {
        return scanConsoleOutput;
    }

    public boolean isScanEnvironmentVariables() {
        return scanEnvironmentVariables;
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, EnvVars env, Launcher launcher, TaskListener listener)
            throws InterruptedException, IOException {
        listener.getLogger().println("Starting LeakTK scan...");

        String osArch = workspace.act(new MasterToSlaveCallable<String, IOException>() {
            @Override
            public String call() throws IOException {
                return System.getProperty("os.arch");
            }
        });

        String leaktkResourcePath;

        if (osArch.equals("amd64") || osArch.equals("x86_64")) {
            leaktkResourcePath = "org/leaktk/jenkins_plugin/binaries/linux-x86_64/leaktk";
            listener.getLogger().println("Detected x86_64 architecture (" + osArch + "). Using " + leaktkResourcePath);
        } else if (osArch.equals("aarch64")) {
            leaktkResourcePath = "org/leaktk/jenkins_plugin/binaries/linux-aarch64/leaktk";
            listener.getLogger().println("Detected aarch64 architecture (" + osArch + "). Using " + leaktkResourcePath);
        } else {
            listener.error("Unsupported OS architecture for LeakTK scanner: " + osArch);
            throw new AbortException("LeakTK scanner does not support this operating system architecture.");
        }

        FilePath leaktkWorkspace = workspace.createTempDir("leaktk-", "");
        FilePath leaktkExecutable = leaktkWorkspace.child("leaktk");

        URL leaktkURL = getClass().getResource("/" + leaktkResourcePath);

        if (leaktkURL == null) {
            listener.fatalError("LeakTK binary not found in plugin resources at: " + leaktkResourcePath);
            throw new AbortException("The LeakTK binary could not be found inside the plugin's resources. "
                    + "Please ensure it is correctly packaged.");
        }

        try {
            listener.getLogger()
                    .println("Copying LeakTK binary from plugin resources to agent: " + leaktkExecutable.getRemote());
            leaktkExecutable.copyFrom(leaktkURL);
            leaktkExecutable.chmod(0755);

        } catch (IOException | InterruptedException e) {
            listener.fatalError("Failed to copy or set permissions for LeakTK binary: " + e.getMessage());
            throw e;
        }

        listener.getLogger().println("LeakTK binary extracted and made executable.");

        Map<String, String> targetedCommands = new HashMap<>();

        if (scanSource) {
            targetedCommands.put(
                    workspace.child("src").getRemote().replace("work/workspace/Leaktk-Scan-Job/", ""), "Files");
        }
        if (scanTest) {
            targetedCommands.put(
                    workspace.child("src/test").getRemote().replace("work/workspace/Leaktk-Scan-Job/", ""), "Files");
        }
        if (scanTarget) {
            targetedCommands.put(
                    workspace.child("target").getRemote().replace("work/workspace/Leaktk-Scan-Job/", ""), "Files");
        }

        // Add additional scan targets from new capabilities
        if (scanConsoleOutput) {
            FilePath tempDir = workspace.createTempDir("leaktk-", "");
            FilePath consoleLogFile = tempDir.child("leaktk_console_output.log");
            try (BufferedReader reader = new BufferedReader(run.getLogReader())) {
                StringBuilder logContent = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    logContent.append(line).append("\n");
                }
                consoleLogFile.write(logContent.toString(), "UTF-8");
            }
            targetedCommands.put(consoleLogFile.getRemote(), "Files");
        }

        if (scanEnvironmentVariables) {
            FilePath envVarJsonFile = workspace.createTempDir("leaktk-", "").child("env_vars.json");

            ObjectMapper mapper = new ObjectMapper();
            String jsonContent = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(env);
            envVarJsonFile.write(jsonContent, "UTF-8");

            targetedCommands.put("@" + envVarJsonFile.getRemote(), "JSONData");
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();

        int exitCode = 0;

        for (Map.Entry<String, String> entry : targetedCommands.entrySet()) {
            listener.getLogger().println("Scanning target: " + entry.getKey());

            List<String> cmd = Arrays.asList(
                    leaktkExecutable.getRemote(),
                    "scan",
                    "--kind",
                    entry.getValue(),
                    entry.getKey(),
                    "--leak-exit-code",
                    "164");

            listener.getLogger().println("Executing command: " + String.join(" ", cmd));

            exitCode = launcher.launch()
                    .cmds(cmd)
                    .pwd(workspace)
                    .stdout(outputStream)
                    .stderr(errorStream)
                    .join();
            if (exitCode == 164) {
                listener.getLogger().println("Leaks Found!");
                throw new AbortException("Leaks were found by the LeakTK scanner.");
            }
            listener.getLogger().println("Scanner finished with exit code: " + exitCode);
        }
        listener.getLogger().println("--- Command Output ---");
        listener.getLogger().println(outputStream.toString("UTF-8"));
        listener.getLogger().println("--- Command Error Output ---");
        listener.getLogger().println(errorStream.toString("UTF-8"));

        listener.getLogger().println("All LeakTK scans completed successfully!");
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Run LeakTK Scanner";
        }
    }
}

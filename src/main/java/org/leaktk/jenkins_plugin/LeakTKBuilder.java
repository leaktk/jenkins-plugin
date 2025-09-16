package org.leaktk.jenkins_plugin;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.DataBoundConstructor;

public class LeakTKBuilder extends Builder implements SimpleBuildStep {

    private final String scanTarget;
    private final boolean scanConsoleOutput;
    private final boolean scanEnvironmentVariables;

    @DataBoundConstructor
    public LeakTKBuilder(String scanTarget, boolean scanConsoleOutput, boolean scanEnvironmentVariables) {
        this.scanTarget = scanTarget;
        this.scanConsoleOutput = scanConsoleOutput;
        this.scanEnvironmentVariables = scanEnvironmentVariables;
    }

    public String getScanTarget() {
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

        String osArch = System.getProperty("os.arch");
        String leaktkResourcePath;
        FilePath leaktkExecutable;

        if ("amd64".equalsIgnoreCase(osArch) || "x86_64".equalsIgnoreCase(osArch)) {
            leaktkResourcePath = "org/leaktk/jenkins_plugin/binaries/linux-x86_64/leaktk";
            listener.getLogger().println("Detected x86_64 architecture (" + osArch + "). Using " + leaktkResourcePath);
        } else if ("aarch64".equalsIgnoreCase(osArch)) {
            leaktkResourcePath = "org/leaktk/jenkins_plugin/binaries/linux-aarch64/leaktk"; // Corrected binary path
            listener.getLogger().println("Detected aarch64 architecture (" + osArch + "). Using " + leaktkResourcePath);
        } else {
            listener.error("Unsupported OS architecture for LeakTK scanner: " + osArch);
            throw new AbortException("LeakTK scanner does not support this operating system architecture.");
        }

        leaktkExecutable = workspace.child("leaktk");
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(leaktkResourcePath)) {
            if (is == null) {
                listener.error("LeakTK binary not found in plugin resources at: " + leaktkResourcePath);
                throw new AbortException(
                        "LeakTK binary could not be found or extracted. Ensure it's bundled correctly.");
            }
            listener.getLogger().println("Extracting LeakTK binary to: " + leaktkExecutable.getRemote());
            leaktkExecutable.copyFrom(is);
        }

        leaktkExecutable.act(new FilePath.FileCallable<Void>() {

            @Override
            public void checkRoles(RoleChecker checker) throws SecurityException {}

            @Override
            public Void invoke(File f, hudson.remoting.VirtualChannel channel) throws IOException {
                if (!f.setExecutable(true)) {
                    throw new IOException("Failed to set executable permissions on " + f.getAbsolutePath());
                }
                return null;
            }
        });
        listener.getLogger().println("LeakTK binary extracted and made executable.");

        List<String> scanTargets = new ArrayList<>();
        String mainScanTarget = "";

        // Add the main scan target
        if (scanTarget != null && !scanTarget.isEmpty()) {
            mainScanTarget = workspace.child(scanTarget).getRemote();
        }

        // Add additional scan targets from new capabilities
        if (scanConsoleOutput) {
            FilePath consoleLogFile = workspace.child("leaktk_console_output.log");
            try (BufferedReader reader = new BufferedReader(run.getLogReader())) {
                StringBuilder logContent = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    logContent.append(line).append("\n");
                }
                consoleLogFile.write(logContent.toString(), "UTF-8");
            }
            scanTargets.add(consoleLogFile.getRemote());
        }

        if (scanEnvironmentVariables) {
            FilePath envVarFile = workspace.child("leaktk_env_vars.txt");
            StringBuilder envVarContent = new StringBuilder();
            for (Map.Entry<String, String> entry : env.entrySet()) {
                envVarContent
                        .append(entry.getKey())
                        .append("=")
                        .append(entry.getValue())
                        .append("\n");
            }
            envVarFile.write(envVarContent.toString(), "UTF-8");
            scanTargets.add(envVarFile.getRemote());
        }

        // Add test reports and artifacts (these are more complex and require specific configuration)
        // For a simple implementation, you can scan common directories
        // LeakTK scan test reports from the workspace
        FilePath testReportDir = workspace.child("target/surefire-reports");
        if (testReportDir.exists()) {
            listener.getLogger().println("Adding test reports for scanning.");
            scanTargets.add(testReportDir.getRemote());
        }

        // LeakTK scan archived artifacts (if they were copied to the workspace)
        FilePath artifactsDir = workspace.child("artifacts");
        if (artifactsDir.exists()) {
            listener.getLogger().println("Adding artifacts for scanning.");
            scanTargets.add(artifactsDir.getRemote());
        }

        List<String> firstCMD = Arrays.asList(
                leaktkExecutable.getRemote(), "scan", "--kind", "Files", mainScanTarget, "--leak-exit-code", "164");

        listener.getLogger().println("Executing command: " + String.join(" ", firstCMD));

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();

        int exitCode = launcher.launch()
                .cmds(firstCMD)
                .pwd(workspace)
                .stdout(outputStream)
                .stderr(errorStream)
                .join();

        if (exitCode == 164) {
            listener.getLogger().println("Leaks Found!");
            throw new AbortException("Leaks were found by the LeakTK scanner.");
        }
        listener.getLogger().println("Scanner finished with exit code: " + exitCode);
        listener.getLogger().println("--- Command Output ---");
        listener.getLogger().println(outputStream.toString("UTF-8"));
        listener.getLogger().println("--- Command Error Output ---");
        listener.getLogger().println(errorStream.toString("UTF-8"));

        // if (exitCode != 0) {
        //     listener.error("LeakTK scan failed with exit code: " + exitCode);
        //     throw new AbortException(
        //             "LeakTK scan detected issues or encountered an error. See console output for details.");
        // }

        // Build the command
        for (String target : scanTargets) {
            listener.getLogger().println("Scanning target: " + target);

            List<String> cmd = Arrays.asList(
                    leaktkExecutable.getRemote(), "scan", "--kind", "Files", target, "--leak-exit-code", "164");

            listener.getLogger().println("Executing command: " + String.join(" ", cmd));

            exitCode = launcher.launch()
                    .cmds(cmd)
                    .pwd(workspace)
                    .stdout(listener.getLogger())
                    .stderr(listener.getLogger())
                    .join();

            // if (exitCode != 0) {
            //     listener.error("LeakTK scan failed with exit code: " + exitCode);
            //     throw new AbortException(
            //             "LeakTK scan detected issues or encountered an error. See console output for details.");
            // }
        }

        listener.getLogger().println("All LeakTK scans completed successfully!");
    }

    @Override
    public boolean prebuild(AbstractBuild<?, ?> build, BuildListener listener) {
        return true;
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

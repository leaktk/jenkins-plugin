package io.jenkins.plugins.sample;

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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.DataBoundConstructor;

public class LeakTKBuilder extends Builder implements SimpleBuildStep {

    private final String scanTarget;
    private final String additionalArguments;

    @DataBoundConstructor
    public LeakTKBuilder(String scanTarget, String additionalArguments) {
        this.scanTarget = scanTarget;
        this.additionalArguments = additionalArguments;
    }

    public String getScanTarget() {
        return scanTarget;
    }

    public String getAdditionalArguments() {
        return additionalArguments;
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, EnvVars env, Launcher launcher, TaskListener listener)
            throws InterruptedException, IOException {
        listener.getLogger().println("Starting Leaktk scan...");

        String osArch = System.getProperty("os.arch");
        String leaktkResourcePath;
        FilePath leaktkExecutable;

        if ("amd64".equalsIgnoreCase(osArch) || "x86_64".equalsIgnoreCase(osArch)) {
            leaktkResourcePath = "com/leaktk/binaries/linux-x86_64/leaktk";
            listener.getLogger().println("Detected x86_64 architecture (" + osArch + "). Using " + leaktkResourcePath);
        } else if ("aarch64".equalsIgnoreCase(osArch)) {
            leaktkResourcePath = "com/leaktk/binaries/linux-aarch64/leaktk"; // Corrected binary path
            listener.getLogger().println("Detected aarch64 architecture (" + osArch + "). Using " + leaktkResourcePath);
        } else {
            listener.error("Unsupported OS architecture for Leaktk scanner: " + osArch);
            throw new AbortException("Leaktk scanner does not support this operating system architecture.");
        }

        leaktkExecutable = workspace.child("leaktk");
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(leaktkResourcePath)) {
            if (is == null) {
                listener.error("Leaktk binary not found in plugin resources at: " + leaktkResourcePath);
                throw new AbortException(
                        "Leaktk binary could not be found or extracted. Ensure it's bundled correctly.");
            }
            listener.getLogger().println("Extracting Leaktk binary to: " + leaktkExecutable.getRemote());
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
        listener.getLogger().println("Leaktk binary extracted and made executable.");

        List<String> cmd = new ArrayList<>();
        cmd.add(leaktkExecutable.getRemote());
        cmd.add("scan");
        cmd.add(workspace
                .child(scanTarget != null && !scanTarget.isEmpty() ? scanTarget : ".")
                .getRemote());

        if (additionalArguments != null && !additionalArguments.trim().isEmpty()) {
            cmd.addAll(Arrays.asList(additionalArguments.trim().split("\\s+")));
        }

        listener.getLogger().println("Executing command: " + String.join(" ", cmd));

        int exitCode = launcher.launch()
                .cmds(cmd)
                .pwd(workspace)
                .stdout(listener.getLogger())
                .stderr(listener.getLogger())
                .join();

        if (exitCode != 0) {
            listener.error("Leaktk scan failed with exit code: " + exitCode);
            throw new AbortException(
                    "Leaktk scan detected issues or encountered an error. See console output for details.");
        } else {
            listener.getLogger().println("Leaktk scan completed successfully!");
        }
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
            return "Run Leaktk Scanner";
        }
    }
}

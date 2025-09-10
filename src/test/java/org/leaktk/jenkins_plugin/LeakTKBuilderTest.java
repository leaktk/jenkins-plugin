package org.leaktk.jenkins_plugin;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class LeakTKBuilderTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void testConfigRoundtrip() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();

        // Create a temporary directory for the test.
        Path tempDir = Files.createTempDirectory("leaktk-test-");
        File tempDirFile = tempDir.toFile();
        String scanPath = tempDirFile.getAbsolutePath();

        LeakTKBuilder builder = new LeakTKBuilder(scanPath, true, false);
        project.getBuildersList().add(builder);

        // Perform the config roundtrip: save and reload the project
        project = jenkins.configRoundtrip(project);

        // Assert that the reloaded builder has the same values
        LeakTKBuilder afterRoundtrip = project.getBuildersList().get(LeakTKBuilder.class);
        assertNotNull("Builder should not be null after roundtrip", afterRoundtrip);
        assertEquals("Scan target should match", scanPath, afterRoundtrip.getScanTarget());
        assertEquals("Scan console output should match", true, afterRoundtrip.isScanConsoleOutput());
        assertEquals("Scan environment variables should match", false, afterRoundtrip.isScanEnvironmentVariables());

        // Clean up the temporary directory after the test.
        // It's good practice to ensure temp directories are deleted.
        tempDirFile.delete();
    }

    @Test
    public void testSuccessfulExecution() throws Exception {
        // Prepare mocks for the Jenkins environment
        Launcher mockLauncher = mock(Launcher.class);
        Launcher.ProcStarter mockProcStarter = mock(Launcher.ProcStarter.class);

        // Configure the mock launcher to return a successful exit code (0)
        when(mockLauncher.launch()).thenReturn(mockProcStarter);
        when(mockProcStarter.cmds(any(List.class))).thenReturn(mockProcStarter);
        when(mockProcStarter.pwd(any(FilePath.class))).thenReturn(mockProcStarter);
        when(mockProcStarter.stdout(any(ByteArrayOutputStream.class))).thenReturn(mockProcStarter);
        when(mockProcStarter.stderr(any(ByteArrayOutputStream.class))).thenReturn(mockProcStarter);
        when(mockProcStarter.join()).thenReturn(0); // Simulate success

        // Simulate the host's architecture
        System.setProperty("os.arch", "amd64");

        // Create a project and add the builder with test values
        FreeStyleProject project = jenkins.createFreeStyleProject();
        LeakTKBuilder builder = new LeakTKBuilder(".", true, false);
        project.getBuildersList().add(builder);

        // Perform the build, passing our mock launcher
        FreeStyleBuild build = project.scheduleBuild2(0).get();

        // 1. Verify the build status is SUCCESS
        jenkins.assertBuildStatusSuccess(build);

        String consoleOutput = jenkins.getLog(build);
        assertTrue(consoleOutput.contains("Starting Leaktk scan..."));
        assertTrue(consoleOutput.contains("Detected x86_64 architecture (amd64)."));
        assertTrue(consoleOutput.contains("Leaktk binary extracted and made executable."));
        assertTrue(consoleOutput.contains("Executing command: "));
        assertTrue(consoleOutput.contains("All Leaktk scans completed successfully!"));
    }

    @Test
    public void testLeaksFound() throws Exception {
        System.setProperty("os.arch", "aarch64");
        FreeStyleProject project = jenkins.createFreeStyleProject();

        Path tempDir = Files.createTempDirectory("leaktk-test-");
        File tempDirFile = tempDir.toFile();

        String repoUrl = "https://github.com/leaktk/fake-leaks.git";

        // Clone the repository into the temporary directory
        try {
            Git.cloneRepository().setURI(repoUrl).setDirectory(tempDirFile).call();
        } catch (GitAPIException e) {
            // Handle the exception, e.g., log the error or fail the test
            e.printStackTrace();
            throw new RuntimeException("Failed to clone the Git repository.", e);
        }

        // Use the path of the cloned repository for the builder
        LeakTKBuilder builder = new LeakTKBuilder(tempDirFile.getAbsolutePath(), false, false);
        project.getBuildersList().add(builder);
        FreeStyleBuild build = project.scheduleBuild2(0).get();

        assertEquals(Result.FAILURE, build.getResult());
        String consoleOutput = jenkins.getLog(build);

        // Clean up the temporary directory after the test
        deleteDirectoryRecursively(tempDirFile);
    }

    // Utility method to recursively delete a directory
    private void deleteDirectoryRecursively(File directory) {
        if (directory.isDirectory()) {
            File[] allContents = directory.listFiles();
            if (allContents != null) {
                for (File file : allContents) {
                    deleteDirectoryRecursively(file);
                }
            }
        }
        directory.delete();
    }

    @Test
    public void testUnsupportedArchitecture() throws Exception {
        // 1. Set a system property to simulate an unsupported architecture
        System.setProperty("os.arch", "s390x");

        // 2. Set up the build and run it
        FreeStyleProject project = jenkins.createFreeStyleProject();
        LeakTKBuilder builder = new LeakTKBuilder(".", false, false);
        project.getBuildersList().add(builder);
        FreeStyleBuild build = project.scheduleBuild2(0).get();

        // 3. Verify the build status is FAILURE
        jenkins.assertBuildStatus(Result.FAILURE, build);

        // 4. Verify the console output contains the expected error message
        String consoleOutput = jenkins.getLog(build);
        assertTrue(consoleOutput.contains("Unsupported OS architecture for Leaktk scanner: s390x"));
        assertTrue(consoleOutput.contains("Leaktk scanner does not support this operating system architecture."));
    }
}

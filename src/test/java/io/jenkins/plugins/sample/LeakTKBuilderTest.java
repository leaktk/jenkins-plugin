package io.jenkins.plugins.sample;

// import com.gargoylesoftware.htmlunit.html.HtmlForm;
// import com.gargoylesoftware.htmlunit.html.HtmlTextInput;
import static org.junit.Assert.*;

import hudson.model.FreeStyleProject;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
// import static org.mockito.ArgumentMatchers.any;
// import static org.mockito.ArgumentMatchers.argThat;
// import static org.mockito.Mockito.when;
// import static org.mockito.Mockito.mock;

public class LeakTKBuilderTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void testConfigRoundtrip() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        LeakTKBuilder builder =
                new LeakTKBuilder("/home/alayne/repos/projects/jenkins-plugin", "--verbose", true, true);
        project.getBuildersList().add(builder);

        // Perform the config roundtrip: save and reload the project
        project = jenkins.configRoundtrip(project);

        // Assert that the reloaded builder has the same values
        LeakTKBuilder afterRoundtrip = project.getBuildersList().get(LeakTKBuilder.class);
        assertNotNull("Builder should not be null after roundtrip", afterRoundtrip);
        assertEquals(
                "Scan target should match",
                "/home/alayne/repos/projects/jenkins-plugin",
                afterRoundtrip.getScanTarget());
        assertEquals("Additional arguments should match", "--verbose", afterRoundtrip.getAdditionalArguments());
    }
}

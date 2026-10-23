package io.jenkins.plugins.buildui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.model.CNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** The YAML in the README must configure ArgoCD servers, and exporting must give it back. */
@WithJenkins
class ConfigurationAsCodeTest {

    static final String YAML = """
            unclassified:
              buildUi:
                argoCdServers:
                  - name: default
                    url: https://argocd.example.com
                    credentialsId: argocd-token
                    apps: "web-*"
            """;

    @Test
    void readmeYamlConfiguresTheServersAndExportsBack(JenkinsRule j, @TempDir Path dir) throws Exception {
        Path file = dir.resolve("jenkins.yaml");
        Files.writeString(file, YAML);
        ConfigurationAsCode.get().configure(file.toString());

        ArgoCdServer server = BuildUiConfiguration.get().getArgoCdServers().get(0);
        assertEquals("default", server.getName());
        assertEquals("https://argocd.example.com", server.getUrl());
        assertEquals("argocd-token", server.getCredentialsId());
        assertEquals("web-*", server.getApps());

        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        ConfigurationContext context = new ConfigurationContext(registry);
        CNode exported = registry.lookupOrFail(BuildUiConfiguration.class).describe(BuildUiConfiguration.get(), context);
        String text = exported.toString();
        assertTrue(text.contains("argocd.example.com") && text.contains("web-*"), text);
    }

    @Test
    void approvalCanBeRequiredFromYaml(JenkinsRule j, @TempDir Path dir) throws Exception {
        assertFalse(BuildUiConfiguration.get().isRequireApproval(), "approval must be off unless an admin asks");
        Path file = dir.resolve("jenkins.yaml");
        Files.writeString(file, """
                unclassified:
                  buildUi:
                    requireApproval: true
                """);
        ConfigurationAsCode.get().configure(file.toString());
        assertTrue(BuildUiConfiguration.get().isRequireApproval());
    }

    @Test
    void evalRuntimesCanBeAllowedFromYaml(JenkinsRule j, @TempDir Path dir) throws Exception {
        assertFalse(BuildUiConfiguration.get().isAllowEvalRuntimes(), "eval runtimes must be off unless an admin asks");
        Path file = dir.resolve("jenkins.yaml");
        Files.writeString(file, """
                unclassified:
                  buildUi:
                    allowEvalRuntimes: true
                """);
        ConfigurationAsCode.get().configure(file.toString());
        assertTrue(BuildUiConfiguration.get().isAllowEvalRuntimes());
    }
}

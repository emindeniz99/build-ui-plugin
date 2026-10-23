package io.jenkins.plugins.buildui;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * Rejects a parameter value. Stapler answers 400 with the message when it
 * escapes a request (form, REST); Pipeline shows the same message when it
 * escapes the {@code build} step.
 */
public class InvalidValueException extends IllegalArgumentException implements HttpResponse {

    public InvalidValueException(String message) {
        super(message);
    }

    public InvalidValueException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public void generateResponse(StaplerRequest2 req, StaplerResponse2 rsp, Object node) throws IOException {
        rsp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        rsp.setContentType("text/plain;charset=UTF-8");
        rsp.getOutputStream().write((getMessage() + "\n").getBytes(StandardCharsets.UTF_8));
    }
}

package de.carifin.plugins;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;

@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY)
public class ExceptionGuardMojo extends AbstractMojo {

    // Konfigurierbare Liste von Exception-Klassennamen, die ignoriert werden sollen (z.B. io.cucumber.java.PendingException)
    @Parameter(property = "ignoredExceptions")
    private Set<String> ignoredExceptions = new HashSet<>();

    // Pfad zum Failsafe-Reports-Verzeichnis
    @Parameter(property = "reportsDirectory", defaultValue = "${project.build.directory}/failsafe-reports")
    private File reportsDirectory;

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("--- Running Exception Guard Check on Failsafe Reports ---");
        
        // Fügt die notwendigen Cucumber Exceptions hinzu, falls die Konfiguration leer ist,
        // um das Standardverhalten zu ermöglichen.
        if (ignoredExceptions.isEmpty()) {
            getLog().warn("No exceptions configured. Using default list for Cucumber: PendingException and UndefinedStepException.");
            ignoredExceptions.add("io.cucumber.java.PendingException");
            ignoredExceptions.add("io.cucumber.junit.platform.engine.UndefinedStepException");
        }
        
        getLog().info("Configured exceptions to ignore: " + ignoredExceptions);

        if (!reportsDirectory.exists() || !reportsDirectory.isDirectory()) {
            getLog().info("Failsafe reports directory not found or empty. Assuming tests ran successfully or were skipped.");
            return;
        }

        File[] reportFiles = reportsDirectory.listFiles((dir, name) -> name.startsWith("TEST-") && name.endsWith(".xml"));

        if (reportFiles == null || reportFiles.length == 0) {
            getLog().info("No individual Failsafe XML reports found (TEST-*.xml). Skipping check.");
            return;
        }

        Set<String> criticalExceptionsFound = new HashSet<>();
        SAXBuilder builder = new SAXBuilder();
        
        // Iterieren über alle TEST-*.xml Reports
        for (File reportFile : reportFiles) {
            try {
                Document document = builder.build(reportFile);
                Element rootElement = document.getRootElement();

                // Suchen nach <testcase> Elementen
                for (Element testCase : rootElement.getChildren("testcase")) {
                    
                    // Suchen nach <error> Elementen (enthält Stack Trace und Exception Typ)
                    Element error = testCase.getChild("error");
                    
                    if (error != null) {
                        String exceptionType = error.getAttributeValue("type");
                        
                        // Wenn der Exception-Typ nicht in der Ignorier-Liste ist,
                        // wird er als kritisch eingestuft.
                        if (exceptionType != null && !ignoredExceptions.contains(exceptionType)) {
                            criticalExceptionsFound.add(exceptionType);
                            getLog().error("Critical Exception found in " + reportFile.getName() + ": " + exceptionType);
                        } else if (exceptionType != null) {
                             getLog().debug("Ignoring expected exception: " + exceptionType);
                        }
                    }
                }
            } catch (JDOMException | IOException e) {
                throw new MojoExecutionException("Error parsing Failsafe report: " + reportFile.getAbsolutePath(), e);
            }
        }

        // Ergebnisprüfung und Build-Abbruch
        if (!criticalExceptionsFound.isEmpty()) {
            getLog().error("--- BUILD FAILED DUE TO CRITICAL EXCEPTIONS ---");
            getLog().error("The following exceptions were found and are NOT on the configured exclusion list:");
            for (String ex : criticalExceptionsFound) {
                getLog().error(" -> " + ex);
            }
            throw new MojoExecutionException("Critical exceptions found in Failsafe reports. See logs for details.");
        } else {
            getLog().info("All test errors are on the exclusion list or tests were successful. Build continues.");
        }
    }
}

package org.jboss.arquillian.container.jetty.embedded_12_ee9;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Locale;
import java.util.logging.Logger;

import org.eclipse.jetty.ee9.annotations.AnnotationConfiguration;
import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.ee9.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.ee9.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.ee9.webapp.FragmentConfiguration;
import org.eclipse.jetty.ee9.webapp.JettyWebXmlConfiguration;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;

public class ArquillianAppProvider extends AbstractLifeCycle implements AppProvider {
    private static final Logger LOG = Logger.getLogger(ArquillianAppProvider.class.getName());

    /**
     * The prefix assigned to the temporary file where the archive is exported
     */
    private static final String EXPORT_FILE_PREFIX = "export";

    /**
     * Directory into which we'll extract export the war files
     */
    private static final File EXPORT_DIR;

    private static final String SLASH = "/";

    static {
        /*
         * Use of java.io.tmpdir Should be a last-resort fallback for temp directory.
         * 
         * Use of java.io.tmpdir on CI systems is dangerous (overwrite possibility is extremely high)
         * 
         * Use of java.io.tmpdir on Unix systems is unreliable (due to common /tmp dir cleanup processes)
         */
        File systemDefaultTmpDir = new File(System.getProperty("java.io.tmpdir"));

        // If running under maven + surefire, use information provided by surefire.
        String baseDirVal = System.getProperty("basedir");

        File mavenTmpDir = null;
        if (baseDirVal != null) {
            Path baseDir = Paths.get(baseDirVal);
            if (Files.isDirectory(baseDir)) {
                File targetDir = new File(baseDir.toFile(), "target");
                if (targetDir.isDirectory()) {
                    mavenTmpDir = new File(targetDir, "arquillian-jetty-temp");
                    mavenTmpDir.mkdirs();
                }
            }
        }

        if ((mavenTmpDir != null) && mavenTmpDir.isDirectory()) {
            EXPORT_DIR = mavenTmpDir;
        } else {
            EXPORT_DIR = systemDefaultTmpDir;
        }

        // If the temp location doesn't exist or isn't a directory
        if (!EXPORT_DIR.exists() || !EXPORT_DIR.isDirectory()) {
            throw new IllegalStateException("Could not obtain export directory \"" + EXPORT_DIR.getAbsolutePath() + "\"");
        }
    }

    private final JettyEmbeddedConfiguration config;
    private DeploymentManager deploymentManager;
    private final Collection<WebAppContextProcessor> webAppContextProcessors;

    public ArquillianAppProvider(JettyEmbeddedConfiguration config, Collection<WebAppContextProcessor> webAppContextProcessors) {
        this.config = config;
        this.webAppContextProcessors = webAppContextProcessors;
    }

    protected App createApp(final Archive<?> archive) {
        String name = archive.getName();
        int extOff = name.lastIndexOf('.');
        if (extOff <= 0) {
            throw new RuntimeException("Not a valid Web Archive filename: " + name);
        }
        String ext = name.substring(extOff).toLowerCase();
        if (!ext.equals(".war")) {
            throw new RuntimeException("Not a recognized Web Archive: " + name);
        }
        name = name.substring(0, extOff);

        final File exported;
        try {
            if (this.config.isUseArchiveNameAsContext()) {
                Path tmpDirectory = Files.createTempDirectory("arquillian-jetty");
                Path archivePath = tmpDirectory.resolveSibling(archive.getName());
                Files.deleteIfExists(archivePath);
                exported = Files.createFile(archivePath).toFile();
                exported.deleteOnExit();
            } else {
                // If this method returns successfully then it is guaranteed that:
                // 1. The file denoted by the returned abstract pathname did not exist before this method was invoked, and
                // 2. Neither this method nor any of its variants will return the same abstract pathname again in the current invocation of the virtual machine.
                exported = File.createTempFile(EXPORT_FILE_PREFIX, archive.getName(), EXPORT_DIR);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not create temporary File in " + EXPORT_DIR + " to write exported archive",
                e);
        }
        // We are overwriting the temporary file placeholder reserved by File#createTemplateFile()
        archive.as(ZipExporter.class).exportTo(exported, true);

        // Mark to delete when we come down
        // exported.deleteOnExit();

        // Add the context
        URI uri = exported.toURI();
        LOG.info("Webapp archive location: " + uri.toASCIIString());

        return new ArchiveApp(deploymentManager, this, Path.of(uri), archive);
    }

    static class ArchiveApp extends App  {
        private final Archive<?> archive;

        private WebAppContext webAppContext;

        public ArchiveApp(DeploymentManager manager, AppProvider provider, Path originId, Archive<?> archive) {
            super(manager, provider, originId);
            this.archive = archive;
        }

        protected WebAppContext getWebAppContext() {
            return this.webAppContext;
        }

        protected void setWebAppContext(WebAppContext webAppContext) {
            this.webAppContext = webAppContext;
        }

        private ArchiveApp webAppContext(WebAppContext webAppContext) {
            this.webAppContext = webAppContext;
            return this;
        }
    }

    private static boolean isWebArchiveFile(Path path) {
        if (!path.toFile().isFile()) {
            return false;
        } else {
            String name = path.toFile().getName().toLowerCase(Locale.ENGLISH);
            return name.endsWith(".war") || name.endsWith(".jar");
        }
    }

    @Override
    public ContextHandler createContextHandler(App app) throws Exception {
        if (app instanceof ArquillianAppProvider.ArchiveApp) {
            if(((ArchiveApp)app).webAppContext!=null) {
                return ((ArchiveApp)app).webAppContext.getCoreContextHandler();
            }
        }
        Resource resource = ResourceFactory.root().newResource(app.getPath());
        Path file = resource.getPath();
        if (!resource.exists())
            throw new IllegalStateException("App resouce does not exist " + resource);

        String context = file.toFile().getName();

        if (isWebArchiveFile(file)) {
            // Context Path is the same as the archive.
            context = context.substring(0, context.length() - 4);
        } else {
            throw new IllegalStateException("unable to create ContextHandler for " + app);
        }

        // Ensure "/" is Not Trailing in context paths.
        if (context.endsWith("/") && context.length() > 0) {
            context = context.substring(0, context.length() - 1);
        }

        // Start building the webapplication
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setDisplayName(context);
        webAppContext.setLogUrlOnStart(true);

        String configuredConfigurationClasses = config.getConfigurationClasses();
        if (configuredConfigurationClasses != null && configuredConfigurationClasses.trim().length() > 0) {
            // User provided classlist, use it as-is.
            webAppContext.setConfigurationClasses(configuredConfigurationClasses.split(","));

        } else {
            // Arquillian assumption is that all features of Servlet 3.1 are available.
            // This means that annotation scanning is enabled by default.
            // That means jetty-plus is mandatory.

            // Applying equivalent of etc/jetty-annotations.xml
            webAppContext.addConfiguration(new JettyWebXmlConfiguration(),
                new AnnotationConfiguration());

            // Applying equivalent of etc/jetty-plus.xml
            webAppContext.addConfiguration(new FragmentConfiguration()
                , new EnvConfiguration(), new PlusConfiguration());
        }

        // special case of archive (or dir) named "root" is / context
        if (context.equalsIgnoreCase("root")) {
            context = SLASH;
        } else if (context.toLowerCase(Locale.ENGLISH).startsWith("root-")) {
            int dash = context.toLowerCase(Locale.ENGLISH).indexOf('-');
            String virtual = context.substring(dash + 1);
            webAppContext.setVirtualHosts(new String[]{virtual});
            context = SLASH;
        }

        // Ensure "/" is Prepended to all context paths.
        if (context.charAt(0) != '/') {
            context = "/" + context;
        }

        webAppContext.setContextPath(context);
        webAppContext.setWar(file.toFile().getAbsolutePath());
        if (config.hasDefaultsDescriptor()) {
            webAppContext.setDefaultsDescriptor(config.getDefaultsDescriptor().toASCIIString());
        }
        webAppContext.setExtractWAR(true);

        webAppContext.setParentLoaderPriority(config.getClassloaderBehavior() == JettyEmbeddedConfiguration.ClassLoaderBehavior.JAVA_SPEC);

        if (config.getTempDirectory() != null) {
            /*
             * Since the Temp Dir is really a context base temp directory, Lets set the Temp Directory in a way similar to how WebInfConfiguration does it,
             * instead of setting the WebAppContext.setTempDirectory(File). If we used .setTempDirectory(File) all webapps will wind up in the same temp / work
             * directory, overwriting each others work.
             */
            webAppContext.setAttribute(WebAppContext.BASETEMPDIR, config.getTempDirectory());
        }

        webAppContextProcessors.forEach(processor -> processor.process(webAppContext, ((ArchiveApp)app).archive));

        ((ArchiveApp)app).setWebAppContext(webAppContext);

        return webAppContext.getCoreContextHandler();
    }

    @Override
    public String getEnvironmentName() {
        return "ee9";
    }

    @Override
    public void setDeploymentManager(DeploymentManager deploymentManager) {
        this.deploymentManager = deploymentManager;
    }
}
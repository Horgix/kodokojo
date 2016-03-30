package io.kodokojo.project.starter.marathon;

import io.kodokojo.commons.model.Service;
import io.kodokojo.commons.utils.servicelocator.marathon.MarathonServiceLocator;
import io.kodokojo.model.*;
import io.kodokojo.model.Stack;
import io.kodokojo.project.starter.BrickManager;
import io.kodokojo.project.starter.ConfigurerData;
import io.kodokojo.project.starter.BrickConfigurer;
import io.kodokojo.service.BrickAlreadyExist;
import io.kodokojo.service.BrickConfigurerProvider;
import org.apache.commons.lang.StringUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import retrofit.RestAdapter;
import retrofit.RetrofitError;
import retrofit.mime.TypedString;

import javax.inject.Inject;
import java.io.StringWriter;
import java.util.*;

import static org.apache.commons.lang.StringUtils.isBlank;

public class MarathonBrickManager implements BrickManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarathonBrickManager.class);

    private static final Properties VE_PROPERTIES = new Properties();

    static {
        VE_PROPERTIES.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
        VE_PROPERTIES.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
        VE_PROPERTIES.setProperty("runtime.log.logsystem.class", "org.apache.velocity.runtime.log.NullLogChute");
    }

    private final String marathonUrl;

    private final MarathonRestApi marathonRestApi;

    private final MarathonServiceLocator marathonServiceLocator;

    private final BrickConfigurerProvider brickConfigurerProvider;

    private final boolean constrainByTypeAttribute;

    private final String domain;

    @Inject
    public MarathonBrickManager(String marathonUrl, MarathonServiceLocator marathonServiceLocator, BrickConfigurerProvider brickConfigurerProvider, boolean constrainByTypeAttribute, String domain) {
        if (isBlank(marathonUrl)) {
            throw new IllegalArgumentException("marathonUrl must be defined.");
        }
        if (marathonServiceLocator == null) {
            throw new IllegalArgumentException("marathonServiceLocator must be defined.");
        }
        if (brickConfigurerProvider == null) {
            throw new IllegalArgumentException("brickConfigurerProvider must be defined.");
        }
        if (isBlank(domain)) {
            throw new IllegalArgumentException("domain must be defined.");
        }
        this.marathonUrl = marathonUrl;
        RestAdapter adapter = new RestAdapter.Builder().setEndpoint(marathonUrl).build();
        marathonRestApi = adapter.create(MarathonRestApi.class);
        this.marathonServiceLocator = marathonServiceLocator;
        this.brickConfigurerProvider = brickConfigurerProvider;
        this.constrainByTypeAttribute = constrainByTypeAttribute;
        this.domain = domain;
    }

    public MarathonBrickManager(String marathonUrl, MarathonServiceLocator marathonServiceLocator, BrickConfigurerProvider brickConfigurerProvider, String domain) {
        this(marathonUrl, marathonServiceLocator, brickConfigurerProvider, true, domain);
    }


    @Override
    public Set<Service> start(ProjectConfiguration projectConfiguration, BrickType brickType) throws BrickAlreadyExist {
        if (projectConfiguration == null) {
            throw new IllegalArgumentException("projectConfiguration must be defined.");
        }
        if (brickType == null) {
            throw new IllegalArgumentException("brickType must be defined.");
        }

        Iterator<BrickConfiguration> brickConfigurations = projectConfiguration.getDefaultBrickConfigurations();
        BrickConfiguration brickConfiguration = getBrickConfiguration(brickType, brickConfigurations);

        if (brickConfiguration == null) {
            throw new IllegalStateException("Unable to find brickConfiguration for " + brickType);
        }
        String name = projectConfiguration.getName().toLowerCase();
        String type = brickType.name().toLowerCase();

        String id = "/" + name.toLowerCase() + "/" + brickConfiguration.getType().name().toLowerCase();
        String body = provideStartAppBody(projectConfiguration, brickConfiguration, id);
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Push new Application configuration to Marathon :\n{}", body);
        }
        TypedString input = new TypedString(body);
        try {
            marathonRestApi.startApplication(input);
        } catch (RetrofitError e) {
            if (e.getResponse().getStatus() == 409) {
                throw new BrickAlreadyExist(e,type, name);
            }
        }
        Set<Service> res = new HashSet<>();
        if (brickConfiguration.isWaitRunning()) {
            marathonServiceLocator.getService(type, name);
            boolean haveHttpService = getAnHttpService(res);
            // TODO remove this, listen the Marathon event bus instead
            int nbTry = 0;
            int maxNbTry = 10000;
            while (nbTry < maxNbTry && !haveHttpService) {
                nbTry++;
                res = marathonServiceLocator.getService(type, name);
                haveHttpService = getAnHttpService(res);
                if (!haveHttpService) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        return res;
    }

    @Override
    public void configure(ProjectConfiguration projectConfiguration, BrickType brickType) {
        if (projectConfiguration == null) {
            throw new IllegalArgumentException("projectConfiguration must be defined.");
        }
        if (brickType == null) {
            throw new IllegalArgumentException("brickType must be defined.");
        }

        Iterator<BrickConfiguration> brickConfigurations = projectConfiguration.getDefaultBrickConfigurations();
        BrickConfiguration brickConfiguration = getBrickConfiguration(brickType, brickConfigurations);

        if (brickConfiguration == null) {
            throw new IllegalStateException("Unable to find brickConfiguration for " + brickType);
        }
        String name = projectConfiguration.getName().toLowerCase();
        String type = brickType.name().toLowerCase();
        BrickConfigurer configurer = brickConfigurerProvider.provideFromBrick(brickConfiguration.getBrick());
        if (configurer != null) {
            Set<Service> services = marathonServiceLocator.getService(type, name);
            List<User> users = projectConfiguration.getUsers();
            String entrypoint = getEntryPoint(services);
            ConfigurerData configurerData = configurer.configure(new ConfigurerData(projectConfiguration.getName(),entrypoint,domain, projectConfiguration.getOwner()  , users));
            configurer.addUsers(configurerData, users);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Adding users {} to brick {}", StringUtils.join(users, ","), brickType);
            }
        } else if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Configurer Not defined for brick {}", brickType);
        }

    }

    private String getEntryPoint(Set<Service> services) {
        String res = null;
        Iterator<Service> iterator = services.iterator();
        while (res == null && iterator.hasNext()) {
            Service service = iterator.next();
            String name = service.getName();
            if (name.endsWith("-80") || name.endsWith("-8080") || name.endsWith("-443")) {
                res = "http" + (name.endsWith("-443") ? "s" : "") + "://" + service.getHost() + ":" + service.getPort();
            }
        }
        return res;
    }

    @Override
    public boolean stop(BrickDeploymentState brickDeploymentState) {
        if (brickDeploymentState == null) {
            throw new IllegalArgumentException("brickDeploymentState must be defined.");
        }
        return false;
    }

    private BrickConfiguration getBrickConfiguration(BrickType brickType, Iterator<BrickConfiguration> iterator) {
        BrickConfiguration brickConfiguration = null;
        while (brickConfiguration == null && iterator.hasNext()) {
            BrickConfiguration configuration = iterator.next();
            if (configuration.getType().equals(brickType)) {
                brickConfiguration = configuration;
            }
        }
        return brickConfiguration;
    }

    @Override
    public Stack.OrchestratorType getOrchestratorType() {
        return Stack.OrchestratorType.MARATHON;
    }

    private String provideStartAppBody(ProjectConfiguration projectConfiguration, BrickConfiguration brickConfiguration, String id) {
        VelocityEngine ve = new VelocityEngine();
        ve.init(VE_PROPERTIES);

        Template template = ve.getTemplate("marathon/" + brickConfiguration.getBrick().getName().toLowerCase() + ".json.vm");

        VelocityContext context = new VelocityContext();
        context.put("ID", id);
        context.put("marathonUrl", marathonUrl);
        context.put("project", projectConfiguration);
        context.put("projectName", projectConfiguration.getName().toLowerCase());
        context.put("stack", projectConfiguration.getDefaultStackConfiguration());
        context.put("brick", brickConfiguration);
        context.put("constrainByTypeAttribute", this.constrainByTypeAttribute);
        StringWriter sw = new StringWriter();
        template.merge(context, sw);
        return sw.toString();
    }

    private boolean getAnHttpService(Set<Service> services) {
        boolean res = false;
        Iterator<Service> iterator = services.iterator();
        while (!res && iterator.hasNext()) {
            Service service = iterator.next();
            String name = service.getName();
            res = name.endsWith("-80") || name.endsWith("-8080") || name.endsWith("-443");
        }
        return res;
    }

}

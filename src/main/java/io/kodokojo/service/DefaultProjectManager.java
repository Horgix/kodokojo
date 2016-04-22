package io.kodokojo.service;

/*
 * #%L
 * project-manager
 * %%
 * Copyright (C) 2016 Kodo-kojo
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

import io.kodokojo.commons.model.Service;
import io.kodokojo.commons.utils.servicelocator.ServiceLocator;
import io.kodokojo.commons.utils.ssl.SSLKeyPair;
import io.kodokojo.commons.utils.ssl.SSLUtils;
import io.kodokojo.model.*;
import io.kodokojo.model.Stack;
import io.kodokojo.project.starter.BrickManager;
import io.kodokojo.service.dns.DnsEntry;
import io.kodokojo.service.dns.DnsManager;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.*;
import java.util.concurrent.*;

import static org.apache.commons.lang.StringUtils.isBlank;

public class DefaultProjectManager implements ProjectManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultProjectManager.class);

    private final DnsManager dnsManager;

    private final BrickManager brickManager;

    private final SSLKeyPair caKey;

    private final String domain;

    private final ConfigurationStore configurationStore;

    private final ProjectStore projectStore;

    private final BootstrapConfigurationProvider bootstrapConfigurationProvider;

    private final ExecutorService executorService;

    private final long sslCaDuration;

    @Inject
    public DefaultProjectManager(SSLKeyPair caKey, String domain, DnsManager dnsManager, BrickManager brickManager, ConfigurationStore configurationStore, ProjectStore projectStore, BootstrapConfigurationProvider bootstrapConfigurationProvider, ExecutorService executorService, long sslCaDuration) {
        if (caKey == null) {
            throw new IllegalArgumentException("caKey must be defined.");
        }
        if (isBlank(domain)) {
            throw new IllegalArgumentException("domain must be defined.");
        }
        if (dnsManager == null) {
            throw new IllegalArgumentException("dnsManager must be defined.");
        }
        if (configurationStore == null) {
            throw new IllegalArgumentException("configurationStore must be defined.");
        }
        if (brickManager == null) {
            throw new IllegalArgumentException("brickManager must be defined.");
        }
        if (projectStore == null) {
            throw new IllegalArgumentException("projectStore must be defined.");
        }
        if (bootstrapConfigurationProvider == null) {
            throw new IllegalArgumentException("bootstrapConfigurationProvider must be defined.");
        }
        if (executorService == null) {
            throw new IllegalArgumentException("executorService must be defined.");
        }
        this.dnsManager = dnsManager;
        this.brickManager = brickManager;
        this.executorService = executorService;
        this.caKey = caKey;
        this.domain = domain;
        this.configurationStore = configurationStore;
        this.projectStore = projectStore;
        this.bootstrapConfigurationProvider = bootstrapConfigurationProvider;
        this.sslCaDuration = sslCaDuration;
    }

    @Override
    public BootstrapStackData bootstrapStack(String projectName, String stackName, StackType stackType) {
        if (!projectStore.projectNameIsValid(projectName)) {
            throw new IllegalArgumentException("project name " + projectName + " isn't valid.");
        }
        String loadBalancerIp = bootstrapConfigurationProvider.provideLoadBalancerIp(projectName, stackName);
        int sshPortEntrypoint = 0;
        if (stackType == StackType.BUILD) {
            sshPortEntrypoint = bootstrapConfigurationProvider.provideSshPortEntrypoint(projectName, stackName);
        }
        BootstrapStackData res = new BootstrapStackData(projectName, stackName, loadBalancerIp, sshPortEntrypoint);
        configurationStore.storeBootstrapStackData(res);
        return res;
    }

    @Override
    public Project start(ProjectConfiguration projectConfiguration) throws ProjectAlreadyExistException {
        if (projectConfiguration == null) {
            throw new IllegalArgumentException("projectConfiguration must be defined.");
        }
        if (CollectionUtils.isEmpty(projectConfiguration.getStackConfigurations())) {
            throw new IllegalArgumentException("Unable to create a project without stack.");
        }

        String projectName = projectConfiguration.getName();
        String projectDomainName = (projectName + "." + domain).toLowerCase();
        SSLKeyPair projectCaSSL = SSLUtils.createSSLKeyPair(projectDomainName, caKey.getPrivateKey(), caKey.getPublicKey(), caKey.getCertificates(), sslCaDuration, true);

        Set<Stack> stacks = new HashSet<>();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (StackConfiguration stackConfiguration : projectConfiguration.getStackConfigurations()) {
            Set<BrickDeploymentState> brickEntities = new HashSet<>();
            String lbIp = stackConfiguration.getLoadBalancerIp();

            for (BrickConfiguration brickConfiguration : stackConfiguration.getBrickConfigurations()) {
                    Callable<Void> task = startBrick(projectConfiguration, projectName, projectDomainName, projectCaSSL, lbIp, brickConfiguration);
                    tasks.add(task);
            }

            Stack stack = new Stack(stackConfiguration.getName(), stackConfiguration.getType(), brickManager.getOrchestratorType(), brickEntities);
            stacks.add(stack);

        }
        try {
            List<Future<Void>> futures = executorService.invokeAll(tasks);
            futures.forEach(f -> {
                try {
                    f.get();
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }
            });

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        Project project = new Project(projectName, projectCaSSL, new Date(), stacks);
        return project;
    }

    private Callable<Void> startBrick(ProjectConfiguration projectConfiguration, String projectName, String projectDomainName, SSLKeyPair projectCaSSL, String lbIp, BrickConfiguration brickConfiguration) throws ProjectAlreadyExistException {
        return () -> {
            BrickType brickType = brickConfiguration.getType();
            if (brickType.isRequiredHttpExposed()) {
                String brickTypeName = brickType.name().toLowerCase();
                String brickDomainName = brickTypeName + "." + projectDomainName;
                dnsManager.createOrUpdateDnsEntry(new DnsEntry(brickDomainName, DnsEntry.Type.A, lbIp));
                SSLKeyPair brickSslKeyPair = SSLUtils.createSSLKeyPair(brickDomainName, projectCaSSL.getPrivateKey(), projectCaSSL.getPublicKey(), projectCaSSL.getCertificates());
                configurationStore.storeSSLKeys(projectName, brickTypeName, brickSslKeyPair);
            }
            Set<Service> services = null;
            try {
                services = brickManager.start(projectConfiguration, brickType);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("{} for project {} started : {}", brickType, projectName, StringUtils.join(services, ","));
                }
                brickManager.configure(projectConfiguration, brickType);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("{} for project {} configured", brickType, projectName);
                }
            } catch (BrickAlreadyExist brickAlreadyExist) {
                LOGGER.error("Brick {} already exist for project {}, not reconfigure it.", brickAlreadyExist.getBrickName(), brickAlreadyExist.getProjectName());
            }
            return null;
        };

    }
}

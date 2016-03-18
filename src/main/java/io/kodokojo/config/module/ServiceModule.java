package io.kodokojo.config.module;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import io.kodokojo.commons.utils.servicelocator.marathon.MarathonServiceLocator;
import io.kodokojo.commons.utils.ssl.SSLKeyPair;
import io.kodokojo.commons.utils.ssl.SSLRootCaKey;
import io.kodokojo.config.ApplicationConfig;
import io.kodokojo.config.AwsConfig;
import io.kodokojo.config.MarathonConfig;
import io.kodokojo.config.RedisConfig;
import io.kodokojo.entrypoint.RestEntrypoint;
import io.kodokojo.lifecycle.ApplicationLifeCycleManager;
import io.kodokojo.project.starter.BrickManager;
import io.kodokojo.project.starter.marathon.MarathonBrickManager;
import io.kodokojo.service.*;
import io.kodokojo.service.dns.DnsEntry;
import io.kodokojo.service.dns.DnsManager;
import io.kodokojo.service.dns.NoOpDnsManager;
import io.kodokojo.service.dns.route53.Route53DnsManager;
import io.kodokojo.service.marathon.MarathonConfigurationStore;
import io.kodokojo.service.redis.RedisBootstrapConfigurationProvider;
import io.kodokojo.service.redis.RedisProjectStore;
import io.kodokojo.service.user.SimpleCredential;

import javax.crypto.SecretKey;
import javax.inject.Named;
import java.util.List;

public class ServiceModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(new TypeLiteral<UserAuthenticator<SimpleCredential>>() {/**/}).toProvider(SimpleUserAuthenticatorProvider.class);
    }

    @Provides
    @Singleton
    ApplicationLifeCycleManager provideApplicationLifeCycleManager() {
        return new ApplicationLifeCycleManager();
    }

    @Provides
    @Singleton
    RestEntrypoint provideRestEntrypoint(ApplicationConfig applicationConfig, UserManager userManager, UserAuthenticator<SimpleCredential> userAuthenticator, ProjectManager projectManager, ApplicationLifeCycleManager applicationLifeCycleManager) {
        RestEntrypoint restEntrypoint = new RestEntrypoint(applicationConfig.port(), userManager, userAuthenticator, projectManager);
        applicationLifeCycleManager.addService(restEntrypoint);
        return restEntrypoint;
    }

    @Provides
    @Singleton
    ProjectStore provideProjectStore(@Named("securityKey") SecretKey key, RedisConfig redisConfig, ApplicationLifeCycleManager applicationLifeCycleManager) {
        RedisProjectStore redisProjectStore = new RedisProjectStore(key, redisConfig.host(), redisConfig.port());
        applicationLifeCycleManager.addService(redisProjectStore);
        return redisProjectStore;
    }

    @Provides
    @Singleton
    BootstrapConfigurationProvider provideBootstrapConfigurationProvider(ApplicationConfig applicationConfig, RedisConfig redisConfig, ApplicationLifeCycleManager applicationLifeCycleManager) {
        RedisBootstrapConfigurationProvider redisBootstrapConfigurationProvider = new RedisBootstrapConfigurationProvider(redisConfig.host(), redisConfig.port(), applicationConfig.defaultLoadbalancerIp(), applicationConfig.initialSshPort());
        applicationLifeCycleManager.addService(redisBootstrapConfigurationProvider);
        return redisBootstrapConfigurationProvider;
    }

    @Provides
    @Singleton
    BrickManager provideBrickManager(MarathonConfig marathonConfig) {
        MarathonServiceLocator marathonServiceLocator = new MarathonServiceLocator(marathonConfig.url());
        return new MarathonBrickManager(marathonConfig.url(),marathonServiceLocator);
    }

    @Provides
    @Singleton
    ConfigurationStore provideConfigurationStore(MarathonConfig marathonConfig) {
        return new MarathonConfigurationStore(marathonConfig.url());
    }

    @Provides
    @Singleton
    DnsManager provideDnsManager(ApplicationConfig applicationConfig, AwsConfig awsConfig) {
        if (System.getenv("AWS_SECRET_ACCESS_KEY") == null) {
            return new NoOpDnsManager();
        } else {
            return new Route53DnsManager(applicationConfig.domain(), Region.getRegion(Regions.fromName(awsConfig.region())));
        }
    }

    @Provides
    @Singleton
    ProjectManager provideProjectManager(SSLKeyPair caKey, ApplicationConfig applicationConfig, DnsManager dnsManager, BrickManager brickManager, ConfigurationStore configurationStore, ProjectStore projectStore, BootstrapConfigurationProvider bootstrapConfigurationProvider) {
        return new DefaultProjectManager(caKey, applicationConfig.domain(), dnsManager, brickManager, configurationStore, projectStore, bootstrapConfigurationProvider, applicationConfig.sslCaDuration());
    }

}

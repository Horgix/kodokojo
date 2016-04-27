package io.kodokojo.bdd.stage;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.AfterScenario;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import io.kodokojo.Launcher;
import io.kodokojo.commons.model.Service;
import io.kodokojo.commons.utils.DockerTestSupport;
import io.kodokojo.commons.utils.RSAUtils;
import io.kodokojo.commons.utils.ssl.SSLKeyPair;
import io.kodokojo.commons.utils.ssl.SSLUtils;
import io.kodokojo.config.module.ActorModule;
import io.kodokojo.entrypoint.RestEntrypoint;
import io.kodokojo.model.User;
import io.kodokojo.project.starter.BrickManager;
import io.kodokojo.service.*;
import io.kodokojo.service.dns.DnsManager;
import io.kodokojo.service.redis.RedisProjectStore;
import io.kodokojo.service.user.SimpleUserAuthenticator;
import io.kodokojo.service.user.redis.RedisUserManager;
import io.kodokojo.test.utils.TestUtils;
import org.mockito.Mockito;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;

public class BrickStateNotificationGiven<SELF extends BrickStateNotificationGiven<?>> extends Stage<SELF> {


    @ProvidedScenarioState
    public DockerTestSupport dockerTestSupport = new DockerTestSupport();

    @ProvidedScenarioState
    RestEntrypoint restEntrypoint;

    @ProvidedScenarioState
    ConfigurationStore configurationStore;

    @ProvidedScenarioState
    BootstrapConfigurationProvider bootstrapProvider;

    @ProvidedScenarioState
    String entryPointUrl;

    @ProvidedScenarioState
    User currentUser;

    @ProvidedScenarioState
    BrickManager brickManager;

    @ProvidedScenarioState
    DnsManager dnsManager;

    public SELF kodokojo_is_started() {

        Service service = StageUtils.startDockerRedis(dockerTestSupport);

        brickManager = mock(BrickManager.class);
        bootstrapProvider = mock(BootstrapConfigurationProvider.class);
        dnsManager = mock(DnsManager.class);
        configurationStore = mock(ConfigurationStore.class);

        Mockito.when(bootstrapProvider.provideLoadBalancerIp(anyString(),anyString())).thenReturn("192.168.22.3");
        Mockito.when(bootstrapProvider.provideSshPortEntrypoint(anyString(),anyString())).thenReturn(10022);

        SecretKey tmpKey = null;
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            tmpKey = kg.generateKey();
        } catch (NoSuchAlgorithmException e) {
            fail(e.getMessage());
        }
        final SecretKey secreteKey = tmpKey;
        RedisUserManager redisUserManager = new RedisUserManager(secreteKey, service.getHost(), service.getPort());
        RedisProjectStore redisProjectStore = new RedisProjectStore(secreteKey, service.getHost(), service.getPort(), new DefaultBrickFactory(null));
        Injector injector = Guice.createInjector(new ActorModule(), new AbstractModule() {
            @Override
            protected void configure() {
                bind(UserManager.class).toInstance(redisUserManager);
                bind(ProjectStore.class).toInstance(redisProjectStore);
                bind(BrickStateMsgDispatcher.class).toInstance(new BrickStateMsgDispatcher());
                bind(BrickManager.class).toInstance(brickManager);
                bind(DnsManager.class).toInstance(dnsManager);
                bind(ConfigurationStore.class).toInstance(configurationStore);
            }
        });
        Launcher.INJECTOR = injector;

        int port = TestUtils.getEphemeralPort();
        entryPointUrl = "localhost:" + port;
        KeyPair keyPair = null;
        try {
            keyPair = RSAUtils.generateRsaKeyPair();
        } catch (NoSuchAlgorithmException e) {
            fail(e.getMessage());
        }
        SSLKeyPair caKey = SSLUtils.createSelfSignedSSLKeyPair("Fake CA", (RSAPrivateKey) keyPair.getPrivate(), (RSAPublicKey) keyPair.getPublic());
        DefaultProjectManager projectManager = new DefaultProjectManager(caKey, "kodokojo.dev", configurationStore, redisProjectStore, bootstrapProvider, dnsManager, injector.getInstance(BrickConfigurationStarter.class), 10000000);
        restEntrypoint = new RestEntrypoint(port, injector.getInstance(UserManager.class), new SimpleUserAuthenticator(redisUserManager),redisProjectStore, projectManager,new DefaultBrickFactory(null));
        restEntrypoint.start();
        return self();
    }

    public SELF i_am_user_$(@Quoted String username) {
        currentUser = StageUtils.createUser(username, Launcher.INJECTOR.getInstance(UserManager.class));
        return self();
    }

    @AfterScenario
    public void tear_down() {
        dockerTestSupport.stopAndRemoveContainer();
        if (restEntrypoint != null) {
            restEntrypoint.stop();
            restEntrypoint = null;
        }
    }
}

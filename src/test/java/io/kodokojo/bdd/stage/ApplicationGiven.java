/**
 * Kodo Kojo - Software factory done right
 * Copyright © 2016 Kodo Kojo (infos@kodokojo.io)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package io.kodokojo.bdd.stage;



import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Image;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.*;
import io.kodokojo.Launcher;
import io.kodokojo.brick.BrickUrlFactory;
import io.kodokojo.brick.DefaultBrickFactory;
import io.kodokojo.brick.DefaultBrickUrlFactory;
import io.kodokojo.commons.config.DockerConfig;
import io.kodokojo.commons.model.Service;
import io.kodokojo.commons.utils.DockerTestSupport;
import io.kodokojo.commons.utils.RSAUtils;
import io.kodokojo.commons.utils.properties.PropertyResolver;
import io.kodokojo.commons.utils.properties.provider.*;
import io.kodokojo.config.ApplicationConfig;
import io.kodokojo.entrypoint.RestEntryPoint;
import io.kodokojo.entrypoint.UserAuthenticator;
import io.kodokojo.model.Entity;
import io.kodokojo.model.User;
import io.kodokojo.service.ProjectManager;
import io.kodokojo.service.redis.RedisEntityStore;
import io.kodokojo.service.redis.RedisProjectStore;
import io.kodokojo.service.redis.RedisUserStore;
import io.kodokojo.service.store.EntityStore;
import io.kodokojo.service.store.ProjectStore;
import io.kodokojo.service.store.UserStore;
import io.kodokojo.service.user.SimpleCredential;
import io.kodokojo.service.user.SimpleUserAuthenticator;
import io.kodokojo.test.utils.TestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;

public class ApplicationGiven<SELF extends ApplicationGiven<?>> extends Stage<SELF> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationGiven.class);

    private static final Map<String, String> USER_PASSWORD = new HashMap<>();

    static {
        USER_PASSWORD.put("jpthiery", "jpascal");
    }

    @ProvidedScenarioState
    public DockerTestSupport dockerTestSupport = new DockerTestSupport();

    @ProvidedScenarioState
    DockerClient dockerClient;

    @ProvidedScenarioState
    String redisHost;

    @ProvidedScenarioState
    int redisPort;

    @ProvidedScenarioState
    RestEntryPoint restEntryPoint;

    @ProvidedScenarioState
    String restEntryPointHost;

    @ProvidedScenarioState
    int restEntryPointPort;

    @ProvidedScenarioState
    RedisUserStore userManager;

    @ProvidedScenarioState
    String currentUserLogin;

    @ProvidedScenarioState
    String whoAmI;

    @ProvidedScenarioState
    Map<String, UserInfo> currentUsers = new HashMap<>();

    @ProvidedScenarioState
    ProjectManager projectManager;

    @ProvidedScenarioState
    ProjectStore projectStore;

    @ProvidedScenarioState
    EntityStore entityStore;

    @BeforeScenario
    public void create_a_docker_client() {
        dockerClient = dockerTestSupport.getDockerClient();
        Injector injector = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                LinkedList<PropertyValueProvider> propertyValueProviders = new LinkedList<>();

                propertyValueProviders.add(new SystemPropertyValueProvider());
                propertyValueProviders.add(new SystemEnvValueProvider());
                OrderedMergedValueProvider valueProvider = new OrderedMergedValueProvider(propertyValueProviders);
                PropertyResolver resolver = new PropertyResolver(new DockerConfigValueProvider(valueProvider));

                bind(DockerConfig.class).toInstance(resolver.createProxy(DockerConfig.class));


            }
        });
        Launcher.INJECTOR = injector;
        DockerConfig dockerConfig = injector.getInstance(DockerConfig.class);
    }

    public SELF redis_is_started() {
        List<Image> images = dockerClient.listImagesCmd().exec();
        boolean foundRedis = false;
        Iterator<Image> iterator = images.iterator();
        while (iterator.hasNext() && !foundRedis) {
            Image image = iterator.next();
            foundRedis = image.getId().equals("redis") && Arrays.asList(image.getRepoTags()).contains("latest");
        }
        if (!foundRedis) {
            LOGGER.info("Pulling docker image redis:latest");
            dockerTestSupport.pullImage("redis:latest");
        }

        Service service = StageUtils.startDockerRedis(dockerTestSupport);
        redisHost = service.getHost();
        redisPort = service.getPort();

        return self();
    }

    public SELF kodokojo_restEntrypoint_is_available() {
        int port = TestUtils.getEphemeralPort();
        return kodokojo_restEntrypoint_is_available_on_port_$(port);
    }

    public SELF kodokojo_is_running() {
        redis_is_started();
        return kodokojo_restEntrypoint_is_available();
    }

    public SELF kodokojo_restEntrypoint_is_available_on_port_$(int port) {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(128);
            SecretKey aesKey = generator.generateKey();
            userManager = new RedisUserStore(aesKey, redisHost, redisPort);
            projectStore = new RedisProjectStore(aesKey, redisHost, redisPort, new DefaultBrickFactory());
            entityStore = new RedisEntityStore(aesKey, redisHost, redisPort);
            UserAuthenticator<SimpleCredential> userAuthenticator = new SimpleUserAuthenticator(userManager);
            projectManager = mock(ProjectManager.class);
            restEntryPoint = new RestEntryPoint(port, userManager, userAuthenticator, entityStore, projectStore, projectManager, new DefaultBrickFactory());
            Launcher.INJECTOR = Guice.createInjector(new AbstractModule() {
                @Override
                protected void configure() {
                    bind(UserStore.class).toInstance(userManager);
                    bind(ProjectStore.class).toInstance(projectStore);
                    bind(EntityStore.class).toInstance(entityStore);
                    bind(ApplicationConfig.class).toInstance(new ApplicationConfig() {
                        @Override
                        public int port() {
                            return 80;
                        }

                        @Override
                        public String domain() {
                            return "kodokojo.dev";
                        }

                        @Override
                        public String defaultLoadbalancerIp() {
                            return "192.168.99.100";
                        }

                        @Override
                        public int initialSshPort() {
                            return 1022;
                        }

                        @Override
                        public long sslCaDuration() {
                            return -1;
                        }
                    });
                    bind(BrickUrlFactory.class).toInstance(new DefaultBrickUrlFactory("kodokojo.dev"));
                }
            });
            restEntryPoint.start();
            restEntryPointPort = port;
            restEntryPointHost = "localhost";
        } catch (NoSuchAlgorithmException e) {
            fail(e.getMessage(), e);
        }
        return self();
    }

    public SELF i_will_be_user_$(@Quoted String username) {
        return i_am_user_$(username, false);
    }

    public SELF i_am_user_$(@Quoted String username, @Hidden boolean createUser) {
        currentUserLogin = username;
        if (createUser) {
            String identifier = userManager.generateId();
            String password = USER_PASSWORD.get(username) == null ? new BigInteger(130, new SecureRandom()).toString(32) : USER_PASSWORD.get(username);

            try {
                KeyPair keyPair = RSAUtils.generateRsaKeyPair();
                RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
                String email = username + "@kodokojo.io";

                User user = new User(identifier, username, username, email, password, RSAUtils.encodePublicKey(publicKey, email));

                Entity entity = new Entity(user.getUsername(), user);
                String entityId = entityStore.addEntity(entity);
                entityStore.addUserToEntity(user.getIdentifier(), entityId);
                user = new User(user.getIdentifier(), entityId, user.getFirstName(), user.getLastName(), username, email, password, user.getSshPublicKey());
                boolean userAdded = userManager.addUser(user);
                assertThat(userAdded).isTrue();
                whoAmI = username;
                currentUsers.put(currentUserLogin, new UserInfo(currentUserLogin, identifier, password, email));
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
        }
        return self();
    }

    @AfterScenario
    public void tear_down() {
        dockerTestSupport.stopAndRemoveContainer();
        if (restEntryPoint != null) {
            restEntryPoint.stop();
            restEntryPoint = null;
        }
    }

}

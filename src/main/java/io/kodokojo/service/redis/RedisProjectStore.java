package io.kodokojo.service.redis;

import io.kodokojo.commons.utils.RSAUtils;
import io.kodokojo.model.*;
import io.kodokojo.service.lifecycle.ApplicationLifeCycleListener;
import io.kodokojo.brick.BrickFactory;
import io.kodokojo.service.store.ProjectStore;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.math.BigInteger;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.apache.commons.lang.StringUtils.isBlank;

public class RedisProjectStore  extends  AbstractRedisStore implements ProjectStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisProjectStore.class);

    public static final String PROJECT_PREFIX = "project/";

    private static final String PROJECTCONFIGURATION_ID_KEY = "projectConfigurationId";

    public static final String PROJECTCONFIGURATION_PREFIX = "projectConfiguration/";

    public static final String ENTITY_PREFIX = "entity/";

    public static final String ENTITY_USER_PREFIX = "entityUsers/";

    private static final String ENTITY_ID_KEY = "entityId";

    private static final Pattern PROJECT_NAME_PATTERN = Pattern.compile("([a-zA-Z0-9\\-_]){4,20}");

    private final BrickFactory brickFactory;

    public RedisProjectStore(Key key, String host, int port, BrickFactory brickFactory) {
        super(key, host, port);
        if (brickFactory == null) {
            throw new IllegalArgumentException("brickFactory must be defined.");
        }
        this.brickFactory = brickFactory;
    }

    @Override
    protected String getStoreName() {
        return "ProjectRedisStore";
    }

    @Override
    protected String getGenerateIdKey() {
        return PROJECTCONFIGURATION_ID_KEY;
    }

    @Override
    public boolean projectNameIsValid(String projectName) {
        if (isBlank(projectName)) {
            return false;
        }
        Matcher matcher = PROJECT_NAME_PATTERN.matcher(projectName);
        if (matcher.matches()) {
            try (Jedis jedis = pool.getResource()) {
                byte[] key = RedisUtils.aggregateKey(PROJECT_PREFIX, projectName);
                return !jedis.exists(key);
            }
        }
        return false;
    }

    @Override
    public String addProjectConfiguration(ProjectConfiguration projectConfiguration) {
        if (projectConfiguration == null) {
            throw new IllegalArgumentException("projectConfiguration must be defined.");
        }
        if (StringUtils.isBlank(projectConfiguration.getEntityIdentifier())) {
            throw new IllegalArgumentException("EntityIdentifier must be defined.");
        }
        if (StringUtils.isNotBlank(projectConfiguration.getIdentifier())) {
            throw new IllegalArgumentException("ProjectConfiguration " + projectConfiguration.getName() + " already exist");
        }
        String identifier = generateId();
        return writeProjectConfiguration(new ProjectConfiguration(projectConfiguration.getEntityIdentifier(), identifier, projectConfiguration.getName(), IteratorUtils.toList(projectConfiguration.getAdmins()), projectConfiguration.getStackConfigurations(), IteratorUtils.toList(projectConfiguration.getUsers())));
    }

    @Override
    public void updateProjectConfiguration(ProjectConfiguration projectConfiguration) {
        if (projectConfiguration == null) {
            throw new IllegalArgumentException("projectConfiguration must be defined.");
        }
        writeProjectConfiguration(projectConfiguration);
    }

    private String writeProjectConfiguration(ProjectConfiguration projectConfiguration) {
        try (Jedis jedis = pool.getResource()) {
            String identifier = projectConfiguration.getIdentifier();
            Date versionDate = new Date();
            ProjectConfiguration toInsert = new ProjectConfiguration(projectConfiguration.getEntityIdentifier(), identifier, projectConfiguration.getName(), IteratorUtils.toList(projectConfiguration.getAdmins()), projectConfiguration.getStackConfigurations(), IteratorUtils.toList(projectConfiguration.getUsers()));
            toInsert.setVersionDate(versionDate);
            byte[] encryptedObject = RSAUtils.encryptObjectWithAES(key, toInsert);
            jedis.set(RedisUtils.aggregateKey(PROJECTCONFIGURATION_PREFIX, identifier), encryptedObject);
            return identifier;
        }
    }

    @Override
    public ProjectConfiguration getProjectConfigurationById(String identifier) {
        if (isBlank(identifier)) {
            throw new IllegalArgumentException("identifier must be defined.");
        }
        try (Jedis jedis = pool.getResource()) {
            byte[] projectConfigKey = RedisUtils.aggregateKey(PROJECTCONFIGURATION_PREFIX, identifier);
            if (jedis.exists(projectConfigKey)) {
                byte[] encrypted = jedis.get(projectConfigKey);
                ProjectConfiguration projectConfiguration = (ProjectConfiguration) RSAUtils.decryptObjectWithAES(key, encrypted);
                projectConfiguration.getStackConfigurations().forEach(this::fillStackConfigurationBrick);
                return projectConfiguration;
            }
        }
        return null;
    }

    private void fillStackConfigurationBrick(StackConfiguration stackConfiguration) {
        List<BrickConfiguration> brickConfigurationUpdated = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(stackConfiguration.getBrickConfigurations())) {
            brickConfigurationUpdated.addAll(stackConfiguration.getBrickConfigurations().stream().map(brickConfiguration -> new BrickConfiguration(brickFactory.createBrick(brickConfiguration.getName()), brickConfiguration.getName(), brickConfiguration.getType(), brickConfiguration.getUrl(), brickConfiguration.isWaitRunning())).collect(Collectors.toList()));
        }
        stackConfiguration.getBrickConfigurations().clear();
        stackConfiguration.getBrickConfigurations().addAll(brickConfigurationUpdated);
    }

    @Override
    public String addProject(Project project) {
        if (project == null) {
            throw new IllegalArgumentException("project must be defined.");
        }
        if (projectNameIsValid(project.getName())) {
            try (Jedis jedis = pool.getResource()) {
                String identifier = generateId();
                Project toAdd = new Project(identifier, project.getName(), project.getSslRootCaKey(), project.getSnapshotDate(), project.getStacks());
                byte[] encryptedObject = RSAUtils.encryptObjectWithAES(key, toAdd);
                jedis.set(RedisUtils.aggregateKey(PROJECT_PREFIX, project.getName()), encryptedObject);
                return identifier;
            }
        }
        return null;
    }

    @Override
    public Project getProjectByName(String name) {
        if (isBlank(name)) {
            throw new IllegalArgumentException("name must be defined.");
        }
        try (Jedis jedis = pool.getResource()) {
            byte[] projectKey = RedisUtils.aggregateKey(PROJECT_PREFIX, name);
            if (jedis.exists(projectKey)) {
                byte[] encrypted = jedis.get(projectKey);
                return (Project) RSAUtils.decryptObjectWithAES(key, encrypted);
            }
        }
        return null;
    }

}
package io.kodokojo.user;

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

import io.kodokojo.commons.project.model.User;
import io.kodokojo.commons.project.model.UserService;
import io.kodokojo.commons.utils.RSAUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

import static org.apache.commons.lang.StringUtils.isBlank;

public class RedisUserManager implements UserManager, UserAuthentificator<SimpleCredential> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisUserManager.class);

    private static final String ID_KEY = "kodokojo-userId";

    private static final String SALT_KEY = "kodokojo-salt-key";

    private static final byte[] NEW_USER_CONTENT = new byte[]{0, 1, 1, 0};

    private final Key key;

    private final JedisPool pool;

    private final String salt;

    private final MessageDigest messageDigest;

    public RedisUserManager(Key key, String host, int port) {
        if (key == null) {
            throw new IllegalArgumentException("key must be defined.");
        }
        if (isBlank(host)) {
            throw new IllegalArgumentException("host must be defined.");
        }
        this.key = key;
        pool = createJedisPool(host, port);

        try (Jedis jedis = pool.getResource()) {
            String salt = jedis.get(SALT_KEY);
            if (isBlank(salt)) {
                try {
                    SecureRandom secureRandom = SecureRandom.getInstance("SHA1PRNG");
                    this.salt = new BigInteger(130, secureRandom).toString(32);
                    jedis.set(SALT_KEY, this.salt);
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException("Unable to get a valid SecureRandom instance", e);
                }
            } else {
                this.salt = salt;
            }
        }

        try {
            messageDigest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Unable to get instance of SHA-1 digester");
        }
    }

    //  For testing
    protected JedisPool createJedisPool(String host, int port) {
        return new JedisPool(new JedisPoolConfig(), host, port);
    }

    @Override
    public String generateId() {
        try (Jedis jedis = pool.getResource()) {
            String id = salt + jedis.incr(ID_KEY).toString();
            String newId = hexEncode(messageDigest.digest(id.getBytes()));
            jedis.set(newId.getBytes(), NEW_USER_CONTENT);
            return newId;
        }
    }

    @Override
    public boolean identifierExpectedNewUser(String generatedId) {
        if (isBlank(generatedId)) {
            throw new IllegalArgumentException("generatedId must be defined.");
        }
        try (Jedis jedis = pool.getResource()) {
            return Arrays.equals(jedis.get(generatedId.getBytes()), NEW_USER_CONTENT);
        }
    }

    @Override
    public boolean addUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("user must be defined.");
        }
        ByteArrayOutputStream byteArray = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(byteArray); Jedis jedis = pool.getResource()) {
            byte[] previous = jedis.get(user.getIdentifier().getBytes());
            if (Arrays.equals(previous, NEW_USER_CONTENT)) {
                byte[] password = encrypt(user.getPassword());

                UserValue userValue = new UserValue(user, password);
                out.writeObject(userValue);
                jedis.set(user.getIdentifier().getBytes(), byteArray.toByteArray());
                jedis.set(user.getUsername(), user.getIdentifier());
                return true;
            } else {
                return false;
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to serialized UserValue.", e);
        }
    }


    @Override
    public boolean addUserService(UserService userService) {
        if (userService == null) {
            throw new IllegalArgumentException("userService must be defined.");
        }
        ByteArrayOutputStream byteArray = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(byteArray); Jedis jedis = pool.getResource()) {
            byte[] password = encrypt(userService.getPassword());
            byte[] privateKey = RSAUtils.wrap(key, userService.getPrivateKey());
            byte[] publicKey = RSAUtils.wrap(key, userService.getPublicKey());

            UserServiceValue userServiceValue = new UserServiceValue(userService, password, privateKey, publicKey);
            out.writeObject(userServiceValue);
            jedis.set(userService.getIdentifier().getBytes(), byteArray.toByteArray());
            jedis.set(userService.getName(), userService.getIdentifier());
            return true;
        } catch (IOException e) {
            throw new RuntimeException("Unable to serialized UserValue.", e);
        }
    }

    @Override
    public User getUserByUsername(String username) {
        if (isBlank(username)) {
            throw new IllegalArgumentException("username must be defined.");
        }
        try (Jedis jedis = pool.getResource()) {
            String identifier = jedis.get(username);
            if (isBlank(identifier)) {
                return null;
            }
            UserValue userValue = (UserValue) readFromRedis(identifier.getBytes());
            String password = decrypt(userValue.getPassword());
            return new User(identifier, userValue.getName(), userValue.getUsername(), userValue.getEmail(), password, userValue.getSshPublicKey());
        }
    }

    @Override
    public UserService getUserServiceByName(String name) {
        if (isBlank(name)) {
            throw new IllegalArgumentException("username must be defined.");
        }
        try (Jedis jedis = pool.getResource()) {
            String identifier = jedis.get(name);
            if (isBlank(identifier)) {
                return null;
            }
            UserServiceValue userServiceValue = (UserServiceValue) readFromRedis(identifier.getBytes());
            String password = decrypt(userServiceValue.getPassword());
            RSAPrivateKey privateKey = RSAUtils.unwrapPrivateRsaKey(key, userServiceValue.getPrivateKey());
            RSAPublicKey publicKey = RSAUtils.unwrapPublicRsaKey(key, userServiceValue.getPublicKey());
            RSAUtils.unwrap(key, userServiceValue.getPublicKey());
            return new UserService(userServiceValue.getLogin(), userServiceValue.getName(), userServiceValue.getLogin(), password, privateKey, publicKey);
        }
    }

    private Object readFromRedis(byte[] key) {
        try (Jedis jedis = pool.getResource()) {
            byte[] buffer = jedis.get(key);
            ByteArrayInputStream input = new ByteArrayInputStream(buffer);
            try (ObjectInputStream in = new ObjectInputStream(input)) {
                return in.readObject();
            } catch (IOException e) {
                throw new RuntimeException("Unable to create Object input stream", e);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Unable to found class UserServiceValue ?", e);
            }
        }
    }

    public void stop() {
        if (pool != null) {
            pool.destroy();
        }

    }

    private byte[] encrypt(String data) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher.doFinal(data.getBytes());
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | BadPaddingException | IllegalBlockSizeException e) {
            throw new RuntimeException("Unable to create Cipher", e);
        }
    }

    private String decrypt(byte[] encripted) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, key);
            return new String(cipher.doFinal(encripted));
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | BadPaddingException | IllegalBlockSizeException e) {
            throw new RuntimeException("Unable to create Cipher", e);
        }
    }

    @Override
    public User authenticate(SimpleCredential credentials) {
        if (credentials == null) {
            throw new IllegalArgumentException("credentials must be defined.");
        }
        User user = getUserByUsername(credentials.getUsername());
        return (user != null && user.getPassword().equals(credentials.getPassword())) ? user : null;
    }

    private static String hexEncode(byte[] aInput){
        StringBuilder result = new StringBuilder();
        char[] digits = {'0', '1', '2', '3', '4','5','6','7','8','9','a','b','c','d','e','f'};
        for (int idx = 0; idx < aInput.length; ++idx) {
            byte b = aInput[idx];
            result.append(digits[ (b&0xf0) >> 4 ]);
            result.append(digits[ b&0x0f]);
        }
        return result.toString();
    }
}

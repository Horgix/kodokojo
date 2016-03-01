package io.kodokojo.project.gitlab;

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

import com.squareup.okhttp.*;
import io.kodokojo.commons.utils.properties.provider.PropertyValueProvider;

import io.kodokojo.project.starter.ProjectConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.*;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitlabConfigurer implements ProjectConfigurer<String, String> {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitlabConfigurer.class);

    private static final Pattern FORM_TOKEN_PATTERN = Pattern.compile(".*<input type=\"hidden\" name=\"authenticity_token\" value=\"([^\"]*)\" />.*");

    private static final Pattern META_TOKEN_PATTERN = Pattern.compile(".*<meta name=\"csrf-token\" content=\"([^\"]*)\" />.*");

    private static final Pattern PRIVATE_TOKEN_PATTERN = Pattern.compile(".*<input type=\"text\" name=\"token\" id=\"token\" value=\"([^\"]*)\" class=\"form-control\" />.*");

    private static final String SIGNIN_URL = "/users/sign_in";

    private static final String PASSWORD_URL = "/profile/password";

    private static final String PASSWORD_FORM_URL = PASSWORD_URL + "/new";

    private static final String ACCOUNT_URL = "/profile/account";

    private static final String OLD_PASSWORD = "5iveL!fe";

    private static final String ROOT_LOGIN = "root";

    private final PropertyValueProvider propertyValueProvider;

    public GitlabConfigurer(PropertyValueProvider propertyValueProvider) {
        if (propertyValueProvider == null) {
            throw new IllegalArgumentException("propertyValueProvider must be defined.");
        }
        this.propertyValueProvider = propertyValueProvider;
    }

    @Override
    public String configure(String gitlabUrl) {
        OkHttpClient httpClient = generateDefaultOkHttp();
        CookieManager cookieManager = new CookieManager(new GitlabCookieStore(), CookiePolicy.ACCEPT_ALL);
        httpClient.setCookieHandler(cookieManager);
        if (signIn(httpClient, gitlabUrl, ROOT_LOGIN, OLD_PASSWORD)) {
            String token = getAuthenticityToken(httpClient, gitlabUrl + PASSWORD_FORM_URL, META_TOKEN_PATTERN);
            String newPassword = propertyValueProvider.providePropertyValue(String.class, "gitlab.root.password");
            if (changePassword(httpClient, gitlabUrl, token, OLD_PASSWORD, newPassword)) {
                if (signIn(httpClient, gitlabUrl, ROOT_LOGIN, newPassword)) {
                    Request request = new Request.Builder().get().url(gitlabUrl + ACCOUNT_URL).build();
                    try {
                        Response response = httpClient.newCall(request).execute();
                        return getAuthenticityToken(response.body().string(), PRIVATE_TOKEN_PATTERN);
                    } catch (IOException e) {
                        LOGGER.error("Unable to retrieve account page", e);
                    }
                } else {
                    LOGGER.error("Unable to log on Gitlab with new password");
                }
            }
        }
        return null;
    }

    private static boolean changePassword(OkHttpClient httpClient, String gitlabUrl, String token, String oldPassword, String newPassword) {
        RequestBody formBody = new FormEncodingBuilder()
                .addEncoded("utf8", "%E2%9C%93")
                .add("authenticity_token", token)
                .add("user[current_password]", oldPassword)
                .add("user[password]", newPassword)
                .add("user[password_confirmation]", newPassword)
                .build();
        Request request = new Request.Builder()
                .url(gitlabUrl + PASSWORD_URL)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .post(formBody).build();

        try {
            Response response = httpClient.newCall(request).execute();
            return response.code() == 200;
        } catch (IOException e) {
            LOGGER.error("Unable to change the default password", e);
            return false;
        }

    }

    private static boolean signIn(OkHttpClient httpClient, String gitlabUrl, String login, String password) {
        String token = getAuthenticityToken(httpClient, gitlabUrl + SIGNIN_URL, FORM_TOKEN_PATTERN);
        RequestBody formBody = new FormEncodingBuilder()
                .addEncoded("utf8", "%E2%9C%93")
                .add("authenticity_token", token)
                .add("user[login]", login)
                .add("user[password]", password)
                .add("user[remember_me]", "0")
                .build();

        Request request = new Request.Builder()
                .url(gitlabUrl + SIGNIN_URL)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .post(formBody).build();

        Call call = httpClient.newCall(request);
        try {
            Response response = call.execute();
            return response.isSuccessful() && !response.body().string().contains("Invalid login or password.");
        } catch (IOException e) {
            LOGGER.error("Unable to change default password for Gitlab", e);
            return false;
        }
    }


    private static String getAuthenticityToken(OkHttpClient httpClient, String url, Pattern pattern) {
        Request request = new Request.Builder().url(url).get().build();
        try {
            Response response = httpClient.newCall(request).execute();
            return getAuthenticityToken(response.body().string(), pattern);
        } catch (IOException e) {
            LOGGER.error("Unable to request " + url, e);
        }
        return null;
    }

    private static String getAuthenticityToken(String bodyReponse, Pattern pattern) {
        String token = "";
        Matcher matcher = pattern.matcher(bodyReponse);
        if (matcher.find()) {
            token = matcher.group(1);
        }
        return token;
    }

    private class GitlabCookieStore implements CookieStore {

        private final Map<String, HttpCookie> cache = new HashMap<>();

        @Override
        public void add(URI uri, HttpCookie cookie) {
            cache.put(cookie.getName(), cookie);
        }

        @Override
        public List<HttpCookie> get(URI uri) {
            return new ArrayList<>(cache.values());
        }

        @Override
        public List<HttpCookie> getCookies() {
            return new ArrayList<>(cache.values());
        }

        @Override
        public List<URI> getURIs() {
            return Collections.emptyList();
        }

        @Override
        public boolean remove(URI uri, HttpCookie cookie) {

            return cache.remove(cookie.getName()) != null;
        }

        @Override
        public boolean removeAll() {
            return false;
        }
    }

    public static void main(String[] args) {
        GitlabConfigurer configurer = new GitlabConfigurer(new PropertyValueProvider() {
            @Override
            public <T> T providePropertyValue(Class<T> classType, String key) {
                if ("gitlab.root.password".equals(key)) {
                    return (T) "admin1234";
                }
                return null;
            }
        });
        configurer.configure("https://scm.acme.kodokojo.dev");
    }
    private static OkHttpClient generateDefaultOkHttp() {
        OkHttpClient client = new OkHttpClient();



        final TrustManager[] certs = new TrustManager[]{new X509TrustManager() {

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return null;
            }

            @Override
            public void checkServerTrusted(final X509Certificate[] chain,
                                           final String authType) throws CertificateException {
            }

            @Override
            public void checkClientTrusted(final X509Certificate[] chain,
                                           final String authType) throws CertificateException {
            }
        }};

        SSLContext ctx = null;
        try {
            ctx = SSLContext.getInstance("TLS");
            ctx.init(null, certs, new SecureRandom());
        } catch (final java.security.GeneralSecurityException ex) {
        }

        try {
            final HostnameVerifier hostnameVerifier = new HostnameVerifier() {
                @Override
                public boolean verify(final String hostname,
                                      final SSLSession session) {
                    return true;
                }
            };
            client.setHostnameVerifier(hostnameVerifier);
            client.setSslSocketFactory(ctx.getSocketFactory());
        } catch (final Exception e) {
        }
        return client;
    }
}

package io.kodokojo.entrypoint.dto;

import io.kodokojo.model.User;

import java.io.Serializable;

public class UserDto implements Serializable {

    private String identifier;

    private String username;

    public UserDto(String identifier, String username) {
        this.identifier = identifier;
        this.username = username;
    }

    public UserDto(User user) {
        if (user == null) {
            throw new IllegalArgumentException("user must be defined.");
        }
        this.identifier = user.getIdentifier();
        this.username = user.getUsername();
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = this.identifier;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    @Override
    public String toString() {
        return "UserDto{" +
                "identifier='" + identifier + '\'' +
                ", username='" + username + '\'' +
                '}';
    }
}

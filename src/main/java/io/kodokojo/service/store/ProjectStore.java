package io.kodokojo.service.store;

import io.kodokojo.model.Entity;
import io.kodokojo.model.Project;
import io.kodokojo.model.ProjectConfiguration;

import java.util.List;
import java.util.Set;

public interface ProjectStore {

    boolean projectNameIsValid(String projectName);

    String addProjectConfiguration(ProjectConfiguration projectConfiguration);

    ProjectConfiguration getProjectConfigurationById(String identifier);

    String addProject(Project project, String projectConfigurationIdentifier);

    void updateProject(Project project);

    Project getProjectByIdentifier(String identifier);

    void updateProjectConfiguration(ProjectConfiguration projectConfiguration);

    Set<String> getProjectConfigIdsByUserIdentifier(String userIdentifier);

    String getProjectIdByProjectConfigurationId(String projectConfigurationId);

    Project getProjectByProjectConfigurationId(String projectConfigurationId);
}

package io.kodokojo.lifecycle;

public interface ApplicationLifeCycleListener {

    void start();

    void stop();

    default void stop(Runnable callback) {
        this.stop();
        callback.run();
    }

}

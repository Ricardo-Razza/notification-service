package dev.ricardo.notification.domain;

public class DuplicateApplicationNameException extends RuntimeException {
    public DuplicateApplicationNameException(String name) {
        super(String.format("Application with name '%s' already exists", name));
    }
}

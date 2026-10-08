package dev.ricardo.notification.domain;
public class AdminAuthenticationException extends RuntimeException {
    private final int status;
    public AdminAuthenticationException(int status,String detail) {super(detail);this.status=status;}
    public int status() {return status;}
}

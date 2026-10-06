package com.gymtracker.common;

public class NotSignedInException extends RuntimeException {

    public NotSignedInException() {
        super("Sign in again");
    }
}

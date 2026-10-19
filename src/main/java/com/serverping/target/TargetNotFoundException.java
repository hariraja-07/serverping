package com.serverping.target;

/** No target exists for the requested id — surfaces as HTTP 404. */
public class TargetNotFoundException extends RuntimeException {

    public TargetNotFoundException(long id) {
        super("No target with id " + id);
    }
}

package com.ai.walmartads.connector;

public class ConnectorLoginRequiredException extends RuntimeException {
    public ConnectorLoginRequiredException(String message) {
        super(message);
    }
}

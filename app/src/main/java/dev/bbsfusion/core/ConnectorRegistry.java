package dev.bbsfusion.core;

import dev.bbsfusion.site.NgaConnector;
import dev.bbsfusion.site.S1Connector;

import java.util.Arrays;
import java.util.List;

public final class ConnectorRegistry {
    private static final ForumConnector S1 = new S1Connector();
    private static final ForumConnector NGA = new NgaConnector();

    private ConnectorRegistry() {
    }

    public static ForumConnector s1() {
        return S1;
    }

    public static ForumConnector nga() {
        return NGA;
    }

    public static List<ForumConnector> all() {
        return Arrays.asList(S1, NGA);
    }

    public static ForumConnector byId(String id) {
        if (S1.id().equals(id)) {
            return S1;
        }
        if (NGA.id().equals(id)) {
            return NGA;
        }
        throw new IllegalArgumentException("Unknown connector: " + id);
    }
}

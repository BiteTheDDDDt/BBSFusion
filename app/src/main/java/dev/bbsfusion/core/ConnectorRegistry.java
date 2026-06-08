package dev.bbsfusion.core;

import dev.bbsfusion.site.LinuxDoConnector;
import dev.bbsfusion.site.NgaConnector;
import dev.bbsfusion.site.S1Connector;
import dev.bbsfusion.site.V2exConnector;

import java.util.Arrays;
import java.util.List;

public final class ConnectorRegistry {
    private static final ForumConnector S1 = new S1Connector();
    private static final ForumConnector NGA = new NgaConnector();
    private static final ForumConnector V2EX = new V2exConnector();
    private static final ForumConnector LINUXDO = new LinuxDoConnector();

    private ConnectorRegistry() {
    }

    public static ForumConnector s1() {
        return S1;
    }

    public static ForumConnector nga() {
        return NGA;
    }

    public static List<ForumConnector> all() {
        return Arrays.asList(S1, NGA, V2EX, LINUXDO);
    }

    public static ForumConnector byId(String id) {
        if (S1.id().equals(id)) {
            return S1;
        }
        if (NGA.id().equals(id)) {
            return NGA;
        }
        if (V2EX.id().equals(id)) {
            return V2EX;
        }
        if (LINUXDO.id().equals(id)) {
            return LINUXDO;
        }
        throw new IllegalArgumentException("Unknown connector: " + id);
    }
}

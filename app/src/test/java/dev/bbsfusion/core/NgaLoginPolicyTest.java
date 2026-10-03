package dev.bbsfusion.core;

import org.junit.Test;

import static org.junit.Assert.*;

public final class NgaLoginPolicyTest {
    @Test
    public void onlyExactHttpsLoginEndpointsAreTrusted() {
        assertTrue(NgaLoginPolicy.isTrustedLoginUrl("https://bbs.nga.cn/nuke.php?__lib=login&__act=account&login"));
        assertTrue(NgaLoginPolicy.isTrustedLoginUrl("https://ngabbs.com:443/nuke.php?__act=account&__lib=login"));
        assertFalse(NgaLoginPolicy.isTrustedLoginUrl("http://bbs.nga.cn/nuke.php?__lib=login&__act=account"));
        assertFalse(NgaLoginPolicy.isTrustedLoginUrl("https://bbs.nga.cn.attacker.test/nuke.php?__lib=login&__act=account"));
        assertFalse(NgaLoginPolicy.isTrustedLoginUrl("https://attacker@bbs.nga.cn/nuke.php?__lib=login&__act=account"));
        assertFalse(NgaLoginPolicy.isTrustedLoginUrl("https://bbs.nga.cn:8443/nuke.php?__lib=login&__act=account"));
        assertFalse(NgaLoginPolicy.isTrustedLoginUrl("https://bbs.nga.cn/read.php?__lib=login&__act=account"));
        assertFalse(NgaLoginPolicy.isTrustedLoginUrl("https://bbs.nga.cn/nuke.php?__lib=login&__act=account&__lib=other"));
        assertFalse(NgaLoginPolicy.isTrustedLoginUrl("https://bbs.nga.cn/nuke.php?__lib=login&__act=account&__act=account"));
        assertFalse(NgaLoginPolicy.isTrustedLoginUrl(null));
    }

    @Test
    public void credentialValuesCannotInjectCookieAttributes() {
        assertTrue(NgaLoginPolicy.validCredentials("1234", "aBCd.0_-=+/token"));
        assertFalse(NgaLoginPolicy.validCredentials("0", "token"));
        assertFalse(NgaLoginPolicy.validCredentials("123; Domain=attacker.test", "token"));
        assertFalse(NgaLoginPolicy.validCredentials("123", "token; Domain=attacker.test"));
        assertFalse(NgaLoginPolicy.validCredentials("123", "token\r\nSet-Cookie: bad"));
        assertFalse(NgaLoginPolicy.validCredentials("123", "\"token\""));
        assertFalse(NgaLoginPolicy.validCredentials("123", ""));
        assertFalse(NgaLoginPolicy.validCredentials("123", "a".repeat(4097)));
    }

    @Test
    public void delayedLoginCompletionCannotAffectANewerNavigation() {
        String login = "https://bbs.nga.cn/nuke.php?__lib=login&__act=account&login";
        assertTrue(NgaLoginPolicy.isCurrentLoginPage(2, 2, login, login));
        assertFalse(NgaLoginPolicy.isCurrentLoginPage(2, 3, login, login));
        assertFalse(NgaLoginPolicy.isCurrentLoginPage(2, 2, login, "https://bbs.nga.cn/read.php?tid=1"));
        assertFalse(NgaLoginPolicy.isCurrentLoginPage(2, 2, login,
                "https://ngabbs.com/nuke.php?__lib=login&__act=account&login"));
        assertFalse(NgaLoginPolicy.isCurrentLoginPage(2, 2, null, null));
    }
}

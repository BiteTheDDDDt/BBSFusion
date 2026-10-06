package dev.bbsfusion.core;

import org.junit.Test;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import static org.junit.Assert.assertEquals;

public final class ImageLoadFailureTest {
    private static final String SENSITIVE_URL =
            "https://private.example/image.png?token=test-secret";

    @Test
    public void httpErrorsKeepTheStatusWithoutGuessingTheCause() {
        for (int status : new int[] {403, 404, 500, 502, 503}) {
            assertEquals("图片请求失败（HTTP " + status + "）",
                    ImageLoadFailure.httpStatus(status));
        }
    }

    @Test
    public void classifiesCommonNetworkFailuresWithoutSensitiveMessages() {
        assertEquals("图片域名解析失败",
                ImageLoadFailure.connection(new UnknownHostException(SENSITIVE_URL)));
        assertEquals("图片加载超时",
                ImageLoadFailure.connection(new SocketTimeoutException(SENSITIVE_URL)));
        assertEquals("图片安全连接失败",
                ImageLoadFailure.connection(new SSLException(SENSITIVE_URL)));
        assertEquals("图片安全连接失败",
                ImageLoadFailure.connection(new SSLHandshakeException(SENSITIVE_URL)));
        assertEquals("图片连接失败",
                ImageLoadFailure.connection(new ConnectException(SENSITIVE_URL)));
        assertEquals("图片连接失败",
                ImageLoadFailure.connection(new SocketException(SENSITIVE_URL)));
        assertEquals("图片连接失败",
                ImageLoadFailure.connection(new IOException(SENSITIVE_URL,
                        new Exception(SENSITIVE_URL))));
        assertEquals("图片加载失败",
                ImageLoadFailure.connection(new IllegalArgumentException(SENSITIVE_URL)));
    }

    @Test
    public void neverReadsExceptionMessagesOrStringRepresentations() {
        IOException error = new IOException() {
            @Override
            public String getMessage() {
                throw new AssertionError("Exception messages must stay private");
            }

            @Override
            public String toString() {
                throw new AssertionError("Exception strings must stay private");
            }
        };

        assertEquals("图片连接失败", ImageLoadFailure.connection(error));
        assertEquals("图片加载失败", ImageLoadFailure.connection(null));
    }
}

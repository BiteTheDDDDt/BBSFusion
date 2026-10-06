package dev.bbsfusion.core;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** Safe image failure labels that never include exception text or request URLs. */
public final class ImageLoadFailure {
    private ImageLoadFailure() {
    }

    public static String httpStatus(int status) {
        return "图片请求失败（HTTP " + status + "）";
    }

    public static String connection(Exception error) {
        if (error instanceof UnknownHostException) {
            return "图片域名解析失败";
        }
        if (error instanceof SocketTimeoutException) {
            return "图片加载超时";
        }
        if (error instanceof SSLException) {
            return "图片安全连接失败";
        }
        if (error instanceof IOException) {
            return "图片连接失败";
        }
        return "图片加载失败";
    }
}

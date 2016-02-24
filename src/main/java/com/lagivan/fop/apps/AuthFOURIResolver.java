package com.lagivan.fop.apps;

import org.apache.fop.apps.FOURIResolver;

import javax.xml.bind.DatatypeConverter;
import java.io.UnsupportedEncodingException;
import java.net.URLConnection;
import java.net.URLDecoder;

/**
 * Patched FOURIResolver that supports basic authentication via user info specified in the URL, e.g.
 * http://username:password@domain.com
 *
 * @author Ivan Lagunov
 */
public class AuthFOURIResolver extends FOURIResolver {

    public static final String URL_ENCODING = "UTF-8";

    @Override
    protected void updateURLConnection(URLConnection connection, String href) {
        String userInfo = connection.getURL().getUserInfo();
        if (userInfo != null && userInfo.contains(":")) {
            try {
                connection.setRequestProperty("Authorization", "Basic " + encodeUserInfo(userInfo));
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(
                        "Error during base64 encoding of username/password");
            }
        }
    }

    protected static String encodeUserInfo(String userInfo) throws UnsupportedEncodingException {
        String decoded = URLDecoder.decode(userInfo, URL_ENCODING);
        return DatatypeConverter.printBase64Binary(decoded.getBytes(URL_ENCODING));
    }
}

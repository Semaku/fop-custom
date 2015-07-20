package com.lagivan.fop.apps;

import org.apache.fop.apps.FOURIResolver;

import java.io.UnsupportedEncodingException;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.util.Base64;

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
                String decoded = URLDecoder.decode(userInfo, URL_ENCODING);
                String encoded = Base64.getEncoder().encodeToString(decoded.getBytes(URL_ENCODING));
                connection.setRequestProperty("Authorization", "Basic " + encoded);
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(
                        "Error during base64 encoding of username/password");
            }
        }
    }
}

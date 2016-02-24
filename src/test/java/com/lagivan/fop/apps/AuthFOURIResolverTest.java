package com.lagivan.fop.apps;

import org.junit.Assert;
import org.junit.Test;

import java.io.UnsupportedEncodingException;

/**
 * @author Ivan Lagunov
 */
public class AuthFOURIResolverTest {

    @Test
    public void testEncodeUserInfo() throws UnsupportedEncodingException {
        Assert.assertEquals("aGVsbG8gd29ybGQ=", AuthFOURIResolver.encodeUserInfo("hello world"));
        Assert.assertEquals("aGVsbG8gd29ybGQ=", AuthFOURIResolver.encodeUserInfo("hello%20world"));
    }
}

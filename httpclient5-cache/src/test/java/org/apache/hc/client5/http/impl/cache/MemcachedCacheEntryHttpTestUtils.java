/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.hc.client5.http.impl.cache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.hc.client5.http.cache.HttpCacheEntry;
import org.apache.hc.client5.http.cache.HttpCacheEntrySerializer;
import org.apache.hc.client5.http.cache.HttpCacheStorageEntry;
import org.apache.hc.client5.http.cache.Resource;
import org.apache.hc.client5.http.cache.ResourceIOException;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.Assert;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

class MemcachedCacheEntryHttpTestUtils {
    private final static String TEST_RESOURCE_DIR = "src/test/resources/";
    static final String TEST_STORAGE_KEY = "xyzzy";

    /**
     * Create a new HttpCacheEntry object with default fields, except for those overridden by name in the template.
     *
     * @param template Map of field names to override in the test object
     * @return New object for testing
     */
    static HttpCacheStorageEntry buildSimpleTestObjectFromTemplate(final Map<String, Object> template) {
        final Resource resource = getOrDefault(template, "resource",
                new HeapResource("Hello World".getBytes(Charset.forName("UTF-8"))));

        final Date requestDate = getOrDefault(template, "requestDate", new Date(165214800000L));
        final Date responseDate = getOrDefault(template, "responseDate", new Date(2611108800000L));
        final Integer responseCode = getOrDefault(template, "responseCode", 200);
        final Header[] responseHeaders = getOrDefault(template, "headers",
                new Header[]{
                        new BasicHeader("Content-type", "text/html"),
                        new BasicHeader("Cache-control", "public, max-age=31536000"),
                });
        final Map<String, String> variantMap = getOrDefault(template, "variantMap",
                new HashMap<String, String>());
        final String storageKey = getOrDefault(template, "storageKey", TEST_STORAGE_KEY);
        return new HttpCacheStorageEntry(storageKey,
                new HttpCacheEntry(
                        requestDate,
                        responseDate,
                        responseCode,
                        responseHeaders,
                        resource,
                        variantMap)
        );
    }

    /**
     * Return the value from a map if it is present, and otherwise a default value.
     *
     * Implementation of map#getOrDefault for Java 6.
     *
     * @param map Map to get an entry from
     * @param key Key to look up
     * @param orDefault Value to return if the given key is not found.
     * @param <X> Type of object expected
     * @return Object from map, or default if not found
     */
    static <X> X getOrDefault(final Map<String, Object> map, final String key, final X orDefault) {
        if (map.containsKey(key)) {
            return (X) map.get(key);
        } else {
            return orDefault;
        }
    }

    /**
     * Test serializing and deserializing the given object with the given factory.
     * <p>
     * Compares fields to ensure the deserialized object is equivalent to the original object.
     *
     * @param serializer Factory for creating serializers
     * @param httpCacheStorageEntry    Original object to serialize and test against
     * @throws Exception if anything goes wrong
     */
    static void testWithCache(final HttpCacheEntrySerializer<byte[]> serializer, final HttpCacheStorageEntry httpCacheStorageEntry) throws Exception {
        final byte[] testBytes = serializer.serialize(httpCacheStorageEntry);
        verifyHttpCacheEntryFromBytes(serializer, httpCacheStorageEntry, testBytes);
    }

    /**
     * Verify that the given bytes deserialize to the given storage key and an equivalent cache entry.
     *
     * @param serializer Deserializer
     * @param httpCacheStorageEntry Cache entry to verify
     * @param testBytes Bytes to deserialize
     * @throws Exception if anything goes wrong
     */
    static void verifyHttpCacheEntryFromBytes(final HttpCacheEntrySerializer<byte[]> serializer, final HttpCacheStorageEntry httpCacheStorageEntry, final byte[] testBytes) throws Exception {
        final HttpCacheStorageEntry testMemcachedCacheEntryFromBytes = memcachedCacheEntryFromBytes(serializer, testBytes);

        assertCacheEntriesEqual(httpCacheStorageEntry, testMemcachedCacheEntryFromBytes);
    }

    /**
     * Verify that the given test file deserializes to the given storage key and an equivalent cache entry.
     *
     * @param serializer Deserializer
     * @param httpCacheStorageEntry    Cache entry to verify
     * @param testFileName  Name of test file to deserialize
     * @throws Exception if anything goes wrong
     */
    static void verifyHttpCacheEntryFromTestFile(final HttpCacheEntrySerializer<byte[]> serializer, final HttpCacheStorageEntry httpCacheStorageEntry,
                                                 final String testFileName,
                                                 final boolean reserializeFiles) throws Exception {
        if (reserializeFiles) {
            final File toFile = makeTestFileObject(testFileName);
            saveEntryToFile(serializer, httpCacheStorageEntry, toFile);
        }

        final byte[] bytes = readTestFileBytes(testFileName);

        verifyHttpCacheEntryFromBytes(serializer, httpCacheStorageEntry, bytes);
    }

    /**
     * Get the bytes of the given test file.
     *
     * @param testFileName Name of test file to get bytes from
     * @return Bytes from the given test file
     * @throws Exception if anything goes wrong
     */
    static byte[] readTestFileBytes(final String testFileName) throws Exception {
        final File testFile = makeTestFileObject(testFileName);
        final byte[] bytes = new byte[(int) testFile.length()];
        FileInputStream testStream = null;
        try {
            testStream = new FileInputStream(testFile);
            readFully(testStream, bytes);
        } finally {
            if (testStream != null) {
                testStream.close();
            }
        }
        return bytes;
    }

    /**
     * Create a new memcached cache object from the given bytes.
     *
     * @param serializer Deserializer
     * @param testBytes         Bytes to deserialize
     * @return Deserialized object
     */
    static HttpCacheStorageEntry memcachedCacheEntryFromBytes(final HttpCacheEntrySerializer<byte[]> serializer, final byte[] testBytes) throws ResourceIOException {
        return serializer.deserialize(testBytes);
    }

    /**
     * Assert that the given objects are equivalent
     *
     * @param expected Expected cache entry object
     * @param actual   Actual cache entry object
     * @throws Exception if anything goes wrong
     */
    static void assertCacheEntriesEqual(final HttpCacheStorageEntry expected, final HttpCacheStorageEntry actual) throws Exception {
        assertEquals(expected.getKey(), actual.getKey());

        final HttpCacheEntry expectedContent = expected.getContent();
        final HttpCacheEntry actualContent = actual.getContent();

        assertEquals(expectedContent.getRequestDate(), actualContent.getRequestDate());
        assertEquals(expectedContent.getResponseDate(), actualContent.getResponseDate());
        assertEquals(expectedContent.getRequestMethod(), actualContent.getRequestMethod());
        assertEquals(expectedContent.getStatus(), actualContent.getStatus());

        assertArrayEquals(expectedContent.getVariantMap().keySet().toArray(), actualContent.getVariantMap().keySet().toArray());
        for (final String key : expectedContent.getVariantMap().keySet()) {
            assertEquals("Expected same variantMap values for key '" + key + "'",
                    expectedContent.getVariantMap().get(key), actualContent.getVariantMap().get(key));
        }

        // Requires that all expected headers are present
        // Allows actual headers to contain extra
        // Multiple values for the same header are not currently supported
        for(final Header expectedHeader: expectedContent.getHeaders()) {
            final Header actualHeader = actualContent.getFirstHeader(expectedHeader.getName());

            Assert.assertEquals(expectedHeader.getName(), actualHeader.getName());
            Assert.assertEquals(expectedHeader.getValue(), actualHeader.getValue());
        }

        if (expectedContent.getResource() == null) {
            assertNull("Expected null resource", actualContent.getResource());
        } else {
            final byte[] expectedBytes = readFully(
                    expectedContent.getResource().getInputStream(),
                    (int) expectedContent.getResource().length()
            );
            final byte[] actualBytes = readFully(
                    actualContent.getResource().getInputStream(),
                    (int) actualContent.getResource().length()
            );
            assertArrayEquals(expectedBytes, actualBytes);
        }
    }

    /**
     * Get a File object for the given test file.
     *
     * @param testFileName Name of test file
     * @return File for this test file
     */
    static File makeTestFileObject(final String testFileName) {
        return new File(TEST_RESOURCE_DIR + testFileName);
    }

    /**
     * Save the given storage key and cache entry serialized to the given file.
     *
     * @param serializer Serializer
     * @param httpCacheStorageEntry Cache entry to serialize and save
     * @param outFile Output file to write to
     * @throws Exception if anything goes wrong
     */
    static void saveEntryToFile(final HttpCacheEntrySerializer<byte[]> serializer, final HttpCacheStorageEntry httpCacheStorageEntry, final File outFile) throws Exception {
        final byte[] bytes = serializer.serialize(httpCacheStorageEntry);

        OutputStream out = null;
        try {
            out = new FileOutputStream(outFile);
            out.write(bytes);
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    static void readFully(final InputStream src, final byte[] dest) throws IOException {
        final int destPos = 0;
        final int length = dest.length;
        int totalBytesRead = 0;
        int lastBytesRead;

        while (totalBytesRead < length && (lastBytesRead = src.read(dest, destPos + totalBytesRead, length - totalBytesRead)) != -1) {
            totalBytesRead += lastBytesRead;
        }
    }

    static byte[] readFully(final InputStream src, final int length) throws IOException {
        final byte[] dest = new byte[length];
        readFully(src, dest);
        return dest;
    }
}

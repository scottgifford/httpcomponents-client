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

package org.apache.http.impl.client.cache;
// Must be in Apache package to get access to cache helper classes

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.cache.HttpCacheEntry;
import org.apache.http.client.cache.Resource;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.impl.client.cache.memcached.MemcachedCacheEntry;
import org.apache.http.impl.io.AbstractMessageParser;
import org.apache.http.impl.io.AbstractMessageWriter;
import org.apache.http.impl.io.DefaultHttpResponseParser;
import org.apache.http.impl.io.DefaultHttpResponseWriter;
import org.apache.http.impl.io.HttpTransportMetricsImpl;
import org.apache.http.impl.io.SessionInputBufferImpl;
import org.apache.http.impl.io.SessionOutputBufferImpl;
import org.apache.http.io.SessionInputBuffer;
import org.apache.http.io.SessionOutputBuffer;
import org.apache.http.message.BasicHttpRequest;

/**
 * Cache serializer and deserializer that uses an HTTP-like format.
 *
 * Existing libraries for reading and writing HTTP are used, and metadata is encoded into HTTP
 * pseudo-headers for storage.
 */
public class MemcachedCacheEntryHttp implements MemcachedCacheEntry {
    private static final String SC_CACHE_ENTRY_PREFIX = "hc-";

    private static final String SC_HEADER_NAME_STORAGE_KEY = SC_CACHE_ENTRY_PREFIX + "sk";
    private static final String SC_HEADER_NAME_RESPONSE_DATE = SC_CACHE_ENTRY_PREFIX + "resp-date";
    private static final String SC_HEADER_NAME_REQUEST_DATE = SC_CACHE_ENTRY_PREFIX + "req-date";
    private static final String SC_HEADER_NAME_NO_CONTENT = SC_CACHE_ENTRY_PREFIX + "no-content";
    private static final String SC_HEADER_NAME_VARIANT_MAP_KEY = SC_CACHE_ENTRY_PREFIX + "varmap-key";
    private static final String SC_HEADER_NAME_VARIANT_MAP_VALUE = SC_CACHE_ENTRY_PREFIX + "varmap-val";

    private static final String SC_CACHE_ENTRY_PRESERVE_PREFIX = SC_CACHE_ENTRY_PREFIX + "esc-";

    private static final int BUFFER_SIZE = 8192;

    private String storageKey;
    private HttpCacheEntry httpCacheEntry;

    public MemcachedCacheEntryHttp(final String storageKey, final HttpCacheEntry entry) {
        this.storageKey = storageKey;
        this.httpCacheEntry = entry;
    }

    public MemcachedCacheEntryHttp() {
    }

    @Override
    public byte[] toByteArray() {
        if (this.storageKey == null) {
            throw new IllegalStateException("Cannot serialize cache object with null storage key");
        }
        if (this.httpCacheEntry == null) {
            throw new IllegalStateException("Cannot serialize cache object with null cache entry");
        }

        try {
            return new TryWithResources<byte[]>(3) {
                public byte[] run() throws IOException, HttpException {
                    // Fake HTTP request, required by response generator
                    final HttpRequest httpRequest = new BasicHttpRequest(httpCacheEntry.getRequestMethod(), "/");
                    final HttpRequestWrapper requestWrapper = HttpRequestWrapper.wrap(httpRequest);

                    // This is the package-private class that requires us to be package-private
                    final CacheValidityPolicy cacheValidityPolicy = new NoAgeCacheValidityPolicy();
                    final CachedHttpResponseGenerator cachedHttpResponseGenerator = new CachedHttpResponseGenerator(cacheValidityPolicy);

                    final CloseableHttpResponse httpResponse = cachedHttpResponseGenerator.generateResponse(requestWrapper, httpCacheEntry);
                    addResource(httpResponse);

                    final ByteArrayOutputStream out = new ByteArrayOutputStream();
                    addResource(out);

                    escapeHeaders(httpResponse);
                    addMetadataPseudoHeaders(httpResponse);

                    final Resource resource = httpCacheEntry.getResource();
                    final int resourceLength;
                    final InputStream resourceStream;
                    if (resource == null) {
                        // This means no content, for example a 204 response
                        httpResponse.addHeader(SC_HEADER_NAME_NO_CONTENT, Boolean.TRUE.toString());
                        resourceLength = 0;
                        resourceStream = null;
                    } else {
                        // We should not really ever end up with an item this large, this is just a failsafe
                        // before we cast the length from a long to an int so we can use it as an array index,
                        // and a way to provide a better error message if it somehow happens.  The real limit
                        // will be set in the cache configuration.
                        if (resource.length() > Integer.MAX_VALUE) {
                            throw new IOException(String.format("File of size %d is too big to load into memory", resource.length()));
                        }
                        resourceLength = (int) resource.length();
                        resourceStream = resource.getInputStream();
                        addResource(resourceStream);
                    }

                    // We don't use this metrics object but it's required
                    final HttpTransportMetricsImpl metrics = new HttpTransportMetricsImpl();

                    // Use the default, ASCII-only encoder for HTTP protocol and header values
                    // It's the only thing that's widely used, and it's not worth any extra effort
                    // to support anything else.
                    final SessionOutputBufferImpl outputBuffer = new SessionOutputBufferImpl(metrics, BUFFER_SIZE);
                    outputBuffer.bind(out);
                    final AbstractMessageWriter<HttpResponse> httpResponseWriter = makeHttpResponseWriter(outputBuffer);
                    httpResponseWriter.write(httpResponse);
                    outputBuffer.flush();
                    final byte[] headerBytes = out.toByteArray();

                    final byte[] bytes = new byte[headerBytes.length + resourceLength];
                    System.arraycopy(headerBytes, 0, bytes, 0, headerBytes.length);
                    // resourceStream can be null as long as resourceLength is 0
                    copyBytes(resourceStream, bytes, headerBytes.length, resourceLength);
                    return bytes;
                }
            }.runWithResources();
        } catch (final Exception e) {
            throw new MemcachedCacheEntryHttpException("Cache encoding error", e);
        }
    }

    @Override
    public String getStorageKey() {
        return storageKey;
    }

    @Override
    public HttpCacheEntry getHttpCacheEntry() {
        return httpCacheEntry;
    }

    @Override
    public void set(final byte[] bytes) {
        try {
            new TryWithResources<Void>(2) {
                public Void run() throws IOException, HttpException {
                    final InputStream in = makeByteArrayInputStream(bytes);
                    addResource(in);

                    final ByteArrayOutputStream bytesOut = new ByteArrayOutputStream(bytes.length); // this is bigger than necessary but will save us from reallocating
                    addResource(bytesOut);

                    // We don't use this metrics object but it's required
                    final HttpTransportMetricsImpl metrics = new HttpTransportMetricsImpl();
                    final SessionInputBufferImpl inputBuffer = new SessionInputBufferImpl(metrics, BUFFER_SIZE);
                    inputBuffer.bind(in);
                    final AbstractMessageParser<HttpResponse> responseParser = makeHttpResponseParser(inputBuffer);

                    final HttpResponse response = responseParser.parse();

                    // Extract metadata pseudo-headers
                    MemcachedCacheEntryHttp.this.storageKey = getCachePseudoHeaderAndRemove(response, SC_HEADER_NAME_STORAGE_KEY);
                    final Date requestDate = getCachePseudoHeaderDateAndRemove(response, SC_HEADER_NAME_REQUEST_DATE);
                    final Date responseDate = getCachePseudoHeaderDateAndRemove(response, SC_HEADER_NAME_RESPONSE_DATE);
                    final boolean noBody = getCachePseudoHeaderBooleanAndRemove(response, SC_HEADER_NAME_NO_CONTENT);
                    final Map<String, String> variantMap = getVariantMapPseudoHeadersAndRemove(response);
                    unescapeHeaders(response);

                    final Resource resource;
                    if (noBody) {
                        // This means no content, for example a 204 response
                        resource = null;
                    } else {
                        copyBytes(inputBuffer, bytesOut);
                        resource = new HeapResource(bytesOut.toByteArray());
                    }
                    httpCacheEntry = new HttpCacheEntry(
                            requestDate,
                            responseDate,
                            response.getStatusLine(),
                            response.getAllHeaders(),
                            resource,
                            variantMap
                    );

                    return null;
                }
            }.runWithResources();
        } catch (final IOException e) {
            throw new MemcachedCacheEntryHttpException("Error deserializing cache entry", e);
        } catch (final HttpException e) {
            throw new MemcachedCacheEntryHttpException("Error deserializing cache entry", e);
        }
    }

    /**
     * Helper method to make a new HTTP response writer.
     *
     * Useful to override for testing.
     *
     * @param outputBuffer Output buffer to write to
     * @return HTTP response writer to write to
     */
    protected AbstractMessageWriter<HttpResponse> makeHttpResponseWriter(final SessionOutputBuffer outputBuffer) {
        return new DefaultHttpResponseWriter(outputBuffer, null);
    }

    /**
     * Helper method to make a new ByteArrayInputStream.
     *
     * Useful to override for testing.
     *
     * @param bytes Bytes to read from the stream
     * @return Stream to read the bytes from
     */
    protected InputStream makeByteArrayInputStream(final byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    /**
     * Helper method to make a new HTTP Response parser.
     *
     * Useful to override for testing.
     *
     * @param inputBuffer Buffer to read from
     * @return HTTP response parser
     */
    protected AbstractMessageParser<HttpResponse> makeHttpResponseParser(final SessionInputBuffer inputBuffer) {
        return new DefaultHttpResponseParser(inputBuffer);
    }

    /**
     * Modify the given response to escape any header names that start with the prefix we use for our own pseudo-headers,
     * prefixing them with an escape sequence we can use to recover them later.
     *
     * @param httpResponse HTTP response object to escape headers in
     * @see #unescapeHeaders(HttpResponse) for the corresponding un-escaper.
     */
    private static void escapeHeaders(final HttpResponse httpResponse) {
        final Header[] headers = httpResponse.getAllHeaders();
        for (final Header header : headers) {
            if (header.getName().startsWith(SC_CACHE_ENTRY_PREFIX)) {
                httpResponse.removeHeader(header);
                httpResponse.addHeader(SC_CACHE_ENTRY_PRESERVE_PREFIX + header.getName(), header.getValue());
            }
        }
    }

    /**
     * Modify the given response to remove escaping from any header names we escaped before saving.
     *
     * @param httpResponse HTTP response object to un-escape headers in
     * @see #unescapeHeaders(HttpResponse) for the corresponding escaper
     */
    private void unescapeHeaders(final HttpResponse httpResponse) {
        final Header[] headers = httpResponse.getAllHeaders();
        for (final Header header : headers) {
            if (header.getName().startsWith(SC_CACHE_ENTRY_PRESERVE_PREFIX)) {
                httpResponse.removeHeader(header);
                httpResponse.addHeader(header.getName().substring(SC_CACHE_ENTRY_PRESERVE_PREFIX.length()), header.getValue());
            }
        }
    }

    /**
     * Modify the given response to add our own cache metadata as pseudo-headers.
     *
     * @param httpResponse HTTP response object to add pseudo-headers to
     */
    private void addMetadataPseudoHeaders(final HttpResponse httpResponse) {
        httpResponse.addHeader(SC_HEADER_NAME_STORAGE_KEY, storageKey);
        httpResponse.addHeader(SC_HEADER_NAME_RESPONSE_DATE, Long.toString(httpCacheEntry.getResponseDate().getTime()));
        httpResponse.addHeader(SC_HEADER_NAME_REQUEST_DATE, Long.toString(httpCacheEntry.getRequestDate().getTime()));

        // Encode these so map entries are stored in a pair of headers, one for key and one for value.
        // Header keys look like: {Accept-Encoding=gzip}
        // And header values like: {Accept-Encoding=gzip}https://example.com:1234/foo
        for(final Map.Entry<String, String> entry: httpCacheEntry.getVariantMap().entrySet()) {
            // Headers are ordered
            httpResponse.addHeader(SC_HEADER_NAME_VARIANT_MAP_KEY, entry.getKey());
            httpResponse.addHeader(SC_HEADER_NAME_VARIANT_MAP_VALUE, entry.getValue());
        }
    }

    /**
     * Get the string value for a single metadata pseudo-header, and remove it from the response object.
     *
     * @param response Response object to get and remove the pseudo-header from
     * @param name Name of metadata pseudo-header
     * @return Value for metadata pseudo-header
     * @throws MemcachedCacheEntryHttpException if the given pseudo-header is not found
     */
    private static String getCachePseudoHeaderAndRemove(final HttpResponse response, final String name) {
        final String headerValue = getOptionalCachePseudoHeaderAndRemove(response, name);
        if (headerValue == null) {
            throw new MemcachedCacheEntryHttpException("Expected cache header '" + name + "' not found");
        }
        return headerValue;
    }

    /**
     * Get the string value for a single metadata pseudo-header if it exists, and remove it from the response object.
     *
     * @param response Response object to get and remove the pseudo-header from
     * @param name Name of metadata pseudo-header
     * @return Value for metadata pseudo-header, or null if it does not exist
     */
    private static String getOptionalCachePseudoHeaderAndRemove(final HttpResponse response, final String name) {
        final Header header = response.getFirstHeader(name);
        if (header == null) {
            return null;
        }
        response.removeHeader(header);
        return header.getValue();
    }

    /**
     * Get the date value for a single metadata pseudo-header, and remove it from the response object.
     *
     * @param response Response object to get and remove the pseudo-header from
     * @param name Name of metadata pseudo-header
     * @return Value for metadata pseudo-header
     * @throws MemcachedCacheEntryHttpException if the given pseudo-header is not found, or contains invalid data
     */
    private static Date getCachePseudoHeaderDateAndRemove(final HttpResponse response, final String name) {
        final String value = getCachePseudoHeaderAndRemove(response, name);
        response.removeHeaders(name);
        try {
            final long timestamp = Long.parseLong(value);
            return new Date(timestamp);
        } catch (final NumberFormatException e) {
            throw new MemcachedCacheEntryHttpException("Invalid value for header '" + name + "'", e);
        }
    }

    /**
     * Get the boolean value for a single metadata pseudo-header, and remove it from the response object.
     *
     * @param response Response object to get and remove the pseudo-header from
     * @param name     Name of metadata pseudo-header
     * @return Value for metadata pseudo-header
     */
    private static boolean getCachePseudoHeaderBooleanAndRemove(final HttpResponse response, final String name) {
        // parseBoolean does not throw any exceptions, so no try/catch required.
        return Boolean.parseBoolean(getOptionalCachePseudoHeaderAndRemove(response, name));
    }

    /**
     * Get the variant map metadata pseudo-header, and remove it from the response object.
     *
     * @param response Response object to get and remove the pseudo-header from
     * @return Extracted variant map
     * @throws MemcachedCacheEntryHttpException if the given pseudo-header is not found, or contains invalid data
     */
    private static Map<String, String> getVariantMapPseudoHeadersAndRemove(final HttpResponse response) {
        final Header[] headers = response.getAllHeaders();
        final Map<String, String> variantMap = new HashMap<String, String>(0);
        String lastKey = null;
        for(final Header header: headers) {
            if (header.getName().equals(SC_HEADER_NAME_VARIANT_MAP_KEY)) {
                lastKey = header.getValue();
                response.removeHeader(header);
            } else if (header.getName().equals(SC_HEADER_NAME_VARIANT_MAP_VALUE)) {
                if (lastKey == null) {
                    throw new MemcachedCacheEntryHttpException("Found mismatched variant map key/value headers");
                }
                variantMap.put(lastKey, header.getValue());
                lastKey = null;
                response.removeHeader(header);
            }
        }

        if (lastKey != null) {
            throw new MemcachedCacheEntryHttpException("Found mismatched variant map key/value headers");
        }

        return variantMap;
    }

    /**
     * Copy bytes from the given source buffer to the given output stream until end-of-file is reached.
     *
     * @param src Input source
     * @param dest Output destination
     * @throws IOException if an I/O error occurs
     */
    private static void copyBytes(final SessionInputBuffer src, final OutputStream dest) throws IOException {
        final byte[] buf = new byte[BUFFER_SIZE];
        int lastBytesRead;
        while ((lastBytesRead = src.read(buf)) != -1) {
            dest.write(buf, 0, lastBytesRead);
        }
    }

    /**
     * Copy the given number of bytes from the given source buffer to the given buffer.
     *
     * If end-of-file is reached before the given number of bytes have been read, and IOException will be thrown.
     *
     * @param src Input source
     * @param dest Output destination
     * @param destPos Position in destination buffer to start writing
     * @param length Number of bytes to copy into the buffer
     * @throws IOException if an I/O error occurs or the file is smaller than length
     */
    private static void copyBytes(final InputStream src, final byte[] dest, final int destPos, final int length) throws IOException {
        int totalBytesRead = 0;
        int lastBytesRead;

        while (totalBytesRead < length && (lastBytesRead = src.read(dest, destPos + totalBytesRead, length - totalBytesRead)) != -1) {
            totalBytesRead += lastBytesRead;
        }
        if (totalBytesRead < length) {
            throw new IOException(String.format("Expected to read %d bytes but only read %d before end of file", length, totalBytesRead));
        }
    }

    /**
     * Helper class to run wrapped code while reliably closing resources and ensuring
     * the most interesting exception is thrown if more than one occurs.
     *
     * @param <ReturnType> Return type for wrapped code
     */
    private static abstract class TryWithResources<ReturnType> {
        /** Resources to close when the wrapped code finishes. */
        private final List<Closeable> resources;

        /** Code to run while handling resources */
        abstract ReturnType run() throws IOException, HttpException;

        /** Create a resource handler with the given number of reserved resources. */
        TryWithResources(final int numResources) {
            resources = new ArrayList<Closeable>(numResources);
        }

        /** Add a resource to be closed when the wrapped code finishes executing. */
        void addResource(final Closeable resource) {
            resources.add(resource);
        }

        /** Run the subclass run() method, ensuring resources are closed when it finishes. */
        ReturnType runWithResources() throws IOException, HttpException {
            boolean finishedSuccessfully = false;
            try {
                final ReturnType ret = run();
                finishedSuccessfully = true;
                return ret;
            } finally {
                Throwable closeException = null;
                for(final Closeable resource: resources) {
                    try {
                        resource.close();
                    } catch (final Throwable ex) { // Normally bad form to catch Throwable, but that's what Java 7 try-with-resources does
                        // Only keep the last one, if there is more than one
                        closeException = ex;
                    }
                }
                if (closeException != null &&
                    finishedSuccessfully) { // Only throw an exception from close() if no exception was thrown from run()
                    try {
                        throw closeException;
                    } catch (final IOException ex) { // Only checked exception close() is allowed to throw
                        throw ex;
                    } catch (final RuntimeException ex) {
                        throw ex;
                    } catch (final Error ex) {
                        throw ex;
                    } catch (final Throwable ex) {
                        // Defensive code, cannot happen unless there is a bug in the above code
                        // or the close method throws something unexpected
                        throw new UndeclaredThrowableException(closeException, "Exception of unexpected type occurred");
                    }
                }
            }
        }
    }

    /**
     * Cache validity policy that always returns an age of 0.
     *
     * This prevents the Age header from being written to the cache (it does not make sense to cache it),
     * and is the only thing the policy is used for in this case.
     */
    private static class NoAgeCacheValidityPolicy extends CacheValidityPolicy {
        @Override
        public long getCurrentAgeSecs(final HttpCacheEntry entry, final Date now) {
            return 0L;
        }
    }
}

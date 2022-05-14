/*
 * acme4j - Java ACME client
 *
 * Copyright (C) 2015 Richard "Shred" Körber
 *   http://acme4j.shredzone.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package org.shredzone.acme4j.provider;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.shredzone.acme4j.toolbox.TestUtils.getJSON;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.shredzone.acme4j.Login;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.challenge.TlsAlpn01Challenge;
import org.shredzone.acme4j.challenge.TokenChallenge;
import org.shredzone.acme4j.connector.Connection;
import org.shredzone.acme4j.connector.DefaultConnection;
import org.shredzone.acme4j.connector.HttpConnector;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.exception.AcmeProtocolException;
import org.shredzone.acme4j.toolbox.JSON;
import org.shredzone.acme4j.toolbox.JSONBuilder;
import org.shredzone.acme4j.toolbox.TestUtils;

/**
 * Unit tests for {@link AbstractAcmeProvider}.
 */
public class AbstractAcmeProviderTest {

    private static final URI SERVER_URI = URI.create("http://example.com/acme");
    private static final URL RESOLVED_URL = TestUtils.url("http://example.com/acme/directory");

    /**
     * Test that connect returns a connection.
     */
    @Test
    public void testConnect() {
        final AtomicBoolean invoked = new AtomicBoolean();

        AbstractAcmeProvider provider = new TestAbstractAcmeProvider() {
            @Override
            protected HttpConnector createHttpConnector() {
                invoked.set(true);
                return super.createHttpConnector();
            }
        };

        Connection connection = provider.connect(SERVER_URI);
        assertThat(connection).isNotNull();
        assertThat(connection).isInstanceOf(DefaultConnection.class);
        assertThat(invoked).isTrue();
    }

    /**
     * Verify that the resources directory is read.
     */
    @Test
    public void testResources() throws AcmeException {
        final Connection connection = mock(Connection.class);
        final Session session = mock(Session.class);

        when(connection.readJsonResponse()).thenReturn(getJSON("directory"));

        AbstractAcmeProvider provider = new TestAbstractAcmeProvider(connection);
        JSON map = provider.directory(session, SERVER_URI);

        assertThatJson(map.toString()).isEqualTo(TestUtils.getJSON("directory").toString());

        verify(connection).sendRequest(RESOLVED_URL, session, null);
        verify(connection).getNonce();
        verify(connection).getLastModified();
        verify(connection).getExpiration();
        verify(connection).readJsonResponse();
        verify(connection).close();
        verifyNoMoreInteractions(connection);
    }

    /**
     * Verify that the cache control headers are evaluated.
     */
    @Test
    public void testResourcesCacheControl() throws AcmeException {
        ZonedDateTime lastModified = ZonedDateTime.now().minus(13, ChronoUnit.DAYS);
        ZonedDateTime expiryDate = ZonedDateTime.now().plus(60, ChronoUnit.DAYS);

        final Connection connection = mock(Connection.class);
        final Session session = mock(Session.class);

        when(connection.readJsonResponse()).thenReturn(getJSON("directory"));
        when(connection.getLastModified()).thenReturn(Optional.of(lastModified));
        when(connection.getExpiration()).thenReturn(Optional.of(expiryDate));
        when(session.getDirectoryExpires()).thenReturn(null);
        when(session.getDirectoryLastModified()).thenReturn(null);

        AbstractAcmeProvider provider = new TestAbstractAcmeProvider(connection);
        JSON map = provider.directory(session, SERVER_URI);

        assertThatJson(map.toString()).isEqualTo(TestUtils.getJSON("directory").toString());

        verify(session).setDirectoryLastModified(eq(lastModified));
        verify(session).setDirectoryExpires(eq(expiryDate));
        verify(session).getDirectoryExpires();
        verify(session).getDirectoryLastModified();
        verifyNoMoreInteractions(session);

        verify(connection).sendRequest(RESOLVED_URL, session, null);
        verify(connection).getNonce();
        verify(connection).getLastModified();
        verify(connection).getExpiration();
        verify(connection).readJsonResponse();
        verify(connection).close();
        verifyNoMoreInteractions(connection);
    }

    /**
     * Verify that resorces are not fetched if not yet expired.
     */
    @Test
    public void testResourcesNotExprired() throws AcmeException {
        ZonedDateTime expiryDate = ZonedDateTime.now().plus(60, ChronoUnit.DAYS);

        final Connection connection = mock(Connection.class);
        final Session session = mock(Session.class);

        when(session.getDirectoryExpires()).thenReturn(expiryDate);

        AbstractAcmeProvider provider = new TestAbstractAcmeProvider();
        JSON map = provider.directory(session, SERVER_URI);

        assertThat(map).isNull();

        verify(session).getDirectoryExpires();
        verifyNoMoreInteractions(session);

        verifyNoMoreInteractions(connection);
    }

    /**
     * Verify that resorces are fetched if expired.
     */
    @Test
    public void testResourcesExprired() throws AcmeException {
        ZonedDateTime expiryDate = ZonedDateTime.now().plus(60, ChronoUnit.DAYS);
        ZonedDateTime pastExpiryDate = ZonedDateTime.now().minus(10, ChronoUnit.MINUTES);

        final Connection connection = mock(Connection.class);
        final Session session = mock(Session.class);

        when(connection.readJsonResponse()).thenReturn(getJSON("directory"));
        when(connection.getExpiration()).thenReturn(Optional.of(expiryDate));
        when(connection.getLastModified()).thenReturn(Optional.empty());
        when(session.getDirectoryExpires()).thenReturn(pastExpiryDate);

        AbstractAcmeProvider provider = new TestAbstractAcmeProvider(connection);
        JSON map = provider.directory(session, SERVER_URI);

        assertThatJson(map.toString()).isEqualTo(TestUtils.getJSON("directory").toString());

        verify(session).setDirectoryExpires(eq(expiryDate));
        verify(session).setDirectoryLastModified(eq(null));
        verify(session).getDirectoryExpires();
        verify(session).getDirectoryLastModified();
        verifyNoMoreInteractions(session);

        verify(connection).sendRequest(RESOLVED_URL, session, null);
        verify(connection).getNonce();
        verify(connection).getLastModified();
        verify(connection).getExpiration();
        verify(connection).readJsonResponse();
        verify(connection).close();
        verifyNoMoreInteractions(connection);
    }

    /**
     * Verify that if-modified-since is used.
     */
    @Test
    public void testResourcesIfModifiedSince() throws AcmeException {
        ZonedDateTime modifiedSinceDate = ZonedDateTime.now().minus(60, ChronoUnit.DAYS);

        final Connection connection = mock(Connection.class);
        final Session session = mock(Session.class);

        when(connection.sendRequest(eq(RESOLVED_URL), eq(session), eq(modifiedSinceDate)))
                .thenReturn(HttpURLConnection.HTTP_NOT_MODIFIED);
        when(connection.getLastModified()).thenReturn(Optional.of(modifiedSinceDate));
        when(session.getDirectoryLastModified()).thenReturn(modifiedSinceDate);

        AbstractAcmeProvider provider = new TestAbstractAcmeProvider(connection);
        JSON map = provider.directory(session, SERVER_URI);

        assertThat(map).isNull();

        verify(session).getDirectoryExpires();
        verify(session).getDirectoryLastModified();
        verifyNoMoreInteractions(session);

        verify(connection).sendRequest(RESOLVED_URL, session, modifiedSinceDate);
        verify(connection).close();
        verifyNoMoreInteractions(connection);
    }

    /**
     * Verify that HTTP errors are handled correctly.
     */
    @Test
    public void testResourcesHttpError() throws AcmeException, IOException {
        final HttpURLConnection conn = mock(HttpURLConnection.class);
        final HttpConnector connector = mock(HttpConnector.class);
        final Connection connection = new DefaultConnection(connector);

        when(connector.openConnection(any(), any())).thenReturn(conn);
        when(conn.getResponseCode()).thenReturn(HttpURLConnection.HTTP_INTERNAL_ERROR);
        when(conn.getResponseMessage()).thenReturn("Internal error");

        AbstractAcmeProvider provider = new TestAbstractAcmeProvider(connection);
        Session session = TestUtils.session(provider);

        assertThrows(AcmeException.class, () -> provider.directory(session, SERVER_URI));
    }

    /**
     * Test that challenges are generated properly.
     */
    @Test
    public void testCreateChallenge() {
        Login login = mock(Login.class);

        AbstractAcmeProvider provider = new TestAbstractAcmeProvider();

        Challenge c1 = provider.createChallenge(login, getJSON("httpChallenge"));
        assertThat(c1).isNotNull();
        assertThat(c1).isInstanceOf(Http01Challenge.class);

        Challenge c2 = provider.createChallenge(login, getJSON("httpChallenge"));
        assertThat(c2).isNotSameAs(c1);

        Challenge c3 = provider.createChallenge(login, getJSON("dnsChallenge"));
        assertThat(c3).isNotNull();
        assertThat(c3).isInstanceOf(Dns01Challenge.class);

        Challenge c4 = provider.createChallenge(login, getJSON("tlsAlpnChallenge"));
        assertThat(c4).isNotNull();
        assertThat(c4).isInstanceOf(TlsAlpn01Challenge.class);

        JSON json6 = new JSONBuilder()
                    .put("type", "foobar-01")
                    .put("url", "https://example.com/some/challenge")
                    .toJSON();
        Challenge c6 = provider.createChallenge(login, json6);
        assertThat(c6).isNotNull();
        assertThat(c6).isInstanceOf(Challenge.class);

        JSON json7 = new JSONBuilder()
                        .put("type", "foobar-01")
                        .put("token", "abc123")
                        .put("url", "https://example.com/some/challenge")
                        .toJSON();
        Challenge c7 = provider.createChallenge(login, json7);
        assertThat(c7).isNotNull();
        assertThat(c7).isInstanceOf(TokenChallenge.class);

        assertThrows(AcmeProtocolException.class, () -> {
            JSON json8 = new JSONBuilder()
                    .put("url", "https://example.com/some/challenge")
                    .toJSON();
            provider.createChallenge(login, json8);
        });

        assertThrows(NullPointerException.class, () -> {
            provider.createChallenge(login, null);
        });
    }

    private static class TestAbstractAcmeProvider extends AbstractAcmeProvider {
        private final Connection connection;

        public TestAbstractAcmeProvider() {
            this.connection = null;
        }

        public TestAbstractAcmeProvider(Connection connection) {
            this.connection = connection;
        }

        @Override
        public boolean accepts(URI serverUri) {
            assertThat(serverUri).isEqualTo(SERVER_URI);
            return true;
        }

        @Override
        public URL resolve(URI serverUri) {
            assertThat(serverUri).isEqualTo(SERVER_URI);
            return RESOLVED_URL;
        }

        @Override
        public Connection connect(URI serverUri) {
            assertThat(serverUri).isEqualTo(SERVER_URI);
            return connection != null ? connection : super.connect(serverUri);
        }
    }

}

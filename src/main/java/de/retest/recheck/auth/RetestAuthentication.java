package de.retest.recheck.auth;

import static org.keycloak.adapters.rotation.AdapterTokenVerifier.verifyToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.keycloak.OAuth2Constants;
import org.keycloak.OAuthErrorException;
import org.keycloak.adapters.KeycloakDeployment;
import org.keycloak.adapters.KeycloakDeploymentBuilder;
import org.keycloak.adapters.ServerRequest;
import org.keycloak.adapters.ServerRequest.HttpFailure;
import org.keycloak.adapters.rotation.AdapterTokenVerifier;
import org.keycloak.common.VerificationException;
import org.keycloak.common.util.KeycloakUriBuilder;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.representations.adapters.config.AdapterConfig;

import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RetestAuthentication {

	public static final String AUTH_SERVER_PROPERTY = "de.retest.auth.server";
	private static final String AUTH_SERVER_PROPERTY_DEFAULT = "https://sso.prod.cloud.retest.org/auth";
	public static final String RESOURCE_PROPERTY = "de.retest.auth.resource";
	private static final String RESOURCE_PROPERTY_DEFAULT = "review";

	private final KeycloakDeployment deployment;

	private String refreshToken;
	private AccessToken accessToken;
	private String accessTokenString;

	private static RetestAuthentication instance;

	private RetestAuthentication() {
		final AdapterConfig config = new AdapterConfig();
		config.setRealm( "customer" );
		config.setAuthServerUrl( System.getProperty( AUTH_SERVER_PROPERTY, AUTH_SERVER_PROPERTY_DEFAULT ) );
		config.setSslRequired( "external" );
		config.setResource( System.getProperty( RESOURCE_PROPERTY, RESOURCE_PROPERTY_DEFAULT ) );
		config.setPublicClient( true );

		deployment = KeycloakDeploymentBuilder.build( config );
	}

	public static RetestAuthentication getInstance() {
		if ( instance == null ) {
			instance = new RetestAuthentication();
		}
		return instance;
	}

	public URI getAccountUrl() {
		return URI.create( deployment.getAccountUrl() );
	}

	public boolean isAuthenticated( final String offlineToken ) {
		if ( offlineToken != null ) {
			refreshToken = offlineToken;
			try {
				final AccessTokenResponse response = ServerRequest.invokeRefresh( deployment, offlineToken );
				accessToken = verifyToken( response.getToken(), deployment );
				accessTokenString = response.getToken();
				return true;
			} catch ( IOException | HttpFailure | VerificationException e ) {
				log.info( "Token not recognized, please authenticate" );
				log.debug( "Error verifying offline token", e );
			}
		}

		return false;
	}

	public void login( final AuthenticationHandler handler ) throws IOException, HttpFailure, VerificationException {
		try {
			final CallbackListener callback = new CallbackListener();
			callback.start();

			final String redirectUri = "http://localhost:" + callback.server.getLocalPort();
			final String state = UUID.randomUUID().toString();

			final KeycloakUriBuilder builder = deployment.getAuthUrl().clone() //
					.queryParam( OAuth2Constants.RESPONSE_TYPE, OAuth2Constants.CODE ) //
					.queryParam( OAuth2Constants.CLIENT_ID, deployment.getResourceName() ) //
					.queryParam( OAuth2Constants.REDIRECT_URI, redirectUri ) //
					.queryParam( OAuth2Constants.STATE, state ) //
					.queryParam( OAuth2Constants.SCOPE, OAuth2Constants.OFFLINE_ACCESS );

			final URI loginUri = URI.create( builder.build().toString() );
			log.debug( "Open login URI '{}' in browser to login", loginUri );
			handler.showWebLoginUri( loginUri );

			callback.join();

			if ( !state.equals( callback.result.getState() ) ) {
				final VerificationException reason = new VerificationException( "Invalid state" );
				handler.authenticationFailed( reason );
			}

			if ( callback.result.getError() != null ) {
				final OAuthErrorException reason =
						new OAuthErrorException( callback.result.getError(), callback.result.getErrorDescription() );
				handler.authenticationFailed( reason );
			}

			if ( callback.result.getErrorException() != null ) {
				handler.authenticationFailed( callback.result.getErrorException() );
			}

			final AccessTokenResponse tokenResponse =
					ServerRequest.invokeAccessCodeToToken( deployment, callback.result.getCode(), redirectUri, null );
			refreshToken = tokenResponse.getRefreshToken();
			parseAccessToken( tokenResponse );

			handler.authenticated();
		} catch ( final InterruptedException e ) {
			log.error( "Error during authentication, thread interrupted", e );
			Thread.currentThread().interrupt();
		}

	}

	private void parseAccessToken( final AccessTokenResponse tokenResponse ) throws VerificationException {
		accessTokenString = tokenResponse.getToken();
		final String idTokenString = tokenResponse.getIdToken();

		final AdapterTokenVerifier.VerifiedTokens tokens =
				AdapterTokenVerifier.verifyTokens( accessTokenString, idTokenString, deployment );
		accessToken = tokens.getAccessToken();
	}

	public AccessToken getAccessToken() {
		refreshTokens();
		return accessToken;
	}

	public String getAccessTokenString() {
		refreshTokens();
		return accessTokenString;
	}

	public String getOfflineTokenString() {
		return refreshToken;
	}

	private void refreshTokens() {
		if ( !isTokenValid() ) {
			try {
				parseAccessToken( ServerRequest.invokeRefresh( deployment, refreshToken ) );
			} catch ( final IOException | HttpFailure | VerificationException e ) {
				log.error( "Error refreshing token(s)", e );
			}
		}
	}

	private boolean isTokenValid() {
		try {
			return accessTokenString != null && accessToken != null
					&& verifyToken( accessTokenString, deployment ).isActive();
		} catch ( final VerificationException e ) {
			log.info( "Current token is invalid, requesting new one" );
		}
		return false;
	}

	static KeycloakResult getRequestParameters( final String request ) {
		final String url = "http://localhost/" + request.split( " " )[1];
		final Map<String, String> parameters = URLEncodedUtils.parse( URI.create( url ), StandardCharsets.UTF_8 ) //
				.stream() //
				.collect( Collectors.toMap( NameValuePair::getName, NameValuePair::getValue ) );

		return KeycloakResult.builder() //
				.code( parameters.get( OAuth2Constants.CODE ) ) //
				.error( parameters.get( OAuth2Constants.ERROR ) ) //
				.errorDescription( parameters.get( "error-description" ) ) //
				.state( parameters.get( OAuth2Constants.STATE ) ) //
				.build();
	}

	private class CallbackListener extends Thread {

		private final ServerSocket server;
		private KeycloakResult result;

		public CallbackListener() throws IOException {
			server = new ServerSocket( 0 );
		}

		@Override
		public void run() {
			try ( Socket socket = server.accept() ) {
				@Cleanup
				final BufferedReader br = new BufferedReader( new InputStreamReader( socket.getInputStream() ) );
				final String request = br.readLine();

				result = getRequestParameters( request );

				@Cleanup
				final OutputStreamWriter out = new OutputStreamWriter( socket.getOutputStream() );
				@Cleanup
				final PrintWriter writer = new PrintWriter( out );

				if ( result.getError() == null ) {
					writer.println( "HTTP/1.1 302 Found" );
					writer.println( "Location: " + deployment.getTokenUrl().replace( "/token", "/delegated" ) );

				} else {
					writer.println( "HTTP/1.1 302 Found" );
					writer.println(
							"Location: " + deployment.getTokenUrl().replace( "/token", "/delegated?error=true" ) );

				}
			} catch ( final IOException e ) {
				log.error( "Error during communication with sso.cloud.retest.org", e );
			}
		}

	}

}

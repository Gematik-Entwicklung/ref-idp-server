/*
 * Copyright (c) 2021 gematik GmbH
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.gematik.idp.client;

import static de.gematik.idp.authentication.UriUtils.extractParameterValue;
import static de.gematik.idp.crypto.CryptoLoader.getCertificateFromPem;
import static de.gematik.idp.field.ClaimName.*;
import static org.apache.http.HttpHeaders.CONTENT_TYPE;

import de.gematik.idp.authentication.AuthenticationChallenge;
import de.gematik.idp.authentication.UriUtils;
import de.gematik.idp.client.data.*;
import de.gematik.idp.field.IdpScope;
import de.gematik.idp.token.JsonWebToken;
import de.gematik.idp.token.TokenClaimExtraction;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.ws.rs.core.HttpHeaders;
import kong.unirest.*;
import kong.unirest.jackson.JacksonObjectMapper;
import kong.unirest.json.JSONObject;
import org.apache.http.HttpStatus;
import org.springframework.http.MediaType;

public class AuthenticatorClient {

    {
        Unirest.config().setObjectMapper(new JacksonObjectMapper());
    }

    private static final String USER_AGENT = "IdP-Client";

    public static Map<String, String> getAllHeaderElementsAsMap(final HttpRequest request) {
        return request.getHeaders().all().stream()
            .collect(Collectors.toMap(Header::getName, Header::getValue));
    }

    public static Map<String, Object> getAllFieldElementsAsMap(final MultipartBody request) {
        return request.multiParts().stream()
            .collect(Collectors.toMap(BodyPart::getName, BodyPart::getValue));
    }

    public AuthorizationResponse doAuthorizationRequest(
        final AuthorizationRequest authorizationRequest,
        final Function<GetRequest, GetRequest> beforeCallback,
        final Consumer<HttpResponse<AuthenticationChallenge>> afterCallback
    ) {
        final String scope = authorizationRequest.getScopes().stream()
            .map(IdpScope::getJwtValue)
            .collect(Collectors.joining(" "));

        final GetRequest request = Unirest.get(authorizationRequest.getLink())
            .queryString(CLIENT_ID.getJoseName(), authorizationRequest.getClientId())
            .queryString(RESPONSE_TYPE.getJoseName(), "code")
            .queryString(REDIRECT_URI.getJoseName(), authorizationRequest.getRedirectUri())
            .queryString(STATE.getJoseName(), authorizationRequest.getState())
            .queryString(CODE_CHALLENGE.getJoseName(), authorizationRequest.getCodeChallenge())
            .queryString(CODE_CHALLENGE_METHOD.getJoseName(),
                authorizationRequest.getCodeChallengeMethod())
            .queryString(SCOPE.getJoseName(), scope)
            .queryString("nonce", authorizationRequest.getNonce())
            .header(HttpHeaders.USER_AGENT, USER_AGENT);

        final HttpResponse<AuthenticationChallenge> authorizationResponse = beforeCallback
            .apply(request)
            .asObject(AuthenticationChallenge.class);
        afterCallback.accept(authorizationResponse);
        if (authorizationResponse.getStatus() != HttpStatus.SC_OK) {
            throw new IdpClientRuntimeException(
                "Unexpected Server-Response " + authorizationResponse.getStatus());
        }
        return AuthorizationResponse.builder()
            .authenticationChallenge(authorizationResponse.getBody())
            .build();
    }

    public AuthenticationResponse performAuthentication(
        final AuthenticationRequest authenticationRequest,
        final Function<MultipartBody, MultipartBody> beforeAuthenticationCallback,
        final Consumer<HttpResponse<String>> afterAuthenticationCallback) {

        final MultipartBody request = Unirest
            .post(authenticationRequest.getAuthenticationEndpointUrl())
            .field("signed_challenge", authenticationRequest.getSignedChallenge().getRawValue())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header(HttpHeaders.USER_AGENT, USER_AGENT);

        final HttpResponse<String> loginResponse = beforeAuthenticationCallback.apply(request).asString();
        afterAuthenticationCallback.accept(loginResponse);
        final String location = retrieveLocationFromResponse(loginResponse);

        checkForForwardingExceptionAndThrowIfPresent(location);

        return AuthenticationResponse.builder()
            .code(extractParameterValue(location, "code"))
            .location(location)
            .ssoToken(extractParameterValue(location, "sso_token"))
            .build();
    }

    private void checkForForwardingExceptionAndThrowIfPresent(final String location) {
        UriUtils.extractParameterValueOptional(location, "error")
            .ifPresent(errorCode -> {
                throw new IdpClientRuntimeException("Server-Error with message: " +
                    UriUtils.extractParameterValueOptional(location, "error_description")
                        .orElse(errorCode));
            });
    }

    public AuthenticationResponse performAuthenticationWithSsoToken(
        final AuthenticationRequest authenticationRequest,
        final Function<MultipartBody, MultipartBody> beforeAuthenticationCallback,
        final Consumer<HttpResponse<String>> afterAuthenticationCallback) {

        final MultipartBody request = Unirest.post(authenticationRequest.getAuthenticationEndpointUrl())
            .field("sso_token", authenticationRequest.getSsoToken())
            .field("unsigned_challenge", authenticationRequest.getChallengeToken().getJwtRawString())
            .header(CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
            .header(HttpHeaders.USER_AGENT, USER_AGENT);
        final HttpResponse<String> loginResponse = beforeAuthenticationCallback.apply(request).asString();
        afterAuthenticationCallback.accept(loginResponse);
        final String location = retrieveLocationFromResponse(loginResponse);
        checkForForwardingExceptionAndThrowIfPresent(location);
        return AuthenticationResponse.builder()
            .code(extractParameterValue(location, "code"))
            .location(location)
            .build();
    }

    private String retrieveLocationFromResponse(final HttpResponse<String> response) {
        if (response.getStatus() != 302) {
            throw new IdpClientRuntimeException("Unexpected status code in response: " + response.getStatus());
        }
        return response.getHeaders().getFirst("Location");
    }

    public IdpTokenResult retrieveAcessToken(
        final TokenRequest tokenRequest,
        final Function<MultipartBody, MultipartBody> beforeTokenCallback,
        final Consumer<HttpResponse<JsonNode>> afterTokenCallback) {
        final MultipartBody request = Unirest.post(tokenRequest.getTokenUrl())
            .header(CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
            .field("grant_type", "authorization_code")
            .field("client_id", tokenRequest.getClientId())
            .field("code", tokenRequest.getCode())
            .field("code_verifier", tokenRequest.getCodeVerifier())
            .field("redirect_uri", tokenRequest.getRedirectUrl())
            .header(HttpHeaders.USER_AGENT, USER_AGENT);

        final HttpResponse<JsonNode> tokenResponse = beforeTokenCallback.apply(request)
            .asJson();
        afterTokenCallback.accept(tokenResponse);
        if (tokenResponse.getStatus() != HttpStatus.SC_OK) {
            throw new IdpClientRuntimeException(
                "Unexpected Server-Response " + tokenResponse.getStatus());
        }
        final JSONObject jsonObject = tokenResponse.getBody().getObject();

        final JsonWebToken accessToken = Optional.ofNullable(jsonObject.get("access_token"))
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .map(JsonWebToken::new)
            .orElseThrow(() -> new IdpClientRuntimeException("Unable to extract Access-Token from response!"));

        final String idTokenRawString = jsonObject.get("id_token").toString();

        final String tokenType = tokenResponse.getBody().getObject().getString("token_type");
        final int expiresIn = tokenResponse.getBody().getObject().getInt("expires_in");

        return IdpTokenResult.builder()
            .tokenType(tokenType)
            .expiresIn(expiresIn)
            .accessToken(accessToken)
            .idToken(new JsonWebToken(idTokenRawString))
            .ssoToken(new JsonWebToken(tokenRequest.getSsoToken()))
            .build();
    }

    public DiscoveryDocumentResponse retrieveDiscoveryDocument(final String discoveryDocumentUrl) {
        //TODO aufräumen, checks hinzufügen...
        final HttpResponse<String> discoveryDocumentResponse = Unirest.get(discoveryDocumentUrl)
            .header(HttpHeaders.USER_AGENT, USER_AGENT)
            .asString();
        final Map<String, Object> discoveryClaims = TokenClaimExtraction
            .extractClaimsFromTokenBody(discoveryDocumentResponse.getBody());

        final HttpResponse<JsonNode> pukAuthResponse = Unirest
            .get(discoveryClaims.get("puk_uri_auth").toString())
            .header(HttpHeaders.USER_AGENT, USER_AGENT)
            .asJson();
        final JSONObject keyObject = pukAuthResponse.getBody().getObject();

        final String verificationCertificate = keyObject.getJSONArray(X509_CERTIFICATE_CHAIN.getJoseName())
            .getString(0);

        return DiscoveryDocumentResponse.builder()
            .authorizationEndpoint(discoveryClaims.get("authorization_endpoint").toString())
            .tokenEndpoint(discoveryClaims.get("token_endpoint").toString())

            .keyId(keyObject.getString("kid"))
            .verificationCertificate(verificationCertificate)

            .serverTokenCertificate(
                getCertificateFromPem(Base64.getDecoder().decode(verificationCertificate)))

            .build();
    }
}

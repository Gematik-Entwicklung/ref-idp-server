/*
 * Copyright (c) 2020 gematik GmbH
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

package de.gematik.idp.server.controllers;

import static de.gematik.idp.IdpConstants.AUTHORIZATION_ENDPOINT;
import static de.gematik.idp.IdpConstants.TOKEN_ENDPOINT;
import static de.gematik.idp.field.ClaimName.CLIENT_ID;
import static de.gematik.idp.field.ClaimName.CODE_CHALLENGE;

import de.gematik.idp.authentication.AuthenticationChallenge;
import de.gematik.idp.authentication.AuthenticationChallengeBuilder;
import de.gematik.idp.field.CodeChallengeMethod;
import de.gematik.idp.server.ServerUrlService;
import de.gematik.idp.server.data.TokenResponse;
import de.gematik.idp.server.services.IdpAuthenticator;
import de.gematik.idp.server.services.PkceChecker;
import de.gematik.idp.token.AccessTokenBuilder;
import de.gematik.idp.token.IdTokenBuilder;
import de.gematik.idp.token.JsonWebToken;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Api(tags = {
    "Idp-Dienst"}, description = "REST Endpunkte für das Authentifizieren, Authorisieren und die Tokenabfrage")
public class IdpController {

    private final ServerUrlService serverUrlService;
    private final AuthenticationChallengeBuilder authenticationChallengeBuilder;
    private final IdpAuthenticator idpAuthenticator;
    private final AccessTokenBuilder accessTokenBuilder;
    private final IdTokenBuilder idTokenBuilder;
    private final PkceChecker pkceChecker;

    @GetMapping(AUTHORIZATION_ENDPOINT)
    @ApiOperation(httpMethod = "GET", value = "Endpunkt für Authentifizierung", notes = "Die übergebenen Parameter werden zu einer Liste von JWTClaims zusammengefasst und daraus dann die zurückgelieferte AuthenticationChallenge gebaut.", response = AuthenticationChallenge.class)
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Erfolgreich Daten für Autorisierung erhalten"),
        @ApiResponse(responseCode = "401", description = "Nicht autorisierter Zugriff"),
        @ApiResponse(responseCode = "403", description = "Nicht erlaubter Zugriff"),
        @ApiResponse(responseCode = "404", description = "Nicht gefunden - Methodenaufruf nicht korrekt")
    })
    public AuthenticationChallenge getAuthenticationChallenge(
        @RequestParam(name = "client_id") @ApiParam(value = "Identifier für den zugreifenden Client") final String clientId,
        @RequestParam(name = "state") @ApiParam(value = "Eine Sicherheitsmaßnahme gegen CSRF-Angriffe") final String state,
        @RequestParam(name = "redirect_uri") @ApiParam(value = "TODO redirect_uri") final String redirectUri,
        @RequestParam(name = "code_challenge") @ApiParam(value = "Authentifizierungscode") final String codeChallenge,
        @RequestParam(name = "code_challenge_method") @ApiParam(value = "TODO code_challenge_method") final CodeChallengeMethod codeChallengeMethod,
        final HttpServletResponse response) {
        idpAuthenticator.validateRedirectUri(redirectUri);
        pkceChecker.checkCodeChallengeMethod(codeChallengeMethod);
        setNoCacheHeader(response);
        return authenticationChallengeBuilder
            .buildAuthenticationChallenge(clientId, state, redirectUri, codeChallenge);
    }

    @PostMapping(AUTHORIZATION_ENDPOINT)
    @ApiOperation(httpMethod = "POST", value = "Endpunkt für Authorisierung", notes =
        "Der Endpunkt kann 2 Parameter entgegennehmen, wird aber nur einen Parameter verarbeiten." +
            "\nWird ein SsoToken übergeben, so wird aus diesem der Code für die Tokenabfrage generiert. Der Code wird dann als Query parameter mit der URL zum Token Endpunkt zurückgeliefert."
            +
            "\nWird kein SsoToken und nur eine signierte Challenge an den Endpunkt übergeben, wird diese validiert, das Client-Zertifikat extrahiert und daraus der Code für die Tokenabfrage und ein SSO Token generiert. Der Code und der SsoToken werden dann zusammen als Query parameter mit der URL zum Token Endpunkt zurückgeliefert."
            +
            "\nWird kein Parameter an den Endpunkt übergeben, wird eine Exception zurückgegeben und der HttpStatusCode 400")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Erfolgreich Daten für Token-Abfrage erhalten"),
        @ApiResponse(responseCode = "401", description = "Nicht autorisierter Zugriff"),
        @ApiResponse(responseCode = "403", description = "Nicht erlaubter Zugriff"),
        @ApiResponse(responseCode = "404", description = "Nicht gefunden - Methodenaufruf nicht korrekt")
    })
    public void validateChallengeAndGetTokenCode(
        @RequestParam(value = "signed_challenge", required = false) @ApiParam(value = "Signierte Challenge") final JsonWebToken signedChallenge,
        @RequestParam(value = "sso_token", required = false) @ApiParam(value = "Single Sign-On Token") final JsonWebToken ssoToken,
        @RequestParam(value = "challenge_token", required = false) @ApiParam(value = "Originale Server-Challenge. Benötigt für den SSO-Flow") final JsonWebToken challengeToken,
        final HttpServletResponse response,
        final HttpServletRequest request) {
        setNoCacheHeader(response);
        response.setStatus(HttpStatus.FOUND.value());

        final String tokenLocation = idpAuthenticator.getTokenLocation(
            signedChallenge,
            ssoToken,
            challengeToken,
            serverUrlService.determineServerUrl(request));

        response.setHeader(HttpHeaders.LOCATION, tokenLocation);
    }

    @PostMapping(TOKEN_ENDPOINT)
    @ApiOperation(httpMethod = "POST", value = "Endpunkt für Tokenabfrage", notes = "Es wird der Token Code mit dem Code Verifier geprüft, entwertet und bei Erfolg daraus ein Zugangstoken erstellt. Der Zugangstoken wird gemeinsam mit einem ID Token zurückgeliefert.", response = TokenResponse.class)
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Erfolgreich Token-Response erhalten"),
        @ApiResponse(responseCode = "401", description = "Nicht autorisierter Zugriff"),
        @ApiResponse(responseCode = "403", description = "Nicht erlaubter Zugriff"),
        @ApiResponse(responseCode = "404", description = "Nicht gefunden - Methodenaufruf nicht korrekt")
    })
    public TokenResponse getTokensForCode(
        @RequestParam(value = "code") @ApiParam(value = "Tokenzugriffscode") final JsonWebToken authenticationToken,
        @RequestParam("code_verifier") @ApiParam(value = "Verifikation des Authentifizierungscodes") final String codeVerifier,
        final HttpServletResponse response) {
        final String codeChallenge = (String) authenticationToken.getBodyClaims().get(CODE_CHALLENGE.getJoseName());
        pkceChecker.checkCodeVerifier(codeVerifier, codeChallenge);
        final String clientId = (String) authenticationToken.getBodyClaims().get(CLIENT_ID.getJoseName());

        setNoCacheHeader(response);
        return TokenResponse.builder()
            .tokenType("Bearer")
            .expiresIn(300)
            .accessToken(accessTokenBuilder.buildAccessToken(authenticationToken).getJwtRawString())
            .idToken(idTokenBuilder.buildIdToken(clientId).getJwtRawString())
            .build();
    }

    private void setNoCacheHeader(final HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
    }
}
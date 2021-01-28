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

package de.gematik.idp.authentication;

import de.gematik.idp.crypto.model.PkiIdentity;
import de.gematik.idp.exceptions.IdpJoseException;
import de.gematik.idp.field.ClaimName;
import de.gematik.idp.token.JsonWebToken;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;

@Data
@Builder
@AllArgsConstructor
public class AuthenticationChallengeVerifier {

    private PkiIdentity serverIdentity;

    public void verifyResponseAndThrowExceptionIfFail(final JsonWebToken authenticationResponse) {
        final X509Certificate clientCertificate = extractClientCertificateFromChallenge(authenticationResponse)
            .orElseThrow(
                () -> new IdpJoseException("Could not extract client certificate from challenge response header"));

        performClientSignatureValidation(clientCertificate, authenticationResponse.getJwtRawString());
        performServerSignatureValidationOfNjwt(authenticationResponse);
    }

    public void verifyResponseWithCertAndThrowExceptionIfFail(final X509Certificate validationCert,
        final JsonWebToken authenticationResponse) {
        performClientSignatureValidation(validationCert, authenticationResponse.getJwtRawString());
    }

    private void performClientSignatureValidation(final X509Certificate clientCertificate,
        final String authResponse) {
        final JwtConsumer serverJwtConsumer = new JwtConsumerBuilder()
            .setVerificationKey(clientCertificate.getPublicKey())
            .build();
        try {
            serverJwtConsumer.process(authResponse);
        } catch (final InvalidJwtException e) {
            throw new IdpJoseException(e);
        }
    }

    private void performServerSignatureValidationOfNjwt(final JsonWebToken authenticationResponse) {
        authenticationResponse.getBodyClaim(ClaimName.NESTED_JWT)
            .map(njwt -> new JsonWebToken(njwt.toString()))
            .ifPresentOrElse(jsonWebToken -> jsonWebToken.verify(serverIdentity.getCertificate().getPublicKey()),
                () -> {
                    throw new IdpJoseException("Server certificate mismatch");
                }
            );
    }

    public Optional<X509Certificate> extractClientCertificateFromChallenge(final JsonWebToken authenticationResponse) {
        return authenticationResponse.getClientCertificateFromHeader();
    }

    public Map<String, Object> extractClaimsFromSignedChallenge(final AuthenticationResponse authenticationResponse) {
        return authenticationResponse.getSignedChallenge().getBodyClaims();
    }
}

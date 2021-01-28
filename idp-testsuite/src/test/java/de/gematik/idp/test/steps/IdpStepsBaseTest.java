package de.gematik.idp.test.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import de.gematik.idp.crypto.CryptoLoader;
import de.gematik.idp.data.IdpKeyDescriptor;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import lombok.SneakyThrows;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

public class IdpStepsBaseTest {

    @Test
    @SneakyThrows
    public void checkSelfSignedCertIsDetected() {
        final byte[] data = IOUtils
            .toByteArray(getClass().getResourceAsStream("/certs/invalid/smcb-idp-selfsigned.p12"));
        final X509Certificate cert = CryptoLoader.getCertificateFromP12(data, "00");
        final IdpKeyDescriptor desc = IdpKeyDescriptor.constructFromX509Certificate(cert);

        assertThatThrownBy(() ->
            new IdpStepsBase().jsonObjectShouldBeValidCertificate(new JSONObject(desc.toJSONString())))
            .isInstanceOf(AssertionError.class);
    }

    @Test
    @SneakyThrows
    public void checkExpiredCertIsDetected() {
        final byte[] data = IOUtils
            .toByteArray(getClass().getResourceAsStream("/certs/invalid/smcb-idp-expired-ecc.p12"));
        final X509Certificate cert = CryptoLoader.getCertificateFromP12(data, "00");
        final IdpKeyDescriptor desc = IdpKeyDescriptor.constructFromX509Certificate(cert);

        assertThatThrownBy(() ->
            new IdpStepsBase().jsonObjectShouldBeValidCertificate(new JSONObject(desc.toJSONString())))
            .isInstanceOf(CertificateExpiredException.class);
    }

    @Test
    @SneakyThrows
    public void checkValidCertPassChecks() {
        final byte[] data = IOUtils
            .toByteArray(getClass().getResourceAsStream("/certs/valid/80276883110000129068-C_SMCB_HCI_AUT_E256.p12"));
        final X509Certificate cert = CryptoLoader.getCertificateFromP12(data, "00");
        assertThat(cert).isNotNull();
        final IdpKeyDescriptor desc = IdpKeyDescriptor.constructFromX509Certificate(cert);
        new IdpStepsBase().jsonObjectShouldBeValidCertificate(new JSONObject(desc.toJSONString()));
    }
}

package com.lifeos.gateway.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/** RS256 test tokens. {@link #decoder()} trusts only {@link #TRUSTED}, standing in for Keycloak's JWKS. */
public final class TestJwts {

    public static final KeyPair TRUSTED = rsa();
    public static final KeyPair UNTRUSTED = rsa();

    private TestJwts() {
    }

    public static ReactiveJwtDecoder decoder() {
        return NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) TRUSTED.getPublic()).build();
    }

    public static String token(String subject) {
        return token(TRUSTED.getPrivate(), subject);
    }

    public static String token(PrivateKey key, String subject) {
        try {
            Instant now = Instant.now();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                    .subject(subject)
                    .audience("lifeos-api")
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(300)))
                    .build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.juli22292.vencordandroidapp;

import android.content.Context;
import android.util.AtomicFile;

import com.android.apksig.ApkSigner;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

/**
 * Generates and stores the persistent local RSA-2048 APK signing keystore (ks.keystore)
 * used by LSPatch to sign the patched official Discord APKs on-device.
 */
public class SignerHelper {

    public static final String KEYSTORE_PASSWORD = "password";
    public static final String KEY_ALIAS = "alias";

    public static ApkSigner.SignerConfig loadApkSigner(File keystoreFile) throws Exception {
        char[] password = KEYSTORE_PASSWORD.toCharArray();
        KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
        try (FileInputStream input = new FileInputStream(keystoreFile)) {
            keystore.load(input, password);
        }
        return new ApkSigner.SignerConfig.Builder(
                KEY_ALIAS,
                (PrivateKey) keystore.getKey(KEY_ALIAS, password),
                Collections.singletonList((X509Certificate) keystore.getCertificate(KEY_ALIAS))
        ).build();
    }

    public static File getOrCreateKeystore(Context context) throws Exception {
        File ksFile = new File(context.getFilesDir(), "ks.keystore");
        if (ksFile.isFile() && ksFile.length() > 0) {
            return ksFile;
        }

        SecureRandom random = new SecureRandom();
        BigInteger serial;
        do {
            serial = new BigInteger(64, random);
        } while (serial.compareTo(BigInteger.ZERO) <= 0);

        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair pair = keyGen.generateKeyPair();

        X500Name subject = new X500Name("CN=VencordForAndroid");
        Date notBefore = new Date(System.currentTimeMillis() - 1000L * 60L * 60L * 24L * 30L);
        Date notAfter = new Date(System.currentTimeMillis() + 1000L * 60L * 60L * 24L * 365L * 30L);

        X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(
                subject,
                serial,
                notBefore,
                notAfter,
                Locale.ENGLISH,
                subject,
                SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded())
        );

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certBuilder.build(signer));

        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, KEYSTORE_PASSWORD.toCharArray());
        keyStore.setKeyEntry(
                KEY_ALIAS,
                pair.getPrivate(),
                KEYSTORE_PASSWORD.toCharArray(),
                new Certificate[]{cert}
        );

        AtomicFile atomicKeystore = new AtomicFile(ksFile);
        FileOutputStream fos = atomicKeystore.startWrite();
        try {
            keyStore.store(fos, KEYSTORE_PASSWORD.toCharArray());
            atomicKeystore.finishWrite(fos);
        } catch (Exception e) {
            atomicKeystore.failWrite(fos);
            throw e;
        }

        return ksFile;
    }
}

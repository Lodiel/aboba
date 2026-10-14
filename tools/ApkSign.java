import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/** Signs an APK with v1 + v2 signatures using apksig. Args: in out keystore storepass alias keypass */
public class ApkSign {
    public static void main(String[] args) throws Exception {
        File in = new File(args[0]);
        File out = new File(args[1]);
        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(args[2])) {
            ks.load(fis, args[3].toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey(args[4], args[5].toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(args[4]);
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                args[4], key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(in)
                .setOutputApk(out)
                // v1 (JAR) signing in apksig 2.3.0 needs JDK internals removed in JDK 9+; the
                // app's minSdk is 26 and APK Signature Scheme v2 is supported since Android 7.0.
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setMinSdkVersion(26)
                .build()
                .sign();
        System.out.println("signed " + out + " (" + out.length() + " bytes)");

        ApkVerifier.Result result = new ApkVerifier.Builder(out).setMinCheckedPlatformVersion(26).build().verify();
        System.out.println("verified: " + result.isVerified()
                + " v1=" + result.isVerifiedUsingV1Scheme() + " v2=" + result.isVerifiedUsingV2Scheme());
        for (ApkVerifier.IssueWithParams e : result.getErrors()) System.out.println("ERROR: " + e);
        for (ApkVerifier.IssueWithParams w : result.getWarnings()) System.out.println("warning: " + w);
        if (!result.isVerified()) System.exit(1);
    }
}

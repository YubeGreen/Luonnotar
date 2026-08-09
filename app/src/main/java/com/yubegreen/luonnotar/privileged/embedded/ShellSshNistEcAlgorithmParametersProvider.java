package com.yubegreen.luonnotar.privileged.embedded;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.IOException;
import java.security.AlgorithmParameters;
import java.security.AlgorithmParametersSpi;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyPairGeneratorSpi;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidParameterSpecException;
import java.security.InvalidAlgorithmParameterException;
import java.util.Locale;

/**
 * Process-local JCA EC compatibility shim for Apache MINA's SSH curve names.
 *
 * MINA 2.19 reaches the NIST curve catalogue through more than one JCA surface
 * on the Android shell runtime. AlgorithmParameters.EC alone is therefore not
 * sufficient: the daemon can pass the bootstrap probe and still fail while
 * ECCurves initializes. This provider translates nistp256/nistp384/nistp521
 * for both AlgorithmParameters.EC and KeyPairGenerator.EC and delegates the
 * actual EC implementation to Luonnotar's bundled Bouncy Castle instance.
 */
public final class ShellSshNistEcAlgorithmParametersProvider extends Provider {
    static final String NAME = "LuonnotarSSH-EC";
    private static final double VERSION = 1.1d;
    private static final Provider BACKEND = new BouncyCastleProvider();

    ShellSshNistEcAlgorithmParametersProvider() {
        super(NAME, VERSION, "Luonnotar SSH NIST EC JCA compatibility shim");
        put("AlgorithmParameters.EC", NistEcAlgorithmParametersSpi.class.getName());
        put("KeyPairGenerator.EC", NistEcKeyPairGeneratorSpi.class.getName());
    }

    private static String canonicalCurveName(String name) {
        if (name == null) {
            return null;
        }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "nistp256":
                return "secp256r1";
            case "nistp384":
                return "secp384r1";
            case "nistp521":
                return "secp521r1";
            default:
                return name;
        }
    }

    private static AlgorithmParameterSpec canonicalize(AlgorithmParameterSpec paramSpec) {
        if (paramSpec instanceof ECGenParameterSpec) {
            ECGenParameterSpec ec = (ECGenParameterSpec) paramSpec;
            return new ECGenParameterSpec(canonicalCurveName(ec.getName()));
        }
        return paramSpec;
    }

    public static final class NistEcAlgorithmParametersSpi extends AlgorithmParametersSpi {
        private AlgorithmParameters delegate;

        private AlgorithmParameters newDelegate() throws GeneralSecurityException {
            return AlgorithmParameters.getInstance("EC", BACKEND);
        }

        @Override
        protected void engineInit(AlgorithmParameterSpec paramSpec) throws InvalidParameterSpecException {
            try {
                delegate = newDelegate();
                delegate.init(canonicalize(paramSpec));
            } catch (GeneralSecurityException error) {
                InvalidParameterSpecException wrapped = new InvalidParameterSpecException(
                        "Unable to initialize delegated EC parameters: " + error.getMessage());
                wrapped.initCause(error);
                throw wrapped;
            }
        }

        @Override
        protected void engineInit(byte[] params) throws IOException {
            try {
                delegate = newDelegate();
                delegate.init(params);
            } catch (GeneralSecurityException error) {
                throw new IOException("Unable to initialize delegated EC parameters", error);
            }
        }

        @Override
        protected void engineInit(byte[] params, String format) throws IOException {
            try {
                delegate = newDelegate();
                delegate.init(params, format);
            } catch (GeneralSecurityException error) {
                throw new IOException("Unable to initialize delegated EC parameters", error);
            }
        }

        private AlgorithmParameters requireDelegate() {
            if (delegate == null) {
                throw new IllegalStateException("EC parameters not initialized");
            }
            return delegate;
        }

        @Override
        protected <T extends AlgorithmParameterSpec> T engineGetParameterSpec(Class<T> paramSpec)
                throws InvalidParameterSpecException {
            return requireDelegate().getParameterSpec(paramSpec);
        }

        @Override
        protected byte[] engineGetEncoded() throws IOException {
            return requireDelegate().getEncoded();
        }

        @Override
        protected byte[] engineGetEncoded(String format) throws IOException {
            return requireDelegate().getEncoded(format);
        }

        @Override
        protected String engineToString() {
            return delegate == null ? "Luonnotar SSH EC alias parameters (uninitialized)" : delegate.toString();
        }
    }

    public static final class NistEcKeyPairGeneratorSpi extends KeyPairGeneratorSpi {
        private KeyPairGenerator delegate;

        private KeyPairGenerator newDelegate() {
            try {
                return KeyPairGenerator.getInstance("EC", BACKEND);
            } catch (GeneralSecurityException error) {
                throw new IllegalStateException("Unable to create delegated EC key generator", error);
            }
        }

        @Override
        public void initialize(int keysize, SecureRandom random) {
            delegate = newDelegate();
            if (random == null) {
                delegate.initialize(keysize);
            } else {
                delegate.initialize(keysize, random);
            }
        }

        @Override
        public void initialize(AlgorithmParameterSpec params, SecureRandom random)
                throws InvalidAlgorithmParameterException {
            delegate = newDelegate();
            AlgorithmParameterSpec mapped = canonicalize(params);
            if (random == null) {
                delegate.initialize(mapped);
            } else {
                delegate.initialize(mapped, random);
            }
        }

        @Override
        public KeyPair generateKeyPair() {
            if (delegate == null) {
                delegate = newDelegate();
            }
            return delegate.generateKeyPair();
        }
    }
}

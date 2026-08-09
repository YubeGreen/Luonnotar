package com.yubegreen.luonnotar.privileged.embedded;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.IOException;
import java.security.AlgorithmParameters;
import java.security.AlgorithmParametersSpi;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidParameterSpecException;
import java.util.Locale;

/**
 * Process-local AlgorithmParameters.EC shim for Apache MINA's SSH curve names.
 *
 * MINA 2.19 asks JCA for EC parameters using SSH names such as "nistp384".
 * Android's platform provider on the target shell runtime does not resolve
 * those aliases, and BC itself intentionally accepts the standard SEC names
 * instead. This provider translates only those three SSH aliases and delegates
 * all EC parameter work to Luonnotar's bundled Bouncy Castle instance.
 */
public final class ShellSshNistEcAlgorithmParametersProvider extends Provider {
    static final String NAME = "LuonnotarSSH-EC";
    private static final double VERSION = 1.0d;

    ShellSshNistEcAlgorithmParametersProvider() {
        super(NAME, VERSION, "Luonnotar SSH NIST EC AlgorithmParameters alias shim");
        put("AlgorithmParameters.EC", NistEcAlgorithmParametersSpi.class.getName());
    }

    public static final class NistEcAlgorithmParametersSpi extends AlgorithmParametersSpi {
        private static final Provider BACKEND = new BouncyCastleProvider();
        private AlgorithmParameters delegate;

        private AlgorithmParameters newDelegate() throws GeneralSecurityException {
            return AlgorithmParameters.getInstance("EC", BACKEND);
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

        @Override
        protected void engineInit(AlgorithmParameterSpec paramSpec) throws InvalidParameterSpecException {
            AlgorithmParameterSpec mapped = paramSpec;
            if (paramSpec instanceof ECGenParameterSpec) {
                ECGenParameterSpec ec = (ECGenParameterSpec) paramSpec;
                mapped = new ECGenParameterSpec(canonicalCurveName(ec.getName()));
            }
            try {
                delegate = newDelegate();
                delegate.init(mapped);
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
}

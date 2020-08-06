package com.payline.payment.natixis.utils.security;

import com.payline.payment.natixis.exception.PluginException;

import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.Base64;

public class RSAUtils {
    private static final String ALGORITHM = "AES";
    private static final int KEY_SIZE = 256;
    private static final String PROVIDER = "SunJCE";


    private Cipher aesCipher;

    private RSAUtils() {
        try {
            this.aesCipher = Cipher.getInstance(ALGORITHM, PROVIDER);
        } catch (NoSuchPaddingException | NoSuchAlgorithmException | NoSuchProviderException e) {
            // does nothing, AES is a valid algorithm
        }
    }

    private static class Holder {
        private static final RSAUtils instance = new RSAUtils();
    }

    public static RSAUtils getInstance() {
        return Holder.instance;
    }

    public String generateKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance(ALGORITHM);
            generator.init(KEY_SIZE); // The AES key size in number of bits
            SecretKey secretKey = generator.generateKey();
            return Base64.getEncoder().encodeToString(secretKey.getEncoded());
        } catch (NoSuchAlgorithmException e) {
            // does nothing, AES is a valid algorithm
            return null;
        }
    }

    private SecretKey getKey(String key) {
        byte[] decodedKey = Base64.getDecoder().decode(key);
        // rebuild key using SecretKeySpec
        return new SecretKeySpec(decodedKey, ALGORITHM);
    }


    public String encrypt(String data, String key) {
        try {
            SecretKey secretKey = getKey(key);
            aesCipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] ciphered = aesCipher.doFinal(data.getBytes());

            return Base64.getEncoder().encodeToString(ciphered);
        } catch (BadPaddingException | InvalidKeyException | IllegalBlockSizeException e) {
            throw new PluginException("Unable to encrypt message", e);
        }
    }

    public String decrypt(String data, String key) {
        try {
            aesCipher.init(Cipher.DECRYPT_MODE, getKey(key));
            byte[] ciphered = Base64.getDecoder().decode(data);

            byte[] bytePlainText = aesCipher.doFinal(ciphered);
            return new String(bytePlainText);
        } catch (BadPaddingException | InvalidKeyException | IllegalBlockSizeException e) {
            throw new PluginException("Unable to encrypt message", e);
        }
    }
}

package com.payline.payment.natixis.service.impl;

import com.payline.payment.natixis.exception.InvalidDataException;
import com.payline.payment.natixis.exception.PluginException;
import com.payline.payment.natixis.utils.PluginUtils;
import com.payline.payment.natixis.utils.security.RSAUtils;
import com.payline.pmapi.bean.common.FailureCause;
import com.payline.pmapi.bean.paymentform.bean.form.BankTransferForm;
import com.payline.pmapi.bean.wallet.bean.WalletDisplay;
import com.payline.pmapi.bean.wallet.bean.field.WalletDisplayFieldText;
import com.payline.pmapi.bean.wallet.bean.field.WalletField;
import com.payline.pmapi.bean.wallet.request.WalletCreateRequest;
import com.payline.pmapi.bean.wallet.request.WalletDeleteRequest;
import com.payline.pmapi.bean.wallet.request.WalletDisplayRequest;
import com.payline.pmapi.bean.wallet.request.WalletUpdateRequest;
import com.payline.pmapi.bean.wallet.response.WalletCreateResponse;
import com.payline.pmapi.bean.wallet.response.WalletDeleteResponse;
import com.payline.pmapi.bean.wallet.response.WalletDisplayResponse;
import com.payline.pmapi.bean.wallet.response.WalletUpdateResponse;
import com.payline.pmapi.bean.wallet.response.impl.WalletCreateResponseFailure;
import com.payline.pmapi.bean.wallet.response.impl.WalletCreateResponseSuccess;
import com.payline.pmapi.bean.wallet.response.impl.WalletDeleteResponseSuccess;
import com.payline.pmapi.logger.LogManager;
import com.payline.pmapi.service.WalletService;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class WalletServiceImpl implements WalletService {
    private static final Logger LOGGER = LogManager.getLogger(WalletServiceImpl.class);
    private RSAUtils rsaUtils = RSAUtils.getInstance();

    @Override
    public WalletDeleteResponse deleteWallet(WalletDeleteRequest walletDeleteRequest) {
        return WalletDeleteResponseSuccess.builder().build();
    }

    @Override
    public WalletUpdateResponse updateWallet(WalletUpdateRequest walletUpdateRequest) {
        // this function is not used yet
        return null;
    }

    @Override
    public WalletCreateResponse createWallet(WalletCreateRequest walletCreateRequest) {
        try {
            // get wallet data
            String bic = walletCreateRequest.getPaymentFormContext().getPaymentFormParameter().get(BankTransferForm.BANK_KEY);

            // encrypt it
            String key = PluginUtils.extractKey(walletCreateRequest.getPluginConfiguration());
            if (key == null){
                throw new InvalidDataException("No key in pluginConfiguration");
            }
            String paymentData = rsaUtils.encrypt(bic, key);

            // create wallet
            return WalletCreateResponseSuccess.builder()
                    .pluginPaymentData(paymentData)
                    .build();
        } catch (PluginException e) {
            LOGGER.warn("Unable to create wallet ", e);
            return WalletCreateResponseFailure.builder()
                    .errorCode(e.getErrorCode())
                    .failureCause(e.getFailureCause())
                    .build();
        } catch (RuntimeException e){
            LOGGER.error("Unexpected plugin error", e);
            return WalletCreateResponseFailure.builder()
                    .errorCode( PluginException.runtimeErrorCode( e ) )
                    .failureCause( FailureCause.INTERNAL_ERROR )
                    .build();
        }
    }

    @Override
    public WalletDisplayResponse displayWallet(WalletDisplayRequest walletDisplayRequest) {
        List<WalletField> walletFields = new ArrayList<>();
        try {
            // decrypt the encrypted data
            String encryptedData = walletDisplayRequest.getWallet().getPluginPaymentData();

            String key = PluginUtils.extractKey(walletDisplayRequest.getPluginConfiguration());
            String data = rsaUtils.decrypt(encryptedData, key);


            //Build wallet display fields
            walletFields.add(WalletDisplayFieldText.builder().content(data).build());

        } catch (PluginException e){
            LOGGER.warn("Unable to display wallet ", e);
        }
        // create and return walletDisplayResponse
        return WalletDisplay.builder()
                .walletFields(walletFields)
                .build();
    }
}

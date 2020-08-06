package com.payline.payment.natixis.service.impl;

import com.payline.payment.natixis.MockUtils;
import com.payline.payment.natixis.exception.PluginException;
import com.payline.payment.natixis.utils.security.RSAUtils;
import com.payline.pmapi.bean.common.FailureCause;
import com.payline.pmapi.bean.payment.PaymentFormContext;
import com.payline.pmapi.bean.payment.Wallet;
import com.payline.pmapi.bean.paymentform.bean.form.BankTransferForm;
import com.payline.pmapi.bean.wallet.bean.WalletDisplay;
import com.payline.pmapi.bean.wallet.bean.field.WalletDisplayFieldText;
import com.payline.pmapi.bean.wallet.request.WalletCreateRequest;
import com.payline.pmapi.bean.wallet.request.WalletDisplayRequest;
import com.payline.pmapi.bean.wallet.response.WalletCreateResponse;
import com.payline.pmapi.bean.wallet.response.impl.WalletCreateResponseFailure;
import com.payline.pmapi.bean.wallet.response.impl.WalletCreateResponseSuccess;
import com.payline.pmapi.bean.wallet.response.impl.WalletDeleteResponseSuccess;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;

class WalletServiceImplTest {
    @InjectMocks
    WalletServiceImpl service;

    @Mock
    RSAUtils rsaUtils;

    @BeforeEach
    void setUp() {
        service = new WalletServiceImpl();
        MockitoAnnotations.initMocks(this);
    }

    @Test
    void deleteWallet() {
        Assertions.assertEquals(WalletDeleteResponseSuccess.class, service.deleteWallet(null).getClass());

    }

    @Test
    void updateWallet() {
        // not used for now
        Assertions.assertNull(service.updateWallet(null));
    }

    @Test
    void createWallet() {
        String pluginPaymentData = "thisIsWalletDataEncrypted";

        Mockito.doReturn(pluginPaymentData).when(rsaUtils).encrypt(anyString(), anyString());

        Map<String, String> paymentFormDataContext = new HashMap<>();
        paymentFormDataContext.put(BankTransferForm.BANK_KEY, "thisIsABank");

        PaymentFormContext context = PaymentFormContext.PaymentFormContextBuilder
                .aPaymentFormContext()
                .withPaymentFormParameter(paymentFormDataContext)
                .build();
        WalletCreateRequest request = WalletCreateRequest.builder()
                .paymentFormContext(context)
                .pluginConfiguration(MockUtils.aPluginConfiguration())
                .build();
        WalletCreateResponse response = service.createWallet(request);

        Assertions.assertEquals(WalletCreateResponseSuccess.class, response.getClass());
        WalletCreateResponseSuccess responseSuccess = (WalletCreateResponseSuccess) response;
        Assertions.assertEquals(pluginPaymentData, responseSuccess.getPluginPaymentData());
    }

    @Test
    void createWalletFailure() {

        Mockito.doThrow(new PluginException("foo")).when(rsaUtils).encrypt(anyString(), anyString());

        Map<String, String> paymentFormDataContext = new HashMap<>();
        paymentFormDataContext.put(BankTransferForm.BANK_KEY, "thisIsABank");

        PaymentFormContext context = PaymentFormContext.PaymentFormContextBuilder
                .aPaymentFormContext()
                .withPaymentFormParameter(paymentFormDataContext)
                .build();
        WalletCreateRequest request = WalletCreateRequest.builder()
                .paymentFormContext(context)
                .pluginConfiguration(MockUtils.aPluginConfiguration())
                .build();
        WalletCreateResponse response = service.createWallet(request);

        Assertions.assertEquals(WalletCreateResponseFailure.class, response.getClass());
        WalletCreateResponseFailure responseFailure = (WalletCreateResponseFailure) response;
        Assertions.assertEquals(FailureCause.INTERNAL_ERROR, responseFailure.getFailureCause());
        Assertions.assertEquals("foo", responseFailure.getErrorCode());
    }

    @Test
    void createWalletFailureNoPluginConfiguration() {
        Map<String, String> paymentFormDataContext = new HashMap<>();
        paymentFormDataContext.put(BankTransferForm.BANK_KEY, "thisIsABank");

        PaymentFormContext context = PaymentFormContext.PaymentFormContextBuilder
                .aPaymentFormContext()
                .withPaymentFormParameter(paymentFormDataContext)
                .build();
        WalletCreateRequest request = WalletCreateRequest.builder()
                .paymentFormContext(context)
                .pluginConfiguration("") // will create an ArrayIndexOutOfBoundsException
                .build();
        WalletCreateResponse response = service.createWallet(request);

        Assertions.assertEquals(WalletCreateResponseFailure.class, response.getClass());
        WalletCreateResponseFailure responseFailure = (WalletCreateResponseFailure) response;
        Assertions.assertEquals(FailureCause.INVALID_DATA, responseFailure.getFailureCause());
        Assertions.assertEquals("No key in pluginConfiguration", responseFailure.getErrorCode());
    }

    @Test
    void displayWallet() {
        String pluginPaymentData = "thisIsWalletData";
        Mockito.doReturn(pluginPaymentData).when(rsaUtils).decrypt(anyString(), anyString());

        Wallet wallet = Wallet.builder()
                .pluginPaymentData("foo")
                .build();

        WalletDisplayRequest request = WalletDisplayRequest.builder()
                .wallet(wallet)
                .pluginConfiguration(MockUtils.aPluginConfiguration())
                .build();

        WalletDisplay response = (WalletDisplay) service.displayWallet(request);
        Assertions.assertNotNull(response.getWalletFields());
        Assertions.assertEquals(1, response.getWalletFields().size());
        Assertions.assertEquals(WalletDisplayFieldText.class, response.getWalletFields().get(0).getClass());
        WalletDisplayFieldText field = (WalletDisplayFieldText) response.getWalletFields().get(0);
        Assertions.assertEquals(pluginPaymentData, field.getContent());
    }

    @Test
    void displayWalletFailure() {
        Mockito.doThrow(new PluginException("foo")).when(rsaUtils).decrypt(anyString(), anyString());

        Wallet wallet = Wallet.builder()
                .pluginPaymentData("foo")
                .build();

        WalletDisplayRequest request = WalletDisplayRequest.builder()
                .wallet(wallet)
                .pluginConfiguration(MockUtils.aPluginConfiguration())
                .build();

        WalletDisplay response = (WalletDisplay) service.displayWallet(request);
        Assertions.assertNotNull(response.getWalletFields());
        Assertions.assertEquals(0, response.getWalletFields().size());
    }
}
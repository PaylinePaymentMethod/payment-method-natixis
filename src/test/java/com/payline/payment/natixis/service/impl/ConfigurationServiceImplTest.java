package com.payline.payment.natixis.service.impl;

import com.payline.payment.natixis.MockUtils;
import com.payline.payment.natixis.bean.business.NatixisBanksResponse;
import com.payline.payment.natixis.bean.configuration.RequestConfiguration;
import com.payline.payment.natixis.exception.PluginException;
import com.payline.payment.natixis.utils.Constants;
import com.payline.payment.natixis.utils.PluginUtils;
import com.payline.payment.natixis.utils.http.NatixisHttpClient;
import com.payline.payment.natixis.utils.properties.ReleaseProperties;
import com.payline.payment.natixis.utils.security.RSAUtils;
import com.payline.pmapi.bean.configuration.PartnerConfiguration;
import com.payline.pmapi.bean.configuration.ReleaseInformation;
import com.payline.pmapi.bean.configuration.parameter.AbstractParameter;
import com.payline.pmapi.bean.configuration.parameter.impl.ListBoxParameter;
import com.payline.pmapi.bean.configuration.request.ContractParametersCheckRequest;
import com.payline.pmapi.bean.configuration.request.RetrievePluginConfigurationRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.text.SimpleDateFormat;
import java.time.Month;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConfigurationServiceImplTest {

    /* I18nService is not mocked here, on purpose, to validate the existence of all
    the messages related to this class, at least in the default locale */
    @Mock
    private NatixisHttpClient natixisHttpClient;
    @Mock
    private ReleaseProperties releaseProperties;

    @Mock
    private RSAUtils rsaUtils;

    @InjectMocks
    private ConfigurationServiceImpl service;

    @BeforeAll
    static void before() {
        // This allows to test the default messages.properties file (no locale suffix)
        Locale.setDefault(Locale.CHINESE);
    }

    @BeforeEach
    void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @AfterAll
    static void after() {
        // Back to standard default locale
        Locale.setDefault(Locale.ENGLISH);
    }

    @Test
    void check_nominal() {
        // given: a valid configuration, including client ID / secret
        ContractParametersCheckRequest checkRequest = MockUtils.aContractParametersCheckRequest();
        doReturn(MockUtils.anAuthorization()).when(natixisHttpClient).authorize(any(RequestConfiguration.class));

        // when: checking the configuration
        Map<String, String> errors = service.check(checkRequest);

        // then: error map is empty
        assertTrue(errors.isEmpty());
    }

    @Test
    void check_emptyAccountInfo() {
        // given: an empty accountInfo
        ContractParametersCheckRequest checkRequest = MockUtils.aContractParametersCheckRequestBuilder()
                .withAccountInfo(new HashMap<>())
                .build();

        // when: checking the configuration
        Map<String, String> errors = service.check(checkRequest);

        // then: there is an error for each parameter, each error has a valid message and authorize method is never called
        assertEquals(service.getParameters(Locale.getDefault()).size(), errors.size());
        for (Map.Entry<String, String> error : errors.entrySet()) {
            assertNotNull(error.getValue());
            assertFalse(error.getValue().contains("???"));
        }
        verify(natixisHttpClient, never()).authorize(any(RequestConfiguration.class));
    }

    @Test
    void check_wrongAuthorization() {
        // given: the client ID or secret is wrong. The partner authorization API returns an error.
        ContractParametersCheckRequest checkRequest = MockUtils.aContractParametersCheckRequest();
        doThrow(PluginException.class).when(natixisHttpClient).authorize(any(RequestConfiguration.class));

        // when: checking the configuration
        Map<String, String> errors = service.check(checkRequest);

        // then: no exception is thrown, but there are some errors
        assertTrue(errors.size() > 0);
    }

    @Test
    void getName() {
        // when: calling the method getName
        String name = service.getName(Locale.getDefault());

        // then: the method returns the name
        assertNotNull(name);
    }

    @ParameterizedTest
    @MethodSource("getLocales")
    void getParameters() {
        // when: retrieving the contract parameters
        List<AbstractParameter> parameters = service.getParameters(Locale.getDefault());

        // then: each parameter has a unique key, a label and a description. List box parameters have at least 1 possible value.
        List<String> keys = new ArrayList<>();
        for (AbstractParameter param : parameters) {
            // 2 different parameters should not have the same key
            assertFalse(keys.contains(param.getKey()));
            keys.add(param.getKey());

            // each parameter should have a label and a description
            assertNotNull(param.getLabel());
            assertFalse(param.getLabel().contains("???"));
            assertNotNull(param.getDescription());
            assertFalse(param.getDescription().contains("???"));

            // in case of a ListBoxParameter, it should have at least 1 value
            if (param instanceof ListBoxParameter) {
                assertFalse(((ListBoxParameter) param).getList().isEmpty());
            }
        }
    }

    /**
     * Set of locales to test the getParameters() method. ZZ allows to search in the default messages.properties file.
     */
    static Stream<Locale> getLocales() {
        return Stream.of(Locale.FRENCH, Locale.ENGLISH, new Locale("ZZ"));
    }

    @Test
    void getReleaseInformation() {
        SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy");
        String version = "M.m.p";

        // given: the release properties are OK
        doReturn(version).when(releaseProperties).get("release.version");
        Calendar cal = new GregorianCalendar();
        cal.set(2019, Calendar.AUGUST, 19);
        doReturn(formatter.format(cal.getTime())).when(releaseProperties).get("release.date");

        // when: calling the method getReleaseInformation
        ReleaseInformation releaseInformation = service.getReleaseInformation();

        // then: releaseInformation contains the right values
        assertEquals(version, releaseInformation.getVersion());
        assertEquals(2019, releaseInformation.getDate().getYear());
        assertEquals(Month.AUGUST, releaseInformation.getDate().getMonth());
        assertEquals(19, releaseInformation.getDate().getDayOfMonth());
    }

    @Test
    void retrievePluginConfiguration_empty() {
        // given: the HTTP client returns a proper response
        String banks = PluginUtils.extractBanks( MockUtils.aPluginConfiguration());
        doReturn(NatixisBanksResponse.fromJson(banks)).when(natixisHttpClient).banks(any(RequestConfiguration.class));

        // given: rsaUtils generate a proper key
        doReturn("foo").when(rsaUtils).generateKey();

        RetrievePluginConfigurationRequest request = MockUtils.aRetrievePluginConfigurationRequestBuilder()
                .withPluginConfiguration("")
                .build();

        // when: calling the method retrievePluginConfiguration
        String result = service.retrievePluginConfiguration(request);

        // then: the returned value contains the banks as a JSON string
        String expected = banks + "&&&foo";
        assertEquals(expected, result);
    }

    @Test
    void retrievePluginConfiguration_noKey() {
        // given: the HTTP client returns a proper response
        String banks = PluginUtils.extractBanks( MockUtils.aPluginConfiguration());
        doReturn(NatixisBanksResponse.fromJson(banks)).when(natixisHttpClient).banks(any(RequestConfiguration.class));

        // given: rsaUtils generate a proper key
        doReturn("foo").when(rsaUtils).generateKey();

        RetrievePluginConfigurationRequest request = MockUtils.aRetrievePluginConfigurationRequestBuilder()
                .withPluginConfiguration("initial configuration&&&")
                .build();

        // when: calling the method retrievePluginConfiguration
        String result = service.retrievePluginConfiguration(request);

        // then: the returned value contains the banks as a JSON string
        String expected = banks + "&&&foo";
        assertEquals(expected, result);
    }

    @Test
    void retrievePluginConfiguration_newBanks() {
        // given: the HTTP client returns a proper response
        String banks = PluginUtils.extractBanks( MockUtils.aPluginConfiguration());
        doReturn(NatixisBanksResponse.fromJson(banks)).when(natixisHttpClient).banks(any(RequestConfiguration.class));

        RetrievePluginConfigurationRequest request = MockUtils.aRetrievePluginConfigurationRequestBuilder()
                .withPluginConfiguration("initial configuration&&&key")
                .build();

        // when: calling the method retrievePluginConfiguration
        String result = service.retrievePluginConfiguration(request);

        // then: the returned value contains the banks as a JSON string
        String expected = banks + "&&&key";
        assertEquals(expected, result);
    }

    @Test
    void retrievePluginConfiguration_exception() {
        // given: the HTTP client throws an exception (partner API could not be reached, for example)
        doThrow(PluginException.class).when(natixisHttpClient).banks(any(RequestConfiguration.class));

        String initialConfiguration = "initial configuration&&&key";
        RetrievePluginConfigurationRequest request = MockUtils.aRetrievePluginConfigurationRequestBuilder()
                .withPluginConfiguration(initialConfiguration)
                .build();

        // when: calling the method retrievePluginConfiguration
        String result = service.retrievePluginConfiguration(request);

        // then: the returned value contains the initial plugin configuration
        assertEquals(initialConfiguration, result);
    }


    @Test
    void retrievePluginConfiguration_missingPaylineClientId(){
        // given: the PartnerConfiguration is missing the paylineClientName
        Map<String, String> partnerConfigurationMap = new HashMap<>();
        partnerConfigurationMap.put(Constants.PartnerConfigurationKeys.API_AUTH_BASE_URL, "https://np-auth.api.qua.natixis.com/api");
        partnerConfigurationMap.put(Constants.PartnerConfigurationKeys.API_PAYMENT_BASE_URL, "https://np.api.qua.natixis.com/hub-pisp/v1");
        partnerConfigurationMap.put(Constants.PartnerConfigurationKeys.SIGNATURE_KEYID, "signature-key-id");
        partnerConfigurationMap.put(Constants.PartnerConfigurationKeys.CLIENT_SECRET, "XXXXXXX");

        Map<String, String> sensitiveConfigurationMap = new HashMap<>();
        sensitiveConfigurationMap.put( Constants.PartnerConfigurationKeys.CLIENT_CERTIFICATE, MockUtils.aClientCertificatePem() );
        sensitiveConfigurationMap.put( Constants.PartnerConfigurationKeys.CLIENT_PRIVATE_KEY, MockUtils.aPrivateKeyPem() );
        PartnerConfiguration partnerConfiguration = new PartnerConfiguration( partnerConfigurationMap, sensitiveConfigurationMap );

        String initialConfiguration = "initial configuration";
        RetrievePluginConfigurationRequest request = MockUtils.aRetrievePluginConfigurationRequestBuilder()
                .withPluginConfiguration(initialConfiguration)
                .withPartnerConfiguration( partnerConfiguration )
                .build();

        // when: calling the method retrievePluginConfiguration
        String result = service.retrievePluginConfiguration( request );

        // then: the HTTP client is never called, and the result equals the initial configuration
        verify( natixisHttpClient, never() ).init( any(PartnerConfiguration.class) );
        verify( natixisHttpClient, never() ).banks( any(RequestConfiguration.class) );
        assertEquals( initialConfiguration, result );
    }

    @Test
    void retrievePluginConfiguration_missingClientSecret(){
        // given: the PartnerConfiguration is missing the paylineOnboardingId
        Map<String, String> partnerConfigurationMap = new HashMap<>();
        partnerConfigurationMap.put(Constants.PartnerConfigurationKeys.API_AUTH_BASE_URL, "https://np-auth.api.qua.natixis.com/api");
        partnerConfigurationMap.put(Constants.PartnerConfigurationKeys.API_PAYMENT_BASE_URL, "https://np.api.qua.natixis.com/hub-pisp/v1");
        partnerConfigurationMap.put(Constants.PartnerConfigurationKeys.SIGNATURE_KEYID, "signature-key-id");

        Map<String, String> sensitiveConfigurationMap = new HashMap<>();
        sensitiveConfigurationMap.put( Constants.PartnerConfigurationKeys.CLIENT_CERTIFICATE, MockUtils.aClientCertificatePem() );
        sensitiveConfigurationMap.put( Constants.PartnerConfigurationKeys.CLIENT_PRIVATE_KEY, MockUtils.aPrivateKeyPem() );
        PartnerConfiguration partnerConfiguration = new PartnerConfiguration( partnerConfigurationMap, sensitiveConfigurationMap );

        String initialConfiguration = "initial configuration";
        RetrievePluginConfigurationRequest request = MockUtils.aRetrievePluginConfigurationRequestBuilder()
                .withPluginConfiguration(initialConfiguration)
                .withPartnerConfiguration( partnerConfiguration )
                .build();

        // when: calling the method retrievePluginConfiguration
        String result = service.retrievePluginConfiguration( request );

        // then: the HTTP client is never called, and the result equals the initial configuration
        verify( natixisHttpClient, never() ).init( any(PartnerConfiguration.class) );
        verify( natixisHttpClient, never() ).banks( any(RequestConfiguration.class) );
        assertEquals( initialConfiguration, result );
    }

}

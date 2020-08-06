package com.payline.payment.natixis.service.impl;

import com.payline.payment.natixis.bean.business.NatixisBanksResponse;
import com.payline.payment.natixis.bean.configuration.RequestConfiguration;
import com.payline.payment.natixis.exception.PluginException;
import com.payline.payment.natixis.utils.Constants;
import com.payline.payment.natixis.utils.PluginUtils;
import com.payline.payment.natixis.utils.http.NatixisHttpClient;
import com.payline.payment.natixis.utils.i18n.I18nService;
import com.payline.payment.natixis.utils.properties.ReleaseProperties;
import com.payline.payment.natixis.utils.security.RSAUtils;
import com.payline.pmapi.bean.configuration.PartnerConfiguration;
import com.payline.pmapi.bean.configuration.ReleaseInformation;
import com.payline.pmapi.bean.configuration.parameter.AbstractParameter;
import com.payline.pmapi.bean.configuration.parameter.impl.InputParameter;
import com.payline.pmapi.bean.configuration.parameter.impl.ListBoxParameter;
import com.payline.pmapi.bean.configuration.parameter.impl.PasswordParameter;
import com.payline.pmapi.bean.configuration.request.ContractParametersCheckRequest;
import com.payline.pmapi.bean.configuration.request.RetrievePluginConfigurationRequest;
import com.payline.pmapi.bean.payment.ContractConfiguration;
import com.payline.pmapi.bean.payment.ContractProperty;
import com.payline.pmapi.logger.LogManager;
import com.payline.pmapi.service.ConfigurationService;
import org.apache.logging.log4j.Logger;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ConfigurationServiceImpl implements ConfigurationService {

    private static final Logger LOGGER = LogManager.getLogger(ConfigurationServiceImpl.class);

    private static final class CategoryPurpose {
        private static final String CASH = "CASH";
        private static final String CORT = "CORT";
        private static final String DVPM = "DVPM";
        private static final String INTC = "INTC";
        private static final String TREA = "TREA";
    }

    private static final class ChargeBearer {
        private static final String CRED = "CRED";
        private static final String DEBT = "DEBT";
        private static final String SHAR = "SHAR";
        private static final String SLEV = "SLEV";
    }

    private static final class ServiceLevel {
        private static final String SEPA = "SEPA";
        private static final String NURG = "NURG";
    }

    private static final class Purpose {
        private static final String ACCT = "ACCT";
        private static final String CASH = "CASH";
        private static final String COMC = "COMC";
        private static final String CPKC = "CPKC";
        private static final String TRPT = "TRPT";
    }

    private ReleaseProperties releaseProperties = ReleaseProperties.getInstance();
    private I18nService i18n = I18nService.getInstance();
    private NatixisHttpClient natixisHttpClient = NatixisHttpClient.getInstance();
    private RSAUtils rsaUtils = RSAUtils.getInstance();

    @Override
    public List<AbstractParameter> getParameters(Locale locale) {
        List<AbstractParameter> parameters = new ArrayList<>();

        // creditorName
        InputParameter creditorName = new InputParameter();
        creditorName.setKey(Constants.ContractConfigurationKeys.CREDITOR_NAME);
        creditorName.setLabel(i18n.getMessage("contract.creditorName.label", locale));
        creditorName.setDescription(i18n.getMessage("contract.creditorName.description", locale));
        creditorName.setRequired(true);
        parameters.add(creditorName);

        // creditorBic
        InputParameter creditorBic = new InputParameter();
        creditorBic.setKey(Constants.ContractConfigurationKeys.CREDITOR_BIC);
        creditorBic.setLabel(i18n.getMessage("contract.creditorBic.label", locale));
        creditorBic.setDescription(i18n.getMessage("contract.creditorBic.description", locale));
        creditorBic.setRequired(true);
        parameters.add(creditorBic);

        // creditorIban
        InputParameter creditorIban = new InputParameter();
        creditorIban.setKey(Constants.ContractConfigurationKeys.CREDITOR_IBAN);
        creditorIban.setLabel(i18n.getMessage("contract.creditorIban.label", locale));
        creditorIban.setDescription(i18n.getMessage("contract.creditorIban.description", locale));
        creditorIban.setRequired(true);
        parameters.add(creditorIban);

        // clientId
        InputParameter clientId = new InputParameter();
        clientId.setKey(Constants.ContractConfigurationKeys.CLIENT_ID);
        clientId.setLabel(i18n.getMessage("contract.clientId.label", locale));
        clientId.setDescription(i18n.getMessage("contract.clientId.description", locale));
        clientId.setRequired(true);
        parameters.add(clientId);

        // clientSecret
        PasswordParameter clientSecret = new PasswordParameter();
        clientSecret.setKey(Constants.ContractConfigurationKeys.CLIENT_SECRET);
        clientSecret.setLabel(i18n.getMessage("contract.clientSecret.label", locale));
        clientSecret.setDescription(i18n.getMessage("contract.clientSecret.description", locale));
        clientSecret.setRequired(true);
        parameters.add(clientSecret);

        // serviceLevel
        ListBoxParameter serviceLevel = new ListBoxParameter();
        serviceLevel.setKey(Constants.ContractConfigurationKeys.SERVICE_LEVEL);
        serviceLevel.setLabel(i18n.getMessage("contract.serviceLevel.label", locale));
        serviceLevel.setDescription(i18n.getMessage("contract.serviceLevel.description", locale));
        Map<String, String> serviceLevelValues = new HashMap<>();
        serviceLevelValues.put(ServiceLevel.SEPA, ServiceLevel.SEPA);
        serviceLevelValues.put(ServiceLevel.NURG, ServiceLevel.NURG);
        serviceLevel.setList(serviceLevelValues);
        serviceLevel.setRequired(true);
        serviceLevel.setValue(ServiceLevel.SEPA);
        parameters.add(serviceLevel);

        // localInstrument
        ListBoxParameter localInstrument = new ListBoxParameter();
        localInstrument.setKey(Constants.ContractConfigurationKeys.LOCAL_INSTRUMENT);
        localInstrument.setLabel(i18n.getMessage("contract.localInstrument.label", locale));
        localInstrument.setDescription(i18n.getMessage("contract.localInstrument.description", locale));
        Map<String, String> localInstrumentValues = new HashMap<>();
        localInstrumentValues.put("INST", "INST");
        localInstrument.setList(localInstrumentValues);
        localInstrument.setRequired(true);
        localInstrument.setValue("INST");
        parameters.add(localInstrument);

        // categoryPurpose
        ListBoxParameter categoryPurpose = new ListBoxParameter();
        categoryPurpose.setKey(Constants.ContractConfigurationKeys.CATEGORY_PURPOSE);
        categoryPurpose.setLabel(i18n.getMessage("contract.categoryPurpose.label", locale));
        categoryPurpose.setDescription(i18n.getMessage("contract.categoryPurpose.description", locale));
        Map<String, String> categoryPurposeValues = new HashMap<>();
        categoryPurposeValues.put(CategoryPurpose.CASH, CategoryPurpose.CASH);
        categoryPurposeValues.put(CategoryPurpose.CORT, CategoryPurpose.CORT);
        categoryPurposeValues.put(CategoryPurpose.DVPM, CategoryPurpose.DVPM);
        categoryPurposeValues.put(CategoryPurpose.INTC, CategoryPurpose.INTC);
        categoryPurposeValues.put(CategoryPurpose.TREA, CategoryPurpose.TREA);
        categoryPurpose.setList(categoryPurposeValues);
        categoryPurpose.setRequired(true);
        categoryPurpose.setValue(CategoryPurpose.DVPM);
        parameters.add(categoryPurpose);

        // purpose
        ListBoxParameter purpose = new ListBoxParameter();
        purpose.setKey(Constants.ContractConfigurationKeys.PURPOSE);
        purpose.setLabel(i18n.getMessage("contract.purpose.label", locale));
        purpose.setDescription(i18n.getMessage("contract.purpose.description", locale));
        Map<String, String> purposeValues = new HashMap<>();
        purposeValues.put(Purpose.ACCT, Purpose.ACCT);
        purposeValues.put(Purpose.CASH, Purpose.CASH);
        purposeValues.put(Purpose.COMC, Purpose.COMC);
        purposeValues.put(Purpose.CPKC, Purpose.CPKC);
        purposeValues.put(Purpose.TRPT, Purpose.TRPT);
        purpose.setList(purposeValues);
        purpose.setRequired(true);
        purpose.setValue(Purpose.COMC);
        parameters.add(purpose);

        // chargeBearer
        ListBoxParameter chargeBearer = new ListBoxParameter();
        chargeBearer.setKey(Constants.ContractConfigurationKeys.CHARGE_BEARER);
        chargeBearer.setLabel(i18n.getMessage("contract.chargeBearer.label", locale));
        chargeBearer.setDescription(i18n.getMessage("contract.chargeBearer.description", locale));
        Map<String, String> chargeBearerValues = new HashMap<>();
        chargeBearerValues.put(ChargeBearer.CRED, ChargeBearer.CRED);
        chargeBearerValues.put(ChargeBearer.DEBT, ChargeBearer.DEBT);
        chargeBearerValues.put(ChargeBearer.SHAR, ChargeBearer.SHAR);
        chargeBearerValues.put(ChargeBearer.SLEV, ChargeBearer.SLEV);
        chargeBearer.setList(chargeBearerValues);
        chargeBearer.setRequired(true);
        chargeBearer.setValue(ChargeBearer.SLEV);
        parameters.add(chargeBearer);

        return parameters;
    }

    @Override
    public Map<String, String> check(ContractParametersCheckRequest contractParametersCheckRequest) {
        final Map<String, String> errors = new HashMap<>();

        Map<String, String> accountInfo = contractParametersCheckRequest.getAccountInfo();
        Locale locale = contractParametersCheckRequest.getLocale();

        // check required fields
        for (AbstractParameter param : this.getParameters(locale)) {
            if (param.isRequired() && accountInfo.get(param.getKey()) == null) {
                String message = i18n.getMessage("contract." + param.getKey() + ".requiredError", locale);
                errors.put(param.getKey(), message);
            }
        }

        // If client ID or client secret is missing, no need to go further, as they are both required
        String clientIdKey = Constants.ContractConfigurationKeys.CLIENT_ID;
        String clientSecretKey = Constants.ContractConfigurationKeys.CLIENT_SECRET;
        if (errors.containsKey(clientIdKey)
                || errors.containsKey(clientSecretKey)) {
            return errors;
        }

        // Check validity of client ID and secret by retrieving an access token
        // to do so, we first need to replace the value of client ID and secret in existing ContractConfiguration
        RequestConfiguration requestConfiguration = RequestConfiguration.build(contractParametersCheckRequest);
        Map<String, ContractProperty> contractProperties = requestConfiguration.getContractConfiguration().getContractProperties();
        contractProperties.put(clientIdKey, new ContractProperty(accountInfo.get(clientIdKey)));
        contractProperties.put(clientSecretKey, new ContractProperty(accountInfo.get(clientSecretKey)));

        // Init HTTP client
        natixisHttpClient.init(requestConfiguration.getPartnerConfiguration());
        try {
            // Try to retrieve an access token
            natixisHttpClient.authorize(requestConfiguration);
        } catch (PluginException e) {
            // If an exception is thrown, it means that the client ID or secret is wrong
            errors.put(clientIdKey, e.getErrorCode());
            errors.put(clientSecretKey, e.getErrorCode());
        }

        return errors;
    }

    @Override
    public String retrievePluginConfiguration(RetrievePluginConfigurationRequest retrievePluginConfigurationRequest) {
        try {
            /*
            /*
            PAYLAPMEXT-218: This method is frequently called by a batch.
            In the context of this call, there is no current transaction, so no merchant contract identified.
            So the ContractConfiguration contained in the request might be null. Or its content might be irrelevant.
            Still, the signature algorithm will need a clientId and a clientSecret (@see NatixisHttpClient#authorize).
            We create here a fake ContractConfiguration, containing specific clientId and a clientSecret from the
            PartnerConfiguration. This way, there is no need to duplicate ay of the code in the HTTP client.
             */
            /*
            PAYLAPMEXT-220: add a key in pluginConfiguration to encrypt and decrypt wallet.
            pluginConfigurationIs now formated as: { banks }&&&privateKey
             */
            PartnerConfiguration partnerConfiguration = retrievePluginConfigurationRequest.getPartnerConfiguration();
            if (partnerConfiguration.getProperty(Constants.PartnerConfigurationKeys.CLIENT_ID) == null) {
                throw new PluginException("Missing Payline clientName from partner configuration");
            }
            if (partnerConfiguration.getProperty(Constants.PartnerConfigurationKeys.CLIENT_SECRET) == null) {
                throw new PluginException("Missing Payline onboardingId from partner configuration");
            }
            Map<String, ContractProperty> contractProperties = new HashMap<>();
            contractProperties.put(Constants.ContractConfigurationKeys.CLIENT_ID,
                    new ContractProperty(partnerConfiguration.getProperty(Constants.PartnerConfigurationKeys.CLIENT_ID)));
            contractProperties.put(Constants.ContractConfigurationKeys.CLIENT_SECRET,
                    new ContractProperty(partnerConfiguration.getProperty(Constants.PartnerConfigurationKeys.CLIENT_SECRET)));
            ContractConfiguration contractConfiguration = new ContractConfiguration("fake contract", contractProperties);

            RequestConfiguration requestConfiguration = new RequestConfiguration(
                    contractConfiguration,
                    retrievePluginConfigurationRequest.getEnvironment(),
                    retrievePluginConfigurationRequest.getPartnerConfiguration());

            // Init HTTP client
            natixisHttpClient.init(requestConfiguration.getPartnerConfiguration());

            // Retrieve account service providers list
            NatixisBanksResponse banks = natixisHttpClient.banks(requestConfiguration);


            // if not present, generate a new key
            String key = PluginUtils.extractKey(retrievePluginConfigurationRequest.getPluginConfiguration());
            if (PluginUtils.isEmpty(key)){
                key = rsaUtils.generateKey();
            }

            return banks.toString() + PluginUtils.SEPARATOR + key;
        } catch (RuntimeException e) {
            LOGGER.error("Could not retrieve plugin configuration due to a plugin error", e);
            return retrievePluginConfigurationRequest.getPluginConfiguration();
        }
    }

    @Override
    public ReleaseInformation getReleaseInformation() {
        return ReleaseInformation.ReleaseBuilder.aRelease()
                .withDate(LocalDate.parse(releaseProperties.get("release.date"), DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                .withVersion(releaseProperties.get("release.version"))
                .build();
    }

    @Override
    public String getName(Locale locale) {
        return i18n.getMessage("paymentMethod.name", locale);
    }
}

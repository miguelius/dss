package eu.europa.esig.dss.validation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.jaxb.XmlDiagnosticData;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.DSSException;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.policy.EtsiValidationPolicy;
import eu.europa.esig.dss.policy.ValidationPolicy;
import eu.europa.esig.dss.policy.ValidationPolicyFacade;
import eu.europa.esig.dss.policy.jaxb.ConstraintsParameters;
import eu.europa.esig.dss.spi.DSSUtils;
import eu.europa.esig.dss.spi.x509.CertificatePool;
import eu.europa.esig.dss.utils.Utils;
import eu.europa.esig.dss.validation.executor.DocumentProcessExecutor;
import eu.europa.esig.dss.validation.executor.signature.ValidationLevel;
import eu.europa.esig.dss.validation.reports.Reports;
import eu.europa.esig.dss.validation.timestamp.TimestampToken;

public abstract class AbstractDocumentValidator implements DocumentValidator {

	private static final Logger LOG = LoggerFactory.getLogger(AbstractDocumentValidator.class);

	/**
	 * The document to be validated (with the signature(s))
	 */
	protected DSSDocument document;
	
	/**
	 * The set validation time
	 */
	private Date validationTime = new Date();

	/**
	 * The reference to the certificate verifier. The current DSS implementation
	 * proposes {@link eu.europa.esig.dss.validation.CommonCertificateVerifier}.
	 * This verifier encapsulates the references to different sources used in
	 * the signature validation process.
	 */
	protected CertificateVerifier certificateVerifier;

	/**
	 * This variable can hold a specific {@code DocumentProcessExecutor}
	 */
	protected DocumentProcessExecutor processExecutor = null;

	/**
	 * This is the pool of certificates used in the validation process. The
	 * pools present in the certificate verifier are merged and added to this
	 * pool.
	 */
	protected CertificatePool validationCertPool = null;

	// Default configuration with the highest level
	private ValidationLevel validationLevel = ValidationLevel.ARCHIVAL_DATA;
	
	// Produces the ETSI Validation Report by default
	private boolean enableEtsiValidationReport = true;

	/**
	 * To carry out the validation process of the signature(s) some external
	 * sources of certificates and of revocation data can be needed. The
	 * certificate verifier is used to pass these values. Note that once this
	 * setter is called any change in the content of the
	 * <code>CommonTrustedCertificateSource</code> or in adjunct certificate
	 * source is not taken into account.
	 *
	 * @param certificateVerifier
	 */
	@Override
	public void setCertificateVerifier(final CertificateVerifier certificateVerifier) {
		this.certificateVerifier = certificateVerifier;
		if (validationCertPool == null) {
			validationCertPool = certificateVerifier.createValidationPool();
		}
	}

	@Override
	public void setProcessExecutor(final DocumentProcessExecutor processExecutor) {
		this.processExecutor = processExecutor;
	}

	/**
	 * This method returns the process executor. If the instance of this class
	 * is not yet instantiated then the new instance is created.
	 *
	 * @return {@code SignatureProcessExecutor}
	 */
	protected DocumentProcessExecutor provideProcessExecutorInstance() {
		if (processExecutor == null) {
			processExecutor = getDefaultProcessExecutor();
		}
		return processExecutor;
	}
	
	/**
	 * Allows to define a custom validation time
	 * @param validationTime {@link Date}
	 */
	@Override
	public void setValidationTime(Date validationTime) {
		this.validationTime = validationTime;
	}

	@Override
	public void setValidationLevel(ValidationLevel validationLevel) {
		this.validationLevel = validationLevel;
	}
	
	@Override
	public void setEnableEtsiValidationReport(boolean enableEtsiValidationReport) {
		this.enableEtsiValidationReport = enableEtsiValidationReport;
	}

	@Override
	public Reports validateDocument() {
		return validateDocument((InputStream) null);
	}

	@Override
	public Reports validateDocument(final URL validationPolicyURL) {
		if (validationPolicyURL == null) {
			return validateDocument((InputStream) null);
		}
		try {
			return validateDocument(validationPolicyURL.openStream());
		} catch (IOException e) {
			throw new DSSException(e);
		}
	}

	@Override
	public Reports validateDocument(final String policyResourcePath) {
		if (policyResourcePath == null) {
			return validateDocument((InputStream) null);
		}
		return validateDocument(getClass().getResourceAsStream(policyResourcePath));
	}

	@Override
	public Reports validateDocument(final File policyFile) {
		if ((policyFile == null) || !policyFile.exists()) {
			return validateDocument((InputStream) null);
		}
		final InputStream inputStream = DSSUtils.toByteArrayInputStream(policyFile);
		return validateDocument(inputStream);
	}

	/**
	 * Validates the document and all its signatures. The policyDataStream
	 * contains the constraint file. If null or empty the default file is used.
	 *
	 * @param policyDataStream
	 *            the {@code InputStream} with the validation policy
	 * @return the validation reports
	 */
	@Override
	public Reports validateDocument(final InputStream policyDataStream) {
		ValidationPolicy validationPolicy = null;
		try {
			validationPolicy = ValidationPolicyFacade.newFacade().getValidationPolicy(policyDataStream);
		} catch (Exception e) {
			throw new DSSException("Unable to load the policy", e);
		}
		return validateDocument(validationPolicy);
	}

	/**
	 * Validates the document and all its signatures. The
	 * {@code validationPolicyDom} contains the constraint file. If null or
	 * empty the default file is used.
	 *
	 * @param validationPolicyJaxb
	 *            the {@code ConstraintsParameters} to use in the validation process
	 * @return the validation reports
	 */
	@Override
	public Reports validateDocument(final ConstraintsParameters validationPolicyJaxb) {
		final ValidationPolicy validationPolicy = new EtsiValidationPolicy(validationPolicyJaxb);
		return validateDocument(validationPolicy);
	}

	/**
	 * Validates the document and all its signatures. The
	 * {@code validationPolicyDom} contains the constraint file. If null or
	 * empty the default file is used.
	 *
	 * @param validationPolicy
	 *            the {@code ValidationPolicy} to use in the validation process
	 * @return the validation reports
	 */
	@Override
	public Reports validateDocument(final ValidationPolicy validationPolicy) {
		LOG.info("Document validation...");
		assertConfigurationValid();

		final ValidationContext validationContext = new SignatureValidationContext(validationCertPool);
		validationContext.setCurrentTime(validationTime);
		
		final XmlDiagnosticData diagnosticData = prepareDiagnosticDataBuilder(validationContext, validationPolicy).build();

		return processValidationPolicy(diagnosticData, validationPolicy);
	}
	
	/**
	 * Checks if the Validator configuration is valid
	 */
	protected void assertConfigurationValid() {
		Objects.requireNonNull(certificateVerifier, "CertificateVerifier is not defined");
		Objects.requireNonNull(document, "Document is not provided to the validator");
	}
	
	/**
	 * Creates a DiagnosticData to pass to the validation process
	 * 
	 * @param validationContext {@link ValidationContext}
	 * @param validationPolicy {@link ValidationPolicy} to use
	 * @return {@link DiagnosticData}
	 */
	protected DiagnosticDataBuilder prepareDiagnosticDataBuilder(final ValidationContext validationContext, final ValidationPolicy validationPolicy) {
		List<AdvancedSignature> allSignatures = prepareSignatureValidationContext(validationContext, validationPolicy);
		List<TimestampToken> timestampTokens = Collections.emptyList();
		if (Utils.isCollectionEmpty(allSignatures)) {
			// in case if no signatures found, process the timestamp only validation
			timestampTokens = prepareTimestampValidationContext(validationContext, validationPolicy);
		}
		
		return new DiagnosticDataBuilder().document(document).foundSignatures(allSignatures).setExternalTimestamps(timestampTokens)
				.usedCertificates(validationContext.getProcessedCertificates()).usedRevocations(validationContext.getProcessedRevocations())
				.setDefaultDigestAlgorithm(certificateVerifier.getDefaultDigestAlgorithm())
				.includeRawCertificateTokens(certificateVerifier.isIncludeCertificateTokenValues())
				.includeRawRevocationData(certificateVerifier.isIncludeCertificateRevocationValues())
				.includeRawTimestampTokens(certificateVerifier.isIncludeTimestampTokenValues())
				.certificateSourceTypes(validationContext.getCertificateSourceTypes())
				.trustedCertificateSources(certificateVerifier.getTrustedCertSources())
				.validationDate(validationContext.getCurrentTime());
	}
	
	/**
	 * Prepares the {@code validationContext} for signature validation process and returns a list of signatures to validate
	 * 
	 * @param validationContext {@link ValidationContext}
	 * @param validationPolicy {@link ValidationPolicy}
	 * @return list of {@link AdvancedSignature}s
	 */
	protected List<AdvancedSignature> prepareSignatureValidationContext(final ValidationContext validationContext, final ValidationPolicy validationPolicy) {
		// not implemented by default
		// see {@code DefaultDocumentValidator}
		return Collections.emptyList();
	}
	
	/**
	 * Prepares the {@code validationContext} for a timestamp validation process
	 * 
	 * @param validationContext {@link ValidationContext}
	 * @param validationPolicy {@link ValidationPolicy}
	 * @return a list of {@link TimestampToken}s to be validated
	 */
	protected List<TimestampToken> prepareTimestampValidationContext(final ValidationContext validationContext, final ValidationPolicy validationPolicy) {
		List<TimestampToken> timestampTokens = getTimestamps();
		for (TimestampToken timestampToken : timestampTokens) {
			validationContext.addTimestampTokenForVerification(timestampToken);
			CertificateToken issuer = validationCertPool.getIssuer(timestampToken);
			if (issuer != null) {
				validationContext.addCertificateTokenForVerification(issuer);
			}
		}
		
		validateContext(validationContext);
		
		return timestampTokens;
	}
	
	/**
	 * Process the validation
	 * 
	 * @param validationContext {@link ValidationContext} to process
	 */
	protected void validateContext(final ValidationContext validationContext) {
		validationContext.setCurrentTime(validationTime);
		validationContext.initialize(certificateVerifier);
		validationContext.validate();
	}
	
	/**
	 * Returns a list of {@link TimestampToken}s to be validated
	 * 
	 * @return a list of {@link TimestampToken}s
	 */
	protected List<TimestampToken> getTimestamps() {
		// not implemented by default
		// required in implementations of {@code TimestampToken}
		return Collections.emptyList();
	}

	/**
	 * Executes the validation regarding to the given {@code validationPolicy}
	 * 
	 * @param diagnosticData {@link DiagnosticData} contained a data to be validated
	 * @param validationPolicy {@link ValidationPolicy}
	 * @return validation {@link Reports}
	 */
	protected final Reports processValidationPolicy(XmlDiagnosticData diagnosticData, ValidationPolicy validationPolicy) {
		final DocumentProcessExecutor executor = provideProcessExecutorInstance();
		executor.setValidationPolicy(validationPolicy);
		executor.setValidationLevel(validationLevel);
		executor.setDiagnosticData(diagnosticData);
		executor.setEnableEtsiValidationReport(enableEtsiValidationReport);
		executor.setCurrentTime(validationTime);
		return executor.execute();
	}
}

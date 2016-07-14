/**
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Copyright 2016 the original author or authors.
 */
package uk.co.lucasweb.aws.v4.signer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.Charset;

/**
 * @author Richard Lucas
 */
public class Signer {

    private static final String AUTH_TAG = "AWS4";
    private static final String ALGORITHM = AUTH_TAG + "-HMAC-SHA256";
    private static final String TERMINATION_STRING = "aws4_request";
    private static final Charset UTF_8 = Throwables.returnableInstance(() -> Charset.forName("UTF-8"), SigningException::new);
    private static final String X_AMZ_DATE = "X-Amz-Date";
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final CanonicalRequest request;
    private final AwsCredentials awsCredentials;
    private final String service;
    private final String region;

    private Signer(CanonicalRequest request, AwsCredentials awsCredentials, String service, String region) {
        this.request = request;
        this.awsCredentials = awsCredentials;
        this.service = service;
        this.region = region;
    }

    public String getSignature() {
        String date = request.getHeaders().getFirstValue(X_AMZ_DATE)
                .orElseThrow(() -> new SigningException("headers missing '" + X_AMZ_DATE + "' header"));
        String dateWithoutTimestamp = formatDateWithoutTimestamp(date);
        String credentialScope = buildCredentialScope(dateWithoutTimestamp, service, region);
        String hashedCanonicalRequest = Sha256.get(request.get(), UTF_8);
        String stringToSign = buildStringToSign(date, credentialScope, hashedCanonicalRequest);
        String signature = buildSignature(awsCredentials.getSecretKey(), dateWithoutTimestamp, stringToSign, service, region);
        return buildAuthHeader(awsCredentials.getAccessKey(), credentialScope, request.getHeaders().getNames(), signature);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String formatDateWithoutTimestamp(String date) {
        return date.substring(0, 8);
    }

    private static String buildStringToSign(String date, String credentialScope, String hashedCanonicalRequest) {
        return ALGORITHM + "\n" + date + "\n" + credentialScope + "\n" + hashedCanonicalRequest;
    }

    private static String buildCredentialScope(String dateWithoutTimeStamp, String service, String region) {
        return dateWithoutTimeStamp + "/" + region + "/" + service + "/" + TERMINATION_STRING;
    }

    private static String buildAuthHeader(String accessKey, String credentialScope, String signedHeaders, String signature) {
        return ALGORITHM + " " + "Credential=" + accessKey + "/" + credentialScope + ", " + "SignedHeaders=" + signedHeaders + ", " + "Signature=" + signature;
    }

    private static byte[] hmacSha256(byte[] key, String value) {
        try {
            String algorithm = HMAC_SHA256;
            Mac mac = Mac.getInstance(algorithm);
            SecretKeySpec signingKey = new SecretKeySpec(key, algorithm);
            mac.init(signingKey);
            return mac.doFinal(value.getBytes(UTF_8));
        } catch (Exception e) {
            throw new SigningException("Error signing request", e);
        }
    }

    private static String buildSignature(String secretKey, String dateWithoutTimestamp, String stringToSign, String service, String region) {
        byte[] kSecret = (AUTH_TAG + secretKey).getBytes(UTF_8);
        byte[] kDate = hmacSha256(kSecret, dateWithoutTimestamp);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        byte[] kSigning = hmacSha256(kService, TERMINATION_STRING);
        return Base16.encode(hmacSha256(kSigning, stringToSign)).toLowerCase();
    }

    public static class Builder {

        private static final String DEFAULT_REGION = "us-east-1";
        private static final String S3 = "s3";
        private static final String GLACIER = "glacier";

        private AwsCredentials awsCredentials;
        private String region = DEFAULT_REGION;

        public Builder awsCredentials(AwsCredentials awsCredentials) {
            this.awsCredentials = awsCredentials;
            return this;
        }

        public Builder region(String region) {
            this.region = region;
            return this;
        }

        public Signer build(HttpRequest request, CanonicalHeaders headers, String service, String contentSha256) {
            // TODO get awsCredentials from system and environment properties if not set
            return new Signer(new CanonicalRequest(request, headers, contentSha256), awsCredentials, service, region);
        }

        public Signer buildS3(HttpRequest request, CanonicalHeaders headers, String contentSha256) {
            return build(request, headers, S3, contentSha256);
        }

        public Signer buildGlacier(HttpRequest request, CanonicalHeaders headers, String contentSha256) {
            return build(request, headers, GLACIER, contentSha256);
        }

    }
}